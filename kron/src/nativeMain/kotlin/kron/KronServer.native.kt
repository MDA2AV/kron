package kron

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kron.sys.kr_thread_join
import kron.sys.kr_thread_start
import kron.sys.kr_thread_timed_join

internal actual fun startReactorThread(reactor: Reactor): Long {
    val ref = StableRef.create(reactor)
    val thread = kr_thread_start(reactorEntry, ref.asCPointer(), "reactor-${reactor.id}")
    if (thread < 0) {
        ref.dispose()
    }

    return thread
}

internal actual fun joinReactorThread(handle: Long) {
    kr_thread_join(handle)
}

internal actual fun timedJoinReactorThread(handle: Long, timeoutMs: Long): Int = kr_thread_timed_join(handle, timeoutMs)

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
