package ominous

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlin.native.concurrent.ThreadLocal
import kotlin.time.TimeSource
import platform.posix.fputs
import platform.posix.sched_yield
import platform.posix.stderr
import platform.posix.uname
import platform.posix.usleep
import platform.posix.utsname

// What the JVM gave the port for free, on Kotlin/Native.

/** The reactor running on this thread, if any: what [Reactor.onReactorThread] compares against. */
@ThreadLocal
internal var currentReactor: Reactor? = null

/** One line to stderr, unbuffered. */
internal fun printErr(message: String) {
    fputs(message + "\n", stderr)
}

/** A spin loop's pause: gives the CPU away, as the wait is for another thread. */
internal fun spinWait() {
    sched_yield()
}

internal fun sleepMs(milliseconds: Long) {
    usleep((milliseconds * 1000).toUInt())
}

/** The running kernel's release, as `uname -r` prints it. */
internal fun kernelRelease(): String = memScoped {
    val name = alloc<utsname>()
    if (uname(name.ptr) == 0) name.release.toKString() else "unknown"
}

internal fun checkIndex(index: Int, length: Int) {
    if (index < 0 || index >= length) throw IndexOutOfBoundsException("Index $index out of bounds for length $length")
}

internal fun checkIndex(index: Long, length: Long) {
    if (index < 0 || index >= length) throw IndexOutOfBoundsException("Index $index out of bounds for length $length")
}

internal fun checkFromIndexSize(fromIndex: Int, size: Int, length: Int) {
    if (fromIndex < 0 || size < 0 || fromIndex > length - size) {
        throw IndexOutOfBoundsException("Range [$fromIndex, $fromIndex + $size) out of bounds for length $length")
    }
}

internal fun checkFromIndexSize(fromIndex: Long, size: Long, length: Long) {
    if (fromIndex < 0 || size < 0 || fromIndex > length - size) {
        throw IndexOutOfBoundsException("Range [$fromIndex, $fromIndex + $size) out of bounds for length $length")
    }
}

/** The monotonic clock in milliseconds, from an origin that makes it start at 1 (0 means "never"). */
internal object Clock {
    private val origin = TimeSource.Monotonic.markNow()

    fun monotonicMs(): Long = origin.elapsedNow().inWholeMilliseconds + 1
}

/** An I/O failure surfaced to a caller, as java.io.IOException is on the JVM. */
open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
