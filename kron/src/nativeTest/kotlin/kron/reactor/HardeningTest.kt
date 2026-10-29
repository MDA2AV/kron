package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.posix.F_GETFD
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.dup2
import platform.posix.fcntl
import platform.posix.open

/**
 * The hardening regressions: what onStart guarantees a host and its handlers, and a handler that throws - after
 * letting go or not - releasing its connection exactly once (ioxide #94, #242).
 */
class HardeningTest {
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

    private fun isOpen(fd: Int): Boolean = fcntl(fd, F_GETFD) != -1

    // ---- onStart ----------------------------------------------------------------------------------------------

    @Test
    fun `onStart runs with the listener bound and before any connection is served`() {
        // The listener opens before onStart, so a client can connect while it runs; nothing is accepted until it
        // returns, so a handler sees everything onStart set up. The delay keeps the client waiting on it.
        val port = AtomicInt(0)
        val configured = AtomicInt(0)
        val seenByHandler = AtomicInt(-1)
        val inOnStart = Latch(1)

        val server = KronServer(
            testConfig(),
            onStart = { reactor ->
                port.store(reactor.listenPorts[0])
                inOnStart.countDown()
                sleepMs(200)
                configured.store(1)
            },
        ) { conn ->
            seenByHandler.store(configured.load())
            echo(conn)
        }.start()

        try {
            assertTrue(inOnStart.await(10_000), "onStart never ran")

            TestSocket().use { client ->
                client.setReceiveTimeout(5_000)
                client.connect(port.load())
                client.write("hi")
                assertEquals("hi", client.readBytes(2).decodeToString())
            }
        } finally {
            server.close()
        }

        assertEquals(1, seenByHandler.load(), "the connection was served before onStart had returned")
    }

    @Test
    fun `a refusal thrown from onStart reaches onFault and the reactor closes its listener`() {
        val port = AtomicInt(0)
        val fault = AtomicReference<String?>(null)
        val faulted = Latch(1)

        val server = KronServer(
            testConfig(),
            onStart = { reactor ->
                port.store(reactor.listenPorts[0])
                throw IllegalStateException("refused-on-purpose")
            },
            handler = ::echo,
        )
        server.reactors[0].onFault = { _, e ->
            fault.store(e.message)
            faulted.countDown()
        }
        server.start()

        try {
            assertTrue(faulted.await(10_000), "the refusal thrown from onStart never reached onFault")
            assertEquals("refused-on-purpose", fault.load())
        } finally {
            server.close()   // returns once the reactor thread has torn down
        }

        TestSocket().use { client ->
            val refused = runCatching { client.connect(port.load()) }.exceptionOrNull()
            assertTrue(refused is IOException, "the refused reactor left its listener open")
        }
    }

    // ---- A handler that throws --------------------------------------------------------------------------------

    @Test
    fun `a handler that faults releases its connection - issue 94`() {
        // Each suspends once, as an await would, then throws: its ref must still be let go, so the peer gets its
        // FIN and the connection is recycled once the peer has closed too.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            RingTimer(conn.reactor).delay(1)
            throw IllegalStateException("boom (test)")
        }

        TestServer(testConfig(), handler).use { server ->
            repeat(10) { i ->
                server.connect().use { client ->
                    client.write("x")
                    assertEquals(-1, client.read(), "connection $i: no FIN after its handler faulted")
                }
            }

            assertTrue(
                eventually(5_000) { onReactor(server.reactor) { server.reactor.tcp!!.openConnections } == 0 },
                "connections were never recycled after their handlers faulted and their peers closed",
            )
        }
    }

    // How a handler ends: letting go and then throwing, letting go and returning, or returning and leaving the release
    // to the reactor alone.
    private enum class Ending { LET_GO_THEN_THROW, LET_GO_THEN_RETURN, RETURN }

    // Serves one connection with a handler that ends as [ending], waits for the server to close the number it was
    // given, hands that number to /dev/null, and stops the server: the numbers its teardown closed under their new
    // owner.
    private fun numbersClosedUnderTheirNextOwner(ending: Ending): List<Int> {
        // Opened before any connection, so its number cannot be one of theirs.
        val devNull = open("/dev/null", O_RDONLY)
        check(devNull >= 0) { "open(/dev/null) failed" }

        val handled = AtomicReference<List<Int>>(emptyList())
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            handled.store(handled.load() + conn.clientFd)
            if (ending != Ending.RETURN) {
                try {
                    if (ending == Ending.LET_GO_THEN_THROW) {
                        throw IllegalStateException("thrown after the handler let go (test)")
                    }
                } finally {
                    conn.decRef()
                }
            }
        }

        val server = TestServer(testConfig(), handler)
        val numbers = try {
            server.connect().use { client ->
                assertEquals(-1, client.read(), "the handler was never let go")   // its FIN
            }

            val given = handled.load().distinct()
            assertTrue(given.isNotEmpty(), "no connection reached the handler")
            assertTrue(
                eventually(5_000) { given.none { isOpen(it) } },
                "the server never closed ${given.filter { isOpen(it) }}",
            )

            for (number in given) {
                assertEquals(number, dup2(devNull, number), "dup2 onto $number failed")
            }

            given
        } catch (e: Throwable) {
            server.close()
            throw e
        } finally {
            close(devNull)
        }

        server.close()   // the teardown under test

        val closed = numbers.filter { !isOpen(it) }
        for (number in numbers - closed.toSet()) {
            close(number)
        }

        return closed
    }

    @Test
    fun `a handler that lets go and then throws is released once - so teardown closes no number it gave back - issue 242`() {
        // try { throw } finally { decRef() }: released by the handler and again by the fault path, the connection
        // would be recycled under the reactor's ref while its table slot outlived it.
        val closed = numbersClosedUnderTheirNextOwner(Ending.LET_GO_THEN_THROW)
        assertTrue(closed.isEmpty(), "the server's teardown closed $closed, numbers its connections had given back")
    }

    @Test
    fun `a handler that lets go and then returns is released once too`() {
        // ioxide's control, which is not one here: the reactor lets go of the handler's ref when it returns, as it
        // does when it throws, so an early decRef() is a second release on this path as well.
        val closed = numbersClosedUnderTheirNextOwner(Ending.LET_GO_THEN_RETURN)
        assertTrue(closed.isEmpty(), "the server's teardown closed $closed, numbers its connections had given back")
    }

    @Test
    fun `control - a handler the reactor alone releases leaves a number it gave back alone at teardown`() {
        val closed = numbersClosedUnderTheirNextOwner(Ending.RETURN)
        assertTrue(closed.isEmpty(), "the server's teardown closed $closed")
    }
}
