package kron

import kotlinx.cinterop.interpretPointed
import kotlinx.cinterop.nativeNullPtr
import kotlinx.cinterop.plus
import kotlinx.cinterop.sizeOf
import kron.sys.IORING_ACCEPT_MULTISHOT
import kron.sys.IORING_CQE_BUFFER_SHIFT
import kron.sys.IORING_CQE_F_BUFFER
import kron.sys.IORING_CQE_F_BUF_MORE
import kron.sys.IORING_CQE_F_MORE
import kron.sys.IORING_CQE_F_NOTIF
import kron.sys.IORING_CQE_F_SOCK_NONEMPTY
import kron.sys.IORING_ENTER_EXT_ARG
import kron.sys.IORING_ENTER_GETEVENTS
import kron.sys.IORING_ENTER_REGISTERED_RING
import kron.sys.IORING_FEAT_SINGLE_MMAP
import kron.sys.IORING_OFF_SQES
import kron.sys.IORING_OFF_SQ_RING
import kron.sys.IORING_POLL_ADD_MULTI
import kron.sys.IORING_RECVSEND_POLL_FIRST
import kron.sys.IORING_RECV_MULTISHOT
import kron.sys.IORING_REGISTER_PBUF_RING
import kron.sys.IORING_REGISTER_RING_FDS
import kron.sys.IORING_SETUP_CQSIZE
import kron.sys.IORING_SETUP_DEFER_TASKRUN
import kron.sys.IORING_SETUP_NO_SQARRAY
import kron.sys.IORING_SETUP_SINGLE_ISSUER
import kron.sys.IORING_SETUP_SUBMIT_ALL
import kron.sys.IORING_SQ_CQ_OVERFLOW
import kron.sys.IORING_UNREGISTER_PBUF_RING
import kron.sys.IORING_UNREGISTER_RING_FDS
import kron.sys.IOSQE_ASYNC
import kron.sys.IOSQE_BUFFER_SELECT
import kron.sys.IOSQE_CQE_SKIP_SUCCESS
import kron.sys.IOSQE_FIXED_FILE
import kron.sys.IOSQE_IO_LINK
import kron.sys.IOU_PBUF_RING_INC
import kron.sys.io_uring_buf
import kron.sys.io_uring_buf_reg
import kron.sys.io_uring_cqe
import kron.sys.io_uring_getevents_arg
import kron.sys.io_uring_op
import kron.sys.io_uring_params
import kron.sys.io_uring_rsrc_update
import kron.sys.io_uring_sqe
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IoUring.kt keeps the JVM port's literals, so the ring code stays line for line with it. These check every one
 * against the kernel's own header (include/linux/io_uring.h, Linux v7.3) as cinterop compiled it: values compared,
 * and each struct written through its generated fields and read back at the offsets the ring code uses.
 */
class IoUringLayoutTest {
    @Test
    fun `opcodes match the header`() {
        assertEquals(io_uring_op.IORING_OP_NOP.value.toInt(), IoUring.OP_NOP)
        assertEquals(io_uring_op.IORING_OP_POLL_ADD.value.toInt(), IoUring.OP_POLL_ADD)
        assertEquals(io_uring_op.IORING_OP_SENDMSG.value.toInt(), IoUring.OP_SENDMSG)
        assertEquals(io_uring_op.IORING_OP_TIMEOUT.value.toInt(), IoUring.OP_TIMEOUT)
        assertEquals(io_uring_op.IORING_OP_TIMEOUT_REMOVE.value.toInt(), IoUring.OP_TIMEOUT_REMOVE)
        assertEquals(io_uring_op.IORING_OP_ACCEPT.value.toInt(), IoUring.OP_ACCEPT)
        assertEquals(io_uring_op.IORING_OP_ASYNC_CANCEL.value.toInt(), IoUring.OP_ASYNC_CANCEL)
        assertEquals(io_uring_op.IORING_OP_CLOSE.value.toInt(), IoUring.OP_CLOSE)
        assertEquals(io_uring_op.IORING_OP_READ.value.toInt(), IoUring.OP_READ)
        assertEquals(io_uring_op.IORING_OP_WRITE.value.toInt(), IoUring.OP_WRITE)
        assertEquals(io_uring_op.IORING_OP_SEND.value.toInt(), IoUring.OP_SEND)
        assertEquals(io_uring_op.IORING_OP_RECV.value.toInt(), IoUring.OP_RECV)
        assertEquals(io_uring_op.IORING_OP_SHUTDOWN.value.toInt(), IoUring.OP_SHUTDOWN)
        assertEquals(io_uring_op.IORING_OP_SEND_ZC.value.toInt(), IoUring.OP_SEND_ZC)
    }

    @Test
    fun `flags match the header`() {
        assertEquals(IOSQE_FIXED_FILE.toInt(), IoUring.IOSQE_FIXED_FILE)
        assertEquals(IOSQE_IO_LINK.toInt(), IoUring.IOSQE_IO_LINK)
        assertEquals(IOSQE_ASYNC.toInt(), IoUring.IOSQE_ASYNC)
        assertEquals(IOSQE_BUFFER_SELECT.toInt(), IoUring.IOSQE_BUFFER_SELECT)
        assertEquals(IOSQE_CQE_SKIP_SUCCESS.toInt(), IoUring.IOSQE_CQE_SKIP_SUCCESS)

        assertEquals(IORING_ACCEPT_MULTISHOT.toInt(), IoUring.ACCEPT_MULTISHOT)
        assertEquals(IORING_RECVSEND_POLL_FIRST.toInt(), IoUring.RECVSEND_POLL_FIRST)
        assertEquals(IORING_RECV_MULTISHOT.toInt(), IoUring.RECV_MULTISHOT)
        assertEquals(IORING_POLL_ADD_MULTI.toInt(), IoUring.POLL_ADD_MULTI)

        assertEquals(IORING_CQE_F_BUFFER.toInt(), IoUring.CQE_F_BUFFER)
        assertEquals(IORING_CQE_F_MORE.toInt(), IoUring.CQE_F_MORE)
        assertEquals(IORING_CQE_F_SOCK_NONEMPTY.toInt(), IoUring.CQE_F_SOCK_NONEMPTY)
        assertEquals(IORING_CQE_F_NOTIF.toInt(), IoUring.CQE_F_NOTIF)
        assertEquals(IORING_CQE_F_BUF_MORE.toInt(), IoUring.CQE_F_BUF_MORE)
        assertEquals(IORING_CQE_BUFFER_SHIFT, IoUring.CQE_BUFFER_SHIFT)

        assertEquals(IORING_ENTER_GETEVENTS.toInt(), IoUring.ENTER_GETEVENTS)
        assertEquals(IORING_ENTER_EXT_ARG.toInt(), IoUring.ENTER_EXT_ARG)
        assertEquals(IORING_ENTER_REGISTERED_RING.toInt(), IoUring.ENTER_REGISTERED_RING)

        assertEquals(IORING_SETUP_CQSIZE.toInt(), IoUring.SETUP_CQSIZE)
        assertEquals(IORING_SETUP_SUBMIT_ALL.toInt(), IoUring.SETUP_SUBMIT_ALL)
        assertEquals(IORING_SETUP_SINGLE_ISSUER.toInt(), IoUring.SETUP_SINGLE_ISSUER)
        assertEquals(IORING_SETUP_DEFER_TASKRUN.toInt(), IoUring.SETUP_DEFER_TASKRUN)
        assertEquals(IORING_SETUP_NO_SQARRAY.toInt(), IoUring.SETUP_NO_SQARRAY)

        assertEquals(IORING_FEAT_SINGLE_MMAP.toInt(), IoUring.FEAT_SINGLE_MMAP)
        assertEquals(IORING_SQ_CQ_OVERFLOW.toInt(), IoUring.SQ_CQ_OVERFLOW)

        assertEquals(IORING_REGISTER_RING_FDS.toInt(), IoUring.REGISTER_RING_FDS)
        assertEquals(IORING_UNREGISTER_RING_FDS.toInt(), IoUring.UNREGISTER_RING_FDS)
        assertEquals(IORING_REGISTER_PBUF_RING.toInt(), IoUring.REGISTER_PBUF_RING)
        assertEquals(IORING_UNREGISTER_PBUF_RING.toInt(), IoUring.UNREGISTER_PBUF_RING)
        assertEquals(IOU_PBUF_RING_INC.toInt(), IoUring.IOU_PBUF_RING_INC)

        assertEquals(IORING_OFF_SQ_RING.toLong(), IoUring.OFF_SQ_RING)
        assertEquals(IORING_OFF_SQES.toLong(), IoUring.OFF_SQES)
    }

    @Test
    fun `struct sizes match the header`() {
        assertEquals(sizeOf<io_uring_sqe>(), IoUring.SQE_SIZE.toLong())
        assertEquals(sizeOf<io_uring_cqe>(), IoUring.CQE_SIZE.toLong())
        assertEquals(sizeOf<io_uring_params>(), IoUring.PARAMS_SIZE)
        assertEquals(sizeOf<io_uring_buf>(), IoUring.BUF_SIZE.toLong())
        assertEquals(sizeOf<io_uring_buf_reg>(), IoUring.BUF_REG_SIZE)
        assertEquals(sizeOf<io_uring_rsrc_update>(), IoUring.RSRC_UPDATE_SIZE)
        assertEquals(sizeOf<io_uring_getevents_arg>(), IoUring.GETEVENTS_ARG_SIZE)
    }

    @Test
    fun `prep writes each field where the header puts it`() {
        withZeroed(IoUring.SQE_SIZE.toLong()) { sqe ->
            IoUring.prep(
                sqe, IoUring.OP_RECV, 0x1234_5678, 0x1111_2222_3333_4444L, 0x0ABC_DEF0, 0x5555_6666_7777_0000L,
                0x0102_0304_0506_0708L, flags = IoUring.IOSQE_BUFFER_SELECT, ioprio = IoUring.RECV_MULTISHOT,
                opFlags = 0x0F0E_0D0C, bufIndex = 0xBEEF, fileIndex = 0x7654_3210,
            )

            val s = interpretPointed<io_uring_sqe>(nativeNullPtr + sqe)
            assertEquals(IoUring.OP_RECV.toUByte(), s.opcode)
            assertEquals(IoUring.IOSQE_BUFFER_SELECT.toUByte(), s.flags)
            assertEquals(IoUring.RECV_MULTISHOT.toUShort(), s.ioprio)
            assertEquals(0x1234_5678, s.fd)
            assertEquals(0x5555_6666_7777_0000uL, s.off)
            assertEquals(0x1111_2222_3333_4444uL, s.addr)
            assertEquals(0x0ABC_DEF0u, s.len)
            assertEquals(0x0F0E_0D0Cu, s.msg_flags)
            assertEquals(0x0102_0304_0506_0708uL, s.user_data)
            assertEquals(0xBEEF.toUShort(), s.buf_group)
            assertEquals(0x7654_3210u, s.file_index)
            assertEquals(0uL, s.addr3)
        }
    }

    @Test
    fun `the offsets the ring reads match the header`() {
        withZeroed(IoUring.PARAMS_SIZE) { p ->
            val params = interpretPointed<io_uring_params>(nativeNullPtr + p)
            params.sq_entries = 0x11u
            params.cq_entries = 0x22u
            params.flags = 0x33u
            params.features = 0x44u
            params.sq_off.head = 0x51u
            params.sq_off.tail = 0x52u
            params.sq_off.ring_mask = 0x53u
            params.sq_off.flags = 0x55u
            params.sq_off.array = 0x57u
            params.cq_off.head = 0x61u
            params.cq_off.tail = 0x62u
            params.cq_off.ring_mask = 0x63u
            params.cq_off.cqes = 0x66u

            assertEquals(0x11, getInt(p + IoUring.P_SQ_ENTRIES))
            assertEquals(0x22, getInt(p + IoUring.P_CQ_ENTRIES))
            assertEquals(0x33, getInt(p + IoUring.P_FLAGS))
            assertEquals(0x44, getInt(p + IoUring.P_FEATURES))

            // Ring.map's reads, at its literal offsets into io_sqring_offsets and io_cqring_offsets.
            val sqOff = p + IoUring.P_SQ_OFF
            val cqOff = p + IoUring.P_CQ_OFF
            assertEquals(listOf(0x51, 0x52, 0x53, 0x55, 0x57), listOf(0, 4, 8, 16, 24).map { getInt(sqOff + it) })
            assertEquals(listOf(0x61, 0x62, 0x63, 0x66), listOf(0, 4, 8, 20).map { getInt(cqOff + it) })
        }

        withZeroed(IoUring.CQE_SIZE.toLong()) { c ->
            val cqe = interpretPointed<io_uring_cqe>(nativeNullPtr + c)
            cqe.user_data = 0x0102_0304_0506_0708uL
            cqe.res = -125
            cqe.flags = 0x0007_0003u

            // Reactor.loop's reads.
            assertEquals(0x0102_0304_0506_0708L, getLong(c))
            assertEquals(-125, getInt(c + 8))
            assertEquals(0x0007_0003, getInt(c + 12))
        }

        withZeroed(IoUring.BUF_SIZE.toLong()) { b ->
            val buf = interpretPointed<io_uring_buf>(nativeNullPtr + b)
            buf.addr = 0x1000_2000_3000_4000uL
            buf.len = 0x8000u
            buf.bid = 0x1234u
            buf.resv = 0x5678u   // bufs[0].resv is the ring's tail

            assertEquals(0x1000_2000_3000_4000L, getLong(b))
            assertEquals(0x8000, getInt(b + 8))
            assertEquals(0x1234.toShort(), getShort(b + 12))
            assertEquals(0x5678.toShort(), getShort(b + IoUring.BUF_RING_TAIL))
        }

        withZeroed(IoUring.BUF_REG_SIZE) { r ->
            val reg = interpretPointed<io_uring_buf_reg>(nativeNullPtr + r)
            reg.ring_addr = 0x7000_0000_0000_1000uL
            reg.ring_entries = 4096u
            reg.bgid = 7u
            reg.flags = IOU_PBUF_RING_INC.toUShort()

            // BufferRing's writes.
            assertEquals(0x7000_0000_0000_1000L, getLong(r))
            assertEquals(4096, getInt(r + 8))
            assertEquals(7.toShort(), getShort(r + 12))
            assertEquals(IoUring.IOU_PBUF_RING_INC.toShort(), getShort(r + 14))
        }

        withZeroed(IoUring.RSRC_UPDATE_SIZE) { u ->
            val update = interpretPointed<io_uring_rsrc_update>(nativeNullPtr + u)
            update.offset = 3u
            update.data = 42uL

            assertEquals(3, getInt(u))
            assertEquals(42L, getLong(u + 8))
        }

        withZeroed(IoUring.GETEVENTS_ARG_SIZE) { a ->
            val arg = interpretPointed<io_uring_getevents_arg>(nativeNullPtr + a)
            arg.ts = 0x0BAD_F00DuL

            assertEquals(0x0BAD_F00DL, getLong(a + 16))   // Ring.submitAndWait's timespec pointer
        }
    }

    private inline fun withZeroed(size: Long, block: (Long) -> Unit) {
        val p = Native.malloc(size)
        try {
            zeroMemory(p, size)
            block(p)
        } finally {
            Native.free(p)
        }
    }
}
