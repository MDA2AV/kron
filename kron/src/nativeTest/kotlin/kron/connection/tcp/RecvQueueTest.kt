package kron

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [RecvQueue], a connection's ring of recv completions: order, capacity, snapshots, and the incremental-mode merge. */
class RecvQueueTest {
    private val item = RecvItem()

    // Every field of item n derives from n, so an item out of place, torn or repeated shows.
    private fun enqueue(queue: RecvQueue, n: Int): Boolean = queue.tryEnqueue(BASE + n * 4096L, 1 + n % 4000, n and 0x7FFF, n * 3)

    private fun assertItem(n: Int, item: RecvItem, message: String = "item $n") {
        assertEquals(BASE + n * 4096L, item.address, "$message: address")
        assertEquals(1 + n % 4000, item.length, "$message: length")
        assertEquals(n and 0x7FFF, item.bufferId, "$message: bufferId")
        assertEquals(n * 3, item.generation, "$message: generation")
    }

    private fun assertTaken(queue: RecvQueue, address: Long, length: Int, bufferId: Int, generation: Int) {
        assertTrue(queue.tryDequeue(item), "the queue is empty")
        assertEquals(address, item.address, "address")
        assertEquals(length, item.length, "length")
        assertEquals(bufferId, item.bufferId, "bufferId")
        assertEquals(generation, item.generation, "generation")
    }

    @Test
    fun `items come out in order across many wraps of the ring`() {
        val queue = RecvQueue(8)
        val random = Random(416)
        var produced = 0
        var consumed = 0

        // A random walk over every fill level, so head and tail meet each slot at every distance; the two dequeues
        // alternate, as the handler's and the recycle's do.
        repeat(20_000) {
            val queued = produced - consumed
            if (queued == 0 || (queued < 8 && random.nextBoolean())) {
                assertTrue(enqueue(queue, produced), "item $produced was refused with $queued queued")
                produced++
            } else {
                val taken = if (random.nextBoolean()) {
                    queue.tryDequeue(item)
                } else {
                    queue.tryDequeueUntil(queue.snapshotTail(), item)
                }

                assertTrue(taken, "nothing came out with $queued queued")
                assertItem(consumed, item)
                consumed++
            }
        }

        while (queue.tryDequeue(item)) {
            assertItem(consumed, item)
            consumed++
        }

        assertEquals(produced, consumed)
        assertTrue(produced > 8 * 1000, "only $produced items went through, too few wraps")
    }

    @Test
    fun `an item past capacity is refused - and taken once a dequeue frees an entry`() {
        val queue = RecvQueue(4)
        for (n in 0 until 4) {
            assertTrue(enqueue(queue, n))
        }

        assertFalse(enqueue(queue, 4), "a fifth item fit in four entries")
        assertFalse(enqueue(queue, 4), "a fifth item fit in four entries on the second try")

        assertTrue(queue.tryDequeue(item))
        assertItem(0, item)
        assertTrue(enqueue(queue, 4), "the entry the dequeue freed was not taken")
        assertFalse(enqueue(queue, 5), "a fifth item fit after the freed entry was taken")

        // The refused attempts left no trace.
        for (n in 1..4) {
            assertTrue(queue.tryDequeue(item))
            assertItem(n, item)
        }
        assertFalse(queue.tryDequeue(item))
    }

    @Test
    fun `a dequeue stops at its snapshot's tail`() {
        val queue = RecvQueue(8)
        for (n in 0 until 3) {
            enqueue(queue, n)
        }

        val snapshot = queue.snapshotTail()
        for (n in 3 until 5) {
            enqueue(queue, n)   // after the snapshot: not the reader's yet
        }

        for (n in 0 until 3) {
            assertTrue(queue.tryDequeueUntil(snapshot, item))
            assertItem(n, item)
        }
        assertFalse(queue.tryDequeueUntil(snapshot, item), "an item queued after the snapshot was taken through it")
        assertFalse(queue.isEmpty())

        val next = queue.snapshotTail()
        for (n in 3 until 5) {
            assertTrue(queue.tryDequeueUntil(next, item))
            assertItem(n, item)
        }
        assertFalse(queue.tryDequeueUntil(next, item))
        assertFalse(queue.tryDequeueUntil(snapshot, item), "an older snapshot took an item")
    }

    @Test
    fun `countUntil counts up to a snapshot - and isEmpty and reset agree`() {
        val queue = RecvQueue(8)
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.countUntil(queue.snapshotTail()))

        for (n in 0 until 5) {
            enqueue(queue, n)
        }
        val snapshot = queue.snapshotTail()
        enqueue(queue, 5)

        assertFalse(queue.isEmpty())
        assertEquals(5, queue.countUntil(snapshot))
        assertEquals(6, queue.countUntil(queue.snapshotTail()))

        queue.tryDequeue(item)
        queue.tryDequeue(item)
        assertEquals(3, queue.countUntil(snapshot))

        // A snapshot the head has already passed counts nothing, not a negative number.
        assertEquals(0, queue.countUntil(1))

        // Recycle's reset: empty, back at the start, and in order from there.
        queue.reset()
        assertTrue(queue.isEmpty())
        assertEquals(0L, queue.snapshotTail())
        assertEquals(0, queue.countUntil(queue.snapshotTail()))
        assertFalse(queue.tryDequeue(item))

        for (n in 100 until 108) {
            assertTrue(enqueue(queue, n), "item $n was refused after the reset")
        }
        assertFalse(enqueue(queue, 108), "the reset queue held more than its capacity")
        for (n in 100 until 108) {
            assertTrue(queue.tryDequeue(item))
            assertItem(n, item)
        }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a contiguous continuation grows the last item - at every slot of the ring - and dequeues as one`() {
        val queue = RecvQueue(4)

        // Twelve rounds walk the last item across every slot, the wrap from the last to the first included.
        for (round in 0 until 12) {
            val address = BASE + round * 4096L
            val visible = queue.snapshotTail()   // the reader's last snapshot, taken before the item arrived
            assertTrue(queue.tryEnqueue(address, 100, 7, 9))

            assertTrue(queue.tryExtendLast(address + 100, 50, 7, 9, visible), "round $round: the continuation was refused")
            assertTrue(queue.tryExtendLast(address + 150, 1, 7, 9, visible), "round $round: the second continuation was refused")
            assertEquals(1, queue.countUntil(queue.snapshotTail()), "round $round: a merge took an entry of its own")

            assertTaken(queue, address, 151, 7, 9)
            assertTrue(queue.isEmpty())
        }
    }

    @Test
    fun `a merge into the last item is not blocked by a snapshot that reaches only the items before it`() {
        val queue = RecvQueue(8)
        queue.tryEnqueue(BASE, 10, 1, 9)
        val visible = queue.snapshotTail()   // reaches the first item only
        queue.tryEnqueue(BASE + 4096, 10, 2, 9)

        assertTrue(queue.tryExtendLast(BASE + 4106, 5, 2, 9, visible))
        assertTaken(queue, BASE, 10, 1, 9)
        assertTaken(queue, BASE + 4096, 15, 2, 9)
    }

    @Test
    fun `tryExtendLast refuses whatever is not a continuation no reader can see yet`() {
        // The last item, 100 bytes of buffer 5 in life 9, as each case below starts.
        fun queueWithOne(): RecvQueue = RecvQueue(8).also {
            it.tryEnqueue(BASE, 100, 5, 9)
        }

        fun assertRefused(case: String, extend: (RecvQueue) -> Boolean) {
            val queue = queueWithOne()
            assertFalse(extend(queue), "$case was merged")
            assertTaken(queue, BASE, 100, 5, 9)   // untouched
            assertFalse(queue.tryDequeue(item), "$case left an item of its own")
        }

        assertRefused("another buffer") { it.tryExtendLast(BASE + 100, 10, 6, 9, 0) }
        assertRefused("another life of the buffer") { it.tryExtendLast(BASE + 100, 10, 5, 10, 0) }
        assertRefused("a slice after a gap") { it.tryExtendLast(BASE + 101, 10, 5, 9, 0) }
        assertRefused("a slice overlapping the item") { it.tryExtendLast(BASE + 99, 10, 5, 9, 0) }
        assertRefused("a continuation of an item a snapshot reaches") { it.tryExtendLast(BASE + 100, 10, 5, 9, 1) }

        assertFalse(RecvQueue(8).tryExtendLast(BASE, 10, 5, 9, 0), "a slice was merged into an empty queue")

        // The last item already taken by the reader: nothing to grow, even with a stale snapshot.
        val drained = queueWithOne()
        drained.tryDequeue(item)
        assertFalse(drained.tryExtendLast(BASE + 100, 10, 5, 9, 0), "a slice was merged into an item already dequeued")
        assertTrue(drained.isEmpty())
    }

    private companion object {
        // A plausible slab address; the queue never dereferences what it holds.
        const val BASE = 0x7F00_0000_0000L
    }
}
