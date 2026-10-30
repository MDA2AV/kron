package kron.glyph11

/*
 * What a parser made of a request, field by field, as Ktron's Equivalence.kt has it, so that two outcomes can be
 * compared: the fast path's with the exact parser's, and either with the one Ktron's reference gave the corpus
 * (Corpus.kt, generated on the JVM by glyph11/golden-corpus.sh).
 */

/** A request head as one parser saw it: accepted with its fields, incomplete, or refused with a status. */
data class Outcome(
    val kind: String,
    val status: Int = 0,
    val message: String? = null,
    val headLength: Int = 0,
    val method: String = "",
    val path: String = "",
    val query: String = "",
    val version: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val params: List<Pair<String, String>> = emptyList(),
    val framing: Int = 0,
    val contentLength: Long = 0,
) {
    override fun toString(): String = when (kind) {
        ACCEPTED -> "accepted($headLength) $method ${escape(path)} ?${escape(query)} $version " +
            "headers=${headers.map { escape(it.first) + ":" + escape(it.second) }} " +
            "params=${params.map { escape(it.first) + "=" + escape(it.second) }} framing=$framing/$contentLength"
        REFUSED -> "refused $status \"$message\""
        else -> kind
    }

    companion object {
        const val ACCEPTED = "accepted"
        const val INCOMPLETE = "incomplete"
        const val REFUSED = "refused"
        val incomplete = Outcome(INCOMPLETE)
        fun refused(status: Int, message: String?) = Outcome(REFUSED, status, message)
        fun crashed(e: Throwable) = Outcome("crashed ${e::class.simpleName}: ${e.message}")

        fun accepted(
            headLength: Int, method: String, path: String, query: String, version: String,
            headers: List<Pair<String, String>>, params: List<Pair<String, String>>, framing: Int, contentLength: Long,
        ) = Outcome(ACCEPTED, 0, null, headLength, method, path, query, version, headers, params, framing, contentLength)
    }
}

/** A chunked body as one decoder saw it. */
data class ChunkOutcome(val kind: String, val wire: Int = 0, val payload: String = "", val status: Int = 0, val message: String? = null) {
    override fun toString(): String = when (kind) {
        Outcome.ACCEPTED -> "decoded($wire) ${escape(payload)}"
        Outcome.REFUSED -> "refused $status \"$message\""
        else -> kind
    }

    companion object {
        val incomplete = ChunkOutcome(Outcome.INCOMPLETE)
        fun decoded(wire: Int, payload: String) = ChunkOutcome(Outcome.ACCEPTED, wire, payload)
        fun refused(status: Int, message: String?) = ChunkOutcome(Outcome.REFUSED, status = status, message = message)
    }
}

/** A corpus request, one char per byte, and the outcome Ktron's reference gives it parsed whole under [limits]. */
class CorpusRequest(val source: String, val limits: ParserLimits, text: String, val expected: Outcome) {
    val bytes: ByteArray = latin1(text)
}

/** A corpus chunked body, one char per byte, and the outcome Ktron's reference gives it decoded whole. */
class CorpusBody(text: String, val expected: ChunkOutcome) {
    val bytes: ByteArray = latin1(text)
}

object Outcomes {
    /** What [head] says after a parse that returned [n]. */
    fun of(input: ByteArray, n: Int, head: RequestHead): Outcome {
        if (n == 0) return Outcome.incomplete
        return Outcome(
            Outcome.ACCEPTED,
            headLength = n,
            method = latin1(input, head.methodStart, head.methodLength),
            path = latin1(input, head.pathStart, head.pathLength),
            query = latin1(input, head.queryStart, head.queryLength),
            version = latin1(input, head.versionStart, head.versionLength),
            headers = (0 until head.headerCount).map {
                latin1(input, head.headerNameStart(it), head.headerNameLength(it)) to
                    latin1(input, head.headerValueStart(it), head.headerValueLength(it))
            },
            params = (0 until head.queryCount).map {
                latin1(input, head.queryKeyStart(it), head.queryKeyLength(it)) to
                    latin1(input, head.queryValueStart(it), head.queryValueLength(it))
            },
            framing = head.bodyFraming,
            contentLength = head.contentLength,
        )
    }

    inline fun outcome(input: ByteArray, head: RequestHead, parse: () -> Int): Outcome =
        try {
            of(input, parse(), head)
        } catch (e: HttpParseException) {
            Outcome.refused(e.statusCode, e.message)
        } catch (e: RuntimeException) {
            Outcome.crashed(e)
        }

    fun glyph11(input: ByteArray, offset: Int, length: Int, limits: ParserLimits, head: RequestHead = RequestHead()): Outcome =
        outcome(input, head) { Glyph11.parse(input, offset, length, head, limits) }

    /** The exact parser alone, as Glyph11.parse uses it for what its fast path leaves: a negative result is a refusal. */
    fun exact(input: ByteArray, offset: Int, length: Int, limits: ParserLimits, head: RequestHead = RequestHead()): Outcome =
        outcome(input, head) {
            val n = ExactParser.parse(input, offset, length, head, limits)
            if (n < 0) throw Refusals.exception(n)
            n
        }

    fun glyph11Chunked(input: ByteArray, offset: Int, length: Int, into: ByteBuilder = ByteBuilder()): ChunkOutcome =
        try {
            val n = Glyph11.decodeChunked(input, offset, length, into)
            if (n == 0) ChunkOutcome.incomplete else ChunkOutcome.decoded(n, latin1(into.array, 0, into.length))
        } catch (e: HttpParseException) {
            ChunkOutcome.refused(e.statusCode, e.message)
        } catch (e: RuntimeException) {
            ChunkOutcome("crashed ${e::class.simpleName}: ${e.message}")
        }
}

// ---- bytes as text: Latin-1, one char per byte, which Kotlin/Native has no Charset for ----

fun latin1(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

fun latin1(a: ByteArray, start: Int, length: Int): String = CharArray(length) { (a[start + it].toInt() and 0xFF).toChar() }.concatToString()

fun escape(s: String): String = escape(latin1(s))

fun escape(b: ByteArray, from: Int = 0, to: Int = b.size): String {
    val sb = StringBuilder()
    for (i in from until to) {
        val x = b[i].toInt() and 0xFF
        when {
            x == 0x5C -> sb.append("\\\\")
            x == 0x0D -> sb.append("\\r")
            x == 0x0A -> sb.append("\\n")
            x == 0x09 -> sb.append("\\t")
            x in 0x20..0x7E -> sb.append(x.toChar())
            else -> sb.append("\\x").append(x.toString(16).padStart(2, '0'))
        }
    }
    return sb.toString()
}

fun describe(limits: ParserLimits): String {
    val d = ParserLimits.Default
    val parts = mutableListOf<String>()
    if (limits.maxHeaderCount != d.maxHeaderCount) parts += "maxHeaderCount=${limits.maxHeaderCount}"
    if (limits.maxHeaderNameLength != d.maxHeaderNameLength) parts += "maxHeaderNameLength=${limits.maxHeaderNameLength}"
    if (limits.maxHeaderValueLength != d.maxHeaderValueLength) parts += "maxHeaderValueLength=${limits.maxHeaderValueLength}"
    if (limits.maxUrlLength != d.maxUrlLength) parts += "maxUrlLength=${limits.maxUrlLength}"
    if (limits.maxQueryParameterCount != d.maxQueryParameterCount) parts += "maxQueryParameterCount=${limits.maxQueryParameterCount}"
    if (limits.maxMethodLength != d.maxMethodLength) parts += "maxMethodLength=${limits.maxMethodLength}"
    if (limits.maxTotalHeaderBytes != d.maxTotalHeaderBytes) parts += "maxTotalHeaderBytes=${limits.maxTotalHeaderBytes}"
    return if (parts.isEmpty()) "-" else parts.joinToString(",")
}
