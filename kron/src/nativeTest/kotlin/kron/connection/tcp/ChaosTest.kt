package kron

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.IPPROTO_TCP
import platform.posix.SOL_SOCKET
import platform.posix.SO_LINGER
import platform.posix.TCP_NODELAY
import platform.posix.linger
import platform.posix.setsockopt
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** TCP chaos: bytes split, coalesced, oversized, garbled, reset or dribbled, and a fresh request answered after each. */
class TcpChaosTest {
    @Test
    fun `byte-by-byte fragmentation reassembles to one response`() {
        TestServer(chaosConfig(), framer()).use { server ->
            assertEquals(1, responsesFragmented(server.port, request(), chunk = 1, pauseMs = 2).occurrences(OK_LINE))
            assertHealthy(server.port)
        }
    }

    @Test
    fun `a pipelined burst is answered once per request`() {
        TestServer(chaosConfig(), framer()).use { server ->
            val burst = (0 until 20).fold(ByteArray(0)) { all, i -> all + request("/$i") }

            assertEquals(20, responses(server.port, burst).occurrences(OK_LINE))
            assertHealthy(server.port)
        }
    }

    @Test
    fun `headers spanning many recv buffers get one response`() {
        TestServer(chaosConfig(), framer()).use { server ->
            // ~32 KiB of header lines: past the 4 KiB recv buffers, under the refusal cap.
            val head = StringBuilder("GET / HTTP/1.1\r\nHost: chaos\r\n")
            var i = 0
            while (head.length < 32 * 1024) {
                head.append("X-Pad-${i++}: ${"y".repeat(64)}\r\n")
            }
            head.append("\r\n")

            assertEquals(1, responses(server.port, head.toString().encodeToByteArray()).occurrences(OK_LINE))
            assertHealthy(server.port)
        }
    }

    @Test
    fun `never-terminated headers are refused - not buffered without bound`() {
        TestServer(chaosConfig(), framer()).use { server ->
            // 128 KiB with no CRLFCRLF: the handler bounds what it carries and refuses, and others are still served.
            val flood = ("GET / HTTP/1.1\r\n" + "a".repeat(128 * 1024)).encodeToByteArray()

            val seen = responses(server.port, flood)
            assertEquals(0, seen.occurrences(OK_LINE), "an unterminated head was answered")
            assertEquals(1, seen.occurrences(TOO_LARGE_LINE), "the refusal never arrived: $seen")
            assertHealthy(server.port)
        }
    }

    @Test
    fun `binary garbage does not crash the server`() {
        TestServer(chaosConfig(), framer()).use { server ->
            sendThenClose(server.port, Random(1).nextBytes(8 * 1024))
            assertHealthy(server.port)
        }
    }

    @Test
    fun `NUL bytes in the request line are framed - not fatal`() {
        TestServer(chaosConfig(), framer()).use { server ->
            val withNuls = "GET /".encodeToByteArray() + ByteArray(3) + " HTTP/1.1\r\nHost: chaos\r\n\r\n".encodeToByteArray()

            assertEquals(1, responses(server.port, withNuls).occurrences(OK_LINE))
            assertHealthy(server.port)
        }
    }

    @Test
    fun `bare-LF line endings never frame but do not wedge the server`() {
        TestServer(chaosConfig(), framer()).use { server ->
            // Never CRLFCRLF, so nothing is due: the point is that an unmatched framing neither hangs nor crashes.
            val bareLf = "GET / HTTP/1.1\nHost: chaos\n\n".encodeToByteArray()

            assertEquals(0, responses(server.port, bareLf, settleMs = 400).occurrences(OK_LINE))
            assertHealthy(server.port)
        }
    }

    @Test
    fun `connect then immediate close with no bytes is handled`() {
        TestServer(chaosConfig(), framer()).use { server ->
            repeat(20) {
                connect(server.port).close()   // a bare accept, then EOF
            }

            assertHealthy(server.port)
        }
    }

    @Test
    fun `an abortive RST mid-request leaves the server serving`() {
        TestServer(chaosConfig(), framer()).use { server ->
            repeat(20) {
                reset(server.port, "GET / HTTP/1.1\r\nHost: ch".encodeToByteArray())
            }

            assertHealthy(server.port)
        }
    }

    @Test
    fun `rapid connect and disconnect churn`() {
        TestServer(chaosConfig(), framer()).use { server ->
            repeat(100) {
                connect(server.port).use { client ->
                    client.write("x")
                }
            }

            assertHealthy(server.port)
        }
    }

    @Test
    fun `a slow-loris drip cannot starve other connections`() {
        TestServer(chaosConfig(), framer()).use { server ->
            connect(server.port).use { slow ->
                noDelay(slow)
                slow.write("GET / HTTP/1.1\r\n")

                // Header bytes, never the terminator: the slow connection is parked, not blocking the reactor.
                for (i in 0 until 5) {
                    slow.write("X-Drip-$i: y\r\n")
                    sleepMs(40)
                    assertHealthy(server.port)
                }
            }
        }
    }

    @Test
    fun `a half-closing client is torn down cleanly and the server keeps serving`() {
        // A peer FIN is teardown here as in ioxide, so the response races the close and is best effort. What holds
        // is that half-close never wedges or kills the reactor.
        TestServer(chaosConfig(), framer()).use { server ->
            repeat(10) {
                connect(server.port, timeoutMs = 2_000).use { client ->
                    client.write(request())
                    client.shutdownOutput()

                    try {
                        client.read(ByteArray(256), 0, 256)
                    } catch (e: IOException) {
                        // a reset or the timeout: either way the reactor must go on
                    }
                }
            }

            assertHealthy(server.port)
        }
    }
}

/** Chaos at the reactor's bookkeeping: torn-down connections leak and carry nothing, and SO_REUSEPORT shares a port. */
class ReactorChaosTest {
    @Test
    fun `a chaos storm leaks no file descriptors`() {
        TestServer(chaosConfig(), framer()).use { server ->
            assertHealthy(server.port)   // warms the pool and settles before the baseline
            val before = stableFdCount()

            val garbage = Random(5).nextBytes(4096)
            val oversize = ("GET / HTTP/1.1\r\n" + "a".repeat(96 * 1024)).encodeToByteArray()
            repeat(25) {
                sendThenClose(server.port, garbage)                                       // garbage, then FIN
                reset(server.port, "GET / HTTP/1.1\r\nHost: ch".encodeToByteArray())      // RST mid-request
                responses(server.port, oversize, settleMs = 80)                           // refused and ended
            }

            assertHealthy(server.port)

            val leaked = stableFdCount() - before
            assertTrue(leaked <= 3, "$leaked fds leaked after a chaos storm: every torn-down connection must be recycled")
        }
    }

    @Test
    fun `a pooled connection carries no state into its next life`() {
        // Every life's connection object, and how many handlers have ended; one reactor, so the list needs no more.
        val lives = AtomicReference<List<TcpConnection>>(emptyList())
        val ended = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            lives.store(lives.load() + conn)
            try {
                serve(conn, OK)
            } finally {
                ended.addAndFetch(1)
            }
        }

        TestServer(chaosConfig(), handler).use { server ->
            for (i in 0 until 40) {
                // Partial, never-terminated bytes left behind by a connection that then goes back to the pool...
                sendThenClose(server.port, "GET /junk-$i HTTP/1.1\r\nX-partial: ".encodeToByteArray())
                assertTrue(eventually { ended.load() == 2 * i + 1 }, "round $i: the junk connection was never let go")

                // ...and a clean request on the connection it left: anything carried over corrupts the answer.
                assertEquals(200 to "ok", get(server.port), "round $i")
                assertTrue(eventually { ended.load() == 2 * i + 2 }, "round $i: the clean connection was never let go")
            }
        }

        val served = lives.load()
        val reused = (0 until 40).count { served[2 * it + 1] === served[2 * it] }
        assertEquals(40, reused, "precondition: the clean requests were not all served by the junk connection's object")
    }

    @Test
    fun `SO_REUSEPORT shards connections across reactors`() {
        // Each reactor answers with its own id, so the client sees which one the kernel picked.
        val shards = 4
        val (server, port) = startSharded(shards) { conn ->
            serve(conn, response(200, "${conn.reactor.id}"))
        }

        try {
            val seen = AtomicIntArray(shards)
            val failures = AtomicInt(0)
            val firstFailure = AtomicReference<String?>(null)
            val threads = TestThreads()

            repeat(4) {
                threads.start {
                    repeat(50) {
                        try {
                            val (status, body) = get(port)
                            check(status == 200) { "status $status" }
                            seen.storeAt(body.toInt(), 1)
                        } catch (e: Throwable) {
                            failures.addAndFetch(1)
                            firstFailure.compareAndSet(null, e.message ?: e.toString())
                        }
                    }
                }
            }
            threads.joinAll()

            assertEquals(0, failures.load(), "requests failed under sharding, the first: ${firstFailure.load()}")
            val used = (0 until shards).count { seen.loadAt(it) == 1 }
            assertTrue(used > 1, "200 connections landed on $used of $shards reactors: SO_REUSEPORT did not spread them")
        } finally {
            server.close()
        }
    }

    // Reactors sharing one port the kernel had free a moment ago - port 0 would give each its own - started on it, and
    // the only listeners on it: a collision with another process's SO_REUSEPORT listener is retried, not reported.
    private fun startSharded(shards: Int, handler: suspend (TcpConnection) -> Unit): Pair<KronServer, Int> {
        repeat(10) {
            val probe = Sockets.listen(0, 1, reusePort = false)
            val port = Sockets.localPort(probe)
            Native.close(probe)

            val config = ServerConfig(
                reactorCount = shards,
                ringEntries = 256,
                recvBufferSize = 4096,
                recvSlots = 256,
                tcp = TcpOptions(port = port, poolMax = 64),
            )
            val ready = Latch(shards)
            val faulted = AtomicInt(0)
            val server = KronServer(config, onStart = { ready.countDown() }, handler = handler)
            server.reactors.forEach {
                it.onFault = { _, _ -> faulted.store(1) }   // a bind someone else's socket refused
            }
            server.start()

            val started = eventually(10_000) { ready.count == 0 || faulted.load() != 0 } && faulted.load() == 0
            if (started && listenersOn(port) == ownListenersOn(port) && ownListenersOn(port).size == shards) {
                return server to port
            }

            server.close()
        }

        fail("no port held only by this server's $shards listeners after 10 tries")
    }
}

// ---- The chaos server -----------------------------------------------------------------------------------------------

// Head bytes a request may carry unterminated before it is refused, as a real server caps its request head.
private const val MAX_HEAD_BYTES = 64 * 1024

private const val OK_LINE = "HTTP/1.1 200"
private const val TOO_LARGE_LINE = "HTTP/1.1 431"

private val OK = response(200, "ok")
private val TOO_LARGE = response(431, "too large")

private fun response(status: Int, body: String): ByteArray =
    "HTTP/1.1 $status X\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body".encodeToByteArray()

// The small buffers ioxide's chaos server runs on: 4 KiB recv buffers, so a request crosses many of them.
private fun chaosConfig(): ServerConfig = testConfig(TcpOptions(port = 0, poolMax = 64), recvSlots = 256)

private fun framer(response: ByteArray = OK): suspend (TcpConnection) -> Unit = { conn ->
    serve(conn, response)
}

// A minimal framer: bytes carried across recvs, an answer per request (a head ended by CRLFCRLF), and a head that
// outgrows the cap refused once, ending the connection, rather than buffered without bound. Bodies are not read.
private suspend fun serve(conn: TcpConnection, response: ByteArray) {
    val item = RecvItem()
    val carry = HeadBuffer()

    while (true) {
        val snapshot = conn.read()
        while (conn.tryGetItem(snapshot, item)) {
            if (item.hasBuffer) {
                carry.append(item)
                conn.returnBuffer(item)
            }
        }

        // Per request, not per recv: fragmentation and coalescing both resolve here.
        val requests = carry.takeRequests()
        if (requests == 0 && carry.size > MAX_HEAD_BYTES) {
            conn.write(TOO_LARGE)
            conn.flush()
            return
        }

        repeat(requests) {
            conn.write(response)
        }
        if (requests > 0) {
            conn.flush()
        }

        if (snapshot.isClosed) {
            return
        }
    }
}

// Request bytes carried across recvs until a CRLFCRLF ends each request.
private class HeadBuffer {
    private var bytes = ByteArray(4096)

    var size = 0
        private set

    fun append(item: RecvItem) {
        if (size + item.length > bytes.size) {
            bytes = bytes.copyOf(maxOf(bytes.size * 2, size + item.length))
        }

        item.copyTo(bytes, size)
        size += item.length
    }

    // How many whole requests the carried bytes hold; those are dropped and the rest kept.
    fun takeRequests(): Int {
        var requests = 0
        var start = 0
        var i = 0
        while (i + 3 < size) {
            if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) {
                requests++
                i += 4
                start = i
            } else {
                i++
            }
        }

        if (start > 0) {
            bytes.copyInto(bytes, 0, start, size)
            size -= start
        }

        return requests
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
    }
}

// ---- The chaos clients ----------------------------------------------------------------------------------------------

private fun request(path: String = "/"): ByteArray = "GET $path HTTP/1.1\r\nHost: chaos\r\n\r\n".encodeToByteArray()

// A raw client whose every read is bounded.
private fun connect(port: Int, timeoutMs: Int = 6_000): TestSocket = TestSocket().also {
    it.setReceiveTimeout(timeoutMs)
    it.connect(port)
}

private fun noDelay(socket: TestSocket) = memScoped {
    val one = alloc<IntVar>()
    one.value = 1
    setsockopt(socket.fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
}

// One well-formed request on a fresh connection: the status and the body.
private fun get(port: Int, path: String = "/"): Pair<Int, String> = connect(port).use { client ->
    client.write(request(path))

    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val c = client.read()
        if (c < 0) {
            throw IOException("the connection ended inside the response head: $head")
        }
        head.append(c.toChar())
    }

    val text = head.toString()
    val status = text.substringAfter("HTTP/1.1 ").substringBefore(' ').toInt()
    val length = text.substringAfter("Content-Length: ").substringBefore("\r\n").toInt()
    status to client.readBytes(length).decodeToString()
}

// A fresh, well-formed request is still answered "200 ok" after any assault.
private fun assertHealthy(port: Int) {
    assertEquals(200 to "ok", get(port), "a fresh request after the assault")
}

// Writes [payload] in one go, then returns everything the server sent until the line went quiet.
private fun responses(port: Int, payload: ByteArray, settleMs: Long = 800): String = connect(port).use { client ->
    client.write(payload)
    drain(client, settleMs)
}

// Writes [payload] in [chunk]-byte pieces with a pause between, so they land as separate recvs: NODELAY stops the
// sender batching, the pause stops the receiver coalescing.
private fun responsesFragmented(port: Int, payload: ByteArray, chunk: Int, pauseMs: Long): String =
    connect(port, timeoutMs = 15_000).use { client ->
        noDelay(client)
        var at = 0
        while (at < payload.size) {
            client.write(payload.copyOfRange(at, minOf(at + chunk, payload.size)))
            at += chunk
            if (at < payload.size) {
                sleepMs(pauseMs)
            }
        }

        drain(client, settleMs = 1_200)
    }

// Waits [settleMs] for the server's answers, then reads until the line is quiet for 500 ms or the server ends it.
private fun drain(client: TestSocket, settleMs: Long): String {
    sleepMs(settleMs)
    client.setReceiveTimeout(500)

    val seen = StringBuilder()
    val chunk = ByteArray(16 * 1024)
    try {
        while (true) {
            val n = client.read(chunk, 0, chunk.size)
            if (n < 0) {
                break
            }
            for (i in 0 until n) {
                seen.append((chunk[i].toInt() and 0xFF).toChar())
            }
        }
    } catch (e: IOException) {
        // the quiet period, or a reset: what the server sent is in
    }

    return seen.toString()
}

private fun String.occurrences(line: String): Int {
    var count = 0
    var at = indexOf(line)
    while (at >= 0) {
        count++
        at = indexOf(line, at + line.length)
    }

    return count
}

// Writes and closes without reading: fire and forget.
private fun sendThenClose(port: Int, payload: ByteArray) {
    connect(port).use { client ->
        client.write(payload)
    }
}

// An abortive close: SO_LINGER at 0 makes the close a reset rather than a FIN.
private fun reset(port: Int, preface: ByteArray) {
    connect(port).use { client ->
        client.write(preface)

        memScoped {
            val abort = alloc<linger>()
            abort.l_onoff = 1
            abort.l_linger = 0
            setsockopt(client.fd, SOL_SOCKET, SO_LINGER, abort.ptr, sizeOf<linger>().convert())
        }
    }
}

private fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = Clock.monotonicMs() + timeoutMs
    while (Clock.monotonicMs() < deadline) {
        if (condition()) {
            return true
        }

        sleepMs(5)
    }

    return condition()
}

// ---- Descriptors ----------------------------------------------------------------------------------------------------

private fun fdCount(): Int = procEntries("/proc/self/fd").size

// The descriptor count once it stops moving: connections recycle on the reactor's own schedule.
private fun stableFdCount(): Int {
    val deadline = Clock.monotonicMs() + 5_000
    var previous = fdCount()
    while (Clock.monotonicMs() < deadline) {
        sleepMs(100)
        val current = fdCount()
        if (current == previous) {
            return current
        }
        previous = current
    }

    println("[fdcount] never settled within 5000 ms; using $previous")
    return previous
}

// The names in a /proc directory, without "." and "..".
private fun procEntries(path: String): List<String> {
    val dir = platform.posix.opendir(path) ?: return emptyList()
    try {
        val names = ArrayList<String>()
        while (true) {
            val entry = platform.posix.readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") {
                names.add(name)
            }
        }

        return names
    } finally {
        platform.posix.closedir(dir)
    }
}

// What a descriptor of this process refers to ("socket:[inode]"), or null when it is not open.
private fun fdTarget(fd: String): String? = memScoped {
    val buffer = allocArray<ByteVar>(256)
    val n = platform.posix.readlink("/proc/self/fd/$fd", buffer, 255u)
    if (n < 0) null else buffer.readBytes(n.toInt()).decodeToString()
}

// Every TCP socket in the namespace listening on [port], v4 and v6, by inode: /proc/net lists other processes' too.
private fun listenersOn(port: Int): Set<String> {
    val found = HashSet<String>()
    for (table in listOf("/proc/self/net/tcp", "/proc/self/net/tcp6")) {
        val rows = readFileBytes(table)?.decodeToString()?.lines()?.drop(1) ?: continue
        for (row in rows) {
            // sl local_address rem_address st ... inode: "0100007F:4E20", state 0A is LISTEN.
            val f = row.trim().split(Regex(" +"))
            if (f.size > 9 && f[3] == "0A" && f[1].substringAfter(':').toInt(16) == port) {
                found.add("socket:[${f[9]}]")
            }
        }
    }

    return found
}

// The listeners on [port] that this process holds.
private fun ownListenersOn(port: Int): Set<String> {
    val mine = procEntries("/proc/self/fd").mapNotNull { fdTarget(it) }.toSet()
    return listenersOn(port).filter { it in mine }.toSet()
}
