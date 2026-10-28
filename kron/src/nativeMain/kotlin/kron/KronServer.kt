package kron

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kron.sys.kr_thread_join
import kron.sys.kr_thread_start

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

    // pthread handles; 0 once joined, or before start.
    private val threads = LongArray(reactors.size)

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

    /** Waits for every reactor thread to end. */
    fun join() {
        for (i in threads.indices) {
            val thread = threads[i]
            if (thread != 0L) {
                threads[i] = 0L
                kr_thread_join(thread)
            }
        }
    }

    /** Stops every reactor and waits for their teardown. */
    override fun close() {
        reactors.forEach(Reactor::stop)
        join()
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
