package kron

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ReadBuffer]'s scans and copies over a [SliceChain] of native segments, and the chain growing while it is wrapped. */
class ReadBufferTest {
    // Bytes from a small alphabet, the high-bit values and the newlines a parser hunts included, mixed with any
    // byte, so most values recur and some never occur.
    private fun content(size: Int, random: Random): ByteArray = ByteArray(size) {
        if (random.nextBoolean()) ALPHABET[random.nextInt(ALPHABET.size)] else random.nextInt(256).toByte()
    }

    private fun indexOfReference(bytes: ByteArray, value: Byte, from: Int): Long {
        for (i in maxOf(from, 0) until bytes.size) {
            if (bytes[i] == value) {
                return i.toLong()
            }
        }

        return -1
    }

    private fun indexOfReference(bytes: ByteArray, pattern: ByteArray, from: Int): Long {
        for (i in maxOf(from, 0)..bytes.size - pattern.size) {
            if (pattern.indices.all { bytes[i + it] == pattern[it] }) {
                return i.toLong()
            }
        }

        return -1
    }

    // One segment per array in [contents], each in its own allocation; [consumed] is how much of the first is
    // already consumed.
    private val layouts: List<Pair<List<Int>, Int>> = listOf(
        listOf(71) to 0,
        listOf(13, 58) to 0,
        listOf(9, 1, 47) to 0,
        listOf(24, 17, 33) to 5,
        listOf(8, 16, 7) to 7,
    )

    @Test
    fun `indexOf a byte from every start - across one to three segments and after a consumed head`() {
        val random = Random(9)

        for ((sizes, consumed) in layouts) {
            Segments(sizes.map { content(it, random) }, consumed).use { s ->
                val n = s.expected.size

                for (v in 0 until 256) {
                    val value = v.toByte()
                    s.fillStray(value)

                    for (from in -1..n + 1) {
                        assertEquals(
                            indexOfReference(s.expected, value, from),
                            s.buffer.indexOf(value, from.toLong()),
                            "segments $sizes, $consumed consumed: indexOf($v, from = $from)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `indexOf a pattern that straddles two and three segments - past a decoy cut off at a boundary`() {
        val pattern = "\r\n\r\n".encodeToByteArray()

        // A decoy, three bytes of the pattern and then not the fourth, then two matches that overlap.
        val logical = "ab\r\n\rX\r\n\r\n\r\ncd".encodeToByteArray()
        val n = logical.size

        // Cut every way into two and three segments, so each match and the decoy fall across every boundary.
        val cuts = ArrayList<List<Int>>()
        for (a in 1 until n) {
            cuts.add(listOf(a))
            for (b in a + 1 until n) {
                cuts.add(listOf(a, b))
            }
        }

        var straddledTwo = false
        var straddledThree = false
        for (cut in cuts) {
            val bounds = listOf(0) + cut + n
            val contents = bounds.zipWithNext { from, to -> logical.copyOfRange(from, to) }

            // The first match, at 6..9, across how many segments?
            val spans = bounds.zipWithNext().count { (from, to) -> from < 10 && to > 6 }
            straddledTwo = straddledTwo || spans == 2
            straddledThree = straddledThree || spans == 3

            Segments(contents, 0).use { s ->
                s.fillStray('\n'.code.toByte())
                for (from in 0..n) {
                    assertEquals(
                        indexOfReference(logical, pattern, from),
                        s.buffer.indexOf(pattern, from.toLong()),
                        "cut at $cut: indexOf(CRLFCRLF, from = $from)",
                    )
                }
            }
        }

        assertTrue(straddledTwo && straddledThree, "no cut put the match across two and three segments")

        // And behind a consumed head, which takes the decoy's first byte: positions count from what is left.
        Segments(listOf(logical.copyOfRange(0, 5), logical.copyOfRange(5, 8), logical.copyOfRange(8, n)), 3).use { s ->
            s.fillStray('\r'.code.toByte())
            assertEquals(3L, s.buffer.indexOf(pattern))   // 6 in the logical bytes, 3 consumed
            assertEquals(5L, s.buffer.indexOf(pattern, 4))
            assertEquals(-1L, s.buffer.indexOf(pattern, 6))
        }
    }

    @Test
    fun `matches at segment boundaries and past the end`() {
        Segments(listOf("abcd", "efgh", "ijkl").map { it.encodeToByteArray() }, 0).use { s ->
            fun matches(index: Long, text: String) = s.buffer.matches(index, text.encodeToByteArray())

            assertTrue(matches(0, "abcd"), "a whole first segment")
            assertTrue(matches(4, "efgh"), "a whole middle segment")
            assertTrue(matches(3, "defg"), "across one boundary")
            assertTrue(matches(2, "cdefghij"), "across two boundaries")
            assertTrue(matches(8, "ijkl"), "ending exactly at the end")
            assertTrue(matches(11, "l"), "the last byte")

            assertFalse(matches(4, "efgX"), "a mismatch in the last byte of a segment")
            assertFalse(matches(3, "dXfg"), "a mismatch right after a boundary")
            assertFalse(matches(9, "jklm"), "a match running past the end")
            assertFalse(matches(11, "lx"), "one byte past the end")
            assertFalse(matches(12, "a"), "at the end")
            assertFalse(matches(100, "a"), "far past the end")
            assertFalse(matches(-1, "ab"), "before the start")
        }

        Segments(listOf("xxab", "cd").map { it.encodeToByteArray() }, 2).use { s ->
            assertTrue(s.buffer.matches(0, "abcd".encodeToByteArray()), "from a consumed head")
            assertFalse(s.buffer.matches(0, "xxab".encodeToByteArray()), "the consumed bytes")
        }
    }

    @Test
    fun `get reads every byte - and refuses an index outside the buffer`() {
        val random = Random(11)

        for ((sizes, consumed) in layouts) {
            Segments(sizes.map { content(it, random) }, consumed).use { s ->
                val n = s.expected.size
                assertEquals(n.toLong(), s.buffer.length)

                for (i in 0 until n) {
                    assertEquals(s.expected[i], s.buffer[i.toLong()], "segments $sizes, $consumed consumed: byte $i")
                }

                assertFailsWith<IndexOutOfBoundsException> { s.buffer[-1L] }
                assertFailsWith<IndexOutOfBoundsException> { s.buffer[n.toLong()] }
            }
        }
    }

    @Test
    fun `copyTo and toByteArray copy any range across segments`() {
        val random = Random(13)

        for ((sizes, consumed) in layouts) {
            Segments(sizes.map { content(it, random) }, consumed).use { s ->
                val n = s.expected.size
                assertContentEquals(s.expected, s.buffer.toByteArray(), "segments $sizes, $consumed consumed: the whole buffer")

                for (from in 0..n) {
                    for (count in 0..n - from) {
                        val range = s.expected.copyOfRange(from, from + count)
                        val where = "segments $sizes, $consumed consumed: from $from, count $count"
                        assertContentEquals(range, s.buffer.toByteArray(from.toLong(), count), where)

                        // Exactly the range, at the offset asked for: the bytes around it keep their sentinel.
                        val destination = ByteArray(count + 6) { SENTINEL }
                        s.buffer.copyTo(destination, 3, from.toLong(), count)
                        assertContentEquals(range, destination.copyOfRange(3, 3 + count), where)
                        assertTrue(
                            (0 until 3).all { destination[it] == SENTINEL } &&
                                (3 + count until destination.size).all { destination[it] == SENTINEL },
                            "$where: copyTo wrote outside its range",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `copyTo and toByteArray refuse a range outside the buffer`() {
        Segments(listOf("abcd", "efgh", "ijkl").map { it.encodeToByteArray() }, 1).use { s ->
            val n = s.buffer.length   // 11
            val destination = ByteArray(32)

            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, 0, -1, 1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, 0, n, 1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, 0, n + 1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, 0, 0, n.toInt() + 1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, 0, 5, n.toInt() - 4) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, 0, 0, -1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(ByteArray(4), 0, 0, 5) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, -1, 0, 1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.copyTo(destination, 31, 0, 2) }

            assertFailsWith<IndexOutOfBoundsException> { s.buffer.toByteArray(-1, 1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.toByteArray(n, 1) }
            assertFailsWith<IndexOutOfBoundsException> { s.buffer.toByteArray(3, n.toInt()) }
            assertFails("toByteArray from past the end") { s.buffer.toByteArray(n + 1) }

            // The control: the same calls in range.
            assertEquals("bcdefghijkl", s.buffer.toByteArray().decodeToString())
            assertEquals("l", s.buffer.toByteArray(n - 1, 1).decodeToString())
            assertEquals(0, s.buffer.toByteArray(n, 0).size)
        }
    }

    @Test
    fun `a chain that grows while it is wrapped keeps its slices in order`() {
        val block = Native.malloc(BLOCK)
        try {
            for (i in 0 until BLOCK.toInt()) {
                putByte(block + i, (i * 7 + 1).toByte())
            }

            val chain = SliceChain()
            val live = ArrayDeque<Slice>()
            var next = 0

            fun add() {
                val slice = Slice(block + (next * 37 % 3000), 1 + next % 13, next)
                chain.add(slice.address, slice.length, slice.id)
                live.addLast(slice)
                next++
            }

            fun removeHead() {
                assertEquals(live.removeFirst().id, chain.removeHead(), "removeHead's buffer id")
            }

            // Two growths, each with the ring full and wrapped: 16 slots, then 32.
            for (capacity in listOf(16, 32)) {
                while (chain.count < capacity / 2) {
                    add()
                }
                repeat(capacity / 2 - 3) {
                    removeHead()
                }
                while (chain.count < capacity) {
                    add()
                }

                assertTrue(chain.head != 0, "capacity $capacity: the chain is not wrapped, so the growth below tests nothing")
                add()
                assertEquals(capacity + 1, chain.count)

                // In order through the chain's own slots, and through a ReadBuffer over it.
                for (i in 0 until chain.count) {
                    val slot = chain.slot(i)
                    assertEquals(live[i].address, chain.address(slot), "capacity $capacity: slice $i's address")
                    assertEquals(live[i].length, chain.length(slot), "capacity $capacity: slice $i's length")
                }

                val expected = live.fold(ByteArray(0)) { bytes, slice -> bytes + slice.bytes() }
                val buffer = ReadBuffer(chain)
                buffer.capture(expected.size.toLong())
                assertEquals(chain.count, buffer.segmentCount)
                assertContentEquals(expected, buffer.toByteArray(), "capacity $capacity: the bytes in order")
            }

            while (chain.count > 0) {
                removeHead()
            }
        } finally {
            Native.free(block)
        }
    }

    private class Slice(val address: Long, val length: Int, val id: Int) {
        fun bytes(): ByteArray = ByteArray(length).also {
            copyToArray(address, it, 0, length)
        }
    }

    private companion object {
        val ALPHABET = byteArrayOf(0x00, 0x01, 0x0A, 0x0D, 0x20, 0x7F, 0x80.toByte(), 0x81.toByte(), 0xFE.toByte(), 0xFF.toByte())
        const val SENTINEL: Byte = 0x5A
        const val BLOCK = 4096L
    }
}

/**
 * A [ReadBuffer] over one native segment per array, each in its own allocation followed by [PAD] stray bytes, the
 * first [consumed] bytes of the first segment already consumed. [fillStray] puts a value in every byte the buffer
 * does not cover, so a scan that strays outside its segments finds it there and answers wrong.
 */
private class Segments(contents: List<ByteArray>, private val consumed: Int) : AutoCloseable {
    private val addresses = LongArray(contents.size) { Native.malloc(contents[it].size + PAD) }
    private val lengths = IntArray(contents.size) { contents[it].size }

    val chain = SliceChain()
    val buffer = ReadBuffer(chain)
    val expected: ByteArray

    init {
        contents.forEachIndexed { i, bytes ->
            copyFromArray(bytes, 0, addresses[i], bytes.size)
            chain.add(addresses[i], bytes.size, i)
        }

        chain.headConsumed = consumed
        val all = contents.fold(ByteArray(0)) { bytes, segment -> bytes + segment }
        expected = all.copyOfRange(consumed, all.size)
        buffer.capture(expected.size.toLong())
    }

    fun fillStray(value: Byte) {
        for (i in addresses.indices) {
            for (k in 0 until PAD) {
                putByte(addresses[i] + lengths[i] + k, value)
            }
        }

        for (k in 0 until consumed) {
            putByte(addresses[0] + k, value)
        }
    }

    override fun close() {
        addresses.forEach {
            Native.free(it)
        }
    }

    private companion object {
        const val PAD = 16L
    }
}
