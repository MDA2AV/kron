package kron

import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Graceful stop: [Reactor.stopAccepting], and [KronServer.close] with a grace period. */
class StopTest {
    // One reactor running [handler] on a port the kernel picks, started.
    private fun server(handler: suspend (TcpConnection) -> Unit = ::echo): KronServer {
        val ready = Latch(1)
        val server = KronServer(
            testConfig(),
            onStart = {
                ready.countDown()
            },
            handler = handler,
        ).start()

        check(ready.await(10_000)) { "the reactor never started" }
        return server
    }

    private val KronServer.port: Int get() = reactors[0].listenPorts[0]

    private fun connect(port: Int): TestSocket = TestSocket().also {
        it.setReceiveTimeout(5_000)
        it.connect(port)
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

    // The client's view of a connection the server closed: the end of the stream, or a reset (ECONNRESET) - not a
    // read that timed out.
    private fun ended(client: TestSocket): Boolean = try {
        client.read() == -1
    } catch (e: IOException) {
        e.message?.endsWith("errno 104") == true
    }

    @Test
    fun `a reactor that stops accepting refuses new connections and serves its open ones`() {
        val server = server()
        try {
            val reactor = server.reactors[0]
            val port = server.port

            connect(port).use { client ->
                client.write("one")
                assertEquals("one", client.readBytes(3).decodeToString())

                reactor.stopAccepting()
                assertTrue(eventually(2_000) { refused(port) }, "the port still takes connections")
                assertTrue(reactor.draining)

                client.write("two")
                assertEquals("two", client.readBytes(3).decodeToString())
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `close with a grace period returns once the last connection has ended`() {
        val server = server()
        val client = connect(server.port)

        val threads = TestThreads()
        val returnedAfter = AtomicLong(-1)
        val start = Clock.monotonicMs()
        threads.start {
            server.close(5_000)
            returnedAfter.store(Clock.monotonicMs() - start)
        }

        try {
            sleepMs(200)
            assertEquals(-1L, returnedAfter.load(), "close returned with a connection still open")
        } finally {
            client.close()
            threads.joinAll()
        }

        assertTrue(returnedAfter.load() in 200L..2_000L, "close returned after ${returnedAfter.load()} ms")
    }

    @Test
    fun `the grace period bounds a connection that never ends`() {
        val server = server()

        connect(server.port).use { client ->
            val start = Clock.monotonicMs()
            server.close(300)
            val took = Clock.monotonicMs() - start

            assertTrue(took in 300L..2_000L, "close took $took ms")
            assertTrue(ended(client), "the connection outlived the reactor")
        }
    }

    @Test
    fun `a reactor with no connection open stops at once`() {
        val server = server()

        val start = Clock.monotonicMs()
        server.close(5_000)
        val took = Clock.monotonicMs() - start

        assertTrue(took < 1_000, "close took $took ms with no connection open")
    }

    @Test
    fun `a handler that reads draining ends its keep-alive connection after the current answer`() {
        // echo, except that it lets the connection go once an answer has left a draining reactor.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            while (true) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) {
                        conn.write(item.address, item.length)
                    }
                    conn.returnBuffer(item)
                }

                conn.flush()
                if (snapshot.isClosed || conn.reactor.draining) {
                    break
                }
            }
        }

        val server = server(handler)
        val client = connect(server.port)

        val threads = TestThreads()
        val returnedAfter = AtomicLong(-1)
        val start = Clock.monotonicMs()
        threads.start {
            server.close(5_000)
            returnedAfter.store(Clock.monotonicMs() - start)
        }

        try {
            assertTrue(eventually(2_000) { server.reactors[0].draining }, "the reactor never started draining")

            // The request in flight is answered, then the server ends the connection.
            client.write("last")
            assertEquals("last", client.readBytes(4).decodeToString())
            assertTrue(ended(client), "the connection outlived its last answer")
        } finally {
            // As any client does once the server has closed its side; then the reactor has nothing left.
            client.close()
            threads.joinAll()
        }

        assertTrue(returnedAfter.load() < 2_000, "close waited ${returnedAfter.load()} ms for a closed connection")
    }
}
