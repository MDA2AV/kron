package kronos

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kronos.sys.kr_thread_join
import kronos.sys.kr_thread_start
import kronos.sys.kr_thread_timed_join

internal actual fun startProactorThread(proactor: Proactor): Long {
    val ref = StableRef.create(proactor)
    val thread = kr_thread_start(proactorEntry, ref.asCPointer(), "proactor-${proactor.id}")
    if (thread < 0) {
        ref.dispose()
    }

    return thread
}

internal actual fun joinProactorThread(handle: Long) {
    kr_thread_join(handle)
}

internal actual fun timedJoinProactorThread(handle: Long, timeoutMs: Long): Int = kr_thread_timed_join(handle, timeoutMs)

// A proactor thread's entry, with the ref start() made. An exception escaping into C would end the process, so
// it ends only the thread, as an uncaught one does on the JVM.
private val proactorEntry = staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
    val ref = argument!!.asStableRef<Proactor>()
    val proactor = ref.get()
    ref.dispose()

    try {
        proactor.run()
    } catch (e: Throwable) {
        printErr("[p${proactor.id}] proactor thread ended: ${e.stackTraceToString()}")
    }

    null
}
