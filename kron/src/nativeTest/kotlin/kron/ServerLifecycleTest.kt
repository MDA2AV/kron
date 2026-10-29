package kron

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EADDRINUSE
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.bind
import platform.posix.errno
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KronServer]'s own lifecycle: close, join and close(gracePeriodMs) after a close, racing a join or from a handler,
 * and the port given back.
 */
class ServerLifecycleTest {
    // One reactor echoing on a port the kernel picks, started.
    private fun start(): KronServer {
        val ready = Latch(1)
        val server = KronServer(
            testConfig(),
            onStart = {
                ready.countDown()
            },
            handler = ::echo,
        ).start()

        if (!ready.await(10_000)) {
            server.close()
            error("the reactor never started")
        }

        return server
    }

    private val KronServer.port: Int get() = reactors[0].listenPorts[0]

    // The control each test starts from: the server answers on its port.
    private fun assertServes(port: Int) {
        TestSocket().use { client ->
            client.setReceiveTimeout(5_000)
            client.connect(port)
            client.write("hi")
            assertEquals("hi", client.readBytes(2).decodeToString())
        }
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

    private fun millisTaken(block: () -> Unit): Long {
        val start = Clock.monotonicMs()
        block()
        return Clock.monotonicMs() - start
    }

    // A listener of our own on [port], with SO_REUSEADDR and without SO_REUSEPORT, as most servers open one: 0 once
    // it binds and listens, else the errno. Unlike another kron listener, it cannot share the port with one still open.
    private fun bindPlain(port: Int): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket failed with errno $errno" }

        try {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())

            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            address.sin_port = (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toUShort()   // htons
            address.sin_addr.s_addr = 0u   // INADDR_ANY, as the server binds

            if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                return errno
            }
            if (listen(fd, 16) != 0) {
                return errno
            }

            0
        } finally {
            platform.posix.close(fd)
        }
    }

    @Test
    fun `close twice is harmless`() {
        val server = start()
        val port = server.port
        assertServes(port)

        server.close()
        assertTrue(refused(port), "the port still takes connections after close")

        val took = millisTaken {
            server.close()
        }
        assertTrue(took < 1_000, "the second close took $took ms")
        assertTrue(refused(port), "the port takes connections again after the second close")
    }

    @Test
    fun `join returns after close`() {
        val server = start()
        assertServes(server.port)
        server.close()

        val took = millisTaken {
            server.join()
            server.join()
        }
        assertTrue(took < 1_000, "join after close took $took ms")
    }

    @Test
    fun `a thread blocked in join returns once close is called`() {
        val server = start()
        assertServes(server.port)

        val threads = TestThreads()
        val joined = Latch(1)
        threads.start {
            server.join()
            joined.countDown()
        }

        try {
            sleepMs(50)
            assertEquals(1, joined.count, "join returned while the server was running")

            server.close()
            assertTrue(joined.await(5_000), "join never returned after close")
        } finally {
            server.close()
            threads.joinAll()
        }
    }

    @Test
    fun `close waits for the teardown while another thread is blocked in join`() {
        // Ten rounds: close() returned with the reactor still listening in 27 of 30 rounds when measured.
        repeat(10) { round ->
            val server = start()
            val port = server.port
            val threads = TestThreads()
            threads.start {
                server.join()
            }

            try {
                sleepMs(20)
                server.close()
                assertTrue(refused(port), "round $round: close returned with the reactor still listening")
            } finally {
                server.close()
                threads.joinAll()
            }
        }
    }

    @Test
    fun `a second thread in join waits for the teardown too`() {
        val server = start()
        val port = server.port
        assertServes(port)

        // Each join checks, as it returns, that the reactor is gone.
        val threads = TestThreads()
        val joined = Latch(2)
        val listening = AtomicInt(0)
        repeat(2) {
            threads.start {
                server.join()
                if (!refused(port)) {
                    listening.addAndFetch(1)
                }
                joined.countDown()
            }
        }

        try {
            sleepMs(50)
            assertEquals(2, joined.count, "a join returned while the server was running")

            server.close()
            assertTrue(joined.await(5_000), "a join never returned after close")
            assertEquals(0, listening.load(), "a join returned with the reactor still listening")
        } finally {
            server.close()
            threads.joinAll()
        }
    }

    @Test
    fun `close with a grace period waits out an open connection while another thread is blocked in join`() {
        val server = start()
        val threads = TestThreads()
        val joined = Latch(1)
        threads.start {
            server.join()
            joined.countDown()
        }

        // The open connection keeps the reactor draining, so close can return only once the grace period runs out.
        TestSocket().use { client ->
            client.setReceiveTimeout(5_000)
            client.connect(server.port)
            client.write("hi")
            assertEquals("hi", client.readBytes(2).decodeToString())

            try {
                sleepMs(20)
                val took = millisTaken {
                    server.close(300)
                }
                assertTrue(took >= 250, "close(300) returned after $took ms, with a connection still open")
                assertTrue(joined.await(5_000), "join never returned after close")
            } finally {
                server.close()
                threads.joinAll()
            }
        }
    }

    // A server whose handler calls [close] on it, with another thread blocked in join, and a connection to run it: the
    // milliseconds from the connect until that join returned, or -1 if it did not within 10 s. After a deadlock nothing
    // is cleaned up, as a close or a join here would hang the run with it.
    private fun joinWhileHandlerCloses(close: (KronServer) -> Unit): Long {
        val ready = Latch(1)
        val holder = arrayOfNulls<KronServer>(1)
        val server = KronServer(
            testConfig(),
            onStart = {
                ready.countDown()
            },
            handler = {
                close(holder[0]!!)
            },
        ).start()
        holder[0] = server

        if (!ready.await(10_000)) {
            server.close()
            error("the reactor never started")
        }

        val port = server.port
        val threads = TestThreads()
        val joined = Latch(1)
        threads.start {
            server.join()
            joined.countDown()
        }

        sleepMs(20)
        val start = Clock.monotonicMs()
        TestSocket().use { client ->
            client.connect(port)
        }

        if (!joined.await(10_000)) {
            return -1
        }

        val took = Clock.monotonicMs() - start
        threads.joinAll()
        assertTrue(refused(port), "the port still takes connections after the handler's close")
        return took
    }

    @Test
    fun `a handler closing the server while another thread is blocked in join does not deadlock`() {
        // On the reactor thread close can only stop the reactors: their teardown comes after the handler returns.
        val took = joinWhileHandlerCloses { it.close() }
        assertTrue(took >= 0, "join never returned after a handler closed the server")
    }

    @Test
    fun `a handler closing the server with a grace period while another thread is in join does not hold its reactor`() {
        // The reactor cannot drain while its handler waits, so waiting out the grace period would only stall it.
        val took = joinWhileHandlerCloses { it.close(5_000) }
        assertTrue(took >= 0, "join never returned after a handler closed the server")
        assertTrue(took < 2_000, "join returned after $took ms: the handler held its reactor for the grace period")
    }

    @Test
    fun `close with a grace period after close is harmless`() {
        val server = start()
        assertServes(server.port)
        server.close()

        // Nothing is left to drain: waiting out the grace period would mean it waited on reactors already gone.
        val took = millisTaken {
            server.close(5_000)
        }
        assertTrue(took < 1_000, "close(5_000) after close took $took ms")
        assertTrue(refused(server.port), "the port takes connections again")
    }

    @Test
    fun `after close the listening port can be bound again`() {
        val server = start()
        val port = server.port
        try {
            assertServes(port)

            // The control: while the server listens, such a listener is refused the port.
            assertEquals(EADDRINUSE, bindPlain(port), "a listener of our own shared the port with the server's")
        } finally {
            server.close()
        }

        assertEquals(0, bindPlain(port), "binding the port after close failed with that errno")
    }
}
