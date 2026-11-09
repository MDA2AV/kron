package kronos

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kronos.sys.kr_thread_join
import kronos.sys.kr_thread_start
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.errno
import platform.posix.ioctl
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr
import platform.posix.socketpair

internal actual object TestSys {
    actual fun connect(fd: Int, address: Long, length: Int): Int =
        if (platform.posix.connect(fd, address.toCPointer<sockaddr>(), length.convert()) != 0) -errno else 0

    actual fun send(fd: Int, buffer: Long, length: Long, flags: Int): Long {
        val n = send(fd, buffer.toCPointer<ByteVar>(), length.convert(), flags)
        return if (n < 0) -errno.toLong() else n
    }

    actual fun recv(fd: Int, buffer: Long, length: Long, flags: Int): Long {
        val n = recv(fd, buffer.toCPointer<ByteVar>(), length.convert(), flags)
        return if (n < 0) -errno.toLong() else n
    }

    actual fun unacknowledged(fd: Int): Int = memScoped {
        val bytes = alloc<IntVar>()
        if (ioctl(fd, TIOCOUTQ, bytes.ptr) != 0) -errno else bytes.value
    }

    actual fun dup2(oldFd: Int, newFd: Int): Int {
        val rc = platform.posix.dup2(oldFd, newFd)
        return if (rc < 0) -errno else rc
    }

    actual fun socketPair(): IntArray = memScoped {
        val fds = allocArray<IntVar>(2)
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0) { "socketpair failed with errno $errno" }
        intArrayOf(fds[0], fds[1])
    }

    // SIOCOUTQ: bytes written and not yet acknowledged (asm-generic/ioctls.h).
    private const val TIOCOUTQ = 0x5411UL
}

internal actual fun startTestThread(block: () -> Unit): Long {
    val ref = StableRef.create(block)
    val thread = kr_thread_start(threadEntry, ref.asCPointer(), "test")
    if (thread <= 0) {
        ref.dispose()
    }

    return thread
}

internal actual fun joinTestThread(handle: Long) {
    kr_thread_join(handle)
}

private val threadEntry = staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
    val ref = argument!!.asStableRef<() -> Unit>()
    val block = ref.get()
    ref.dispose()
    try {
        block()
    } catch (e: Throwable) {
        println("test thread failed: ${e.stackTraceToString()}")
    }

    null
}
