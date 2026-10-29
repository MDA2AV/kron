package kron

import kotlinx.cinterop.LongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import platform.linux.prctl
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A refused io_uring_setup explained from the kernel's own errno, and a ring's syscalls failing with a negative errno. */
class RingSetupTest {
    @Test
    fun `a ring with more entries than the kernel allows names the limit - not the kernel`() {
        val refused = assertFailsWith<IllegalStateException> {
            Ring.create(65_536).close()
        }

        val message = refused.message ?: ""
        assertTrue(message.contains("at most 32768"), "the message does not name the entry limit: $message")
        assertFalse(message.contains("6.1 or later"), "a current kernel was blamed for being too old: $message")
    }

    @Test
    fun `a ring a seccomp profile refuses says io_uring is disabled`() {
        // What a container runtime's default profile does to io_uring_setup. The filter is one thread's, and goes
        // with it.
        val outcome = AtomicReference<String?>(null)
        val threads = TestThreads()
        threads.start {
            outcome.store(
                if (!refuseIoUringSetupOnThisThread()) {
                    NO_FILTER
                } else {
                    try {
                        Ring.create(16).close()
                        "created"
                    } catch (e: IllegalStateException) {
                        e.message
                    }
                },
            )
        }
        threads.joinAll()

        val message = outcome.load()
        if (message == NO_FILTER) {
            println("skipped: this process cannot install a seccomp filter")
            return
        }

        assertTrue(message != null && message.contains("errno 1 "), "the filter's EPERM was not what failed: $message")
        assertTrue(message.contains("disabled"), "EPERM was not explained: $message")

        // Control: the refusal was the filter's - this thread, which has none, still gets its ring.
        Ring.create(16).close()
    }

    @Test
    fun `io_uring_setup reports a negative errno - not -1`() {
        // Zero entries: refused by every kernel, so no feature detection and no accidental pass.
        val params = Native.malloc(IoUring.PARAMS_SIZE)
        try {
            zeroMemory(params, IoUring.PARAMS_SIZE)
            assertEquals(-Native.EINVAL, Native.ioUringSetup(0, params))
        } finally {
            Native.free(params)
        }
    }

    @Test
    fun `closing a ring frees its registered slot`() {
        val ring = Ring.create(16)
        val slot = ring.enterFd
        try {
            // Asked of the kernel, not read off the numbers: a slot can have the same number as a descriptor.
            assertEquals(0, enterBySlot(slot), "precondition: an enter by the slot reaches the ring")
        } finally {
            ring.close()
        }

        // A slot still holding the ring keeps it alive past its close, and would answer this.
        assertEquals(-Native.EBADF, enterBySlot(slot))
    }

    private fun enterBySlot(slot: Int): Int =
        Native.ioUringEnter(slot, 0, 0, IoUring.ENTER_REGISTERED_RING or IoUring.ENTER_GETEVENTS, 0L, 0L)

    // A seccomp filter on the calling thread alone that fails io_uring_setup with EPERM and allows everything else:
    // false when this kernel or sandbox takes none. No privilege needed once no_new_privs is set.
    private fun refuseIoUringSetupOnThisThread(): Boolean = memScoped {
        if (prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) != 0) {
            return false
        }

        // struct sock_filter { u16 code; u8 jt; u8 jf; u32 k; }, one per long.
        val program = allocArray<LongVar>(4)
        program[0] = instruction(BPF_LD_W_ABS, 0, 0, SECCOMP_DATA_NR)
        program[1] = instruction(BPF_JEQ_K, 0, 1, IO_URING_SETUP)
        program[2] = instruction(BPF_RET_K, 0, 0, SECCOMP_RET_ERRNO or Native.EPERM.toLong())
        program[3] = instruction(BPF_RET_K, 0, 0, SECCOMP_RET_ALLOW)

        // struct sock_fprog { unsigned short len; struct sock_filter *filter; }
        val fprog = allocArray<LongVar>(2)
        fprog[0] = 4L
        fprog[1] = program.rawValue.toLong()

        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, fprog) == 0
    }

    private fun instruction(code: Int, jt: Int, jf: Int, k: Long): Long =
        code.toLong() or (jt.toLong() shl 16) or (jf.toLong() shl 24) or (k shl 32)

    private companion object {
        const val NO_FILTER = "no filter"

        // linux/prctl.h and linux/seccomp.h, newer than the toolchain's headers.
        const val PR_SET_NO_NEW_PRIVS = 38
        const val PR_SET_SECCOMP = 22
        const val SECCOMP_MODE_FILTER = 2L
        const val SECCOMP_RET_ERRNO = 0x0005_0000L
        const val SECCOMP_RET_ALLOW = 0x7fff_0000L

        // linux/filter.h: load the syscall number, compare it, return.
        const val BPF_LD_W_ABS = 0x20
        const val BPF_JEQ_K = 0x15
        const val BPF_RET_K = 0x06
        const val SECCOMP_DATA_NR = 0L   // offsetof(struct seccomp_data, nr)

        // The same number on x86-64 and arm64: io_uring's syscalls are in the generic table.
        const val IO_URING_SETUP = 425L
    }
}
