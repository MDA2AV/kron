package kron

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reactor's setup and teardown: the sizes it refuses, the listeners it opens, a start that fails, what a stop frees. */
class ReactorSetupTest {
    // ---- Construction -------------------------------------------------------------------------------------------

    private class Refusal(val case: String, val property: String, val construct: () -> Unit)

    @Test
    fun `config sizes a reactor cannot run with are refused at construction - by name`() {
        // They would otherwise pass construction and fail at the first accept, inside completion dispatch.
        val refusals = listOf(
            Refusal("tcp.recvQueueEntries not a power of two", "tcp.recvQueueEntries") {
                Reactor(0, ServerConfig(tcp = TcpOptions(port = 0, recvQueueEntries = 48)))
            },
            Refusal("tcp.writeSlabSize of 0", "tcp.writeSlabSize") {
                Reactor(0, ServerConfig(tcp = TcpOptions(port = 0, writeSlabSize = 0)))
            },
            Refusal("recvSlots not a power of two", "recvSlots") {
                Reactor(0, ServerConfig(recvSlots = 3000, tcp = TcpOptions(port = 0)))
            },
            Refusal("recvSlots above 32768", "recvSlots") {
                Reactor(0, ServerConfig(recvSlots = 65_536, tcp = TcpOptions(port = 0)))
            },
            Refusal("recvBufferSize of 0", "recvBufferSize") {
                Reactor(0, ServerConfig(recvBufferSize = 0, tcp = TcpOptions(port = 0)))
            },
            Refusal("incremental recvSlots not a power of two", "recvSlots") {
                Reactor(0, ServerConfig(tcp = TcpOptions(port = 0), incremental = IncrementalOptions(recvSlots = 24)))
            },
            Refusal("incremental recvSlots above 32768", "recvSlots") {
                Reactor(0, ServerConfig(tcp = TcpOptions(port = 0), incremental = IncrementalOptions(recvSlots = 65_536)))
            },
            Refusal("incremental recvBufferSize of 0", "recvBufferSize") {
                Reactor(0, ServerConfig(tcp = TcpOptions(port = 0), incremental = IncrementalOptions(recvBufferSize = 0)))
            },
            Refusal("incremental maxConnections of 0", "maxConnections") {
                Reactor(0, ServerConfig(tcp = TcpOptions(port = 0), incremental = IncrementalOptions(maxConnections = 0)))
            },
            Refusal("incremental maxConnections above 65534", "maxConnections") {
                Reactor(0, ServerConfig(tcp = TcpOptions(port = 0), incremental = IncrementalOptions(maxConnections = 65_535)))
            },
        )

        for (refusal in refusals) {
            val refused = assertFailsWith<IllegalArgumentException>(refusal.case) {
                refusal.construct()
            }
            assertTrue(
                refused.message?.contains(refusal.property) == true,
                "${refusal.case}: the message does not name ${refusal.property}: ${refused.message}",
            )
        }
    }

    @Test
    fun `control - the largest sizes the kernel and the gid space take still construct`() {
        Reactor(0, ServerConfig(recvSlots = 32_768, tcp = TcpOptions(port = 0, recvQueueEntries = 1, writeSlabSize = 1)))
        Reactor(
            0,
            ServerConfig(
                tcp = TcpOptions(port = 0),
                incremental = IncrementalOptions(maxConnections = 65_534, recvSlots = 32_768, recvBufferSize = 1),
            ),
        )
    }

    // ---- The listeners a config asks for ------------------------------------------------------------------------

    @Test
    fun `a config without tcp opens no TCP listener`() {
        val config = ServerConfig(reactorCount = 1)
        assertNull(config.tcp, "a config that never set tcp asks for a TCP listener on ${config.tcp?.port}")

        val opened = listenersOpenedBy(config)
        assertTrue(opened.isEmpty(), "the reactor opened TCP listeners: $opened")
    }

    @Test
    fun `control - a config with tcp opens its listener - and the same probe sees it`() {
        assertEquals(1, listenersOpenedBy(testConfig()).size, "the probe did not see the one listener the config asked for")
    }

    // ---- A setup that fails -------------------------------------------------------------------------------------

    @Test
    fun `a bind that fails tears down without closing stdin`() {
        // Whatever launched the binary left on fd 0 is watched; with nothing there, /dev/null takes the number.
        val placeholder = if (fdTarget(0) == null) open("/dev/null", O_RDONLY) else -1
        try {
            val stdin = fdTarget(0)
            assertNotNull(stdin, "fd 0 must be open before the test says anything")

            // A SO_REUSEPORT bind is refused unless every socket on the port asked for it, and a plain listener asks
            // for nothing: EADDRINUSE, deterministic and unprivileged. It throws in openListeners, before the wake fd
            // exists, so the teardown runs over a listener table and a wake fd that were never filled.
            val blocker = Sockets.listen(0, 16, reusePort = false)
            try {
                val port = Sockets.localPort(blocker)
                val reactor = Reactor(0, ServerConfig(reactorCount = 1, ringEntries = 256, tcp = TcpOptions(port = port)))
                reactor.tcpHandler = {}

                val run = runToEnd(reactor)

                assertTrue(run.ended, "the reactor should have failed its bind and returned, not parked in the loop")
                assertTrue(
                    run.thrown?.message?.contains("errno 98") == true,
                    "binding a port held without SO_REUSEPORT must fail the reactor: ${run.thrown}",
                )
                assertEquals(
                    stdin,
                    fdTarget(0),
                    "teardown after the failed bind closed fd 0 - stdin - and the number is free for the next socket " +
                        "the process opens",
                )
            } finally {
                Native.close(blocker)
            }
        } finally {
            if (placeholder >= 0) {
                close(placeholder)
            }
        }
    }

    @Test
    fun `a ring the kernel refuses reaches onFault instead of escaping run`() {
        // Every kernel refuses more than 32768 entries with EINVAL - the errno a kernel older than 6.1 gives
        // SINGLE_ISSUER | DEFER_TASKRUN. A host that asked to hear of faults must hear of this one too.
        val reported = AtomicReference<Throwable?>(null)
        val reactor = Reactor(0, ServerConfig(reactorCount = 1, ringEntries = 65_536))
        reactor.onFault = { _, e ->
            reported.store(e)
        }

        val run = runToEnd(reactor)

        assertTrue(run.ended, "run should have returned after the ring was refused")
        assertNull(run.thrown, "the refused ring was thrown out of run past onFault: ${run.thrown}")

        val fault = reported.load()
        assertTrue(fault is IllegalStateException, "onFault was not told: $fault")
        assertTrue(fault.message?.contains("32768") == true, "the message does not say what the kernel refused: ${fault.message}")
    }

    @Test
    fun `onFault is raised before the teardown - with the listener still open`() {
        // A throw from onStart reaches the catch that calls onFault after the listener is bound; the teardown is the
        // finally after it, so a host's handler runs against a reactor that is still whole.
        val reported = AtomicReference<Throwable?>(null)
        val port = AtomicInt(0)
        val listeningInOnFault = AtomicInt(-1)

        val reactor = Reactor(0, testConfig())
        reactor.tcpHandler = {}
        reactor.onStart = {
            throw IllegalStateException("fault-on-purpose")
        }
        reactor.onFault = { r, e ->
            reported.store(e)
            port.store(r.listenPorts[0])
            listeningInOnFault.store(if (accepts(r.listenPorts[0])) 1 else 0)
        }

        val run = runToEnd(reactor)

        assertTrue(run.ended, "run should have returned after onStart threw")
        assertEquals("fault-on-purpose", reported.load()?.message, "onFault was not told of the fault: ${reported.load()}")
        assertEquals(1, listeningInOnFault.load(), "the listener was already closed when onFault ran, so the teardown came first")

        // Control: the same probe sees the listener gone once the teardown has run.
        assertFalse(accepts(port.load()), "the listener still accepted after run returned, so the probe cannot tell")
    }

    // ---- What a stop frees --------------------------------------------------------------------------------------

    @Test
    fun `a stop frees its pooled connections' write slabs`() {
        val kept = stopWithPooledConnection(stopConfig(incremental = false))

        assertEquals(0L, kept.writeBuffer, "the pooled connection's write slab outlived its reactor")
    }

    @Test
    fun `a stop frees its pooled connections' write slabs and buffer rings - incremental`() {
        if (incrementalUnsupported()) {
            return
        }

        val kept = stopWithPooledConnection(stopConfig(incremental = true), ownRing = true)

        assertEquals(0L, kept.writeBuffer, "the pooled connection's write slab outlived its reactor")
        assertNull(kept.ring, "the pooled connection's buffer ring outlived its reactor")
    }

    private fun stopConfig(incremental: Boolean) = ServerConfig(
        reactorCount = 1,
        ringEntries = 256,
        recvBufferSize = 4096,
        recvSlots = 256,
        tcp = TcpOptions(port = 0, writeSlabSize = SLAB_SIZE, poolMax = 8, recvQueueEntries = 64),
        incremental = if (incremental) IncrementalOptions(maxConnections = 16, recvSlots = 4) else null,
    )

    // Answers one request past the write slab, holds the connection until its client closes - the reactor recycles
    // it into the pool in the dispatch that wakes the read, before its loop can see a stop - then stops the reactor.
    // Returns the connection as the stop left it.
    private fun stopWithPooledConnection(config: ServerConfig, ownRing: Boolean = false): TcpConnection {
        val kept = AtomicReference<TcpConnection?>(null)
        val keptGeneration = AtomicInt(0)
        val hadRing = AtomicInt(0)
        val released = Latch(1)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            try {
                var snapshot = conn.read()
                returnAll(conn, snapshot, item)
                if (!snapshot.isClosed) {
                    conn.write(ByteArray(RESPONSE_BYTES))
                    conn.flush()
                    keptGeneration.store(conn.generation)
                    hadRing.store(if (conn.ring != null) 1 else 0)
                    kept.store(conn)

                    while (!snapshot.isClosed) {
                        snapshot = conn.read()
                        returnAll(conn, snapshot, item)
                    }
                }
            } finally {
                if (kept.load() === conn) {
                    released.countDown()
                }
            }
        }

        val server = TestServer(config, handler)
        try {
            server.connect().use { client ->
                client.write("GET / HTTP/1.1\r\n\r\n")
                assertEquals(RESPONSE_BYTES, client.readBytes(RESPONSE_BYTES).size)
            }

            assertTrue(released.await(10_000), "the handler never saw the client close")
        } finally {
            server.close()
        }

        val conn = kept.load()!!
        assertNotEquals(keptGeneration.load(), conn.generation, "the connection was never recycled, so it never reached the pool")
        if (ownRing) {
            assertEquals(1, hadRing.load(), "the connection had no buffer ring of its own, so there was nothing to free")
        }

        return conn
    }

    private fun returnAll(conn: TcpConnection, snapshot: RecvSnapshot, item: RecvItem) {
        while (conn.tryGetItem(snapshot, item)) {
            conn.returnBuffer(item)
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    private class Run(val ended: Boolean, val thrown: Throwable?)

    // Runs [reactor] on a thread of its own until run returns, at most 10 s: one still running then is stopped, so
    // the join cannot hang the binary.
    private fun runToEnd(reactor: Reactor): Run {
        val done = Latch(1)
        val thrown = AtomicReference<Throwable?>(null)
        val threads = TestThreads()

        threads.start {
            try {
                reactor.run()
            } catch (e: Throwable) {
                thrown.store(e)
            } finally {
                done.countDown()
            }
        }

        val ended = done.await(10_000)
        if (!ended) {
            reactor.stop()
        }
        threads.joinAll()

        return Run(ended, thrown.load())
    }

    // The TCP listeners this process gained while a reactor ran [config]; run binds them before onStart.
    private fun listenersOpenedBy(config: ServerConfig): Set<String> {
        val before = ownListeners()
        val started = Latch(1)
        val server = KronServer(config, onStart = { started.countDown() }, handler = {}).start()

        try {
            assertTrue(started.await(10_000), "the reactor never reached onStart")
            return ownListeners() - before
        } finally {
            server.close()
        }
    }

    // This process's listening TCP sockets, v4 and v6: rows in state 0A whose inode a descriptor here holds.
    private fun ownListeners(): Set<String> {
        val listening = HashSet<String>()
        for (table in listOf("/proc/self/net/tcp", "/proc/self/net/tcp6")) {
            val rows = readFileBytes(table)?.decodeToString()?.lines()?.drop(1) ?: continue
            for (row in rows) {
                val f = row.trim().split(Regex(" +"))
                if (f.size > 9 && f[3] == "0A") {
                    listening.add("socket:[${f[9]}]")
                }
            }
        }

        return descriptorNumbers().mapNotNull { fdTarget(it) }.filter { it in listening }.toSet()
    }

    // A connect completes into a listener's backlog without anyone accepting, so it succeeds exactly while the
    // listening socket is open.
    private fun accepts(port: Int): Boolean = TestSocket().use { probe ->
        try {
            probe.connect(port)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun descriptorNumbers(): List<Int> {
        val dir = platform.posix.opendir("/proc/self/fd") ?: return emptyList()
        try {
            val numbers = ArrayList<Int>()
            while (true) {
                val entry = platform.posix.readdir(dir) ?: break
                entry.pointed.d_name.toKString().toIntOrNull()?.let {
                    numbers.add(it)
                }
            }

            return numbers
        } finally {
            platform.posix.closedir(dir)
        }
    }

    // What descriptor [fd] of this process refers to ("socket:[inode]", a path), or null when it is not open.
    private fun fdTarget(fd: Int): String? = memScoped {
        val buffer = allocArray<ByteVar>(256)
        val n = platform.posix.readlink("/proc/self/fd/$fd", buffer, 255u)
        if (n < 0) null else buffer.readBytes(n.toInt()).decodeToString()
    }

    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    private fun incrementalUnsupported(): Boolean {
        val unsupported = Ring.kernelOlderThan(kernelRelease(), 6, 12)
        if (unsupported) {
            println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
        }

        return unsupported
    }

    private companion object {
        const val SLAB_SIZE = 4096

        // Past the slab, so the slab grows to hold it.
        const val RESPONSE_BYTES = 3 * SLAB_SIZE
    }
}
