package kron

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal actual object TestSys {
    private val linker = Linker.nativeLinker()
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
    private val threadState: ThreadLocal<MemorySegment> = ThreadLocal.withInitial { Arena.ofAuto().allocate(stateLayout) }
    private val captureErrno = Linker.Option.captureCallState("errno")

    private fun bind(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, *options)

    private val CONNECT = bind("connect", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT), captureErrno)
    private val SEND = bind("send", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT), captureErrno)
    private val RECV = bind("recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT), captureErrno)
    private val IOCTL = bind(
        "ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG), Linker.Option.firstVariadicArg(2), captureErrno,
    )

    // SIOCOUTQ: bytes written and not yet acknowledged (asm-generic/ioctls.h).
    private const val TIOCOUTQ = 0x5411L

    private fun errno(state: MemorySegment): Int = state.get(JAVA_INT, errnoOffset)

    actual fun connect(fd: Int, address: Long, length: Int): Int {
        val state = threadState.get()
        return if ((CONNECT.invokeExact(state, fd, address, length) as Int) != 0) -errno(state) else 0
    }

    actual fun send(fd: Int, buffer: Long, length: Long, flags: Int): Long {
        val state = threadState.get()
        val n = SEND.invokeExact(state, fd, buffer, length, flags) as Long
        return if (n < 0) -errno(state).toLong() else n
    }

    actual fun recv(fd: Int, buffer: Long, length: Long, flags: Int): Long {
        val state = threadState.get()
        val n = RECV.invokeExact(state, fd, buffer, length, flags) as Long
        return if (n < 0) -errno(state).toLong() else n
    }

    actual fun unacknowledged(fd: Int): Int {
        val state = threadState.get()
        val bytes = Native.malloc(4)
        try {
            return if ((IOCTL.invokeExact(state, fd, TIOCOUTQ, bytes) as Int) != 0) -errno(state) else getInt(bytes)
        } finally {
            Native.free(bytes)
        }
    }
}

private val testThreads = ConcurrentHashMap<Long, Thread>()
private val nextTestThread = AtomicLong(1)

internal actual fun startTestThread(block: () -> Unit): Long {
    val handle = nextTestThread.getAndIncrement()
    val thread = Thread({
        try {
            block()
        } catch (e: Throwable) {
            println("test thread failed: ${e.stackTraceToString()}")
        }
    }, "test")
    testThreads[handle] = thread
    thread.start()
    return handle
}

internal actual fun joinTestThread(handle: Long) {
    testThreads.remove(handle)?.join()
}
