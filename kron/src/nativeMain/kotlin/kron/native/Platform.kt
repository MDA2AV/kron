package kron

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.native.concurrent.ThreadLocal
import kotlin.time.TimeSource
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fputs
import platform.posix.open
import platform.posix.read
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

/** The monotonic clock in milliseconds, from an origin that makes it start at 1 (0 means "never"). */
internal object Clock {
    private val origin = TimeSource.Monotonic.markNow()

    fun monotonicMs(): Long = origin.elapsedNow().inWholeMilliseconds + 1
}

/** An I/O failure surfaced to a caller, as java.io.IOException is on the JVM. */
open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * The whole file at [path], or null when it cannot be opened or read. Buffers it outgrows are zeroed, as the bytes
 * may be a private key.
 */
internal fun readFileBytes(path: String): ByteArray? {
    val fd = open(path, O_RDONLY or O_CLOEXEC)
    if (fd < 0) {
        return null
    }

    try {
        var bytes = ByteArray(4096)
        var length = 0
        while (true) {
            if (length == bytes.size) {
                val grown = bytes.copyOf(bytes.size * 2)
                bytes.fill(0)
                bytes = grown
            }

            val buffer = bytes
            val n = buffer.usePinned { read(fd, it.addressOf(length), (buffer.size - length).convert()) }
            if (n < 0) {
                if (errno == EINTR) {
                    continue
                }

                bytes.fill(0)
                return null
            }

            if (n == 0L) {
                return bytes.copyOf(length).also { bytes.fill(0) }
            }

            length += n.toInt()
        }
    } finally {
        close(fd)
    }
}
