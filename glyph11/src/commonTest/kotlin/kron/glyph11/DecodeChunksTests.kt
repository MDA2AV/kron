package kron.glyph11

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.fail

/**
 * Glyph11.decodeChunks fed a body in pieces by a reader that drops the bytes each call consumed: it appends what
 * Glyph11.decodeChunked appends decoding the whole body, and ends as it does - complete after as many bytes,
 * refused for the same reason, or still waiting. Every corpus body, split in two at every place and in random pieces.
 */
class DecodeChunksTests {
    @Test
    fun `decodeChunks consumes the complete chunks and appends their payloads to what into holds`() {
        val into = ByteBuilder()
        into.append(latin1("x"), 0, 1)
        // two chunks have arrived, the third has not
        val first = latin1("5\r\nhello\r\n3\r\nabc\r\n2\r\nd")
        assertEquals(-(10 + 8) - 1, Glyph11.decodeChunks(first, 0, first.size, into))
        assertEquals("xhelloabc", latin1(into.array, 0, into.length))
        // the next call starts at the third chunk, wherever it now sits; nothing complete is -1
        val again = latin1("zz2\r\nd")
        assertEquals(-1, Glyph11.decodeChunks(again, 2, again.size - 2, into))
        // the rest of the body, the next request after it
        val last = latin1("zz2\r\nde\r\n0\r\nX: y\r\n\r\nGET")
        assertEquals(last.size - 2 - 3, Glyph11.decodeChunks(last, 2, last.size - 2, into))
        assertEquals("xhelloabcde", latin1(into.array, 0, into.length))
    }

    @Test
    fun `decodeChunks refuses as decodeChunked does - in the call the bad bytes arrive in`() {
        val bad = latin1("5\r\nhello\r\n5\r\nHello\nX")
        val expected = assertFailsWith<HttpParseException> { Glyph11.decodeChunked(bad, 0, bad.size, ByteBuilder()) }
        val into = ByteBuilder()
        assertEquals(-10 - 1, Glyph11.decodeChunks(bad, 0, bad.size - 2, into))
        assertSame(expected, assertFailsWith<HttpParseException> { Glyph11.decodeChunks(bad, 10, bad.size - 10, into) })
        assertEquals("Bare LF after chunk data.", expected.message)
    }

    @Test
    fun `every corpus body split in two at every place decodes as decodeChunked decodes it whole`() {
        val rnd = Random(21)
        val failures = mutableListOf<String>()
        for (body in Corpus.chunked.map { it.bytes }) {
            val expected = decodedWhole(body)
            for (k in 0..body.size) compare(body, intArrayOf(k, body.size), expected, rnd, failures)
        }
        if (failures.isNotEmpty()) fail("${failures.size} differ:\n" + failures.take(10).joinToString("\n"))
    }

    @Test
    fun `every corpus body in random pieces and byte by byte decodes as decodeChunked decodes it whole`() {
        val rnd = Random(22)
        val failures = mutableListOf<String>()
        for (body in Corpus.chunked.map { it.bytes }) {
            val expected = decodedWhole(body)
            compare(body, IntArray(body.size + 1) { it }, expected, rnd, failures)
            repeat(16) { compare(body, randomCuts(body.size, rnd), expected, rnd, failures) }
        }
        if (failures.isNotEmpty()) fail("${failures.size} differ:\n" + failures.take(10).joinToString("\n"))
    }

    private fun compare(body: ByteArray, cuts: IntArray, expected: Pair<ChunkOutcome, String>, rnd: Random, failures: MutableList<String>) {
        val actual = decodedInPieces(body, cuts, rnd)
        if (actual != expected) failures += "[${escape(body)}] in pieces ending at ${cuts.toList()}: expected $expected, got $actual"
    }
}

/** How Glyph11.decodeChunked ends on all of [body], and the payload it appended on the way. */
internal fun decodedWhole(body: ByteArray): Pair<ChunkOutcome, String> {
    val into = ByteBuilder()
    return Outcomes.glyph11Chunked(body, 0, body.size, into) to latin1(into.array, 0, into.length)
}

/**
 * How Glyph11.decodeChunks ends on [body] arriving in pieces that end at [cuts], the last at its end: each call is
 * handed what has arrived and was not consumed yet, at a new offset among other bytes - those after it would end
 * the body - and what it consumed is dropped. With the payload it appended on the way, decodedWhole's twin.
 */
internal fun decodedInPieces(body: ByteArray, cuts: IntArray, rnd: Random): Pair<ChunkOutcome, String> {
    val into = ByteBuilder()
    var pending = ByteArray(0)
    var arrived = 0
    var consumed = 0
    val outcome = try {
        var ended: ChunkOutcome = ChunkOutcome.incomplete
        for (cut in cuts) {
            pending += body.copyOfRange(arrived, cut)
            arrived = cut
            val before = rnd.nextInt(4)
            val input = ByteArray(before) { 'z'.code.toByte() } + pending + END_OF_BODY
            val n = Glyph11.decodeChunks(input, before, pending.size, into)
            if (n >= 0) {
                ended = ChunkOutcome.decoded(consumed + n, latin1(into.array, 0, into.length))
                break
            }
            pending = pending.copyOfRange(-n - 1, pending.size)
            consumed += -n - 1
        }
        ended
    } catch (e: HttpParseException) {
        ChunkOutcome.refused(e.statusCode, e.message)
    } catch (e: RuntimeException) {
        ChunkOutcome("crashed ${e::class.simpleName}: ${e.message}")
    }
    return outcome to latin1(into.array, 0, into.length)
}

private val END_OF_BODY = latin1("\r\n0\r\n\r\n")

/** Where the pieces of a [size]-byte body end, at random: a few long pieces, or many short ones. */
internal fun randomCuts(size: Int, rnd: Random): IntArray {
    val every = 1 + rnd.nextInt(16)
    return (0..size).filter { it == size || (it > 0 && rnd.nextInt(every) == 0) }.toIntArray()
}
