package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Reactor.schedule]: tasks run on the reactor thread once their delay has passed, and cancelled ones never do. */
class ScheduleTest {
    // One reactor with no listener, started; [body] runs on the test thread.
    private fun withReactor(body: (Reactor) -> Unit) {
        val ready = Latch(1)
        val server = KronServer(
            ServerConfig(reactorCount = 1, ringEntries = 64),
            onStart = {
                ready.countDown()
            },
            handler = {},
        ).start()

        try {
            assertTrue(ready.await(10_000), "the reactor never started")
            body(server.reactors[0])
        } finally {
            server.close()
        }
    }

    @Test
    fun `tasks run once their delay has passed and the earliest deadline first`() {
        withReactor { reactor ->
            val order = AtomicReference<List<Int>>(emptyList())
            val early = AtomicInt(0)
            val done = Latch(3)

            val start = Clock.monotonicMs()
            for (delay in intArrayOf(60, 20, 40)) {
                reactor.schedule(delay.toLong()) {
                    if (Clock.monotonicMs() - start < delay) {
                        early.addAndFetch(1)
                    }

                    order.store(order.load() + delay)
                    done.countDown()
                }
            }

            assertTrue(done.await(5_000), "the tasks never all ran")
            assertEquals(listOf(20, 40, 60), order.load())
            assertEquals(0, early.load(), "a task ran before its delay had passed")
        }
    }

    @Test
    fun `an idle reactor wakes for a timer and not only on its next tick`() {
        withReactor { reactor ->
            val after = AtomicLong(-1)
            val done = Latch(1)

            // Just started, the reactor's next tick is about 250 ms away.
            val start = Clock.monotonicMs()
            reactor.schedule(20) {
                after.store(Clock.monotonicMs() - start)
                done.countDown()
            }

            assertTrue(done.await(5_000), "the task never ran")
            assertTrue(after.load() in 20L..120L, "a 20 ms task ran after ${after.load()} ms")
        }
    }

    @Test
    fun `a task scheduled from another thread runs on the reactor thread`() {
        withReactor { reactor ->
            val onReactor = AtomicInt(-1)
            val done = Latch(1)

            reactor.schedule(5) {
                onReactor.store(if (reactor.onReactorThread) 1 else 0)
                done.countDown()
            }

            assertTrue(done.await(5_000), "the task never ran")
            assertEquals(1, onReactor.load())
        }
    }

    @Test
    fun `a cancelled task never runs`() {
        withReactor { reactor ->
            val ran = AtomicInt(0)
            val kept = Latch(1)

            // Cancelled on the reactor thread, as soon as it is scheduled.
            reactor.post {
                reactor.schedule(10) {
                    ran.addAndFetch(1)
                }.cancel()
            }

            // Scheduled and cancelled from this thread: both are posted, in that order.
            reactor.schedule(30) {
                ran.addAndFetch(1)
            }.cancel()

            // Due after both, so they have had their chance by the time it runs.
            reactor.schedule(80) {
                kept.countDown()
            }

            assertTrue(kept.await(5_000), "the kept task never ran")
            assertEquals(0, ran.load())
        }
    }

    @Test
    fun `cancelled timers leave the heap at once`() {
        withReactor { reactor ->
            val pending = AtomicInt(-1)
            val done = Latch(1)

            // A long timeout armed and cancelled for every request, as an HTTP engine does: left in the heap until
            // their deadlines, they would pile up by the hundred thousand.
            reactor.post {
                repeat(100_000) {
                    reactor.schedule(45_000) {}.cancel()
                }

                pending.store(reactor.pendingTimers)
                done.countDown()
            }

            assertTrue(done.await(10_000), "the reactor never ran the post")
            assertEquals(0, pending.load())
        }
    }

    @Test
    fun `a throwing task leaves the reactor running its other tasks`() {
        withReactor { reactor ->
            val done = Latch(1)

            reactor.schedule(5) {
                throw IllegalStateException("a task that fails on purpose")
            }
            reactor.schedule(20) {
                done.countDown()
            }

            assertTrue(done.await(5_000), "the reactor stopped running tasks after one threw")
        }
    }

    @Test
    fun `a task rescheduling itself at no delay leaves the loop its io`() {
        val spinning = AtomicInt(1)
        val runs = AtomicInt(0)

        TestServer(testConfig(), ::echo).use { server ->
            val reactor = server.reactor

            fun again() {
                if (spinning.load() == 1) {
                    runs.addAndFetch(1)
                    reactor.schedule(0) {
                        again()
                    }
                }
            }

            reactor.schedule(0) {
                again()
            }

            // Recv completions only reach the CQ through an enter that asks for them (DEFER_TASKRUN): a loop that
            // went straight back to a due timer every pass would never see these echoes.
            server.connect().use { client ->
                for (round in 0 until 20) {
                    client.write("ping")
                    assertEquals("ping", client.readBytes(4).decodeToString(), "round $round")
                }
            }

            spinning.store(0)
        }

        assertTrue(runs.load() > 20, "the task did not keep running: ${runs.load()} runs")
    }
}
