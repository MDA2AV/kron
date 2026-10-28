/*
 * The C side of ominous: the kernel's io_uring ABI, and thin wrappers over the libc calls made outside the
 * ring. Pointers cross as integers, the way the Kotlin side holds them. A wrapper that can fail returns a
 * negative errno, read here before anything else can overwrite it.
 */
#ifndef OMINOUS_SYS_H
#define OMINOUS_SYS_H

#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>

/* The Kotlin/Native toolchain's kernel headers are 4.9's, older than these three (4.13, 5.1 and 5.16). */
typedef int __kernel_rwf_t;
struct __kernel_timespec {
    long long tv_sec;
    long long tv_nsec;
};
#ifndef __DECLARE_FLEX_ARRAY
#define __DECLARE_FLEX_ARRAY(T, member) T member[0]
#endif
#define UAPI_LINUX_IO_URING_H_SKIP_LINUX_TIME_TYPES_H 1
#include "ominous/io_uring.h"

static inline long om_syscall6(long number, long a, long b, long c, long d, long e, long f) {
    long rc = syscall(number, a, b, c, d, e, f);
    return rc < 0 ? -errno : rc;
}

static inline long om_mmap(long length, int prot, int flags, int fd, long offset) {
    void *p = mmap(0, (size_t)length, prot, flags, fd, (off_t)offset);
    return p == MAP_FAILED ? -errno : (long)p;
}

static inline int om_munmap(long address, long length) {
    return munmap((void *)address, (size_t)length);
}

static inline int om_socket(int domain, int type, int protocol) {
    int rc = socket(domain, type, protocol);
    return rc < 0 ? -errno : rc;
}

static inline int om_setsockopt(int fd, int level, int name, long value, int length) {
    return setsockopt(fd, level, name, (const void *)value, (socklen_t)length) < 0 ? -errno : 0;
}

static inline int om_bind(int fd, long address, int length) {
    return bind(fd, (const struct sockaddr *)address, (socklen_t)length) < 0 ? -errno : 0;
}

static inline int om_listen(int fd, int backlog) {
    return listen(fd, backlog) < 0 ? -errno : 0;
}

static inline int om_getsockname(int fd, long address, long lengthAddress) {
    return getsockname(fd, (struct sockaddr *)address, (socklen_t *)lengthAddress) < 0 ? -errno : 0;
}

static inline int om_eventfd(int initval, int flags) {
    int rc = eventfd((unsigned int)initval, flags);
    return rc < 0 ? -errno : rc;
}

/* Best effort, as on the JVM: -1 is all a caller acts on. */
static inline int om_close(int fd) {
    return close(fd);
}
static inline int om_shutdown(int fd, int how) {
    return shutdown(fd, how);
}
static inline long om_read(int fd, long buffer, long count) {
    return read(fd, (void *)buffer, (size_t)count);
}
static inline long om_write(int fd, long buffer, long count) {
    return write(fd, (const void *)buffer, (size_t)count);
}
static inline long om_send(int fd, long buffer, long length, int flags) {
    return send(fd, (const void *)buffer, (size_t)length, flags);
}
static inline long om_recv(int fd, long buffer, long length, int flags) {
    return recv(fd, (void *)buffer, (size_t)length, flags);
}
static inline long om_sendmsg(int fd, long message, int flags) {
    return sendmsg(fd, (const struct msghdr *)message, flags);
}
static inline long om_recvmsg(int fd, long message, int flags) {
    return recvmsg(fd, (struct msghdr *)message, flags);
}

static inline long om_malloc(long size) {
    return (long)malloc((size_t)size);
}
static inline long om_aligned_alloc(long alignment, long size) {
    return (long)aligned_alloc((size_t)alignment, (size_t)size);
}
static inline void om_free(long address) {
    free((void *)address);
}
static inline void om_memmove(long destination, long source, long length) {
    memmove((void *)destination, (const void *)source, (size_t)length);
}
static inline void om_memset(long destination, int value, long length) {
    memset((void *)destination, value, (size_t)length);
}

/* Pins the calling thread to one CPU. */
static inline int om_pin_current_thread(int cpu) {
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);

    return sched_setaffinity(0, sizeof set, &set) < 0 ? -errno : 0;
}

/* A named thread running a Kotlin entry point: its handle, or a negative errno. */
static inline long om_thread_start(void *(*entry)(void *), void *argument, const char *name) {
    pthread_t thread;
    int rc = pthread_create(&thread, 0, entry, argument);
    if (rc != 0) {
        return -rc;
    }

    if (name) {
        pthread_setname_np(thread, name);
    }

    return (long)thread;
}

static inline int om_thread_join(long thread) {
    return -pthread_join((pthread_t)thread, 0);
}

#endif
