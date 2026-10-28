package kron

import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A call made off the reactor runs its checks in a task posted to the reactor. One that fails must come back to
 * its caller as the exception: the reactor survives a posted task that throws, so nothing else would resume it.
 */
class OffReactorTest {
    // Runs [block] as a coroutine started on a thread of its own: how it ended, or null if it had not by
    // [timeoutMs] - a caller hung for good.
    private fun outcomeOffReactor(threads: TestThreads, timeoutMs: Long, block: suspend () -> Any?): String? {
        val outcome = AtomicReference<String?>(null)
        val done = Latch(1)

        threads.start {
            block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
                outcome.store(result.exceptionOrNull()?.let { "threw: ${it.message}" } ?: "returned")
                done.countDown()
            })
        }

        done.await(timeoutMs)
        return outcome.load()
    }

    @Test
    fun `a read from another thread while one is parked throws back to its caller`() {
        val parked = AtomicReference<TcpConnection?>(null)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            parked.store(conn)
            echo(conn)   // parks in read: the client sends nothing yet
        }

        val threads = TestThreads()
        TestServer(testConfig(), handler).use { server ->
            server.connect().use { _ ->
                assertTrue(eventually { parked.load() != null }, "no connection reached the handler")
                sleepMs(50)

                val conn = parked.load()!!
                val outcome = outcomeOffReactor(threads, 2_000) {
                    conn.read()
                }

                assertEquals("threw: read() is already waiting.", outcome, "the second read's caller was never resumed")
            }
        }

        threads.joinAll()
    }

    @Test
    fun `a flush from another thread while one is in progress throws back to its caller`() {
        val inFlight = AtomicReference<TcpConnection?>(null)
        val size = 16 * 1024 * 1024

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            conn.read()

            // More than the socket buffers hold of a peer that is not reading: the flush stays in the kernel.
            conn.write(ByteArray(size))
            inFlight.store(conn)
            conn.flush()
            echo(conn)
        }

        val threads = TestThreads()
        TestServer(testConfig(), handler).use { server ->
            server.connect().use { client ->
                client.setReceiveBuffer(4096)
                client.write("go")

                assertTrue(eventually { inFlight.load() != null }, "the handler never started its flush")
                sleepMs(50)

                val conn = inFlight.load()!!
                val outcome = outcomeOffReactor(threads, 2_000) {
                    conn.flush()
                }

                assertEquals("threw: flush() is already in progress.", outcome, "the second flush's caller was never resumed")
            }   // the client leaves unread, so the handler's own flush fails and it ends
        }

        threads.joinAll()
    }

    @Test
    fun `a delay from another thread while the timer waits throws back to its caller`() {
        val waiting = AtomicReference<RingTimer?>(null)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val timer = RingTimer(conn.reactor)
            waiting.store(timer)
            timer.delay(1_500)
            echo(conn)
        }

        val threads = TestThreads()
        TestServer(testConfig(), handler).use { server ->
            server.connect().use { _ ->
                assertTrue(eventually { waiting.load() != null }, "the handler never started its delay")
                sleepMs(50)

                val timer = waiting.load()!!
                val outcome = outcomeOffReactor(threads, 2_000) {
                    timer.delay(10)
                }

                assertEquals("threw: RingTimer already waiting", outcome, "the second delay's caller was never resumed")
            }
        }

        threads.joinAll()
    }

    private fun eventually(condition: () -> Boolean): Boolean {
        val deadline = Clock.monotonicMs() + 2_000
        while (Clock.monotonicMs() < deadline) {
            if (condition()) {
                return true
            }

            sleepMs(5)
        }

        return condition()
    }
}
