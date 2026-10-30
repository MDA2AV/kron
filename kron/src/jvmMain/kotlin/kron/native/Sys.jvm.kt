package kron

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

// libc through FFM. A call whose failure kron_sys.h turns into a negative errno captures errno with it, into a
// segment of the calling thread's.
internal actual object Sys {
    private val linker: Linker = Linker.nativeLinker()
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
    private val threadState: ThreadLocal<MemorySegment> = ThreadLocal.withInitial { Arena.ofAuto().allocate(stateLayout) }
    private val captureErrno = Linker.Option.captureCallState("errno")

    private fun bind(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, *options)

    private fun errno(state: MemorySegment): Int = state.get(JAVA_INT, errnoOffset)

    private val L = JAVA_LONG
    private val I = JAVA_INT

    // long syscall(long number, ...): every argument a full register, as the kernel reads them.
    private val SYSCALL = bind("syscall", FunctionDescriptor.of(L, L, L, L, L, L, L, L), Linker.Option.firstVariadicArg(1), captureErrno)
    private val MMAP = bind("mmap", FunctionDescriptor.of(L, L, L, I, I, I, L), captureErrno)
    private val MUNMAP = bind("munmap", FunctionDescriptor.of(I, L, L))
    private val SOCKET = bind("socket", FunctionDescriptor.of(I, I, I, I), captureErrno)
    private val SETSOCKOPT = bind("setsockopt", FunctionDescriptor.of(I, I, I, I, L, I), captureErrno)
    private val BIND = bind("bind", FunctionDescriptor.of(I, I, L, I), captureErrno)
    private val LISTEN = bind("listen", FunctionDescriptor.of(I, I, I), captureErrno)
    private val GETSOCKNAME = bind("getsockname", FunctionDescriptor.of(I, I, L, L), captureErrno)
    private val GETPEERNAME = bind("getpeername", FunctionDescriptor.of(I, I, L, L), captureErrno)
    private val INET_NTOP = bind("inet_ntop", FunctionDescriptor.of(L, I, L, L, I), captureErrno)
    private val EVENTFD = bind("eventfd", FunctionDescriptor.of(I, I, I), captureErrno)
    private val CLOSE = bind("close", FunctionDescriptor.of(I, I))
    private val SHUTDOWN = bind("shutdown", FunctionDescriptor.of(I, I, I))
    private val READ = bind("read", FunctionDescriptor.of(L, I, L, L))
    private val WRITE = bind("write", FunctionDescriptor.of(L, I, L, L))
    private val SEND = bind("send", FunctionDescriptor.of(L, I, L, L, I))
    private val RECV = bind("recv", FunctionDescriptor.of(L, I, L, L, I))
    private val SENDMSG = bind("sendmsg", FunctionDescriptor.of(L, I, L, I))
    private val RECVMSG = bind("recvmsg", FunctionDescriptor.of(L, I, L, I))
    private val MALLOC = bind("malloc", FunctionDescriptor.of(L, L))
    private val ALIGNED_ALLOC = bind("aligned_alloc", FunctionDescriptor.of(L, L, L))
    private val FREE = bind("free", FunctionDescriptor.ofVoid(L))
    private val SCHED_SETAFFINITY = bind("sched_setaffinity", FunctionDescriptor.of(I, I, L, L), captureErrno)
    private val SCHED_GETAFFINITY = bind("sched_getaffinity", FunctionDescriptor.of(I, I, L, L), captureErrno)

    // glibc's fixed cpu_set_t: 1024 CPUs, 128 bytes.
    private const val CPU_SET_SIZE = 128L
    private const val CPU_SETSIZE = 1024
    private const val AF_INET = 2
    private const val AF_INET6 = 10
    private const val EAFNOSUPPORT = 97
    private const val SOCKADDR_STORAGE_SIZE = 128L

    actual fun syscall6(number: Long, a: Long, b: Long, c: Long, d: Long, e: Long, f: Long): Long {
        val state = threadState.get()
        val rc = SYSCALL.invokeExact(state, number, a, b, c, d, e, f) as Long
        return if (rc < 0) -errno(state).toLong() else rc
    }

    actual fun mmap(length: Long, prot: Int, flags: Int, fd: Int, offset: Long): Long {
        val state = threadState.get()
        val rc = MMAP.invokeExact(state, 0L, length, prot, flags, fd, offset) as Long
        return if (rc == -1L) -errno(state).toLong() else rc
    }

    actual fun munmap(address: Long, length: Long): Int = MUNMAP.invokeExact(address, length) as Int

    actual fun socket(domain: Int, type: Int, protocol: Int): Int {
        val state = threadState.get()
        val rc = SOCKET.invokeExact(state, domain, type, protocol) as Int
        return if (rc < 0) -errno(state) else rc
    }

    actual fun setsockopt(fd: Int, level: Int, name: Int, value: Long, length: Int): Int {
        val state = threadState.get()
        return if ((SETSOCKOPT.invokeExact(state, fd, level, name, value, length) as Int) < 0) -errno(state) else 0
    }

    actual fun bind(fd: Int, address: Long, length: Int): Int {
        val state = threadState.get()
        return if ((BIND.invokeExact(state, fd, address, length) as Int) < 0) -errno(state) else 0
    }

    actual fun listen(fd: Int, backlog: Int): Int {
        val state = threadState.get()
        return if ((LISTEN.invokeExact(state, fd, backlog) as Int) < 0) -errno(state) else 0
    }

    actual fun getsockname(fd: Int, address: Long, lengthAddress: Long): Int {
        val state = threadState.get()
        return if ((GETSOCKNAME.invokeExact(state, fd, address, lengthAddress) as Int) < 0) -errno(state) else 0
    }

    // kr_socket_address: one end of a connected socket, its IP as text at host and its port as an int at port.
    actual fun socketAddress(fd: Int, peer: Int, host: Long, hostLength: Int, port: Long): Int {
        val state = threadState.get()
        val storage = malloc(SOCKADDR_STORAGE_SIZE + 4)
        try {
            val lengthAddress = storage + SOCKADDR_STORAGE_SIZE
            putInt(lengthAddress, SOCKADDR_STORAGE_SIZE.toInt())
            val name = if (peer != 0) GETPEERNAME else GETSOCKNAME
            if ((name.invokeExact(state, fd, storage, lengthAddress) as Int) < 0) {
                return -errno(state)
            }

            // sockaddr_in and sockaddr_in6 both start family, port; the address is at 4 or at 8.
            val family = getShort(storage).toInt() and 0xFFFF
            val bytes = when (family) {
                AF_INET -> storage + 4
                AF_INET6 -> storage + 8
                else -> return -EAFNOSUPPORT
            }

            if ((INET_NTOP.invokeExact(state, family, bytes, host, hostLength) as Long) == 0L) {
                return -errno(state)
            }

            val networkPort = getShort(storage + 2).toInt() and 0xFFFF
            putInt(port, ((networkPort and 0xFF) shl 8) or (networkPort ushr 8))
            return 0
        } finally {
            free(storage)
        }
    }

    actual fun eventfd(initval: Int, flags: Int): Int {
        val state = threadState.get()
        val rc = EVENTFD.invokeExact(state, initval, flags) as Int
        return if (rc < 0) -errno(state) else rc
    }

    actual fun close(fd: Int): Int = CLOSE.invokeExact(fd) as Int

    actual fun shutdown(fd: Int, how: Int): Int = SHUTDOWN.invokeExact(fd, how) as Int

    actual fun read(fd: Int, buffer: Long, count: Long): Long = READ.invokeExact(fd, buffer, count) as Long

    actual fun write(fd: Int, buffer: Long, count: Long): Long = WRITE.invokeExact(fd, buffer, count) as Long

    actual fun send(fd: Int, buffer: Long, length: Long, flags: Int): Long =
        SEND.invokeExact(fd, buffer, length, flags) as Long

    actual fun recv(fd: Int, buffer: Long, length: Long, flags: Int): Long =
        RECV.invokeExact(fd, buffer, length, flags) as Long

    actual fun sendmsg(fd: Int, message: Long, flags: Int): Long = SENDMSG.invokeExact(fd, message, flags) as Long

    actual fun recvmsg(fd: Int, message: Long, flags: Int): Long = RECVMSG.invokeExact(fd, message, flags) as Long

    actual fun malloc(size: Long): Long = MALLOC.invokeExact(size) as Long

    actual fun alignedAlloc(alignment: Long, size: Long): Long = ALIGNED_ALLOC.invokeExact(alignment, size) as Long

    actual fun free(address: Long) {
        FREE.invokeExact(address)
    }

    actual fun pinCurrentThread(cpu: Int): Int {
        val state = threadState.get()
        val set = malloc(CPU_SET_SIZE)
        try {
            zeroMemory(set, CPU_SET_SIZE)
            val word = set + 8L * (cpu / 64)
            putLong(word, getLong(word) or (1L shl (cpu % 64)))
            return if ((SCHED_SETAFFINITY.invokeExact(state, 0, CPU_SET_SIZE, set) as Int) < 0) -errno(state) else 0
        } finally {
            free(set)
        }
    }

    actual fun allowedCpus(out: Long, max: Int): Int {
        val state = threadState.get()
        val set = malloc(CPU_SET_SIZE)
        try {
            if ((SCHED_GETAFFINITY.invokeExact(state, 0, CPU_SET_SIZE, set) as Int) < 0) {
                return -errno(state)
            }

            var count = 0
            var cpu = 0
            while (cpu < CPU_SETSIZE && count < max) {
                if (getLong(set + 8L * (cpu / 64)) and (1L shl (cpu % 64)) != 0L) {
                    putInt(out + 4L * count, cpu)
                    count++
                }
                cpu++
            }

            return count
        } finally {
            free(set)
        }
    }
}
