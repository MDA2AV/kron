package kron

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kron.sys.kr_thread_join
import kron.sys.kr_thread_start
import kron.sys.kr_thread_timed_join
import kotlin.concurrent.atomics.AtomicInt

/**
 * The host side, for the common case: [ServerConfig.reactorCount] reactors, each on its own thread named
 * `reactor-<id>`, all serving [handler]. The core creates no threads itself - this is all a host has to do
 * with [Reactor] directly.
 */
class KronServer(
    private val config: ServerConfig,
    private val onStart: ((Reactor) -> Unit)? = null,
    private val handler: suspend (TcpConnection) -> Unit,
) : AutoCloseable {
    val reactors: List<Reactor> = List(config.reactorCount) { id ->
        Reactor(id, config).also {
            it.tcpHandler = handler
            it.onStart = onStart
        }
    }

    // pthread handles; 0 once joined, or before start. Joined only by the holder of joining: a thread can be joined
    // once, so another caller waits for the flag until the holder has seen every thread end.
    private val threads = LongArray(reactors.size)
    private val joining = AtomicInt(0)

    // A reactor thread can wait for neither its own end nor the flag, which a thread in join() holds until then.
    private val onReactorThread: Boolean get() = reactors.any { it.onReactorThread }

    fun start(): KronServer {
        // Refused here rather than as a reactor fault: the threads inherit this thread's affinity.
        if (config.pinReactors) {
            val cpus = Native.allowedCpus().size
            if (cpus < reactors.size) {
                throw IllegalStateException("pinReactors needs a CPU per reactor: ${reactors.size} reactors, $cpus allowed")
            }
        }

        for ((i, reactor) in reactors.withIndex()) {
            val ref = StableRef.create(reactor)
            val thread = kr_thread_start(reactorEntry, ref.asCPointer(), "reactor-${reactor.id}")
            if (thread < 0) {
                ref.dispose()
                throw IllegalStateException("pthread_create failed with errno ${-thread}")
            }
            threads[i] = thread
        }

        return this
    }

    /** Waits for every reactor thread to end. On a reactor thread it returns at once: that thread ends only after. */
    fun join() {
        if (onReactorThread) {
            return
        }

        lockJoins(Long.MAX_VALUE)
        try {
            for (i in threads.indices) {
                val thread = threads[i]
                if (thread != 0L) {
                    kr_thread_join(thread)
                    threads[i] = 0L
                }
            }
        } finally {
            joining.store(0)
        }
    }

    /** Stops every reactor and waits for their teardown. On a reactor thread it only stops them, as [join] says. */
    override fun close() {
        reactors.forEach(Reactor::stop)
        join()
    }

    /**
     * Stops gracefully: every reactor stops accepting at once, and stops by itself when its last open connection
     * is gone. Those still serving after [gracePeriodMs] are stopped as [close] does, closing what is left. Returns
     * once every reactor thread has ended. On a reactor thread, whose reactor drains only once the handler returns, it
     * closes at once.
     */
    fun close(gracePeriodMs: Long) {
        reactors.forEach(Reactor::stopAccepting)

        // A thread in join() holds the flag until these same threads end, so waiting for it is waiting for them.
        val deadline = Clock.monotonicMs() + gracePeriodMs
        if (!onReactorThread && lockJoins(deadline)) {
            try {
                for (i in threads.indices) {
                    val thread = threads[i]
                    val remainingMs = deadline - Clock.monotonicMs()
                    if (thread == 0L || remainingMs <= 0) {
                        continue
                    }

                    if (kr_thread_timed_join(thread, remainingMs) == 0) {
                        threads[i] = 0L
                    }
                }
            } finally {
                joining.store(0)
            }
        }

        close()
    }

    // Takes the joining flag, giving up at deadline: true once held. Only a caller racing another one waits, polled.
    private fun lockJoins(deadline: Long): Boolean {
        while (!joining.compareAndSet(0, 1)) {
            if (Clock.monotonicMs() >= deadline) {
                return false
            }
            sleepMs(1)
        }

        return true
    }
}

// A reactor thread's entry, with the ref start() made. An exception escaping into C would end the process, so
// it ends only the thread, as an uncaught one does on the JVM.
private val reactorEntry = staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
    val ref = argument!!.asStableRef<Reactor>()
    val reactor = ref.get()
    ref.dispose()

    try {
        reactor.run()
    } catch (e: Throwable) {
        printErr("[r${reactor.id}] reactor thread ended: ${e.stackTraceToString()}")
    }

    null
}
