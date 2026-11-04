package kronos

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Proactor.post] from other threads, and [Proactor.addTicker]: work run on the proactor's thread, in its own loop. */
class ProactorPostTest {
    // One proactor serving no TCP, started, with [onStart] run on its thread first.
    private fun startProactor(onStart: (Proactor) -> Unit = {}): KronosServer {
        val ready = Latch(1)
        val server = KronosServer(
            ServerConfig(proactorCount = 1, ringEntries = 64),
            onStart = { proactor ->
                onStart(proactor)
                ready.countDown()
            },
            handler = {},
        ).start()

        if (!ready.await(10_000)) {
            server.close()
            error("the proactor never started")
        }

        return server
    }

    @Test
    fun `posts from four threads each run once - on the proactor thread and in each producer's order`() {
        val server = startProactor()
        val proactor = server.proactors[0]
        val threads = TestThreads()

        // Per producer, the sequence number its next task must carry; a task run twice, skipped or early breaks it.
        val next = AtomicIntArray(PRODUCERS)
        val ran = AtomicInt(0)
        val offThread = AtomicInt(0)
        val outOfOrder = AtomicReference<String?>(null)

        try {
            val go = Latch(1)
            for (p in 0 until PRODUCERS) {
                threads.start {
                    if (!go.await(10_000)) {
                        return@start
                    }

                    for (s in 0 until PER_PRODUCER) {
                        proactor.post {
                            if (!proactor.onProactorThread) {
                                offThread.addAndFetch(1)
                            }
                            if (next.loadAt(p) != s && outOfOrder.load() == null) {
                                outOfOrder.store("producer $p's task $s ran when ${next.loadAt(p)} was next")
                            }

                            next.storeAt(p, s + 1)
                            ran.addAndFetch(1)
                        }
                    }
                }
            }

            go.countDown()
            val deadline = Clock.monotonicMs() + 30_000
            while (ran.load() < PRODUCERS * PER_PRODUCER && Clock.monotonicMs() < deadline) {
                sleepMs(5)
            }

            // Time for a task run twice to show.
            sleepMs(50)
        } finally {
            threads.joinAll()
            server.close()
        }

        assertEquals(null, outOfOrder.load())
        assertEquals(PRODUCERS * PER_PRODUCER, ran.load(), "tasks run")
        for (p in 0 until PRODUCERS) {
            assertEquals(PER_PRODUCER, next.loadAt(p), "producer $p's tasks run")
        }
        assertEquals(0, offThread.load(), "tasks run off the proactor thread")
    }

    @Test
    fun `a ticker added in onStart runs on the proactor thread again and again - nowMs moving forward`() {
        // The proactor's clock at each tick, appended on its thread only.
        val ticks = AtomicReference<List<Long>>(emptyList())
        val offThread = AtomicInt(0)

        val server = startProactor { proactor ->
            proactor.addTicker {
                if (!proactor.onProactorThread) {
                    offThread.addAndFetch(1)
                }
                ticks.store(ticks.load() + proactor.nowMs)
            }
        }

        try {
            // A proactor with nothing to do still wakes for its ticker, about every TICK_MS.
            val deadline = Clock.monotonicMs() + 10_000
            while (ticks.load().size < 4 && Clock.monotonicMs() < deadline) {
                sleepMs(10)
            }
        } finally {
            server.close()
        }

        val seen = ticks.load()
        assertTrue(seen.size >= 4, "the ticker ran ${seen.size} times in 10 s")
        assertEquals(0, offThread.load(), "ticks run off the proactor thread")
        seen.zipWithNext { a, b ->
            assertTrue(b - a >= Proactor.TICK_MS, "ticks at nowMs $seen: less than ${Proactor.TICK_MS} ms apart")
        }
    }

    private companion object {
        const val PRODUCERS = 4
        const val PER_PRODUCER = 10_000
    }
}
