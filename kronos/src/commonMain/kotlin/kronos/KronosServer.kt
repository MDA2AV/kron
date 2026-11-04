package kronos

import kotlin.concurrent.atomics.AtomicInt

/**
 * The host side, for the common case: [ServerConfig.proactorCount] proactors, each on its own thread named
 * `proactor-<id>`, all serving [handler]. The core creates no threads itself - this is all a host has to do
 * with [Proactor] directly.
 */
class KronosServer(
    private val config: ServerConfig,
    private val onStart: ((Proactor) -> Unit)? = null,
    private val handler: suspend (TcpConnection) -> Unit,
) : AutoCloseable {
    val proactors: List<Proactor> = List(config.proactorCount) { id ->
        Proactor(id, config).also {
            it.tcpHandler = handler
            it.onStart = onStart
        }
    }

    // Thread handles; 0 once joined, or before start. Joined only by the holder of joining: a thread can be joined
    // once, so another caller waits for the flag until the holder has seen every thread end.
    private val threads = LongArray(proactors.size)
    private val joining = AtomicInt(0)

    // A proactor thread can wait for neither its own end nor the flag, which a thread in join() holds until then.
    private val onProactorThread: Boolean get() = proactors.any { it.onProactorThread }

    fun start(): KronosServer {
        // Refused here rather than as a proactor fault: the threads inherit this thread's affinity.
        if (config.pinProactors) {
            val cpus = Native.allowedCpus().size
            if (cpus < proactors.size) {
                throw IllegalStateException("pinProactors needs a CPU per proactor: ${proactors.size} proactors, $cpus allowed")
            }
        }

        for ((i, proactor) in proactors.withIndex()) {
            val thread = startProactorThread(proactor)
            if (thread < 0) {
                throw IllegalStateException("Starting proactor-${proactor.id}'s thread failed with errno ${-thread}")
            }
            threads[i] = thread
        }

        return this
    }

    /** Waits for every proactor thread to end. On a proactor thread it returns at once: that thread ends only after. */
    fun join() {
        if (onProactorThread) {
            return
        }

        lockJoins(Long.MAX_VALUE)
        try {
            for (i in threads.indices) {
                val thread = threads[i]
                if (thread != 0L) {
                    joinProactorThread(thread)
                    threads[i] = 0L
                }
            }
        } finally {
            joining.store(0)
        }
    }

    /** Stops every proactor and waits for their teardown. On a proactor thread it only stops them, as [join] says. */
    override fun close() {
        proactors.forEach(Proactor::stop)
        join()
    }

    /**
     * Stops gracefully: every proactor stops accepting at once, and stops by itself when its last open connection
     * is gone. Those still serving after [gracePeriodMs] are stopped as [close] does, closing what is left. Returns
     * once every proactor thread has ended. On a proactor thread, whose proactor drains only once the handler returns, it
     * closes at once.
     */
    fun close(gracePeriodMs: Long) {
        proactors.forEach(Proactor::stopAccepting)

        // A thread in join() holds the flag until these same threads end, so waiting for it is waiting for them.
        val deadline = Clock.monotonicMs() + gracePeriodMs
        if (!onProactorThread && lockJoins(deadline)) {
            try {
                for (i in threads.indices) {
                    val thread = threads[i]
                    val remainingMs = deadline - Clock.monotonicMs()
                    if (thread == 0L || remainingMs <= 0) {
                        continue
                    }

                    if (timedJoinProactorThread(thread, remainingMs) == 0) {
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

/** A thread named after [proactor] that runs it, started: its handle, or a negative errno. */
internal expect fun startProactorThread(proactor: Proactor): Long

internal expect fun joinProactorThread(handle: Long)

/** As [joinProactorThread], giving up after [timeoutMs]: 0 once the thread has ended, negative if it has not. */
internal expect fun timedJoinProactorThread(handle: Long, timeoutMs: Long): Int
