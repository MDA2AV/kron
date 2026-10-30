package kron


/**
 * libc through cinterop: io_uring's three syscalls (glibc wraps none of them, so they go through `syscall`),
 * the mmaps for the rings, and the handful of socket and descriptor calls made outside the ring.
 *
 * Every wrapper returns a negative errno on failure, liburing's convention, which is what every caller
 * compares against; the C side reads errno before returning (kron_sys.h). Pointers travel as `Long`
 * addresses, see [getLong].
 */
internal object Native {
    private const val SYS_IO_URING_SETUP = 425L
    private const val SYS_IO_URING_ENTER = 426L
    private const val SYS_IO_URING_REGISTER = 427L

    fun ioUringSetup(entries: Int, params: Long): Int =
        Sys.syscall6(SYS_IO_URING_SETUP, entries.toLong(), params, 0L, 0L, 0L, 0L).toInt()

    fun ioUringEnter(fd: Int, toSubmit: Int, minComplete: Int, flags: Int, arg: Long, argSize: Long): Int =
        Sys.syscall6(
            SYS_IO_URING_ENTER, fd.toLong(), toSubmit.toLong() and 0xFFFF_FFFFL,
            minComplete.toLong() and 0xFFFF_FFFFL, flags.toLong(), arg, argSize,
        ).toInt()

    fun ioUringRegister(fd: Int, opcode: Int, arg: Long, nrArgs: Int): Int =
        Sys.syscall6(SYS_IO_URING_REGISTER, fd.toLong(), opcode.toLong(), arg, nrArgs.toLong(), 0L, 0L).toInt()

    /** The mapping's address, or a negative errno. */
    fun mmap(length: Long, prot: Int, flags: Int, fd: Int, offset: Long): Long =
        Sys.mmap(length, prot, flags, fd, offset)

    fun munmap(addr: Long, length: Long): Int = Sys.munmap(addr, length)

    fun close(fd: Int): Int = Sys.close(fd)

    fun socket(domain: Int, type: Int, protocol: Int): Int = Sys.socket(domain, type, protocol)

    fun setsockopt(fd: Int, level: Int, name: Int, value: Long, length: Int): Int =
        Sys.setsockopt(fd, level, name, value, length)

    fun bind(fd: Int, addr: Long, length: Int): Int = Sys.bind(fd, addr, length)

    fun listen(fd: Int, backlog: Int): Int = Sys.listen(fd, backlog)

    fun getsockname(fd: Int, addr: Long, lengthAddr: Long): Int = Sys.getsockname(fd, addr, lengthAddr)

    fun socketAddress(fd: Int, peer: Boolean, host: Long, hostLength: Int, port: Long): Int =
        Sys.socketAddress(fd, if (peer) 1 else 0, host, hostLength, port)

    fun shutdown(fd: Int, how: Int): Int = Sys.shutdown(fd, how)

    fun eventfd(initval: Int, flags: Int): Int = Sys.eventfd(initval, flags)

    fun write(fd: Int, buf: Long, count: Long): Long = Sys.write(fd, buf, count)

    fun read(fd: Int, buf: Long, count: Long): Long = Sys.read(fd, buf, count)

    // The direct socket calls TLS makes beside the ring: a teardown close_notify, and the kTLS RX handoff's
    // look at what is waiting. Best effort, so no errno: -1 is all a caller acts on.
    fun send(fd: Int, buf: Long, length: Long, flags: Int): Long = Sys.send(fd, buf, length, flags)

    fun recv(fd: Int, buf: Long, length: Long, flags: Int): Long = Sys.recv(fd, buf, length, flags)

    fun sendmsg(fd: Int, msg: Long, flags: Int): Long = Sys.sendmsg(fd, msg, flags)

    fun recvmsg(fd: Int, msg: Long, flags: Int): Long = Sys.recvmsg(fd, msg, flags)

    fun malloc(size: Long): Long {
        val p = Sys.malloc(size)
        if (p == 0L) {
            throw OutOfMemoryError("malloc($size)")
        }

        return p
    }

    /** [size] is rounded up to a multiple of [alignment], which aligned_alloc requires. */
    fun alignedAlloc(alignment: Long, size: Long): Long {
        val rounded = (size + alignment - 1) / alignment * alignment
        val p = Sys.alignedAlloc(alignment, rounded)
        if (p == 0L) {
            throw OutOfMemoryError("aligned_alloc($alignment, $size)")
        }

        return p
    }

    fun free(addr: Long) {
        if (addr != 0L) {
            Sys.free(addr)
        }
    }

    /** Pins the calling thread to one CPU. */
    fun pinCurrentThread(cpu: Int): Int = Sys.pinCurrentThread(cpu)

    /** The CPUs the calling thread may run on, ascending: its affinity mask, as a cpuset or taskset narrows it. */
    fun allowedCpus(): IntArray {
        val out = malloc(4L * CPU_SETSIZE)
        try {
            val count = Sys.allowedCpus(out, CPU_SETSIZE)
            if (count < 0) {
                throw IllegalStateException("sched_getaffinity failed with errno ${-count}")
            }

            return IntArray(count) { getInt(out + 4L * it) }
        } finally {
            free(out)
        }
    }

    // glibc's fixed cpu_set_t: CPUs 0 to 1023.
    private const val CPU_SETSIZE = 1024

    // errno values the callers compare against.
    const val EPERM = 1
    const val ENOENT = 2
    const val EINTR = 4
    const val EBADF = 9
    const val EAGAIN = 11
    const val ENOMEM = 12
    const val EBUSY = 16
    const val EINVAL = 22
    const val ENFILE = 23
    const val EMFILE = 24
    const val EPIPE = 32
    const val ENOSYS = 38
    const val ETIME = 62
    const val ECONNABORTED = 103
    const val ECONNRESET = 104
    const val ENOBUFS = 105
    const val ENOTCONN = 107
    const val ECANCELED = 125

    const val PROT_READ = 1
    const val PROT_WRITE = 2
    const val MAP_SHARED = 0x01
    const val MAP_PRIVATE = 0x02
    const val MAP_ANONYMOUS = 0x20
    const val MAP_POPULATE = 0x8000

    const val EFD_NONBLOCK = 0x800
    const val EFD_CLOEXEC = 0x80000
}
