package kronos.tls

import kronos.getByte
import kronos.getShort
import kronos.printErr
import kronos.putByte
import kronos.putLong

/**
 * The three functions BoringSSL calls back into, as C function pointers made once per process. An exception
 * escaping one would cross BoringSSL's C frame and end the process, so each body is guarded.
 */
internal expect object TlsCallbacks {
    /**
     * The session whose handshake step is running on this thread. The keylog callback only fires inside
     * SSL_accept, which the handshake makes on the proactor thread with this set around it.
     */
    var handshaking: TlsSession?

    /** `void (*)(const SSL *, const char *line)`: the traffic secrets, for the kTLS handoff. */
    val keylog: Long

    /** `int (*)(SSL *, const uint8_t **out, uint8_t *out_len, const uint8_t *in, unsigned in_len, void *arg)`. */
    val alpnSelect: Long

    /** `int (*)(char *buf, int size, int rwflag, void *userdata)`: there is never a passphrase. */
    val noPassphrase: Long
}

private val SERVER_TRAFFIC_SECRET = "SERVER_TRAFFIC_SECRET_0 ".encodeToByteArray()
private val CLIENT_TRAFFIC_SECRET = "CLIENT_TRAFFIC_SECRET_0 ".encodeToByteArray()

// "<SIDE>_TRAFFIC_SECRET_0 <client_random_hex> <secret_hex>"; every other line is ignored. Read where BoringSSL wrote
// it: a String of the line would keep the secret, as hex, where nothing can zero it.
internal fun onKeylog(ssl: Long, line: Long) {
    try {
        val session = TlsCallbacks.handshaking ?: return
        val server = startsWith(line, SERVER_TRAFFIC_SECRET)
        if (!server && !startsWith(line, CLIENT_TRAFFIC_SECRET)) {
            return
        }

        val secret = trafficSecret(line) ?: return
        if (server) {
            session.serverSecret = secret
        } else {
            session.clientSecret = secret
        }
    } catch (e: Throwable) {
        printErr("[tls] keylog callback faulted: $e")
    }
}

// No prefix byte is 0, so a shorter line differs at its terminator and nothing past it is read.
private fun startsWith(line: Long, prefix: ByteArray): Boolean {
    for (i in prefix.indices) {
        if (getByte(line + i) != prefix[i]) {
            return false
        }
    }
    return true
}

/** The secret ending the NUL-terminated key-log line at [line]: the hex after its last space, decoded from its bytes. */
internal fun trafficSecret(line: Long): ByteArray? {
    var end = 0L
    var lastSpace = -1L
    while (true) {
        val b = getByte(line + end).toInt()
        if (b == 0) {
            break
        }
        if (b == ' '.code) {
            lastSpace = end
        }
        end++
    }

    while (end > lastSpace + 1 && getByte(line + end - 1).toInt().let { it == '\n'.code || it == '\r'.code }) {
        end--
    }

    val start = lastSpace + 1
    val digits = end - start
    if (lastSpace < 0 || digits == 0L || digits % 2 != 0L) {
        return null
    }

    val secret = ByteArray((digits / 2).toInt())
    for (i in secret.indices) {
        val high = hexDigit(getByte(line + start + 2 * i))
        val low = hexDigit(getByte(line + start + 2 * i + 1))
        if (high < 0 || low < 0) {
            secret.fill(0)
            return null
        }
        secret[i] = ((high shl 4) or low).toByte()
    }
    return secret
}

private fun hexDigit(b: Byte): Int = when (val c = b.toInt()) {
    in '0'.code..'9'.code -> c - '0'.code
    in 'a'.code..'f'.code -> c - 'a'.code + 10
    in 'A'.code..'F'.code -> c - 'A'.code + 10
    else -> -1
}

// Server preference: our list is walked in order and the first protocol the client also offered wins.
// *out points into the client's buffer, which outlives the callback. arg is the wire list: u16 length, then
// each protocol as a length byte and its ASCII name.
internal fun onAlpnSelect(ssl: Long, out: Long, outLength: Long, offered: Long, offeredLength: Int, arg: Long): Int {
    try {
        if (arg == 0L) {
            return BoringSsl.SSL_TLSEXT_ERR_NOACK
        }

        val wireLength = getShort(arg).toInt() and 0xFFFF
        val wire = arg + 2

        var ours = 0
        while (ours < wireLength) {
            val wantLength = getByte(wire + ours).toInt() and 0xFF
            var theirs = 0
            while (theirs < offeredLength) {
                val haveLength = getByte(offered + theirs).toInt() and 0xFF
                if (theirs + 1 + haveLength > offeredLength) {
                    break   // malformed offer: stop, do not read past it
                }

                if (haveLength == wantLength && same(offered + theirs + 1, wire + ours + 1, haveLength)) {
                    putLong(out, offered + theirs + 1)
                    putByte(outLength, haveLength.toByte())
                    return BoringSsl.SSL_TLSEXT_ERR_OK
                }

                theirs += 1 + haveLength
            }

            ours += 1 + wantLength
        }

        // Nothing in common: continue without ALPN, as the client may still speak HTTP/1.1.
        return BoringSsl.SSL_TLSEXT_ERR_NOACK
    } catch (e: Throwable) {
        printErr("[tls] alpn callback faulted: $e")
        return BoringSsl.SSL_TLSEXT_ERR_NOACK
    }
}

private fun same(a: Long, b: Long, length: Int): Boolean {
    for (i in 0 until length) {
        if (getByte(a + i) != getByte(b + i)) {
            return false
        }
    }

    return true
}
