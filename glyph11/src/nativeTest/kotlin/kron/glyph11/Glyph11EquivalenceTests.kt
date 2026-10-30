package kron.glyph11

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every request the corpus holds (Corpus.kt: Ktron's corpus, with the outcome Ktron's reference parser gives each
 * whole) parses to that outcome through Glyph11.parse and through ExactParser alike: accepted or not, the status and
 * message it is refused with, and every field's bytes - alone, and at an offset inside bytes that would complete or
 * end it. Cut at every length, in place and inside those bytes, the fast path and the exact parser agree. The
 * chunked bodies decode to theirs through decodeChunked.
 */
class Glyph11EquivalenceTests {
    private val head = RequestHead() // one head for every parse: nothing may leak from one into the next
    private val into = ByteBuilder()

    private class Mismatches {
        val found = mutableListOf<String>()
        var checked = 0
        fun check(expected: Any, actual: Any, what: () -> String) {
            checked++
            if (expected != actual && found.size < 20) found += "${what()}\n    expected $expected\n    actual   $actual"
        }
        fun assertNone() {
            if (found.isNotEmpty()) fail("${found.size}+ of $checked differ:\n" + found.joinToString("\n"))
        }
    }

    // what surrounds the request in its array: a parser that reads outside its range finds a head's end, or its
    // continuation, there
    private val surroundings = listOf(
        "" to "",
        "\r\n\r\n" to "\r\n\r\n",
        "GET / HTTP/1.1\r\n" to " HTTP/1.1\r\nHost: x\r\n\r\n",
        "\u0000\u0000\u0000" to "\u0000".repeat(16),
        "x" to "\r\nHost: y\r\n\r\n\r\n",
    ).map { (before, after) -> latin1(before) to latin1(after) }

    /** The first [length] of [bytes] between [before] and [after], in an array of their own. */
    private fun inside(before: ByteArray, bytes: ByteArray, length: Int, after: ByteArray): ByteArray {
        val input = ByteArray(before.size + length + after.size)
        before.copyInto(input)
        bytes.copyInto(input, before.size, 0, length)
        after.copyInto(input, before.size + length)
        return input
    }

    /** Glyph11.parse against ExactParser, and both against the [golden] outcome when there is one. */
    private fun compareParse(
        m: Mismatches, input: ByteArray, offset: Int, length: Int, limits: ParserLimits, label: String, golden: Outcome? = null,
    ) {
        val what = { "$label [${escape(input, offset, offset + length)}] at $offset limits ${describe(limits)}" }
        val exact = Outcomes.exact(input, offset, length, limits, head)
        if (golden != null) m.check(golden, exact) { "exact ${what()}" }
        m.check(golden ?: exact, Outcomes.glyph11(input, offset, length, limits, head)) { "parse ${what()}" }
    }

    @Test
    fun `every corpus request parses to its golden outcome - alone and inside other bytes`() {
        val m = Mismatches()
        for (v in Corpus.requests) {
            for ((before, after) in surroundings) {
                compareParse(m, inside(before, v.bytes, v.bytes.size, after), before.size, v.bytes.size, v.limits, v.source, v.expected)
            }
        }
        m.assertNone()
        assertTrue(Corpus.requests.size > 400)
    }

    @Test
    fun `every prefix of every corpus request parses the same through parse and exact - in place and inside other bytes`() {
        val m = Mismatches()
        for (v in Corpus.requests) {
            for (length in 0..v.bytes.size) {
                val label = "${v.source} cut at $length"
                // the bytes past the cut are the rest of the request, as in a buffer that has not all been filled yet
                compareParse(m, v.bytes, 0, length, v.limits, label)
                // or other bytes, right after the cut
                for ((before, after) in surroundings) {
                    compareParse(m, inside(before, v.bytes, length, after), before.size, length, v.limits, label)
                }
            }
        }
        m.assertNone()
    }

    // the outcomes cannot tell: a fast path that bailed on everything would leave every head to the exact parser
    @Test
    fun `the fast path accepts every head the reference accepts - without the exact parser`() {
        val m = Mismatches()
        for (v in Corpus.requests.filter { it.expected.kind == Outcome.ACCEPTED }) {
            val n = FastParser.parse(v.bytes, 0, v.bytes.size, head, v.limits)
            val fast = if (n > 0) Outcomes.of(v.bytes, n, head) else if (n == FastParser.BAIL) "bailed" else "returned $n"
            m.check(v.expected, fast) { "fast ${v.source} [${escape(v.bytes)}]" }
        }
        m.assertNone()
    }

    @Test
    fun `every corpus request parses the same under tight limits`() {
        val m = Mismatches()
        val tight = listOf(
            ParserLimits.Default.copy(maxHeaderCount = 1, maxQueryParameterCount = 1),
            ParserLimits.Default.copy(maxHeaderNameLength = 4, maxHeaderValueLength = 9, maxMethodLength = 3),
            ParserLimits.Default.copy(maxUrlLength = 8, maxTotalHeaderBytes = 64),
            ParserLimits.Default.copy(maxTotalHeaderBytes = 40),
            ParserLimits(0, 0, 0, 0, 0, 0, 0),
        )
        for (v in Corpus.requests) for (limits in tight) compareParse(m, v.bytes, 0, v.bytes.size, limits, v.source)
        m.assertNone()
    }

    @Test
    fun `pipelined corpus requests parse to their golden outcomes one after another`() {
        val m = Mismatches()
        val accepted = Corpus.requests.filter { it.limits == ParserLimits.Default && it.expected.kind == Outcome.ACCEPTED }
        // each accepted head, cut to its length, followed by the next: parse from where the previous one ended
        val batch = accepted.map { it.bytes.copyOf(it.expected.headLength) }.reduce { a, b -> a + b }
        var at = 0
        for (v in accepted) {
            compareParse(m, batch, at, batch.size - at, ParserLimits.Default, "pipelined ${v.source}", v.expected)
            at += v.expected.headLength
        }
        m.assertNone()
        assertTrue(accepted.size > 200 && at == batch.size, "${accepted.size} heads, ended at $at of ${batch.size}")
    }

    @Test
    fun `every corpus chunked body decodes to its golden outcome - inside other bytes and cut at every length`() {
        val m = Mismatches()
        for (body in Corpus.chunked) {
            for ((before, after) in surroundings) {
                val input = inside(before, body.bytes, body.bytes.size, after)
                m.check(body.expected, Outcomes.glyph11Chunked(input, before.size, body.bytes.size, into)) {
                    "decode [${escape(body.bytes)}] at ${before.size}"
                }
            }
            // a prefix waits until the bytes that decide the body are in, then ends as the whole body does
            var decided = false
            for (length in 0..body.bytes.size) {
                val prefix = Outcomes.glyph11Chunked(body.bytes, 0, length, into)
                decided = decided || prefix.kind != Outcome.INCOMPLETE || length == body.bytes.size
                m.check(if (decided) body.expected else ChunkOutcome.incomplete, prefix) { "decode [${escape(body.bytes, 0, length)}]" }
            }
        }
        m.assertNone()
        assertTrue(Corpus.chunked.size > 100)
    }

    @Test
    fun `the golden outcomes cover acceptance and waiting and each status`() {
        val outcomes = Corpus.requests.map { it.expected }
        val kinds = outcomes.groupingBy { if (it.kind == Outcome.REFUSED) "refused ${it.status}" else it.kind }.eachCount()
        for (k in listOf(Outcome.ACCEPTED, Outcome.INCOMPLETE, "refused 400", "refused 431", "refused 505")) {
            assertTrue((kinds[k] ?: 0) > 0, "no $k in the corpus: $kinds")
        }
        // every refusal message the parser has, but the unreachable ones
        val messages = outcomes.mapNotNull { it.message }.toSet()
        assertTrue(messages.size >= 33, "only ${messages.size} messages: $messages")
        val chunked = Corpus.chunked.map { it.expected }.groupingBy { it.kind }.eachCount()
        assertTrue(chunked.size == 3, "the chunked bodies end only as $chunked")
    }
}
