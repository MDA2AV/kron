package kron

import kotlin.test.Test
import kotlin.test.assertNull

class MpscQueueGcTest {
    // A node the consumer has passed must not reach the ones after it: once the collector has moved it to the old
    // generation, every later node would stay alive through it, copied by each young collection and promoted.
    @Test
    fun `a taken node lets go of the nodes after it`() {
        val queue = MpscQueue<String>()
        val stub = field(queue, "tail")!!
        queue.offer("one")
        queue.offer("two")

        queue.poll()
        val first = field(queue, "tail")!!
        queue.poll()

        assertNull(next(stub), "the stub")
        assertNull(next(first), "the first item's node")
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun next(node: Any): Any? = field(node, "next")!!.let { it.javaClass.getMethod("get").invoke(it) }
}
