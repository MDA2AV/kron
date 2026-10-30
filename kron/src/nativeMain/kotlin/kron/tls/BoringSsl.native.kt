package kron.tls

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kron.Native
import kron.boringssl.BIO
import kron.boringssl.BIO_ctrl
import kron.boringssl.BIO_ctrl_pending
import kron.boringssl.BIO_free
import kron.boringssl.BIO_new
import kron.boringssl.BIO_new_mem_buf
import kron.boringssl.BIO_read
import kron.boringssl.BIO_s_mem
import kron.boringssl.BIO_write
import kron.boringssl.ERR_clear_error
import kron.boringssl.ERR_error_string_n
import kron.boringssl.ERR_get_error
import kron.boringssl.ERR_peek_error
import kron.boringssl.EVP_PKEY
import kron.boringssl.EVP_PKEY_free
import kron.boringssl.EVP_sha256
import kron.boringssl.HMAC
import kron.boringssl.PEM_read_bio_PrivateKey
import kron.boringssl.PEM_read_bio_X509
import kron.boringssl.SSL
import kron.boringssl.SSL_CIPHER_get_protocol_id
import kron.boringssl.SSL_CIPHER_standard_name
import kron.boringssl.SSL_CTX
import kron.boringssl.SSL_CTX_add_extra_chain_cert
import kron.boringssl.SSL_CTX_check_private_key
import kron.boringssl.SSL_CTX_free
import kron.boringssl.SSL_CTX_new
import kron.boringssl.SSL_CTX_set1_tls13_ciphers
import kron.boringssl.SSL_CTX_set_alpn_select_cb
import kron.boringssl.SSL_CTX_set_cipher_list
import kron.boringssl.SSL_CTX_set_default_passwd_cb
import kron.boringssl.SSL_CTX_set_keylog_callback
import kron.boringssl.SSL_CTX_set_max_proto_version
import kron.boringssl.SSL_CTX_set_min_proto_version
import kron.boringssl.SSL_CTX_set_num_tickets
import kron.boringssl.SSL_CTX_set_options
import kron.boringssl.SSL_CTX_set_session_id_context
import kron.boringssl.SSL_CTX_use_PrivateKey
import kron.boringssl.SSL_CTX_use_PrivateKey_file
import kron.boringssl.SSL_CTX_use_certificate
import kron.boringssl.SSL_CTX_use_certificate_chain_file
import kron.boringssl.SSL_METHOD
import kron.boringssl.SSL_accept
import kron.boringssl.SSL_free
import kron.boringssl.SSL_get0_alpn_selected
import kron.boringssl.SSL_get_cipher_by_value
import kron.boringssl.SSL_get_current_cipher
import kron.boringssl.SSL_get_error
import kron.boringssl.SSL_in_init
import kron.boringssl.SSL_new
import kron.boringssl.SSL_read
import kron.boringssl.SSL_set_accept_state
import kron.boringssl.SSL_set_bio
import kron.boringssl.SSL_shutdown
import kron.boringssl.SSL_version
import kron.boringssl.SSL_write
import kron.boringssl.TLS_server_method
import kron.boringssl.X509
import kron.boringssl.X509_free
import kron.getByte

/**
 * The BoringSSL surface TLS needs: libssl and libcrypto, built by scripts/build-boringssl.sh and linked into the
 * binary through cinterop. Pointers are `Long` addresses, as everywhere in this port; the conversions to the
 * generated pointer types stay in here.
 */
internal actual object BoringSsl {
    actual const val SSL_FILETYPE_PEM = 1
    actual const val SSL_ERROR_WANT_READ = 2
    actual const val SSL_ERROR_WANT_WRITE = 3
    actual const val SSL_ERROR_ZERO_RETURN = 6
    actual const val TLS1_2_VERSION: Short = 0x0303
    actual const val TLS1_3_VERSION: Short = 0x0304

    /** The RFC 8446 id of the one suite kTLS is programmed for. */
    actual const val TLS_AES_128_GCM_SHA256: Short = 0x1301
    actual const val SSL_TLSEXT_ERR_OK = 0
    actual const val SSL_TLSEXT_ERR_NOACK = 3
    actual const val SSL_OP_CIPHER_SERVER_PREFERENCE = 0x00400000

    /** On a memory BIO: a pointer to its buffer, without consuming it. */
    actual const val BIO_CTRL_INFO = 3

    private const val ERR_LIB_PEM = 9
    private const val PEM_R_NO_START_LINE = 110
    private const val ERR_LIB_X509 = 11
    private const val X509_R_KEY_TYPE_MISMATCH = 115
    private const val X509_R_KEY_VALUES_MISMATCH = 116

    actual fun serverMethod(): Long = TLS_server_method().address()

    actual fun ctxNew(method: Long): Long = SSL_CTX_new(method.toCPointer<SSL_METHOD>()).address()

    actual fun ctxFree(ctx: Long) {
        SSL_CTX_free(ctx.toCPointer<SSL_CTX>())
    }

    actual fun useCertificateChainFile(ctx: Long, path: String): Int =
        SSL_CTX_use_certificate_chain_file(ctx.toCPointer<SSL_CTX>(), path)

    actual fun usePrivateKeyFile(ctx: Long, path: String): Int =
        SSL_CTX_use_PrivateKey_file(ctx.toCPointer<SSL_CTX>(), path, SSL_FILETYPE_PEM)

    actual fun checkPrivateKey(ctx: Long): Int = SSL_CTX_check_private_key(ctx.toCPointer<SSL_CTX>())

    actual fun useCertificate(ctx: Long, x509: Long): Int =
        SSL_CTX_use_certificate(ctx.toCPointer<SSL_CTX>(), x509.toCPointer<X509>())

    actual fun usePrivateKey(ctx: Long, pkey: Long): Int =
        SSL_CTX_use_PrivateKey(ctx.toCPointer<SSL_CTX>(), pkey.toCPointer<EVP_PKEY>())

    /** Takes ownership of [x509] on success. */
    actual fun addExtraChainCert(ctx: Long, x509: Long): Int =
        SSL_CTX_add_extra_chain_cert(ctx.toCPointer<SSL_CTX>(), x509.toCPointer<X509>())

    actual fun setMinProtoVersion(ctx: Long, version: Short): Int =
        SSL_CTX_set_min_proto_version(ctx.toCPointer<SSL_CTX>(), version.toUShort())

    actual fun setMaxProtoVersion(ctx: Long, version: Short): Int =
        SSL_CTX_set_max_proto_version(ctx.toCPointer<SSL_CTX>(), version.toUShort())

    /** TLS 1.3 suites by protocol id, in server preference order; an empty list means BoringSSL's own order. */
    actual fun setTls13Ciphers(ctx: Long, ids: ShortArray): Int = memScoped {
        val list = allocArray<UShortVar>(ids.size.coerceAtLeast(1))
        for (i in ids.indices) {
            list[i] = ids[i].toUShort()
        }

        SSL_CTX_set1_tls13_ciphers(ctx.toCPointer<SSL_CTX>(), list, null, ids.size.convert())
    }

    /**
     * The id of the TLS 1.3 suite called [name] (its RFC 8446 name), or null when this BoringSSL does not
     * implement it - asked of the library, which knows the ids 0x1301-0x1305 it offers.
     */
    actual fun tls13SuiteId(name: String): Short? {
        for (id in 0x1301..0x1305) {
            val cipher = SSL_get_cipher_by_value(id.toUShort())
            if (cipher != null && SSL_CIPHER_standard_name(cipher)?.toKString() == name) {
                return id.toShort()
            }
        }

        return null
    }

    actual fun setCipherList(ctx: Long, list: String): Int = SSL_CTX_set_cipher_list(ctx.toCPointer<SSL_CTX>(), list)

    actual fun setNumTickets(ctx: Long, count: Long): Int = SSL_CTX_set_num_tickets(ctx.toCPointer<SSL_CTX>(), count.convert())

    actual fun setOptions(ctx: Long, options: Int): Int =
        SSL_CTX_set_options(ctx.toCPointer<SSL_CTX>(), options.toUInt()).toInt()

    actual fun setKeylogCallback(ctx: Long, callback: Long) {
        SSL_CTX_set_keylog_callback(ctx.toCPointer<SSL_CTX>(), callback.toCPointer())
    }

    actual fun setAlpnSelectCallback(ctx: Long, callback: Long, arg: Long) {
        SSL_CTX_set_alpn_select_cb(ctx.toCPointer<SSL_CTX>(), callback.toCPointer(), arg.toCPointer<ByteVar>())
    }

    actual fun setDefaultPasswordCallback(ctx: Long, callback: Long) {
        SSL_CTX_set_default_passwd_cb(ctx.toCPointer<SSL_CTX>(), callback.toCPointer())
    }

    actual fun setSessionIdContext(ctx: Long, id: Long, length: Long): Int =
        SSL_CTX_set_session_id_context(ctx.toCPointer<SSL_CTX>(), id.toCPointer<UByteVar>(), length.convert())

    actual fun sslNew(ctx: Long): Long = SSL_new(ctx.toCPointer<SSL_CTX>()).address()

    /** Frees both BIOs as well. */
    actual fun sslFree(ssl: Long) {
        SSL_free(ssl.toCPointer<SSL>())
    }

    actual fun setAcceptState(ssl: Long) {
        SSL_set_accept_state(ssl.toCPointer<SSL>())
    }

    /** [ssl] owns both BIOs from here. */
    actual fun setBio(ssl: Long, rbio: Long, wbio: Long) {
        SSL_set_bio(ssl.toCPointer<SSL>(), rbio.toCPointer<BIO>(), wbio.toCPointer<BIO>())
    }

    actual fun shutdown(ssl: Long): Int = SSL_shutdown(ssl.toCPointer<SSL>())

    /** Whether the handshake is still running: SSL_shutdown on one that is only queues an error. */
    actual fun inInit(ssl: Long): Boolean = SSL_in_init(ssl.toCPointer<SSL>()) != 0

    actual fun version(ssl: Long): Int = SSL_version(ssl.toCPointer<SSL>())

    actual fun currentCipherId(ssl: Long): Int {
        val cipher = SSL_get_current_cipher(ssl.toCPointer<SSL>())
        if (cipher == null) {
            return 0
        }

        return SSL_CIPHER_get_protocol_id(cipher).toInt()
    }

    /** What ALPN settled on, or null when the client offered nothing this port serves. */
    actual fun alpnSelected(ssl: Long): String? = memScoped {
        val data = alloc<CPointerVar<UByteVar>>()
        val length = alloc<UIntVar>()
        SSL_get0_alpn_selected(ssl.toCPointer<SSL>(), data.ptr, length.ptr)

        val bytes = data.value
        val count = length.value.toInt()
        if (bytes == null || count == 0) {
            null
        } else {
            val address = bytes.address()
            CharArray(count) { getByte(address + it).toInt().toChar() }.concatToString()
        }
    }

    // The three operations whose result has to be classified, each run from a clean error queue and returning
    // the classification. The queue belongs to the thread, which here is a reactor shared by every connection:
    // SSL_get_error reads it before asking whether the SSL merely wants more data, so a residue another
    // connection left would read as this one's fatal error. The raw entry points stay private for that reason.

    /** SSL_accept: 0 once the handshake is done, otherwise SSL_get_error's code. */
    actual fun accept(ssl: Long): Int {
        ERR_clear_error()
        val ret = SSL_accept(ssl.toCPointer<SSL>())
        if (ret == 1) {
            return 0
        }

        return SSL_get_error(ssl.toCPointer<SSL>(), ret)
    }

    /** SSL_read: the bytes read, or minus SSL_get_error's code. */
    actual fun read(ssl: Long, buffer: Long, length: Int): Int {
        ERR_clear_error()
        val n = SSL_read(ssl.toCPointer<SSL>(), buffer.toCPointer<ByteVar>(), length)
        if (n > 0) {
            return n
        }

        return -SSL_get_error(ssl.toCPointer<SSL>(), n)
    }

    /** SSL_write of native bytes: the bytes taken, or minus SSL_get_error's code. */
    actual fun write(ssl: Long, buffer: Long, length: Int): Int {
        ERR_clear_error()
        val n = SSL_write(ssl.toCPointer<SSL>(), buffer.toCPointer<ByteVar>(), length)
        if (n > 0) {
            return n
        }

        return -SSL_get_error(ssl.toCPointer<SSL>(), n)
    }

    /** SSL_write straight from a heap array, pinned for the call. */
    actual fun write(ssl: Long, source: ByteArray, offset: Int, length: Int): Int {
        ERR_clear_error()
        val n = source.usePinned { SSL_write(ssl.toCPointer<SSL>(), it.addressOf(offset), length) }
        if (n > 0) {
            return n
        }

        return -SSL_get_error(ssl.toCPointer<SSL>(), n)
    }

    actual fun memoryBio(): Long = BIO_new(BIO_s_mem()).address()

    /** A read-only BIO over [length] bytes at [address], which must outlive it. */
    actual fun memoryBuffer(address: Long, length: Long): Long =
        BIO_new_mem_buf(address.toCPointer<ByteVar>(), length).address()

    actual fun bioFree(bio: Long): Int = BIO_free(bio.toCPointer<BIO>())

    actual fun bioWrite(bio: Long, data: Long, length: Int): Int =
        BIO_write(bio.toCPointer<BIO>(), data.toCPointer<ByteVar>(), length)

    actual fun bioRead(bio: Long, data: Long, length: Int): Int =
        BIO_read(bio.toCPointer<BIO>(), data.toCPointer<ByteVar>(), length)

    actual fun bioPending(bio: Long): Long = BIO_ctrl_pending(bio.toCPointer<BIO>()).toLong()

    actual fun bioCtrl(bio: Long, cmd: Int, larg: Long, parg: Long): Long =
        BIO_ctrl(bio.toCPointer<BIO>(), cmd, larg, parg.toCPointer<ByteVar>())

    actual fun readX509(bio: Long): Long = PEM_read_bio_X509(bio.toCPointer<BIO>(), null, null, null).address()

    actual fun readPrivateKey(bio: Long): Long = PEM_read_bio_PrivateKey(bio.toCPointer<BIO>(), null, null, null).address()

    actual fun x509Free(x509: Long) {
        X509_free(x509.toCPointer<X509>())
    }

    actual fun pkeyFree(pkey: Long) {
        EVP_PKEY_free(pkey.toCPointer<EVP_PKEY>())
    }

    actual fun clearError() {
        ERR_clear_error()
    }

    /** Whether the queued error says a key does not belong to the certificate loaded. */
    actual fun errorIsKeyMismatch(): Boolean {
        val code = ERR_peek_error().toInt()
        val reason = code and 0xFFF
        return (code ushr 24) == ERR_LIB_X509 &&
            (reason == X509_R_KEY_TYPE_MISMATCH || reason == X509_R_KEY_VALUES_MISMATCH)
    }

    /**
     * Drains the queue and reports whether it held only "no start line", PEM's way of saying the BIO is
     * exhausted. Anything else means a read stopped on a malformed block - the difference between the last
     * certificate of a bundle and the rest of it silently dropped.
     */
    actual fun errorIsEndOfPem(): Boolean {
        var sawEnd = false
        var sawOther = false
        while (true) {
            val code = ERR_get_error().toInt()
            if (code == 0) {
                break
            }

            if ((code ushr 24) == ERR_LIB_PEM && (code and 0xFFF) == PEM_R_NO_START_LINE) {
                sawEnd = true
            } else {
                sawOther = true
            }
        }

        return sawEnd && !sawOther
    }

    /** The oldest queued error as text, and the queue drained so the next caller does not read it again. */
    actual fun lastError(): String {
        val code = ERR_get_error()
        if (code == 0u) {
            return "no BoringSSL error"
        }

        val buffer = Native.malloc(256)
        val message = try {
            ERR_error_string_n(code, buffer.toCPointer<ByteVar>(), 256u)
            cString(buffer)
        } finally {
            Native.free(buffer)
        }

        while (ERR_get_error() != 0u) {
            // drain
        }

        return message
    }

    /** HMAC-SHA256 of [data] under [key], for the kTLS key schedule. */
    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(32)
        val ok = key.usePinned { k ->
            data.usePinned { d ->
                out.usePinned { o ->
                    memScoped {
                        val length = alloc<UIntVar>()
                        HMAC(
                            EVP_sha256(), k.addressOf(0), key.size.convert(),
                            d.addressOf(0).reinterpret(), data.size.convert(),
                            o.addressOf(0).reinterpret(), length.ptr,
                        )
                    }
                }
            }
        }
        check(ok != null) { "HMAC-SHA256 failed: ${lastError()}" }

        return out
    }

    actual fun cString(address: Long): String = address.toCPointer<ByteVar>()?.toKString() ?: ""

    private fun CPointer<*>?.address(): Long = this?.rawValue?.toLong() ?: 0L
}
