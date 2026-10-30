package kron.tls

import kron.getByte
import kron.getShort
import kron.printErr
import kron.putByte
import kron.putLong

/**
 * The three functions BoringSSL calls back into, as C function pointers made once per process. An exception
 * escaping one would cross BoringSSL's C frame and end the process, so each body is guarded.
 */
internal expect object TlsCallbacks {
    /**
     * The session whose handshake step is running on this thread. The keylog callback only fires inside
     * SSL_accept, which the handshake makes on the reactor thread with this set around it.
     */
    var handshaking: TlsSession?

    /** `void (*)(const SSL *, const char *line)`: the traffic secrets, for the kTLS handoff. */
    val keylog: Long

    /** `int (*)(SSL *, const uint8_t **out, uint8_t *out_len, const uint8_t *in, unsigned in_len, void *arg)`. */
    val alpnSelect: Long

    /** `int (*)(char *buf, int size, int rwflag, void *userdata)`: there is never a passphrase. */
    val noPassphrase: Long
}

// "<SIDE>_TRAFFIC_SECRET_0 <client_random_hex> <secret_hex>"; every other line is ignored.
internal fun onKeylog(ssl: Long, line: Long) {
    try {
        val session = TlsCallbacks.handshaking ?: return
        val text = BoringSsl.cString(line)
        val server = text.startsWith("SERVER_TRAFFIC_SECRET_0 ")
        if (!server && !text.startsWith("CLIENT_TRAFFIC_SECRET_0 ")) {
            return
        }

        val lastSpace = text.lastIndexOf(' ')
        if (lastSpace <= 0) {
            return
        }

        val secret = text.substring(lastSpace + 1).trimEnd().hexToByteArray()
        if (server) {
            session.serverSecret = secret
        } else {
            session.clientSecret = secret
        }
    } catch (e: Throwable) {
        printErr("[tls] keylog callback faulted: $e")
    }
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
