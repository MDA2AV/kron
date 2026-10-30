package kron

/**
 * The calls kron makes outside the ring, each answering as kron_sys.h's C wrapper of the same name does: what the
 * libc call returns, and a negative errno for a failure where the wrapper says so. On Kotlin/Native they are that
 * inline C; on the JVM, the same libc functions through the Foreign Function & Memory API. Pointers cross as Longs.
 */
internal expect object Sys {
    fun syscall6(number: Long, a: Long, b: Long, c: Long, d: Long, e: Long, f: Long): Long
    fun mmap(length: Long, prot: Int, flags: Int, fd: Int, offset: Long): Long
    fun munmap(address: Long, length: Long): Int
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun setsockopt(fd: Int, level: Int, name: Int, value: Long, length: Int): Int
    fun bind(fd: Int, address: Long, length: Int): Int
    fun listen(fd: Int, backlog: Int): Int
    fun getsockname(fd: Int, address: Long, lengthAddress: Long): Int
    fun socketAddress(fd: Int, peer: Int, host: Long, hostLength: Int, port: Long): Int
    fun eventfd(initval: Int, flags: Int): Int
    fun close(fd: Int): Int
    fun shutdown(fd: Int, how: Int): Int
    fun read(fd: Int, buffer: Long, count: Long): Long
    fun write(fd: Int, buffer: Long, count: Long): Long
    fun send(fd: Int, buffer: Long, length: Long, flags: Int): Long
    fun recv(fd: Int, buffer: Long, length: Long, flags: Int): Long
    fun sendmsg(fd: Int, message: Long, flags: Int): Long
    fun recvmsg(fd: Int, message: Long, flags: Int): Long
    fun malloc(size: Long): Long
    fun alignedAlloc(alignment: Long, size: Long): Long
    fun free(address: Long)
    fun pinCurrentThread(cpu: Int): Int
    fun allowedCpus(out: Long, max: Int): Int
}
