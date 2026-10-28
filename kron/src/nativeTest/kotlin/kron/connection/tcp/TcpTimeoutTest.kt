package kron

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.F_GETFD
import platform.posix.MSG_NOSIGNAL
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.accept
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.timeval
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two clocks on a connection - [TcpOptions.readTimeoutMs] reaps one gone quiet, [TcpOptions.sendTimeoutMs] one
 * whose peer stopped draining - and the FIN a handler's release or [TcpConnection.shutdownWrite] sends.
 *
 * Timeouts are hundreds of milliseconds, against the sweep's 250 ms tick: every deadline is generous against the
 * tick, and the assertion is that a connection closes at all, never at a particular moment.
 */
class TcpTimeoutTest {
    private fun config(readMs: Long, sendMs: Long) = testConfig(
        TcpOptions(port = 0, writeSlabSize = 256 * 1024, poolMax = 64, readTimeoutMs = readMs, sendTimeoutMs = sendMs),
        recvSlots = 256,
    )

    // ---- Helpers ----------------------------------------------------------------------------------------------

    // An operation the handler starts now and waits for later, as a C# handler holds a ValueTask: a coroutine of its
    // own, run inline until it suspends and resumed by the reactor. Reactor thread only.
    private class Pending(block: suspend () -> Unit) {
        var isCompleted = false
            private set

        private var failure: Throwable? = null
        private var waiter: Continuation<Unit>? = null

        init {
            block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
                failure = result.exceptionOrNull()
                isCompleted = true
                waiter?.let {
                    waiter = null
                    it.resume(Unit)
                }
            })
        }

        suspend fun await() {
            if (!isCompleted) {
                suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    waiter = cont
                    COROUTINE_SUSPENDED
                }
            }

            failure?.let { throw it }
        }
    }

    // Takes every item of [snapshot] and hands its buffer back.
    private fun consume(conn: TcpConnection, snapshot: RecvSnapshot) {
        val item = RecvItem()
        while (conn.tryGetItem(snapshot, item)) {
            conn.returnBuffer(item)
        }
    }

    // Answers every read with "ok", so a test can keep a connection demonstrably alive.
    private val okPerRead: suspend (TcpConnection) -> Unit = { conn ->
        while (true) {
            val snapshot = conn.read()
            consume(conn, snapshot)

            conn.write(OK)
            conn.flush()
            if (snapshot.isClosed) {
                break
            }
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

    // Reads to the end of the stream as a slow reader does, pausing after every read, and checks each byte against
    // [pattern]. Returns how many arrived.
    private fun readSlowlyToEnd(client: TestSocket, pattern: (Int) -> Byte): Int {
        val chunk = ByteArray(16 * 1024)
        var total = 0

        while (true) {
            val n = client.read(chunk, 0, chunk.size)
            if (n < 0) {
                return total
            }

            for (i in 0 until n) {
                if (chunk[i] != pattern(total + i)) {
                    fail("byte ${total + i} of the response is not the one written")
                }
            }

            total += n
            sleepMs(5)
        }
    }

    // ---- The read timeout --------------------------------------------------------------------------------------

    @Test
    fun `a read the peer stopped answering is closed at the read timeout and wakes the handler`() {
        val woken = AtomicInt(-1)   // 1: the parked read woke with a closed snapshot
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())

            // Parked with a peer gone quiet: only the sweep ends this.
            woken.store(if (conn.read().isClosed) 1 else 0)
        }

        TestServer(config(readMs = 500, sendMs = 0), handler).use { server ->
            server.connect(receiveTimeoutMs = SWEEP_GRACE_MS.toInt()).use { client ->
                // Bytes, then silence: an active connection going quiet, not one that never spoke.
                client.write("hi")

                assertEquals(-1, client.read(), "the quiet connection was not closed")
                assertTrue(eventually(SWEEP_GRACE_MS) { woken.load() >= 0 }, "the handler was never woken by the sweep")
                assertEquals(1, woken.load(), "the handler woke, but not with a closed snapshot")
            }
        }
    }

    @Test
    fun `a connection still talking is left alone`() {
        TestServer(config(readMs = 500, sendMs = 0), okPerRead).use { server ->
            server.connect(receiveTimeoutMs = 2_000).use { client ->
                // 2 s of traffic at 200 ms intervals against a 500 ms timeout: if activity did not restart the clock,
                // this closes long before the loop ends.
                repeat(10) {
                    client.write("ping")
                    assertEquals("ok", client.readBytes(2).decodeToString(), "exchange $it")
                    sleepMs(200)
                }

                client.write("ping")
                assertEquals("ok", client.readBytes(2).decodeToString(), "the connection did not survive its traffic")
            }
        }
    }

    @Test
    fun `a quiet connection is reaped while another keeps the reactor busy`() {
        // A busy reactor's wait never runs out - a completion always ends it first - so a sweep that waited for an
        // idle moment would leave every quiet connection open for as long as anyone else was talking.
        val accepted = AtomicInt(0)
        val quietClosed = AtomicInt(-1)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            if (accepted.addAndFetch(1) > 1) {
                // The busy one: takes what comes, as fast as it comes.
                while (true) {
                    val snapshot = conn.read()
                    consume(conn, snapshot)
                    if (snapshot.isClosed) {
                        break
                    }
                }
            } else {
                consume(conn, conn.read())
                quietClosed.store(if (conn.read().isClosed) 1 else 0)
            }
        }

        TestServer(config(readMs = 500, sendMs = 0), handler).use { server ->
            server.connect().use { quiet ->
                quiet.write("hi")
                assertTrue(eventually(2_000) { accepted.load() == 1 }, "the quiet connection never reached its handler")

                server.connect().use { busy ->
                    // A stream without pauses: past a tick's deadline the reactor waits 1 ms at most, so request and
                    // answer would leave it gaps that run the wait out.
                    val chunk = ByteArray(64 * 1024)
                    var streamed = 0L
                    val until = Clock.monotonicMs() + SWEEP_GRACE_MS
                    while (quietClosed.load() < 0 && Clock.monotonicMs() < until) {
                        busy.write(chunk)
                        streamed += chunk.size
                    }

                    assertTrue(
                        quietClosed.load() >= 0,
                        "the quiet connection outlived its 500 ms read timeout by $SWEEP_GRACE_MS ms while another " +
                            "streamed ${streamed / (1024 * 1024)} MiB: the sweep never ran on the busy reactor",
                    )
                    assertEquals(1, quietClosed.load(), "the handler woke, but not with a closed snapshot")
                }
            }
        }
    }

    @Test
    fun `with the read timeout at 0 a quiet connection stays open`() {
        TestServer(config(readMs = 0, sendMs = 0), okPerRead).use { server ->
            server.connect(receiveTimeoutMs = 2_000).use { client ->
                sleepMs(1_500)   // quiet for several ticks

                client.write("ping")
                assertEquals("ok", client.readBytes(2).decodeToString(), "the quiet connection was closed")
            }
        }
    }

    @Test
    fun `a handler busy for longer than the read timeout still answers`() {
        // A slow answer with nothing on the wire meanwhile: the server is not waiting on the peer.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())
            RingTimer(conn.reactor).delay(2_000)   // four read timeouts

            conn.write("done".encodeToByteArray())
            conn.flush()

            conn.read()   // parked until the client hangs up
        }

        TestServer(config(readMs = 500, sendMs = 0), handler).use { server ->
            server.connect(receiveTimeoutMs = 6_000).use { client ->
                client.write("hi")
                assertEquals("done", client.readBytes(4).decodeToString())
            }
        }
    }

    @Test
    fun `a server that keeps sending is not waiting on its peer`() {
        // A push feed: a read parked throughout and a peer that never answers. The server's own sends restart the
        // clock, so four read timeouts of pushing go through.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())

            val parked = Pending {
                conn.read()   // until the client hangs up
            }

            val timer = RingTimer(conn.reactor)
            repeat(16) {   // 16 x 150 ms
                timer.delay(150)
                conn.write("tick".encodeToByteArray())
                conn.flush()
            }

            parked.await()
        }

        TestServer(config(readMs = 500, sendMs = 0), handler).use { server ->
            server.connect(receiveTimeoutMs = 4_000).use { client ->
                client.write("hi")
                assertEquals("tick".repeat(16), client.readBytes(64).decodeToString())
            }
        }
    }

    // ---- The FIN a release sends --------------------------------------------------------------------------------

    @Test
    fun `a peer that ignores the FIN is shut down at the read timeout`() {
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())   // and return: the release sends the FIN
        }

        TestServer(config(readMs = 500, sendMs = 0), handler).use { server ->
            server.connect(receiveTimeoutMs = 2_000).use { client ->
                client.write("hi")
                assertEquals(-1, client.read(), "no FIN after the handler returned")

                // Past the deadline the server's socket is gone: a write draws a reset, which only the next write can
                // see - after a FIN a read returns the end of the stream regardless.
                sleepMs(SWEEP_GRACE_MS / 2)

                val outcome = try {
                    client.write("still here")
                    sleepMs(300)
                    client.write("still here")
                    "both writes accepted - the server is still holding the connection"
                } catch (e: IOException) {
                    "reset"
                }
                assertEquals("reset", outcome)
            }
        }
    }

    @Test
    fun `a handler that lets go mid-flush gets the whole flush out before the FIN`() {
        // The FIN waits for the flush, so the peer's time to close must too: a read timeout far shorter than the send
        // takes may not cut the send short.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())

            conn.write(ByteArray(SLOW_BODY_BYTES) { pattern(it) })
            Pending {
                conn.flush()   // let go with it still in flight
            }
        }

        TestServer(config(readMs = 500, sendMs = 30_000), handler).use { server ->
            TestSocket().use { client ->
                client.setReceiveBuffer(4096)   // a slow reader: the flush outlives the read timeout
                client.setReceiveTimeout(5_000)
                client.connect(server.port)
                client.write("hi")

                val start = Clock.monotonicMs()
                val total = readSlowlyToEnd(client, ::pattern)
                val took = Clock.monotonicMs() - start

                assertEquals(SLOW_BODY_BYTES, total, "the FIN cut the flush short")
                assertTrue(took > 1_000, "the whole flush took $took ms, within two read timeouts: this proved nothing")
            }
        }
    }

    @Test
    fun `a handler that lets go after its reactor stopped leaves a reused fd alone`() {
        releaseAfterStop { conn ->
            conn.decRef()
        }
    }

    // ---- Half-close -------------------------------------------------------------------------------------------

    @Test
    fun `a shutdownWrite after its reactor stopped leaves a reused fd alone`() {
        releaseAfterStop { conn ->
            conn.shutdownWrite()
            conn.decRef()
        }
    }

    @Test
    fun `shutdownWrite with a flush in flight gets the whole flush out first`() {
        // The peer drains slowly, so the flush is still the kernel's when shutdownWrite runs: a FIN sent there would
        // cut it short.
        val inFlight = AtomicInt(-1)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())

            conn.write(ByteArray(SLOW_BODY_BYTES) { pattern(it) })
            val flush = Pending {
                conn.flush()
            }
            conn.shutdownWrite()
            inFlight.store(if (flush.isCompleted) 0 else 1)
            flush.await()

            conn.read()   // until the client closes, having read to the FIN
        }

        TestServer(config(readMs = 0, sendMs = 30_000), handler).use { server ->
            TestSocket().use { client ->
                client.setReceiveBuffer(4096)
                client.setReceiveTimeout(5_000)
                client.connect(server.port)
                client.write("hi")

                val total = readSlowlyToEnd(client, ::pattern)

                assertEquals(1, inFlight.load(), "the flush was done before shutdownWrite ran: this proved nothing")
                assertEquals(SLOW_BODY_BYTES, total, "the FIN cut the flush short")
            }
        }
    }

    // Teardown closes every fd without releasing the reactor's ref, so a release after it still sees a live
    // connection. Its FIN must not reach whichever socket took the number since.
    private fun releaseAfterStop(release: (TcpConnection) -> Unit) {
        val captured = AtomicReference<Pair<TcpConnection, Continuation<Unit>>?>(null)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())

            // Resumed by the test on its own thread, once the reactor has stopped.
            suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                captured.store(conn to cont)
                COROUTINE_SUSPENDED
            }
            release(conn)
        }

        // Opened first, so it does not take the number under test.
        val listener = Sockets.listen(0, 64, reusePort = false)
        val clients = ArrayList<TestSocket>()
        val accepted = ArrayList<Int>()

        try {
            // Closed at the end of this block: the reactor stops with the handler still holding the connection.
            val (conn, gate) = TestServer(config(readMs = 0, sendMs = 0), handler).use { server ->
                val client = server.connect()
                clients += client
                client.write("hi")

                assertTrue(eventually(5_000) { captured.load() != null }, "the handler never saw the connection")
                captured.load()!!
            }
            val fd = conn.clientFd

            // Linux hands out the lowest free number, so fd goes to one of the next sockets.
            var victim = -1
            var peer = -1
            for (i in 0 until 64) {
                val c = TestSocket()
                clients += c
                c.connect(Sockets.localPort(listener))

                val a = accept(listener, null, null)
                check(a >= 0) { "accept failed with errno $errno" }
                accepted += a

                if (c.fd == fd) {
                    victim = c.fd
                    peer = a
                    break
                }
                if (a == fd) {
                    victim = a
                    peer = c.fd
                    break
                }
            }
            assertTrue(victim >= 0, "fd $fd was not handed out again within 64 sockets")

            gate.resume(Unit)   // the handler lets go here, on this thread

            assertEquals("ok", oneByteAcross(victim, peer))
        } finally {
            clients.forEach {
                it.close()
            }
            accepted.forEach {
                platform.posix.close(it)
            }
            platform.posix.close(listener)
        }
    }

    // One byte from [from] to [to], its connected peer: "ok", or what went wrong.
    private fun oneByteAcross(from: Int, to: Int): String {
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = 2.convert()
            tv.tv_usec = 0.convert()
            setsockopt(to, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val byte = alloc<ByteVar>()
            byte.value = 'b'.code.toByte()
            if (send(from, byte.ptr, 1.convert(), MSG_NOSIGNAL) != 1L) {
                return "the unrelated socket failed to send: errno $errno"
            }

            return when (recv(to, byte.ptr, 1.convert(), 0)) {
                1L -> "ok"
                0L -> "eof on the unrelated socket"
                else -> "the unrelated socket failed to receive: errno $errno"
            }
        }
    }

    // ---- The send timeout -------------------------------------------------------------------------------------

    @Test
    fun `control - with the send timeout at 0 a stalled flush stays parked`() {
        // The proof that a stalled flush is released by the send timeout and not by some other teardown: with the
        // clock off the same reproduction must not come back.
        val flushing = AtomicInt(0)
        val returned = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())

            conn.write(ByteArray(STALLED_BYTES))
            flushing.store(1)
            conn.flush()
            returned.store(1)
        }

        TestServer(config(readMs = 0, sendMs = 0), handler).use { server ->
            TestSocket().use { client ->
                // Before the connect, so it is the window advertised: the send stalls within a few megabytes.
                client.setReceiveBuffer(4096)
                client.setReceiveTimeout(5_000)
                client.connect(server.port)
                client.write("go")   // and never reads

                assertTrue(eventually(5_000) { flushing.load() == 1 }, "the handler never started its flush")
                sleepMs(2_000)
                assertEquals(0, returned.load(), "the stalled flush was released with no send timeout set")
            }   // the client leaves unread: its reset fails the send, and the handler ends
        }
    }

    @Test
    fun `a flush released by the send timeout closes the connection's descriptor`() {
        val fdSeen = AtomicInt(-1)
        val returned = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            consume(conn, conn.read())
            fdSeen.store(conn.clientFd)

            conn.write(ByteArray(STALLED_BYTES))
            conn.flush()
            returned.store(1)
        }

        TestServer(config(readMs = 0, sendMs = 500), handler).use { server ->
            TestSocket().use { client ->
                client.setReceiveBuffer(4096)
                client.setReceiveTimeout(5_000)
                client.connect(server.port)
                client.write("go")   // and never reads

                assertTrue(eventually(5_000) { fdSeen.load() >= 0 }, "the handler never saw the connection")
                val fd = fdSeen.load()

                // Deadlines many sweeps past the send timeout, not timing assertions.
                assertTrue(
                    eventually(10_000) { returned.load() == 1 },
                    "the flush is still parked 10 s into a 500 ms send timeout",
                )
                assertTrue(
                    eventually(5_000) { fcntl(fd, F_GETFD) == -1 },
                    "the connection's descriptor $fd was never closed",
                )
            }
        }
    }

    private companion object {
        // Two ticks plus slack: what "the sweep has certainly run" costs.
        const val SWEEP_GRACE_MS = 4_000L

        // Far past what a 4 KiB window and the socket buffers hold, so the send stalls and stays stalled.
        const val STALLED_BYTES = 16 * 1024 * 1024

        // What a reader pausing 5 ms per read takes a few seconds to drain through a 4 KiB window.
        const val SLOW_BODY_BYTES = 2 * 1024 * 1024

        val OK = "ok".encodeToByteArray()

        fun pattern(i: Int): Byte = (i % 251).toByte()
    }
}
