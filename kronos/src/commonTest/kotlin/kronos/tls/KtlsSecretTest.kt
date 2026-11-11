package kronos.tls

import kronos.Native
import kronos.putByte
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * What kTLS is programmed from: the traffic secret, decoded where BoringSSL wrote its key-log line, since a String of
 * the line would keep the secret, as hex, where nothing can zero it; and HMAC-SHA256 for the key schedule.
 */
class KtlsSecretTest {
    @Test
    fun `a traffic secret line decodes to its secret in either case and with or without a newline`() {
        for (hex in listOf(SECRET.toHexString(), SECRET.toHexString(HexFormat.UpperCase))) {
            for (end in listOf("", "\n")) {
                withKeylogLine("SERVER_TRAFFIC_SECRET_0 ${"a".repeat(64)} $hex$end") { line ->
                    assertContentEquals(SECRET, trafficSecret(line), "'$hex' ending '${end.replace("\n", "\\n")}'")
                }
            }
        }
    }

    @Test
    fun `a line whose last word is not whole hex gives no secret`() {
        for (tail in listOf("", "abc", "zz" + "00".repeat(31))) {
            withKeylogLine("CLIENT_TRAFFIC_SECRET_0 ${"a".repeat(64)} $tail") { line ->
                assertNull(trafficSecret(line), "'$tail'")
            }
        }
    }

    // RFC 4231, test cases 1 and 2.
    @Test
    fun `hmac-sha256 gives the RFC 4231 results`() {
        assertContentEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7".hexToByteArray(),
            BoringSsl.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()),
        )
        assertContentEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843".hexToByteArray(),
            BoringSsl.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()),
        )
    }
}

internal val SECRET = ByteArray(32) { (it * 7 + 1).toByte() }

/** [text] as a NUL-terminated line in native memory, as BoringSSL hands one to its key-log callback. */
internal fun withKeylogLine(text: String, body: (Long) -> Unit) {
    val bytes = text.encodeToByteArray()
    val line = Native.malloc(bytes.size + 1L)
    try {
        for (i in bytes.indices) {
            putByte(line + i, bytes[i])
        }
        putByte(line + bytes.size, 0)
        body(line)
    } finally {
        Native.free(line)
    }
}
