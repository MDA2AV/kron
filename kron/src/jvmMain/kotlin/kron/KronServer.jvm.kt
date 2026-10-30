package kron

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A reactor's thread, which carries the reactor for [currentReactor]. Daemon, as a process ends with main natively. */
internal class ReactorThread(private val target: Reactor) : Thread("reactor-${target.id}") {
    @JvmField
    var reactor: Reactor? = null

    init {
        isDaemon = true
    }

    // An exception ends only the thread, as a C thread's would be kept from ending the process.
    override fun run() {
        try {
            target.run()
        } catch (e: Throwable) {
            printErr("[r${target.id}] reactor thread ended: ${e.stackTraceToString()}")
        }
    }
}

// Handles for the threads started and not yet joined, as pthread_t values are natively.
private val threads = ConcurrentHashMap<Long, ReactorThread>()
private val nextHandle = AtomicLong(1)

private const val EAGAIN = 11
private const val ETIMEDOUT = 110

internal actual fun startReactorThread(reactor: Reactor): Long {
    val thread = ReactorThread(reactor)
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

internal actual fun joinReactorThread(handle: Long) {
    val thread = threads[handle] ?: return
    thread.join()
    threads.remove(handle)
}

internal actual fun timedJoinReactorThread(handle: Long, timeoutMs: Long): Int {
    val thread = threads[handle] ?: return 0
    thread.join(timeoutMs.coerceAtLeast(1))
    if (thread.isAlive) {
        return -ETIMEDOUT
    }

    threads.remove(handle)
    return 0
}
