package kron

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.IPPROTO_TCP
import platform.posix.TCP_NODELAY
import platform.posix.closedir
import platform.posix.errno
import platform.posix.getsockopt
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.socklen_tVar
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Core TCP: accept-and-recycle churn, the write slab's limit, extra ports, a handler that throws after letting go,
 * TCP_NODELAY, an off-reactor flush racing the close, and teardown.
 */
class CoreTest {
    // ---- Helpers ----------------------------------------------------------------------------------------------

    // Takes the request each exchange here starts with, and hands its buffers back.
    private suspend fun readRequest(conn: TcpConnection) {
        val item = RecvItem()
        val snapshot = conn.read()
        while (conn.tryGetItem(snapshot, item)) {
            conn.returnBuffer(item)
        }
    }

    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = Clock.monotonicMs() + timeoutMs
        while (Clock.monotonicMs() < deadline) {
            if (condition()) {
                return true
            }

            sleepMs(5)
        }

        return condition()
    }

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

    // The client's view of a connection the server closed: the end of the stream, or a reset (ECONNRESET) - not a
    // read that timed out.
    private fun ended(client: TestSocket): Boolean = try {
        client.read() == -1
    } catch (e: IOException) {
        e.message?.endsWith("errno 104") == true
    }

    // Whether a connect to [port] is refused: no listener is left on it.
    private fun refused(port: Int): Boolean {
        val socket = TestSocket()
        try {
            socket.connect(port)
            return false
        } catch (e: IOException) {
            return true
        } finally {
            socket.close()
        }
    }

    // Descriptors this process holds, once the count stops moving: a recycle or a teardown lands on the reactor's
    // schedule, not the test's.
    private fun stableFdCount(): Int {
        var previous = fdCount()
        val deadline = Clock.monotonicMs() + 5_000

        while (Clock.monotonicMs() < deadline) {
            sleepMs(100)
            val current = fdCount()
            if (current == previous) {
                return current
            }
            previous = current
        }

        return previous
    }

    private fun fdCount(): Int {
        val dir = opendir("/proc/self/fd") ?: error("opendir(/proc/self/fd) failed with errno $errno")
        try {
            var count = 0
            while (true) {
                val name = readdir(dir)?.pointed?.d_name?.toKString() ?: break
                if (name != "." && name != "..") {
                    count++
                }
            }

            return count
        } finally {
            closedir(dir)
        }
    }

    // The connection's own TCP_NODELAY as the server sees it: "1", "0", or "error".
    private fun noDelayOf(fd: Int): String = memScoped {
        val value = alloc<IntVar>()
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<IntVar>().convert()

        if (getsockopt(fd, IPPROTO_TCP, TCP_NODELAY, value.ptr, length.ptr) == 0) "${value.value}" else "error"
    }

    private val origin = TimeSource.Monotonic.markNow()

    // Never 0, which the race below reads as "not yet".
    private fun nowUs(): Long = origin.elapsedNow().inWholeMicroseconds + 1

    private fun spinUntil(us: Long) {
        while (nowUs() < us) {
            // microseconds: a sleep would overshoot them all
        }
    }

    // ---- Tests ------------------------------------------------------------------------------------------------

    @Test
    fun `fifty connections one after another are each served and recycled`() {
        TestServer(testConfig(), ::echo).use { server ->
            repeat(50) { i ->
                server.connect().use { client ->
                    client.write("hello $i")
                    assertEquals("hello $i", client.readBytes("hello $i".length).decodeToString(), "connection $i")
                }
            }
        }
    }

    @Test
    fun `a span the write slab cannot grow to fails the write instead of spinning the reactor`() {
        // Doubling the slab's Int size past 1 GiB wrapped it to 0, so its growth never reached the size asked for and
        // the reactor spun inside the call forever (ioxide). Nothing is allocated before the refusal.
        val body = ByteArray(48 * 1024) { 'x'.code.toByte() }
        val outcome = AtomicReference<String?>(null)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            readRequest(conn)

            outcome.store(
                try {
                    conn.getBuffer((1 shl 30) + 1)
                    "a buffer"
                } catch (e: OutOfMemoryError) {
                    "refused: ${e.message}"
                },
            )

            conn.write(body)   // 48 KiB against the 4 KiB slab: it still grows for what it can hold
            conn.flush()
        }

        val server = TestServer(testConfig(TcpOptions(port = 0, writeSlabSize = 4096, poolMax = 8)), handler)
        val client = server.connect(receiveTimeoutMs = 8_000)
        client.write("go")

        // On failure the server is left running: its reactor spins in the handler, and close would wait for it forever.
        val refusal = if (eventually(10_000) { outcome.load() != null }) {
            outcome.load()!!
        } else {
            fail("getBuffer never returned: the slab's growth spun on the reactor")
        }

        server.use {
            client.use {
                assertTrue(refusal.startsWith("refused"), "a buffer past 1 GiB came back as $refusal")
                assertContentEquals(body, client.readBytes(body.size), "the response after the refusal")
            }
        }
    }

    @Test
    fun `each extra port's connections carry its listenerPort`() {
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            readRequest(conn)
            conn.write("${conn.listenerPort}".encodeToByteArray())
            conn.flush()
        }

        TestServer(testConfig(TcpOptions(port = 0, extraPorts = intArrayOf(0))), handler).use { server ->
            val ports = server.reactor.listenPorts
            assertEquals(2, ports.size, "listeners")
            assertTrue(ports[0] != ports[1], "both listeners on ${ports[0]}")

            for (port in ports) {
                TestSocket().use { client ->
                    client.setReceiveTimeout(5_000)
                    client.connect(port)
                    client.write("which")
                    assertEquals("$port", client.readToEnd().decodeToString(), "the connection made to $port")
                }
            }
        }
    }

    @Test
    fun `a handler that lets go and then throws closes cleanly and leaves no table slot behind`() {
        // The fault path released the handler's ref a second time, recycling the connection under the reactor's ref:
        // its table slot outlived it, and teardown closed a reused fd through it (ioxide #242).
        val served = AtomicReference<List<Pair<Int, TcpConnection>>>(emptyList())

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            served.store(served.load() + (conn.clientFd to conn))
            RingTimer(conn.reactor).delay(1)

            conn.decRef()
            throw IllegalStateException("post-release boom (test)")
        }

        val config = testConfig(TcpOptions(port = 0, writeSlabSize = 4096, poolMax = 8), recvBufferSize = 1024)
        TestServer(config, handler).use { server ->
            fun round(i: Int) {
                server.connect(receiveTimeoutMs = 2_000).use { client ->
                    assertTrue(ended(client), "connection $i was not closed after its faulting handler let go")
                }
            }

            // One round before the baseline: the runtime opens its own binary to symbolize the first stack trace a
            // fault prints, and keeps it open.
            round(0)
            val before = stableFdCount()

            for (i in 1..10) {
                round(i)
            }

            val leaked = stableFdCount() - before
            assertTrue(leaked <= 0, "$leaked descriptors leaked across 10 handlers that let go and then threw")

            // Each connection is recycled by now, so none may still hold the slot of the fd it had.
            val stale = onReactor(server.reactor) {
                val tcp = server.reactor.tcp!!
                served.load().count { (fd, conn) -> tcp.connAt(fd, conn.generation and 0xFFFF) === conn }
            }
            assertEquals(0, stale, "table slots still holding a connection that was recycled")
        }
    }

    @Test
    fun `an accepted connection has TCP_NODELAY`() {
        // IPv4 only: kron listens on IPv4, so ioxide's IPv4-mapped and IPv6 cases have no twin.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            readRequest(conn)
            conn.write(noDelayOf(conn.clientFd).encodeToByteArray())
            conn.flush()
        }

        TestServer(testConfig(), handler).use { server ->
            server.connect().use { client ->
                client.write("?")
                assertEquals("1", client.readToEnd().decodeToString())
            }
        }
    }

    @Test
    fun `a flush from another thread that races the connection's close completes`() {
        // ioxide armed an off-reactor flush on the caller's thread, and a close on the reactor landing as it armed
        // released it into a signal reset after it: the caller waited forever. kron carries the whole flush to the
        // reactor, where the two meet in one order or the other. Each round races one flush against the peer's close,
        // milliseconds to microseconds apart either way, and in both orders the caller must come back.
        val parked = AtomicReference<Continuation<Unit>?>(null)
        val closedAtFlush = AtomicInt(-1)
        val flushed = AtomicInt(0)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            readRequest(conn)
            conn.write(byteArrayOf(1))

            // Resumed by the test on its own thread, at the round's moment.
            suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                parked.store(cont)
                COROUTINE_SUSPENDED
            }

            // Posted ahead of the flush's own task, so it runs on the reactor right before it and sees what it sees.
            conn.reactor.post {
                closedAtFlush.store(if (conn.isClosed) 1 else 0)
            }
            conn.flush()
            flushed.addAndFetch(1)
        }

        var closedFirst = 0
        var openFirst = 0
        val threads = TestThreads()

        TestServer(testConfig(), handler).use { server ->
            for (round in 0 until RACE_ROUNDS) {
                val client = server.connect()
                client.write("x")
                assertTrue(eventually(5_000) { parked.load() != null }, "round $round: the handler never parked")
                val cont = parked.exchange(null)!!

                // Positive: the close comes that many microseconds after the flush; negative, before it.
                val skewUs = RACE_SKEWS_US[round % RACE_SKEWS_US.size]
                val ready = AtomicInt(0)
                val go = AtomicLong(0)
                threads.start {
                    ready.store(1)
                    while (go.load() == 0L) {
                        // the round has not started
                    }
                    spinUntil(go.load() + maxOf(skewUs, 0L))
                    client.close()
                }

                // Both sides spinning before the round starts: a thread's start-up would outweigh the skew.
                while (ready.load() == 0) {
                    // the closer is not running yet
                }

                val start = nowUs()
                go.store(start)
                spinUntil(start + maxOf(-skewUs, 0L))
                cont.resume(Unit)   // the handler flushes from this thread

                val came = eventually(5_000) { flushed.load() == round + 1 }
                threads.joinAll()

                val closed = closedAtFlush.exchange(-1) == 1
                val apart = if (skewUs < 0) "${-skewUs} us before" else "$skewUs us after"
                assertTrue(
                    came,
                    "round $round, the close $apart the flush: the flush never came back (it found the connection " +
                        "${if (closed) "closed" else "open"})",
                )

                if (closed) {
                    closedFirst++
                } else {
                    openFirst++
                }
            }
        }

        assertTrue(
            closedFirst >= 10 && openFirst >= 10,
            "the close came first in $closedFirst rounds and the flush in $openFirst: the race never ran both ways",
        )
    }

    @Test
    fun `close ends the reactor thread and closes its descriptors and its port`() {
        val before = stableFdCount()

        val server = TestServer(testConfig(), ::echo)
        val port = server.port
        try {
            server.connect().use { client ->
                client.write("one")
                assertEquals("one", client.readBytes(3).decodeToString())
            }

            // One connection left open across the stop, so teardown has live state to clean up.
            server.connect().use { open ->
                open.write("two")
                assertEquals("two", open.readBytes(3).decodeToString())

                val start = Clock.monotonicMs()
                server.close()
                val took = Clock.monotonicMs() - start
                assertTrue(took < 3_000, "the reactor thread took $took ms to end")
            }
        } finally {
            server.close()   // nothing left to do once closed; here for a failure above
        }

        val leaked = stableFdCount() - before
        assertEquals(0, leaked, "teardown left $leaked descriptors open: the ring, a listener, the wake fd or a socket")
        assertTrue(refused(port), "the port still takes connections after close")
    }

    private companion object {
        const val RACE_ROUNDS = 260

        // Microseconds from the flush to the close: both orders, from far apart to as close as two threads get. On the
        // x64 box this was written on, the order on the reactor flips at about -10.
        val RACE_SKEWS_US = longArrayOf(-3000, -300, -100, -50, -30, -20, -15, -10, -5, 0, 10, 100, 3000)
    }
}
