package kron

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.native.concurrent.ThreadLocal
import platform.posix.EINTR
import platform.posix._SC_PAGESIZE
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fputs
import platform.posix.open
import platform.posix.read
import platform.posix.sched_yield
import platform.posix.stderr
import platform.posix.sysconf
import platform.posix.uname
import platform.posix.usleep
import platform.posix.utsname

// What the JVM gave the port for free, on Kotlin/Native.

/** The reactor running on this thread, if any: what [Reactor.onReactorThread] compares against. */
@ThreadLocal
internal actual var currentReactor: Reactor? = null

/** One line to stderr, unbuffered. */
internal actual fun printErr(message: String) {
    fputs(message + "\n", stderr)
}

/** A spin loop's pause: gives the CPU away, as the wait is for another thread. */
internal actual fun spinWait() {
    sched_yield()
}

internal actual fun sleepMs(milliseconds: Long) {
    usleep((milliseconds * 1000).toUInt())
}

/** The kernel's page size: 4 KiB on x86-64, and 4, 16 or 64 KiB on arm64, as the kernel was built. */
internal actual val pageSize: Long = sysconf(_SC_PAGESIZE)

/** [size] rounded up to whole pages. */

/** The running kernel's release, as `uname -r` prints it. */
internal actual fun kernelRelease(): String = memScoped {
    val name = alloc<utsname>()
    if (uname(name.ptr) == 0) name.release.toKString() else "unknown"
}

/** The monotonic clock in milliseconds, from an origin that makes it start at 1 (0 means "never"). */

/**
 * The whole file at [path], or null when it cannot be opened or read. Buffers it outgrows are zeroed, as the bytes
 * may be a private key.
 */
internal actual fun readFileBytes(path: String): ByteArray? {
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

internal actual fun outOfMemory(message: String): Throwable = OutOfMemoryError(message)
