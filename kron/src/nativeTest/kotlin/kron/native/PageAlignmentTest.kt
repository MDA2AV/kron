package kron

import platform.posix._SC_PAGESIZE
import platform.posix.sysconf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Buffer-ring memory on the kernel's page, whatever its size: 4 KiB on x86-64, and 4, 16 or 64 KiB on arm64. A ring
 * aligned to a fixed 4096, as ioxide's are, is refused by a 16 or 64 KiB-page kernel.
 */
class PageAlignmentTest {
    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    private fun incrementalUnsupported(): Boolean {
        val parts = kernelRelease().split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (major > 6 || (major == 6 && minor >= 12)) {
            return false
        }

        println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
        return true
    }

    @Test
    fun `pageSize is the kernel's page as sysconf reports it`() {
        assertEquals(sysconf(_SC_PAGESIZE), pageSize)
        assertTrue(pageSize >= 4096 && pageSize and (pageSize - 1) == 0L, "a page of $pageSize bytes")
    }

    @Test
    fun `wholePages rounds up to the next page and leaves exact multiples alone`() {
        assertEquals(0L, wholePages(0))
        assertEquals(pageSize, wholePages(1))
        assertEquals(pageSize, wholePages(pageSize - 1))
        assertEquals(pageSize, wholePages(pageSize))
        assertEquals(2 * pageSize, wholePages(pageSize + 1))
        assertEquals(3 * pageSize, wholePages(2 * pageSize + 1))
        assertEquals(3 * pageSize, wholePages(3 * pageSize))
        assertEquals(4097 * pageSize, wholePages(4096 * pageSize + 1))
    }

    // The ring the kernel reads offers from is private to BufferRing; registering it is the kernel's own check of
    // where it starts, refused with EINVAL off a page boundary (see the control below).
    @Test
    fun `a shared buffer ring's memory starts on a page boundary`() {
        Ring.create(8).use { ring ->
            // Eight at once, each its own allocation: one could land on a page boundary by chance.
            val rings = ArrayList<BufferRing>()
            try {
                for (i in 0 until 8) {
                    val buffers = BufferRing(ring, entries = 64, bufferSize = 1000 + i)
                    rings.add(buffers)

                    assertEquals(0L, buffers.slab % pageSize, "ring $i's buffers start at 0x${buffers.slab.toString(16)}")
                    assertEquals(buffers.slab, buffers.bufferAddr(0))
                    assertEquals(0, buffers.register(i + 1), "the kernel refused ring $i")
                }
            } finally {
                rings.forEach {
                    it.close()
                }
            }
        }
    }

    @Test
    fun `an incremental connection ring's memory starts on a page boundary`() {
        if (incrementalUnsupported()) {
            return
        }

        Ring.create(8).use { ring ->
            val rings = ArrayList<ConnectionRing>()
            try {
                for (i in 0 until 8) {
                    val connectionRing = ConnectionRing(ring, entries = 16, bufferSize = 4096 + 100 * i)
                    rings.add(connectionRing)

                    // A fresh ring's first slice lands where its memory starts: buffer 0, offset 0.
                    val start = connectionRing.received(0, 0, kernelMore = true)
                    assertEquals(0L, start % pageSize, "ring $i's buffers start at 0x${start.toString(16)}")
                    assertEquals(0, connectionRing.register(i + 2), "the kernel refused ring $i")
                }
            } finally {
                rings.forEach {
                    it.close()
                }
            }
        }
    }

    @Test
    fun `control - the kernel refuses a buffer ring that does not start on a page boundary`() {
        Ring.create(8).use { ring ->
            val memory = Native.alignedAlloc(pageSize, 2 * pageSize)
            val reg = Native.malloc(IoUring.BUF_REG_SIZE)
            try {
                // A four-entry ring at [address] under group [bgid], registered as BufferRing.register does.
                fun register(address: Long, bgid: Int): Int {
                    zeroMemory(memory, 2 * pageSize)
                    zeroMemory(reg, IoUring.BUF_REG_SIZE)
                    putLong(reg, address)
                    putInt(reg + 8, 4)
                    putShort(reg + 12, bgid.toShort())
                    return Native.ioUringRegister(ring.fd, IoUring.REGISTER_PBUF_RING, reg, 1)
                }

                assertEquals(0, register(memory, 1), "the ring on a page boundary")
                zeroMemory(reg, IoUring.BUF_REG_SIZE)
                putShort(reg + 12, 1)
                assertEquals(0, Native.ioUringRegister(ring.fd, IoUring.UNREGISTER_PBUF_RING, reg, 1))

                assertEquals(-Native.EINVAL, register(memory + 64, 2), "the same ring 64 bytes off a page boundary")

                // ioxide's alignment: on a kernel whose page is larger than 4 KiB, 4096 is not a page boundary.
                if (pageSize > 4096) {
                    assertEquals(-Native.EINVAL, register(memory + 4096, 3), "the same ring 4096 bytes into a page")
                }
            } finally {
                Native.free(reg)
                Native.free(memory)
            }
        }
    }
}
