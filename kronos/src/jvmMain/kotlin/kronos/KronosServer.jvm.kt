package kronos

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A proactor's thread, which carries the proactor for [currentProactor]. Daemon, as a process ends with main natively. */
internal class ProactorThread(private val target: Proactor) : Thread("proactor-${target.id}") {
    @JvmField
    var proactor: Proactor? = null

    init {
        isDaemon = true
    }

    // An exception ends only the thread, as a C thread's would be kept from ending the process.
    override fun run() {
        try {
            target.run()
        } catch (e: Throwable) {
            printErr("[p${target.id}] proactor thread ended: ${e.stackTraceToString()}")
        }
    }
}

// Handles for the threads started and not yet joined, as pthread_t values are natively.
private val threads = ConcurrentHashMap<Long, ProactorThread>()
private val nextHandle = AtomicLong(1)

private const val EAGAIN = 11
private const val ETIMEDOUT = 110

internal actual fun startProactorThread(proactor: Proactor): Long {
    val thread = ProactorThread(proactor)
    val handle = nextHandle.getAndIncrement()
    threads[handle] = thread
    try {
        thread.start()
    } catch (_: OutOfMemoryError) {
        threads.remove(handle)
        return -EAGAIN.toLong()
    }

    return handle
}

internal actual fun joinProactorThread(handle: Long) {
    val thread = threads[handle] ?: return
    thread.join()
    threads.remove(handle)
}

internal actual fun timedJoinProactorThread(handle: Long, timeoutMs: Long): Int {
    val thread = threads[handle] ?: return 0
    thread.join(timeoutMs.coerceAtLeast(1))
    if (thread.isAlive) {
        return -ETIMEDOUT
    }

    threads.remove(handle)
    return 0
}
