package kron

import kotlin.time.TimeSource

// What the JVM gave the port for free: on Kotlin/Native from posix, on the JVM from the JDK.

/** The reactor whose thread this is, null on any other thread. */
internal expect var currentReactor: Reactor?

internal expect fun printErr(message: String)

internal expect fun spinWait()

internal expect fun sleepMs(milliseconds: Long)

internal expect val pageSize: Long

internal fun wholePages(size: Long): Long = (size + pageSize - 1) / pageSize * pageSize

/** The running kernel's release, uname's: "6.14.0-37-generic". */
internal expect fun kernelRelease(): String

/** The whole file at [path], or null when it cannot be opened or read. */
internal expect fun readFileBytes(path: String): ByteArray?

internal fun checkIndex(index: Int, length: Int) {
    if (index < 0 || index >= length) {
        throw IndexOutOfBoundsException("Index $index out of bounds for length $length")
    }
}

internal fun checkIndex(index: Long, length: Long) {
    if (index < 0 || index >= length) {
        throw IndexOutOfBoundsException("Index $index out of bounds for length $length")
    }
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

internal object Clock {
    private val origin = TimeSource.Monotonic.markNow()

    fun monotonicMs(): Long = origin.elapsedNow().inWholeMilliseconds + 1
}

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
