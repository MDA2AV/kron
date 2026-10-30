package kron.glyph11

/**
 * Every reason the offset API refuses a request or a chunked body for, each an [HttpParseException] made once: the
 * exceptions are stackless and immutable (no cause, no suppression), so one instance per reason serves every thread
 * and a refusal allocates nothing. The parsers return a reason as a negative code; [Glyph11] throws its exception.
 */
internal object Refusals {
    private val exceptions = ArrayList<HttpParseException>()

    private fun reason(message: String, status: Int = 400): Int {
        exceptions += HttpParseException(message, status)
        return -exceptions.size
    }

    /** The exception for the negative [code]. */
    fun exception(code: Int): HttpParseException = exceptions[-code - 1]

    // ---- the request head: UltraHardenedParser.tryExtractFullHeader's messages and statuses ----
    val TOTAL_TOO_LARGE = reason("Total header size exceeds limit.", 431)
    val REQUEST_LINE = reason("Invalid HTTP/1.1 request line.")
    val BARE_LF = reason("Bare LF detected; only CRLF line endings are allowed.")
    val MISSING_METHOD = reason("Invalid request line: missing method.")
    val MISSING_VERSION = reason("Invalid request line: missing version.")
    val MULTIPLE_SPACES = reason("Multiple spaces in request line.")
    val METHOD_LENGTH = reason("Method length exceeds limit.")
    val METHOD_TOKEN = reason("Method contains invalid token characters.")
    val CONNECT = reason("CONNECT method is not allowed on origin servers.")
    val URL_TOO_LONG = reason("URL length exceeds limit.", 431)
    val TARGET_CHARACTERS = reason("Request-target contains invalid characters.")
    val VERSION = reason("Invalid HTTP version.", 505)
    val TOO_MANY_PARAMETERS = reason("Query parameter count exceeds limit.", 431)
    val FRAGMENT = reason("Fragment indicator (#) in request-target.")
    val BACKSLASH = reason("Backslash in request path.")
    val DOUBLE_ENCODING = reason("Double-encoded percent (%25) in path.")
    val ENCODED_NULL = reason("Encoded null byte (%00) in path.")
    val DOT_SEGMENT = reason("Dot segment in request path.")
    val ASTERISK_FORM = reason("Asterisk-form request-target is only valid for OPTIONS.")
    val HEADERS = reason("Invalid headers.")
    val OBS_FOLD = reason("Obsolete line folding (obs-fold) is not allowed.")
    val MISSING_COLON = reason("Malformed header line: missing colon.")
    val EMPTY_NAME = reason("Header name is empty.")
    val SPACE_BEFORE_COLON = reason("Whitespace between header name and colon is not allowed.")
    val NAME_TOO_LONG = reason("Header name length exceeds limit.", 431)
    val NAME_TOKEN = reason("Header name contains invalid token characters.")
    val VALUE_TOO_LONG = reason("Header value length exceeds limit.", 431)
    val VALUE_CHARACTERS = reason("Header value contains invalid characters.")
    val TOO_MANY_HEADERS = reason("Header count exceeds limit.", 431)
    val CONTENT_LENGTH_FORMAT = reason("Invalid Content-Length format.")
    val CONTENT_LENGTH_COMMAS = reason("Conflicting comma-separated Content-Length values.")
    val CONTENT_LENGTHS = reason("Conflicting Content-Length headers.")
    val TRANSFER_ENCODING = reason("Invalid Transfer-Encoding value; only 'chunked' is accepted.")
    val HOST_FORMAT = reason("Invalid Host header: contains '@' or '/'.")
    val TE_AND_CL = reason("Both Transfer-Encoding and Content-Length are present.")
    val HOST_COUNT = reason("Request must have exactly one Host header.")

    // ---- the chunked body: ChunkedBodyStream's messages ----
    val CHUNK_LEADING_WHITESPACE = reason("Leading whitespace in chunk size.")
    val CHUNK_NEGATIVE = reason("Negative chunk size.")
    val CHUNK_HEX_PREFIX = reason("Hex prefix in chunk size.")
    val CHUNK_OVERFLOW = reason("Chunk size overflow.")
    val CHUNK_SIZE_CHARACTER = reason("Invalid character in chunk size.")
    val CHUNK_UNDERSCORE = reason("Underscore in chunk size.")
    val CHUNK_SIZE_BARE_LF = reason("Bare LF after chunk size.")
    val CHUNK_SIZE_CRLF = reason("Missing CRLF after chunk size.")
    val CHUNK_EXTENSION_TOO_LARGE = reason("Chunk extension too large.")
    val CHUNK_EXTENSION_NUL = reason("NUL byte in chunk extension.")
    val CHUNK_EXTENSION_BARE_LF = reason("Bare LF in chunk extension.")
    val CHUNK_BARE_SEMICOLON = reason("Bare semicolon with no chunk extension name.")
    val CHUNK_DATA_BARE_LF = reason("Bare LF after chunk data.")
    val CHUNK_DATA_CRLF = reason("Missing CRLF after chunk data.")
    val TRAILER_BARE_LF = reason("Bare LF in chunked trailer.")
}
