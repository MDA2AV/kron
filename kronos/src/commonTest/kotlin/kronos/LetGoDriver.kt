package kronos

import kotlin.concurrent.atomics.AtomicInt
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertTrue

/**
 * Clients that outlive their handlers, for any handler on the TCP transport. Once a handler has let go nobody reads
 * its connection, so what its client goes on sending holds buffers. A server survives that if a new client is still
 * served and the connections end, whichever way the client goes and whether or not a read timeout is set.
 */
internal object LetGoDriver {
    const val GRACE_MS = 4_000L

    private const val CLIENTS = 3

    /** Which rings the clients empty, and what that takes: below the 64-entry queue, so nothing overflows. */
    class Mode(val name: String, val sendsEach: Int, val bytesEach: Int, val incremental: IncrementalOptions?)

    val SHARED = Mode("shared ring", sendsEach = 30, bytesEach = 1, incremental = null)

    val INCREMENTAL = Mode(
        "incremental",
        sendsEach = 8,
        bytesEach = 8 * 1024,
        incremental = IncrementalOptions(maxConnections = 16, recvSlots = 4, recvBufferSize = 4096),
    )

    /** What the clients do next, and whether that ends the connections. */
    class Ending(val name: String, val leave: Boolean, val readTimeoutMs: Long, val ends: Boolean)

    private val THEY_LEAVE = Ending("they leave", leave = true, readTimeoutMs = 300, ends = true)
    private val THEY_LEAVE_NO_TIMEOUT =
        Ending("they leave, no read timeout", leave = true, readTimeoutMs = 0, ends = true)
    private val THEY_STAY = Ending("they stay, until the read timeout", leave = false, readTimeoutMs = 300, ends = true)
    private val THEY_STAY_NO_TIMEOUT =
        Ending("they stay, no read timeout", leave = false, readTimeoutMs = 0, ends = false)

    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    val incrementalSupported: Boolean = run {
        val parts = kernelRelease().split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        major > 6 || (major == 6 && minor >= 12)
    }

    val modes: List<Mode> = if (incrementalSupported) listOf(SHARED, INCREMENTAL) else listOf(SHARED)

    /**
     * Runs [case] for every mode and ending that can show anything, naming the one that failed: a ring of its own takes
     * nobody else's buffers, and with no end coming nothing can be seen to close.
     */
    fun forEachCase(case: (Mode, Ending) -> Unit) {
        for (mode in modes) {
            for (ending in listOf(THEY_LEAVE, THEY_LEAVE_NO_TIMEOUT, THEY_STAY, THEY_STAY_NO_TIMEOUT)) {
                if (mode !== INCREMENTAL || ending !== THEY_STAY_NO_TIMEOUT) {
                    naming("${mode.name}, ${ending.name}") { case(mode, ending) }
                }
            }
        }
    }

    /** Runs [block], putting [name] on what it fails with. */
    fun naming(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$name: ${e.message}")
        } catch (e: IOException) {
            throw AssertionError("$name: ${e.message}")
        }
    }

    fun config(mode: Mode, readTimeoutMs: Long, sendTimeoutMs: Long = 500) = ServerConfig(
        proactorCount = 1,
        ringEntries = 256,
        recvBufferSize = 4096,
        recvSlots = 64,
        tcp = TcpOptions(port = 0, recvQueueEntries = 64, readTimeoutMs = readTimeoutMs, sendTimeoutMs = sendTimeoutMs),
        incremental = mode.incremental,
    )

    /** A client that outlived its handler, and how it goes: a TLS client has a session of its own to free. */
    class Peer(val socket: TestSocket, private val release: () -> Unit = socket::close) {
        fun close() = release()

        fun trySend(bytes: ByteArray) {
            try {
                socket.write(bytes)
            } catch (e: IOException) {
                // The server has already ended this one, which is allowed at any point from here.
            }
        }
    }

    /**
     * [letGoClient] makes a connected client whose handler has let go, or is about to; [served] says whether a new
     * client, using the server as it should, gets its answer.
     */
    fun check(proactor: Proactor, mode: Mode, ending: Ending, letGoClient: () -> Peer, served: () -> Boolean) {
        val clients = ArrayList<Peer>()
        try {
            repeat(CLIENTS) {
                clients.add(letGoClient())
            }

            val message = ByteArray(mode.bytesEach)
            repeat(mode.sendsEach) {
                clients.forEach { it.trySend(message) }
                sleepMs(1)   // apart, so each is a recv of its own
            }

            if (ending.leave) {
                clients.forEach { it.close() }
                clients.clear()
            }

            sleepMs(if (ending.readTimeoutMs > 0) 1_000 else 300)

            assertTrue(served(), "a new client was never answered: the let-go connections kept the buffers")

            if (ending.ends) {
                assertTrue(
                    eventually { openConnections(proactor) == 0 },
                    "${openConnections(proactor)} connections still open, a new client served and their handlers gone",
                )
            }
        } finally {
            clients.forEach { it.close() }
        }
    }

    fun connect(port: Int, receiveBuffer: Int = 0): TestSocket {
        val socket = TestSocket()
        socket.setReceiveTimeout(GRACE_MS.toInt())
        socket.setNoDelay()
        if (receiveBuffer > 0) {
            socket.setReceiveBuffer(receiveBuffer)   // before connect, so it is the window advertised
        }

        socket.connect(port)
        return socket
    }

    /** Connected, and everything the server sent has been read through to its FIN or reset. */
    fun refused(port: Int, first: ByteArray): Peer {
        val client = connect(port)
        client.write(first)
        readToEnd(client)
        return Peer(client)
    }

    private fun readToEnd(client: TestSocket) {
        val buffer = ByteArray(4096)
        try {
            while (client.read(buffer, 0, buffer.size) >= 0) {
                // what it was refused with
            }
        } catch (e: IOException) {
            // A reset is an end too: the sends that follow are what the test is about. A timeout is not.
            if (e.message?.contains("errno 104") != true) {
                throw e
            }
        }
    }

    /** Whether [request] got its answer: a connection the server ended or never served did not. */
    fun answers(request: () -> Boolean): Boolean = try {
        request()
    } catch (e: IOException) {
        false
    }

    fun openConnections(proactor: Proactor): Int = onProactor(proactor) { proactor.tcp!!.openConnections }

    /** Runs [block] on the proactor thread and waits for its answer. */
    fun onProactor(proactor: Proactor, block: () -> Int): Int {
        val result = AtomicInt(Int.MIN_VALUE)
        val done = Latch(1)

        proactor.post {
            result.store(block())
            done.countDown()
        }

        assertTrue(done.await(10_000), "the proactor never ran the task")
        return result.load()
    }

    fun eventually(condition: () -> Boolean): Boolean {
        val deadline = Clock.monotonicMs() + GRACE_MS
        while (Clock.monotonicMs() < deadline) {
            if (condition()) {
                return true
            }

            sleepMs(10)
        }

        return condition()
    }

    /** Starts [block] apart from the handler, which goes on, and returns, while it waits. */
    fun detached(block: suspend () -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) {})
    }
}
