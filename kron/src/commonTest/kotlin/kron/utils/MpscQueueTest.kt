package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The handoff queues: [MpscLongQueue], bounded, for buffer returns, and [MpscQueue], unbounded, for posts. */
class MpscQueueTest {
    // Four producers offer their own values in order, as fast as the queue takes them, while this thread polls
    // them all out. Each value names its producer and its place, so one seen twice, lost, or out of its producer's
    // order shows. [offer] false means full: the producer retries.
    private fun fourProducers(offer: (Long) -> Boolean, poll: () -> Long?) {
        val threads = TestThreads()
        val go = Latch(1)
        val abort = AtomicInt(0)

        for (p in 0 until PRODUCERS) {
            threads.start {
                if (!go.await(10_000)) {
                    return@start
                }

                for (s in 0 until PER_PRODUCER) {
                    while (!offer(p * STRIDE + s)) {
                        if (abort.load() != 0) {
                            return@start
                        }
                        spinWait()
                    }
                }
            }
        }

        val next = IntArray(PRODUCERS)
        var received = 0
        var wrong: String? = null

        try {
            go.countDown()
            val deadline = Clock.monotonicMs() + 60_000

            while (received < PRODUCERS * PER_PRODUCER && Clock.monotonicMs() < deadline) {
                val value = poll()
                if (value == null) {
                    spinWait()
                    continue
                }

                val p = (value / STRIDE).toInt()
                val s = (value % STRIDE).toInt()
                if (p !in 0 until PRODUCERS || s != next[p]) {
                    wrong = "after $received values, producer $p's value $s arrived where ${next.getOrNull(p)} was next"
                    break
                }

                next[p]++
                received++
            }
        } finally {
            abort.store(1)
            threads.joinAll()
        }

        assertNull(wrong)
        assertEquals(PRODUCERS * PER_PRODUCER, received, "values received; per producer: ${next.toList()}")
        assertNull(poll(), "a value arrived after every producer's last")
    }

    @Test
    fun `MpscLongQueue - four producers' values each arrive once and in their producer's order`() {
        // Small, so producers find it full again and again.
        val queue = MpscLongQueue(1024)
        fourProducers(queue::offer) {
            queue.poll().takeIf { it >= 0 }
        }
    }

    @Test
    fun `MpscQueue - four producers' values each arrive once and in their producer's order`() {
        val queue = MpscQueue<Long>()
        fourProducers({
            queue.offer(it)
            true
        }, queue::poll)
    }

    @Test
    fun `MpscLongQueue refuses an offer when full and takes one again after a poll`() {
        val queue = MpscLongQueue(4)
        for (v in 10L until 14L) {
            assertTrue(queue.offer(v))
        }

        assertFalse(queue.offer(14), "a fifth value fit in four slots")
        assertEquals(10L, queue.poll())
        assertTrue(queue.offer(14), "the slot the poll freed was not taken")
        assertFalse(queue.offer(15), "a fifth value fit after the freed slot was taken")

        for (v in 11L..14L) {
            assertEquals(v, queue.poll())
        }
        assertEquals(-1L, queue.poll())
    }

    @Test
    fun `MpscLongQueue refuses a capacity that is not a power of two`() {
        for (capacity in listOf(0, -1, 3, 6, 1000, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException>("capacity $capacity") {
                MpscLongQueue(capacity)
            }
        }

        // The control: powers of two are taken.
        for (capacity in listOf(1, 2, 1 shl 14)) {
            MpscLongQueue(capacity)
        }
    }

    @Test
    fun `an empty poll returns -1 from MpscLongQueue and null from MpscQueue`() {
        val longs = MpscLongQueue(8)
        assertEquals(-1L, longs.poll())
        longs.offer(0)
        assertEquals(0L, longs.poll())
        assertEquals(-1L, longs.poll(), "a drained queue")

        val tasks = MpscQueue<String>()
        assertNull(tasks.poll())
        tasks.offer("one")
        assertEquals("one", tasks.poll())
        assertNull(tasks.poll(), "a drained queue")
    }

    private companion object {
        const val PRODUCERS = 4
        const val PER_PRODUCER = 100_000
        const val STRIDE = 1_000_000L
    }
}
