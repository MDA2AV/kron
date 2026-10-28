package kron

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.posix.memcmp
import platform.posix.memcpy
import platform.posix.memset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** The native memory helpers: copies at every misalignment and length, zeroing, and loads and stores at any address. */
class MemoryTest {
    // Every length up to a few words, both sides of the threshold where a copy moves to C, and one large one.
    private val lengths = (0..64).toList() + listOf(255, 256, 257, LARGE)

    private fun pattern(size: Int, seed: Int) = ByteArray(size) { (it * 131 + seed).toByte() }

    // A 64-aligned block with GUARD bytes of room on each side of any range at a misalignment up to 15.
    private inline fun withBlock(size: Int, block: (Long) -> Unit) {
        val address = Native.alignedAlloc(64, size + 2L * GUARD + 64)
        try {
            block(address + GUARD)
        } finally {
            Native.free(address)
        }
    }

    // Setup and checks go through libc and the runtime, never through the helpers under test. The cases number in
    // the thousands and a debug build is slow, so a check's message is only built when it fails.

    private fun fill(address: Long, length: Int, value: Byte) {
        memset(address.toCPointer<ByteVar>(), value.toInt(), length.convert())
    }

    private fun bytesAt(address: Long, length: Int): ByteArray = address.toCPointer<ByteVar>()!!.readBytes(length)

    private fun put(bytes: ByteArray, address: Long) {
        bytes.usePinned {
            memcpy(address.toCPointer<ByteVar>(), it.addressOf(0), bytes.size.convert())
        }
    }

    // The GUARD bytes on each side of [length] bytes at [address] are still CANARY.
    private fun assertGuards(address: Long, length: Int, what: () -> String) {
        val before = bytesAt(address - GUARD, GUARD)
        val after = bytesAt(address + length, GUARD)
        for (i in 0 until GUARD) {
            if (before[i] != CANARY) {
                fail("${what()}: wrote before its range, at -${GUARD - i}")
            }
            if (after[i] != CANARY) {
                fail("${what()}: wrote past its range, at +$i after its end")
            }
        }
    }

    // [length] bytes at [address] are [expected] from [offset], and nothing around them was written.
    private fun assertNative(expected: ByteArray, offset: Int, address: Long, length: Int, what: () -> String) {
        assertGuards(address, length, what)
        if (length > 0) {
            assertSame(expected, offset, bytesAt(address, length), 0, length, what)
        }
    }

    private fun assertSame(expected: ByteArray, offset: Int, actual: ByteArray, actualOffset: Int, length: Int, what: () -> String) {
        val same = expected.usePinned { e ->
            actual.usePinned { a ->
                memcmp(e.addressOf(offset), a.addressOf(actualOffset), length.convert()) == 0
            }
        }

        if (!same) {
            val i = (0 until length).first { expected[offset + it] != actual[actualOffset + it] }
            assertEquals(expected[offset + i], actual[actualOffset + i], "${what()}: byte $i")
        }
    }

    @Test
    fun `copies between arrays and native memory round-trip at every misalignment and length`() {
        val source = pattern(LARGE + 32, 7)

        withBlock(LARGE + 16) { base ->
            for (misalignment in 0..15) {
                for (length in lengths) {
                    val at = base + misalignment
                    val sourceOffset = (misalignment * 5) % 16   // the array side misaligned too

                    fill(at - GUARD, length + 2 * GUARD, CANARY)
                    copyFromArray(source, sourceOffset, at, length)
                    assertNative(source, sourceOffset, at, length) { "copyFromArray at +$misalignment, length $length" }

                    val back = ByteArray(length + 2 * GUARD) { CANARY }
                    copyToArray(at, back, GUARD, length)
                    for (i in 0 until GUARD) {
                        if (back[i] != CANARY || back[GUARD + length + i] != CANARY) {
                            fail("copyToArray from +$misalignment, length $length: wrote outside its range")
                        }
                    }
                    if (length > 0) {
                        assertSame(source, sourceOffset, back, GUARD, length) { "copyToArray from +$misalignment, length $length" }
                    }
                }
            }
        }
    }

    @Test
    fun `copyMemory round-trips between native ranges at every misalignment and length`() {
        val source = pattern(LARGE + 32, 3)

        withBlock(LARGE + 16) { from ->
            withBlock(LARGE + 16) { to ->
                put(source.copyOf(LARGE + 16), from)

                // Every misalignment on both sides at the same misalignment, and the source off the destination by
                // every distance: t against 2t.
                for (toMisalignment in 0..15) {
                    for (fromMisalignment in setOf(toMisalignment, 2 * toMisalignment % 16)) {
                        for (length in lengths) {
                            val at = to + toMisalignment
                            fill(at - GUARD, length + 2 * GUARD, CANARY)
                            copyMemory(from + fromMisalignment, at, length.toLong())
                            assertNative(source, fromMisalignment, at, length) {
                                "from +$fromMisalignment to +$toMisalignment, length $length"
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `copyMemory moves overlapping ranges either way`() {
        withBlock(256) { base ->
            for (length in 0..64) {
                for (shift in -15..15) {
                    // The bytes as memmove leaves them, built on an array.
                    val before = pattern(160, length + shift)
                    val expected = before.copyOf()
                    before.copyInto(expected, 48 + shift, 48, 48 + length)

                    put(before, base)
                    copyMemory(base + 48, base + 48 + shift, length.toLong())

                    assertSame(expected, 0, bytesAt(base, before.size), 0, before.size) { "length $length, shift $shift" }
                }
            }
        }
    }

    @Test
    fun `zeroMemory clears exactly its range`() {
        val zeros = ByteArray(LARGE)

        withBlock(LARGE + 16) { base ->
            for (misalignment in 0..15) {
                for (length in lengths) {
                    val at = base + misalignment
                    fill(at - GUARD, length + 2 * GUARD, CANARY)

                    zeroMemory(at, length.toLong())
                    assertNative(zeros, 0, at, length) { "at +$misalignment, length $length" }
                }
            }
        }
    }

    @Test
    fun `shorts ints and longs round-trip at every misalignment - in little-endian order`() {
        val shorts = listOf<Short>(0, 1, -1, 0x1234, Short.MIN_VALUE, Short.MAX_VALUE)
        val ints = listOf(0, 1, -1, 0x1234_5678, Int.MIN_VALUE, Int.MAX_VALUE)
        val longs = listOf(0L, 1L, -1L, 0x0102_0304_0506_0708L, Long.MIN_VALUE, Long.MAX_VALUE)

        withBlock(16) { base ->
            for (misalignment in 0..15) {
                val at = base + misalignment

                for (v in shorts) {
                    fill(at - GUARD, 2 + 2 * GUARD, CANARY)
                    putShort(at, v)
                    assertEquals(v, getShort(at), "short $v at +$misalignment")
                    assertGuards(at, 2) { "short $v at +$misalignment" }
                }

                for (v in ints) {
                    fill(at - GUARD, 4 + 2 * GUARD, CANARY)
                    putInt(at, v)
                    assertEquals(v, getInt(at), "int $v at +$misalignment")
                    assertGuards(at, 4) { "int $v at +$misalignment" }
                }

                for (v in longs) {
                    fill(at - GUARD, 8 + 2 * GUARD, CANARY)
                    putLong(at, v)
                    assertEquals(v, getLong(at), "long $v at +$misalignment")
                    assertGuards(at, 8) { "long $v at +$misalignment" }
                }

                // The lowest byte first, as ReadBuffer's eight-bytes-a-step scan takes for granted.
                putLong(at, 0x0807_0605_0403_0201L)
                assertEquals((1..8).map { it.toByte() }, bytesAt(at, 8).toList(), "a long's bytes at +$misalignment")
            }
        }
    }

    private companion object {
        const val GUARD = 16
        const val CANARY: Byte = 0xA5.toByte()
        const val LARGE = 100_003
    }
}
