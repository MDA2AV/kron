package kron

import kron.sys.kr_aligned_alloc
import kron.sys.kr_allowed_cpus
import kron.sys.kr_bind
import kron.sys.kr_close
import kron.sys.kr_eventfd
import kron.sys.kr_free
import kron.sys.kr_getsockname
import kron.sys.kr_listen
import kron.sys.kr_malloc
import kron.sys.kr_mmap
import kron.sys.kr_munmap
import kron.sys.kr_pin_current_thread
import kron.sys.kr_read
import kron.sys.kr_recv
import kron.sys.kr_recvmsg
import kron.sys.kr_send
import kron.sys.kr_sendmsg
import kron.sys.kr_setsockopt
import kron.sys.kr_shutdown
import kron.sys.kr_socket
import kron.sys.kr_socket_address
import kron.sys.kr_syscall6
import kron.sys.kr_write

internal actual object Sys {
    actual fun syscall6(number: Long, a: Long, b: Long, c: Long, d: Long, e: Long, f: Long): Long =
        kr_syscall6(number, a, b, c, d, e, f)

    actual fun mmap(length: Long, prot: Int, flags: Int, fd: Int, offset: Long): Long =
        kr_mmap(length, prot, flags, fd, offset)

    actual fun munmap(address: Long, length: Long): Int = kr_munmap(address, length)

    actual fun socket(domain: Int, type: Int, protocol: Int): Int = kr_socket(domain, type, protocol)

    actual fun setsockopt(fd: Int, level: Int, name: Int, value: Long, length: Int): Int =
        kr_setsockopt(fd, level, name, value, length)

    actual fun bind(fd: Int, address: Long, length: Int): Int = kr_bind(fd, address, length)

    actual fun listen(fd: Int, backlog: Int): Int = kr_listen(fd, backlog)

    actual fun getsockname(fd: Int, address: Long, lengthAddress: Long): Int = kr_getsockname(fd, address, lengthAddress)

    actual fun socketAddress(fd: Int, peer: Int, host: Long, hostLength: Int, port: Long): Int =
        kr_socket_address(fd, peer, host, hostLength, port)

    actual fun eventfd(initval: Int, flags: Int): Int = kr_eventfd(initval, flags)

    actual fun close(fd: Int): Int = kr_close(fd)

    actual fun shutdown(fd: Int, how: Int): Int = kr_shutdown(fd, how)

    actual fun read(fd: Int, buffer: Long, count: Long): Long = kr_read(fd, buffer, count)

    actual fun write(fd: Int, buffer: Long, count: Long): Long = kr_write(fd, buffer, count)

    actual fun send(fd: Int, buffer: Long, length: Long, flags: Int): Long = kr_send(fd, buffer, length, flags)

    actual fun recv(fd: Int, buffer: Long, length: Long, flags: Int): Long = kr_recv(fd, buffer, length, flags)

    actual fun sendmsg(fd: Int, message: Long, flags: Int): Long = kr_sendmsg(fd, message, flags)

    actual fun recvmsg(fd: Int, message: Long, flags: Int): Long = kr_recvmsg(fd, message, flags)

    actual fun malloc(size: Long): Long = kr_malloc(size)

    actual fun alignedAlloc(alignment: Long, size: Long): Long = kr_aligned_alloc(alignment, size)

    actual fun free(address: Long) = kr_free(address)

    actual fun pinCurrentThread(cpu: Int): Int = kr_pin_current_thread(cpu)

    actual fun allowedCpus(out: Long, max: Int): Int = kr_allowed_cpus(out, max)
}
