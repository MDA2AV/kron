package kron

/**
 * A task [Reactor.schedule] runs once its delay has passed; [cancel] stops it.
 *
 * Its state is the reactor thread's, so a cancel from another thread is posted there and lands on the reactor's
 * next pass - after the task has run, if its deadline came first.
 */
class ScheduledTask internal constructor(
    private val reactor: Reactor,
    internal val deadlineMs: Long,
    internal val task: () -> Unit,
) {
    // Breaks ties between equal deadlines: the one scheduled first runs first.
    internal var seq = 0L

    // Its slot in the reactor's heap; -1 while it is not in it.
    internal var heapIndex = -1

    // A cancel that came before the task reached the heap: one posted ahead of the post that schedules it.
    internal var cancelled = false

    /**
     * Stops the task if it has not run yet. On the reactor thread it comes out at once and will not run; from
     * another thread the cancel is posted to the reactor and takes effect on its next pass.
     */
    fun cancel() {
        if (reactor.onReactorThread) {
            reactor.cancelTimer(this)
            return
        }

        reactor.post {
            reactor.cancelTimer(this)
        }
    }
}

/**
 * A binary min-heap of scheduled tasks, by deadline and then by the order they were scheduled. Each task knows its
 * slot, so a cancel takes it out at once rather than leaving it to its deadline. Reactor thread only.
 */
internal class TimerHeap {
    private var tasks = arrayOfNulls<ScheduledTask>(64)

    var size = 0
        private set

    fun peek(): ScheduledTask? = if (size == 0) null else tasks[0]

    fun add(task: ScheduledTask) {
        if (size == tasks.size) {
            tasks = tasks.copyOf(size * 2)
        }

        tasks[size] = task
        task.heapIndex = size
        size++

        siftUp(size - 1)
    }

    /** Takes [task] out wherever it sits: false if it was not in the heap. */
    fun remove(task: ScheduledTask): Boolean {
        val index = task.heapIndex
        if (index < 0) {
            return false
        }

        size--
        val last = tasks[size]!!
        tasks[size] = null
        task.heapIndex = -1

        // The last task fills the hole, then settles whichever way it belongs.
        if (index < size) {
            tasks[index] = last
            last.heapIndex = index

            siftDown(index)
            siftUp(last.heapIndex)
        }

        return true
    }

    fun clear() {
        for (i in 0 until size) {
            tasks[i]!!.heapIndex = -1
            tasks[i] = null
        }

        size = 0
    }

    private fun siftUp(start: Int) {
        val task = tasks[start]!!

        var index = start
        while (index > 0) {
            val parent = (index - 1) / 2
            val above = tasks[parent]!!
            if (!before(task, above)) {
                break
            }

            tasks[index] = above
            above.heapIndex = index
            index = parent
        }

        tasks[index] = task
        task.heapIndex = index
    }

    private fun siftDown(start: Int) {
        val task = tasks[start]!!

        var index = start
        while (true) {
            val left = 2 * index + 1
            if (left >= size) {
                break
            }

            val right = left + 1
            val child = if (right < size && before(tasks[right]!!, tasks[left]!!)) right else left

            val below = tasks[child]!!
            if (!before(below, task)) {
                break
            }

            tasks[index] = below
            below.heapIndex = index
            index = child
        }

        tasks[index] = task
        task.heapIndex = index
    }

    private fun before(a: ScheduledTask, b: ScheduledTask): Boolean =
        a.deadlineMs < b.deadlineMs || (a.deadlineMs == b.deadlineMs && a.seq < b.seq)
}
