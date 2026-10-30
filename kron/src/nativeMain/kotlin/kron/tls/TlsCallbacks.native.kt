package kron.tls

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong
import kron.boringssl.SSL
import kotlin.native.concurrent.ThreadLocal

/**
 * The three functions BoringSSL calls back into, as C function pointers made once per process. An exception
 * escaping one would cross BoringSSL's C frame and end the process, so each body is guarded.
 */
internal actual object TlsCallbacks {
    /**
     * The session whose handshake step is running on this thread. The keylog callback only fires inside
     * SSL_accept, which the handshake makes on the reactor thread with this set around it.
     */
    actual var handshaking: TlsSession?
        get() = handshakingSession
        set(value) {
            handshakingSession = value
        }

    /** `void (*)(const SSL *, const char *line)`: the traffic secrets, for the kTLS handoff. */
    actual val keylog: Long = staticCFunction { ssl: CPointer<SSL>?, line: CPointer<ByteVar>? ->
        onKeylog(ssl.address(), line.address())
    }.address()

    /** `int (*)(SSL *, const uint8_t **out, uint8_t *out_len, const uint8_t *in, unsigned in_len, void *arg)`. */
    actual val alpnSelect: Long = staticCFunction {
            ssl: CPointer<SSL>?,
            out: CPointer<CPointerVar<UByteVar>>?,
            outLength: CPointer<UByteVar>?,
            offered: CPointer<UByteVar>?,
            offeredLength: UInt,
            arg: COpaquePointer?,
        ->
        onAlpnSelect(
            ssl.address(),
            out.address(),
            outLength.address(),
            offered.address(),
            offeredLength.toInt(),
            arg.address(),
        )
    }.address()

    /** `int (*)(char *buf, int size, int rwflag, void *userdata)`: there is never a passphrase. */
    actual val noPassphrase: Long = staticCFunction { _: CPointer<ByteVar>?, _: Int, _: Int, _: COpaquePointer? ->
        // Zero bytes written fails an encrypted key's load with the ordinary error.
        0
    }.address()
}

@ThreadLocal
private var handshakingSession: TlsSession? = null

private fun CPointer<*>?.address(): Long = this?.rawValue?.toLong() ?: 0L
