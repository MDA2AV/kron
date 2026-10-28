package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.linux.RLIMIT_MEMLOCK
import platform.linux.getrlimit
import platform.linux.rlimit
import platform.linux.setrlimit
import platform.posix.IPPROTO_TCP
import platform.posix.TCP_NODELAY
import platform.posix.ioctl
import platform.posix.setsockopt

/**
 * Provided buffers going back to the kernel: from the group shared by a reactor's connections, and from a
 * connection's own ring in incremental mode, where a buffer holds many slices and returns only when the kernel and
 * the reader are both done with it.
 */
class BufferRingTest {
    // ---- Setup ------------------------------------------------------------------------------------------------

    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    private val incrementalSupported: Boolean = run {
        val parts = kernelRelease().split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        major > 6 || (major == 6 && minor >= 12)
    }

    private fun incrementalUnsupported(): Boolean {
        if (!incrementalSupported) {
            println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
        }

        return !incrementalSupported
    }

    private fun shared(slots: Int, size: Int) = ServerConfig(
        reactorCount = 1,
        ringEntries = 256,
        recvSlots = slots,
        recvBufferSize = size,
        tcp = TcpOptions(port = 0, writeSlabSize = 4096, poolMax = 8, recvQueueEntries = 64),
    )

    private fun incremental(maxConnections: Int, slots: Int, size: Int, ringEntries: Int = 256, poolMax: Int = 8) =
        ServerConfig(
            reactorCount = 1,
            ringEntries = ringEntries,
            tcp = TcpOptions(port = 0, writeSlabSize = 4096, poolMax = poolMax, recvQueueEntries = 64),
            incremental = IncrementalOptions(maxConnections, slots, size),
        )

    // ---- Helpers ----------------------------------------------------------------------------------------------

    // Runs [block] on the reactor thread and waits for its answer.
    private fun <T> onReactor(reactor: Reactor, block: () -> T): T {
        val result = AtomicReference<T?>(null)
        val done = Latch(1)

        reactor.post {
            result.store(block())
            done.countDown()
        }

        assertTrue(done.await(10_000), "the reactor never ran the task")
        @Suppress("UNCHECKED_CAST")
        return result.load() as T
    }

    private fun copyOf(item: RecvItem): RecvItem = RecvItem().also {
        it.set(item.address, item.length, item.bufferId, item.generation)
    }

    private fun noDelay(socket: TestSocket) = memScoped {
        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(socket.fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, 4u)
    }

    // The client's view of a connection the server dropped without an answer: the end of the stream, or a reset.
    private fun dropped(client: TestSocket): Boolean = try {
        client.read() == -1
    } catch (e: IOException) {
        e.message?.endsWith("errno 104") == true
    }

    // A connection that answers its "hi", retried until one does: capacity frees on the reactor's own schedule.
    private fun connectServed(port: Int, timeoutMs: Long = 10_000): TestSocket {
        val deadline = Clock.monotonicMs() + timeoutMs
        while (true) {
            val socket = TestSocket()
            try {
                socket.setReceiveTimeout(1_000)
                socket.connect(port)
                socket.write("hi")
                if (socket.readBytes(2).decodeToString() == "hi") {
                    socket.setReceiveTimeout(5_000)
                    return socket
                }
            } catch (e: IOException) {
                // shed, or not served yet
            }

            socket.close()
            check(Clock.monotonicMs() < deadline) { "no connection was served within $timeoutMs ms" }
            sleepMs(20)
        }
    }

    // Reads once and holds what it was given until [released], then drains until [totalBytes] have arrived and
    // answers "done": a reader that falls behind while its peer floods, then catches up.
    private fun holdThenDrain(released: AtomicInt, totalBytes: Int): suspend (TcpConnection) -> Unit = { conn ->
        val item = RecvItem()
        val held = ArrayList<RecvItem>()
        val timer = RingTimer(conn.reactor)
        var got = 0L
        var holding = true
        var answered = false

        while (true) {
            val snapshot = conn.read()
            while (conn.tryGetItem(snapshot, item)) {
                if (!item.hasBuffer) {
                    continue
                }

                got += item.length
                if (holding) {
                    held.add(copyOf(item))
                } else {
                    conn.returnBuffer(item)
                }
            }

            if (holding && held.isNotEmpty()) {
                while (released.load() == 0) {
                    timer.delay(5)
                }

                holding = false
                for (h in held) {
                    conn.returnBuffer(h)
                }
                held.clear()
            }

            if (got >= totalBytes && !answered) {
                answered = true
                conn.write("done".encodeToByteArray())
                conn.flush()
            }

            if (snapshot.isClosed) {
                break
            }
        }
    }

    // Floods past the buffers while the handler holds its first read, then releases it: the connection must pause
    // and resume, not die. The recv must really have starved, or nothing was tested.
    private fun driveExhaustion(server: TestServer, totalBytes: Int, released: AtomicInt) {
        TestSocket().use { client ->
            client.setReceiveTimeout(8_000)
            client.connect(server.port)

            val payload = ByteArray(totalBytes) { (it % 251).toByte() }
            client.write(payload.copyOfRange(0, 1024))   // the handler reads this and holds it
            sleepMs(150)
            client.write(payload.copyOfRange(1024, totalBytes))   // past the buffers
            sleepMs(300)

            val starved = onReactor(server.reactor) { server.reactor.tcp!!.starvedRecvs }
            assertTrue(starved > 0, "the buffers never ran dry, so nothing was tested")

            released.store(1)   // the buffers come back; the recv must resume
            assertEquals(
                "done", client.readBytes(4).decodeToString(),
                "the connection died while its buffers ran dry, where it should have paused and resumed",
            )
        }
    }

    // ---- Both modes: a starved recv resumes as buffers come back (ioxide #93) ---------------------------------

    @Test
    fun `a recv starved of shared buffers resumes once the reader hands them back`() {
        val released = AtomicInt(0)
        TestServer(shared(slots = 8, size = 1024), holdThenDrain(released, 24 * 1024)).use { server ->
            driveExhaustion(server, 24 * 1024, released)
        }
    }

    @Test
    fun `a recv starved of its own ring resumes once the reader hands the buffers back`() {
        if (incrementalUnsupported()) {
            return
        }

        val released = AtomicInt(0)
        TestServer(incremental(maxConnections = 8, slots = 4, size = 1024), holdThenDrain(released, 12 * 1024)).use {
            driveExhaustion(it, 12 * 1024, released)
        }
    }

    // ---- Both modes: the pipe hands buffers back as it advances -----------------------------------------------

    // Reads through the pipe, consuming all it is given, and answers "done" after [totalBytes].
    private fun pipeCounting(totalBytes: Int, count: AtomicLong): suspend (TcpConnection) -> Unit = { conn ->
        val pipe = TcpDuplexPipe(conn)
        var answered = false

        while (true) {
            val result = pipe.input.read()
            count.addAndFetch(result.buffer.length)
            pipe.input.advanceTo(result.buffer.length)

            if (count.load() >= totalBytes && !answered) {
                answered = true
                pipe.output.write("done".encodeToByteArray())
                pipe.output.flush()
            }

            if (result.isCompleted) {
                break
            }
        }

        pipe.input.complete()
    }

    private fun streamThroughPipe(config: ServerConfig) {
        val totalBytes = 512 * 1024
        val count = AtomicLong(0)

        TestServer(config, pipeCounting(totalBytes, count)).use { server ->
            server.connect(receiveTimeoutMs = 10_000).use { client ->
                client.write(ByteArray(totalBytes) { it.toByte() })
                assertEquals(
                    "done", client.readBytes(4).decodeToString(),
                    "the stream stalled at ${count.load()} of $totalBytes bytes: the pipe kept the buffers it had consumed",
                )
            }
        }
    }

    @Test
    fun `the pipe hands shared buffers back as it advances`() {
        // 512 KiB through 8 KiB of buffers: only buffers coming back as the pipe consumes can carry it.
        streamThroughPipe(shared(slots = 8, size = 1024))
    }

    @Test
    fun `the pipe hands its own ring's buffers back as it advances`() {
        if (incrementalUnsupported()) {
            return
        }

        // 512 KiB through a 4 KiB ring of its own.
        streamThroughPipe(incremental(maxConnections = 8, slots = 4, size = 1024))
    }

    // ---- Incremental mode ------------------------------------------------------------------------------------

    @Test
    fun `an incremental connection echoes request after request`() {
        if (incrementalUnsupported()) {
            return
        }

        TestServer(incremental(maxConnections = 16, slots = 16, size = 4096), ::echo).use { server ->
            server.connect().use { client ->
                for (round in 0 until 50) {
                    val message = "request $round ".repeat(round % 7 + 1).encodeToByteArray()
                    client.write(message)
                    assertContentEquals(message, client.readBytes(message.size), "round $round")
                }
            }
        }
    }

    @Test
    fun `a slow reader pauses its connection and resumes it without losing a byte`() {
        if (incrementalUnsupported()) {
            return
        }

        val totalBytes = 256 * 1024
        val starvedSeen = AtomicInt(0)

        // Hands back each slice only after a pause, so the peer gets ahead and the 4 KiB ring runs dry again and
        // again. Sums the bytes, so a lost or repeated one shows.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            val timer = RingTimer(conn.reactor)
            var got = 0L
            var sum = 0L

            while (got < totalBytes) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) {
                        for (i in 0 until item.length) {
                            sum += item[i].toInt() and 0xFF
                        }
                        got += item.length
                        conn.returnBuffer(item)
                    }
                }

                if (conn.reactor.tcp!!.starvedRecvs > 0) {
                    starvedSeen.store(1)
                }
                if (snapshot.isClosed) {
                    break
                }

                timer.delay(1)
            }

            conn.write("$got:$sum".encodeToByteArray())
            conn.flush()
        }

        val payload = ByteArray(totalBytes) { (it * 7 + 3).toByte() }
        val expectedSum = payload.sumOf { (it.toInt() and 0xFF).toLong() }

        TestServer(incremental(maxConnections = 8, slots = 4, size = 1024), handler).use { server ->
            server.connect(receiveTimeoutMs = 20_000).use { client ->
                // No half-close: a peer's FIN ends the connection, answer and all.
                client.write(payload)

                val expected = "$totalBytes:$expectedSum"
                assertEquals(expected, client.readBytes(expected.length).decodeToString())
                assertEquals(1, starvedSeen.load(), "the reader never fell behind, so nothing was tested")
            }
        }
    }

    @Test
    fun `other connections are served while one is paused on its own ring`() {
        if (incrementalUnsupported()) {
            return
        }

        val released = AtomicInt(0)
        val accepted = AtomicInt(0)

        // The first connection holds and floods; every later one echoes.
        val holding = holdThenDrain(released, 64 * 1024)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            if (accepted.addAndFetch(1) == 1) {
                holding(conn)
            } else {
                echo(conn)
            }
        }

        TestServer(incremental(maxConnections = 8, slots = 4, size = 1024), handler).use { server ->
            TestSocket().use { flooder ->
                flooder.setReceiveTimeout(8_000)
                flooder.connect(server.port)
                flooder.write(ByteArray(1024))
                sleepMs(100)
                flooder.write(ByteArray(63 * 1024))
                sleepMs(200)

                assertTrue(
                    onReactor(server.reactor) { server.reactor.tcp!!.starvedRecvs } > 0,
                    "the first connection never ran dry, so nothing was tested",
                )

                server.connect().use { other ->
                    for (round in 0 until 20) {
                        val message = "while the first waits, round $round".encodeToByteArray()
                        other.write(message)
                        assertContentEquals(message, other.readBytes(message.size), "round $round")
                    }
                }

                released.store(1)
                assertEquals("done", flooder.readBytes(4).decodeToString())
            }
        }
    }

    @Test
    fun `a peer trickling bytes to a reader that is not reading does not overflow its recv queue`() {
        if (incrementalUnsupported()) {
            return
        }

        val released = AtomicInt(0)
        val trickled = 200

        // Takes no snapshot until released, so every recv lands while nothing reads: one byte each, more of them
        // than the queue has entries. Slices that continue the last one grow it, so they all fit.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val timer = RingTimer(conn.reactor)
            while (released.load() == 0) {
                timer.delay(5)
            }

            val item = RecvItem()
            val received = ArrayList<Byte>()
            while (received.size < trickled) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) {
                        for (i in 0 until item.length) {
                            received.add(item[i])
                        }
                        conn.returnBuffer(item)
                    }
                }

                if (snapshot.isClosed) {
                    break
                }
            }

            val inOrder = received.withIndex().all { (i, b) -> b == (i % 100 + 1).toByte() }
            val verdict = if (received.size == trickled && inOrder) "ok" else "no"
            conn.write(verdict.encodeToByteArray())
            conn.flush()
        }

        TestServer(incremental(maxConnections = 8, slots = 4, size = 1024), handler).use { server ->
            server.connect().use { client ->
                noDelay(client)
                for (i in 0 until trickled) {
                    client.write(byteArrayOf((i % 100 + 1).toByte()))
                    sleepMs(1)
                }

                sleepMs(50)
                released.store(1)
                assertEquals("ok", client.readBytes(2).decodeToString(), "the trickled bytes did not all arrive in order")
            }
        }
    }

    @Test
    fun `slices handed back from another thread return their buffers to the ring`() {
        if (incrementalUnsupported()) {
            return
        }

        val totalBytes = 64 * 1024
        val threads = TestThreads()
        val offReactorReturns = AtomicInt(0)

        // Raw, so the handler continues on the new thread; a read posted from there resumes it on the reactor.
        suspend fun hop(): Unit = suspendCoroutineUninterceptedOrReturn { cont ->
            threads.start {
                cont.resume(Unit)
            }
            COROUTINE_SUSPENDED
        }

        // Takes each batch on the reactor, then hops to another thread to hand its buffers back.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            val batch = ArrayList<RecvItem>()
            var got = 0L

            while (got < totalBytes) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) {
                        got += item.length
                        batch.add(copyOf(item))
                    }
                }

                hop()
                for (b in batch) {
                    conn.returnBuffer(b)
                }
                if (batch.isNotEmpty() && !conn.reactor.onReactorThread) {
                    offReactorReturns.addAndFetch(1)
                }
                batch.clear()

                if (snapshot.isClosed) {
                    break
                }
            }

            conn.write("done".encodeToByteArray())
            conn.flush()
        }

        try {
            TestServer(incremental(maxConnections = 8, slots = 4, size = 1024), handler).use { server ->
                server.connect(receiveTimeoutMs = 10_000).use { client ->
                    client.write(ByteArray(totalBytes))
                    assertEquals(
                        "done", client.readBytes(4).decodeToString(),
                        "the stream stalled: buffers handed back from another thread never reached the ring",
                    )
                }
            }
        } finally {
            threads.joinAll()
        }

        assertTrue(offReactorReturns.load() > 0, "no buffer was handed back from another thread, so nothing was tested")
    }

    @Test
    fun `a recv ended by a full completion queue still hands over the peer's bytes`() {
        if (incrementalUnsupported()) {
            return
        }

        // The kernel ends a multishot recv whose completion finds the CQ full, and that last completion still
        // carries F_BUF_MORE: the buffer stays at the ring's head, part filled, and the re-armed recv appends to it.
        // Taken as done, the buffer went back into the ring while the kernel was still filling it, and the reader
        // was handed its first bytes while the peer's new ones landed further along (ioxide's test of the same).
        //
        // 4 ring entries is an 8-entry CQ. Each round sends one message on every connection from the reactor
        // thread, so all 24 recvs complete in its next enter and most of them overflow the CQ. Nine 32-byte
        // messages never fill a 1 KiB buffer, so every ending leaves one part filled.
        val connections = 24
        val rounds = 8
        val messageBytes = 32

        fun message(connection: Int, round: Int): ByteArray {
            val text = "connection ${connection.toString().padStart(2, '0')}, round ${round.toString().padStart(2, '0')}|"
            return text.padEnd(messageBytes, '.').encodeToByteArray()
        }

        val serverFds = AtomicReference<List<Int>>(emptyList())
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            serverFds.store(serverFds.load() + conn.clientFd)
            echo(conn)
        }

        // Every server socket holds a whole message the reactor has not received yet.
        fun allWaiting(fds: List<Int>): Boolean = fds.all { fd ->
            memScoped {
                val waiting = alloc<IntVar>()
                ioctl(fd, FIONREAD.convert(), waiting.ptr) == 0 && waiting.value >= messageBytes
            }
        }

        val config = incremental(maxConnections = 64, slots = 4, size = 1024, ringEntries = 4, poolMax = 64)
        TestServer(config, handler).use { server ->
            val clients = ArrayList<TestSocket>()
            try {
                // One at a time, so nothing overflows yet: the control, echoed before any recv ended.
                for (i in 0 until connections) {
                    val client = server.connect()
                    noDelay(client)
                    clients.add(client)
                    val hello = message(i, 0)
                    client.write(hello)
                    assertContentEquals(hello, client.readBytes(messageBytes), "before the rounds, connection $i")
                }

                val fds = serverFds.load()
                assertEquals(connections, fds.size)

                for (round in 1..rounds) {
                    val sent = List(connections) { message(it, round) }

                    val waited = onReactor(server.reactor) {
                        for (i in 0 until connections) {
                            clients[i].write(sent[i])
                        }

                        // Loopback delivers inside the send, unless a loaded box defers it to ksoftirqd.
                        val deadline = Clock.monotonicMs() + 2_000
                        var all = allWaiting(fds)
                        while (!all && Clock.monotonicMs() < deadline) {
                            all = allWaiting(fds)
                        }

                        all
                    }

                    assertTrue(waited, "round $round: the messages were never all waiting at once, so the CQ never overflowed")
                    for (i in 0 until connections) {
                        assertContentEquals(sent[i], clients[i].readBytes(messageBytes), "round $round, connection $i")
                    }
                }
            } finally {
                clients.forEach { it.close() }
            }
        }
    }

    @Test
    fun `accepts past the connection cap are shed and the reactor goes on`() {
        if (incrementalUnsupported()) {
            return
        }

        TestServer(incremental(maxConnections = 4, slots = 4, size = 1024), ::echo).use { server ->
            val held = ArrayList<TestSocket>()
            try {
                for (i in 0 until 4) {
                    held.add(connectServed(server.port))
                }

                // Past the cap: closed at once, unserved.
                for (i in 0 until 3) {
                    TestSocket().use { extra ->
                        extra.setReceiveTimeout(2_000)
                        extra.connect(server.port)
                        assertTrue(dropped(extra), "connection ${i + 5} was served past the cap")
                    }
                }

                // The ones it holds are still served...
                held[0].write("still")
                assertEquals("still", held[0].readBytes(5).decodeToString())

                // ...and a slot freed by a close takes a new connection once the reactor recycles it.
                held.removeAt(3).close()
                connectServed(server.port).close()
            } finally {
                held.forEach { it.close() }
            }
        }
    }

    @Test
    fun `an accept whose ring the kernel refuses for locked memory is shed and the reactor goes on`() {
        if (incrementalUnsupported()) {
            return
        }

        // io_uring charges nothing to RLIMIT_MEMLOCK for a process with CAP_IPC_LOCK, so it cannot run out.
        val status = readFileBytes("/proc/self/status")?.decodeToString() ?: ""
        val caps = status.lineSequence().firstOrNull { it.startsWith("CapEff:") }?.substringAfter(':')?.trim()
        if (caps != null && (caps.toULong(16) and (1uL shl 14)) != 0uL) {
            println("skipped: this process holds CAP_IPC_LOCK, so a ring never meets the locked-memory limit")
            return
        }

        TestServer(incremental(maxConnections = 8, slots = 4, size = 1024), ::echo).use { server ->
            // Served before the limit drops: the control, and a connection that must outlive the refusal.
            val held = connectServed(server.port)
            try {
                val shed = memScoped {
                    val original = alloc<rlimit>()
                    assertEquals(0, getrlimit(RLIMIT_MEMLOCK, original.ptr))
                    val none = alloc<rlimit>()
                    none.rlim_cur = 0u
                    none.rlim_max = original.rlim_max

                    // The soft limit only, so it can be put back; held's ring is already charged.
                    assertEquals(0, setrlimit(RLIMIT_MEMLOCK, none.ptr))
                    try {
                        TestSocket().use { refused ->
                            refused.setReceiveTimeout(5_000)
                            refused.connect(server.port)
                            refused.write("hi")
                            dropped(refused)
                        }
                    } finally {
                        setrlimit(RLIMIT_MEMLOCK, original.ptr)
                    }
                }

                assertTrue(shed, "the connection accepted with no locked memory left was served: its ring was never refused")

                held.write("still")
                assertEquals("still", held.readBytes(5).decodeToString())
                connectServed(server.port).close()
            } finally {
                held.close()
            }
        }
    }

    @Test
    fun `a recycled connection gives its ring and its group id back`() {
        if (incrementalUnsupported()) {
            return
        }

        // Far more connections in turn than the cap: each one served means the last one's group id came back, and
        // its ring was unregistered, or registering the id again would fail.
        TestServer(incremental(maxConnections = 2, slots = 4, size = 1024), ::echo).use { server ->
            for (i in 0 until 40) {
                connectServed(server.port, timeoutMs = 5_000).close()
            }
        }
    }

    @Test
    fun `fragmented and oversized requests resolve on the incremental ring`() {
        if (incrementalUnsupported()) {
            return
        }

        val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray()
        val limit = 64 * 1024

        // Answers each request head, and drops a connection whose head outgrows the limit.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            var carry = ByteArray(0)
            var dropping = false

            while (!dropping) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) {
                        val bytes = ByteArray(item.length)
                        item.copyTo(bytes)
                        carry += bytes
                        conn.returnBuffer(item)
                    }
                }

                while (true) {
                    val end = carry.indexOfHeadEnd()
                    if (end < 0) {
                        break
                    }

                    carry = carry.copyOfRange(end + 4, carry.size)
                    conn.write(response)
                }
                conn.flush()

                dropping = carry.size > limit
                if (snapshot.isClosed) {
                    break
                }
            }
        }

        TestServer(incremental(maxConnections = 64, slots = 16, size = 4096), handler).use { server ->
            // One byte at a time: every slice of the head a recv of its own, in one buffer.
            server.connect().use { client ->
                noDelay(client)
                for (b in "GET / HTTP/1.1\r\nHost: kron\r\n\r\n".encodeToByteArray()) {
                    client.write(byteArrayOf(b))
                    sleepMs(2)
                }

                assertContentEquals(response, client.readBytes(response.size))
            }

            // A head that never ends: dropped, unanswered.
            server.connect().use { client ->
                client.write(("GET / HTTP/1.1\r\n" + "a".repeat(96 * 1024)).encodeToByteArray())
                assertTrue(dropped(client), "a 96 KiB head with no end was answered")
            }

            // And the reactor is still well.
            server.connect().use { client ->
                client.write("GET / HTTP/1.1\r\n\r\n")
                assertContentEquals(response, client.readBytes(response.size))
            }
        }
    }

    private fun ByteArray.indexOfHeadEnd(): Int {
        for (i in 0..size - 4) {
            if (this[i] == CR && this[i + 1] == LF && this[i + 2] == CR && this[i + 3] == LF) {
                return i
            }
        }

        return -1
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()

        // From asm-generic/ioctls.h, the same on x86-64 and arm64.
        const val FIONREAD = 0x541B
    }
}
