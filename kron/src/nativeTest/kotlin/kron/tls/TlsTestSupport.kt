package kron.tls

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kron.Clock
import kron.IOException
import kron.TestSocket
import kron.boringssl.ASN1_INTEGER_set
import kron.boringssl.BIO
import kron.boringssl.BIO_ctrl_pending
import kron.boringssl.BIO_free
import kron.boringssl.BIO_new
import kron.boringssl.BIO_new_mem_buf
import kron.boringssl.BIO_read
import kron.boringssl.BIO_s_mem
import kron.boringssl.EC_KEY_generate_key
import kron.boringssl.EC_KEY_new_by_curve_name
import kron.boringssl.EVP_PKEY
import kron.boringssl.EVP_PKEY_assign_EC_KEY
import kron.boringssl.EVP_PKEY_free
import kron.boringssl.EVP_PKEY_new
import kron.boringssl.EVP_sha256
import kron.boringssl.MBSTRING_ASC
import kron.boringssl.NID_X9_62_prime256v1
import kron.boringssl.PEM_read_bio_X509
import kron.boringssl.PEM_write_bio_PrivateKey
import kron.boringssl.PEM_write_bio_X509
import kron.boringssl.SSL
import kron.boringssl.SSL_CTX
import kron.boringssl.SSL_CTX_free
import kron.boringssl.SSL_CTX_get_cert_store
import kron.boringssl.SSL_CTX_new
import kron.boringssl.SSL_CTX_set_max_proto_version
import kron.boringssl.SSL_CTX_set_min_proto_version
import kron.boringssl.SSL_CTX_set_verify
import kron.boringssl.SSL_ERROR_WANT_READ
import kron.boringssl.SSL_ERROR_WANT_WRITE
import kron.boringssl.SSL_ERROR_ZERO_RETURN
import kron.boringssl.SSL_VERIFY_PEER
import kron.boringssl.SSL_connect
import kron.boringssl.SSL_free
import kron.boringssl.SSL_get_error
import kron.boringssl.SSL_new
import kron.boringssl.SSL_read
import kron.boringssl.SSL_set_alpn_protos
import kron.boringssl.SSL_set_fd
import kron.boringssl.SSL_shutdown
import kron.boringssl.SSL_write
import kron.boringssl.TLS_client_method
import kron.boringssl.X509_NAME_add_entry_by_txt
import kron.boringssl.X509_STORE_add_cert
import kron.boringssl.X509_VERSION_3
import kron.boringssl.X509_free
import kron.boringssl.X509_get_serialNumber
import kron.boringssl.X509_get_subject_name
import kron.boringssl.X509_getm_notAfter
import kron.boringssl.X509_getm_notBefore
import kron.boringssl.X509_gmtime_adj
import kron.boringssl.X509_new
import kron.boringssl.X509_set_issuer_name
import kron.boringssl.X509_set_pubkey
import kron.boringssl.X509_set_version
import kron.boringssl.X509_sign
import kron.readFileBytes
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.atexit
import platform.posix.close
import platform.posix.mkdtemp
import platform.posix.open
import platform.posix.rmdir
import platform.posix.unlink
import platform.posix.write

/**
 * A self-signed localhost certificate made once per run, so no key lives in the repository: an EC P-256 key and its
 * certificate, as PEM text and as files in a temporary directory removed at exit.
 */
object TestCertificate {
    val certificatePem: String
    val keyPem: String
    val directory: String
    val certificate: String
    val key: String

    init {
        val key = newKey()
        try {
            certificatePem = selfSign(key)
            keyPem = pem { PEM_write_bio_PrivateKey(it, key, null, null, 0, null, null) }
        } finally {
            EVP_PKEY_free(key)
        }

        directory = memScoped {
            val template = "/tmp/kron-tls-XXXXXX".cstr.ptr
            mkdtemp(template)?.toKString() ?: error("mkdtemp failed")
        }
        certificate = "$directory/server.crt"
        this.key = "$directory/server.key"
        writeFile(certificate, certificatePem.encodeToByteArray())
        writeFile(this.key, keyPem.encodeToByteArray())

        atexit(staticCFunction<Unit> { TestCertificate.removeFiles() })
    }

    /** A key that is not the certificate's, PEM-encoded. */
    fun otherKeyPem(): String {
        val other = newKey()
        try {
            return pem { PEM_write_bio_PrivateKey(it, other, null, null, 0, null, null) }
        } finally {
            EVP_PKEY_free(other)
        }
    }

    private fun removeFiles() {
        unlink(certificate)
        unlink(key)
        rmdir(directory)
    }

    private fun newKey(): CPointer<EVP_PKEY> {
        val ec = EC_KEY_new_by_curve_name(NID_X9_62_prime256v1)
        check(ec != null && EC_KEY_generate_key(ec) == 1) { "EC P-256 key generation failed" }

        val key = EVP_PKEY_new()
        check(key != null && EVP_PKEY_assign_EC_KEY(key, ec) == 1) { "EVP_PKEY_assign_EC_KEY failed" }

        return key
    }

    private fun selfSign(key: CPointer<EVP_PKEY>): String {
        val certificate = X509_new() ?: error("X509_new failed")
        try {
            X509_set_version(certificate, X509_VERSION_3.toLong())
            ASN1_INTEGER_set(X509_get_serialNumber(certificate), 1)
            X509_gmtime_adj(X509_getm_notBefore(certificate), 0)
            X509_gmtime_adj(X509_getm_notAfter(certificate), 24L * 60 * 60)
            X509_set_pubkey(certificate, key)

            val name = X509_get_subject_name(certificate)
            memScoped {
                X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, "localhost".cstr.ptr.reinterpret(), -1, -1, 0)
            }
            X509_set_issuer_name(certificate, name)
            check(X509_sign(certificate, key, EVP_sha256()) > 0) { "X509_sign failed" }

            return pem { PEM_write_bio_X509(it, certificate) }
        } finally {
            X509_free(certificate)
        }
    }

    private fun pem(write: (CPointer<BIO>) -> Int): String {
        val bio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
        try {
            check(write(bio) == 1) { "PEM write failed" }

            val length = BIO_ctrl_pending(bio).toInt()
            val bytes = ByteArray(length)
            bytes.usePinned {
                BIO_read(bio, it.addressOf(0), length)
            }

            return bytes.decodeToString()
        } finally {
            BIO_free(bio)
        }
    }
}

/** Writes [bytes] to [path], replacing it. */
fun writeFile(path: String, bytes: ByteArray) {
    val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC or O_CLOEXEC, 384)   // 0600
    check(fd >= 0) { "open($path) failed" }

    try {
        var written = 0
        while (written < bytes.size) {
            val n = bytes.usePinned { write(fd, it.addressOf(written), (bytes.size - written).convert()) }
            check(n > 0) { "write($path) failed" }
            written += n.toInt()
        }
    } finally {
        close(fd)
    }
}

/** Whether the kernel has TLS: the `tls` ULP is registered once its module is loaded. */
fun kernelTlsAvailable(): Boolean {
    val ulps = readFileBytes("/proc/sys/net/ipv4/tcp_available_ulp") ?: return false
    return ulps.decodeToString().split(' ', '\n').contains("tls")
}

/**
 * A blocking TLS client over [TestSocket]: BoringSSL, trusting [TestCertificate] and nothing else, at exactly
 * [version], offering [alpn]. An interrupted read or write is retried.
 */
class TlsTestClient(
    port: Int,
    version: Short = BoringSsl.TLS1_3_VERSION,
    alpn: List<String> = listOf("http/1.1"),
) : AutoCloseable {
    private val socket = TestSocket()
    private val ctx: CPointer<SSL_CTX>
    private val ssl: CPointer<SSL>

    init {
        socket.setReceiveTimeout(10_000)
        socket.connect(port)

        ctx = SSL_CTX_new(TLS_client_method()) ?: error("SSL_CTX_new failed")
        SSL_CTX_set_min_proto_version(ctx, version.toUShort())
        SSL_CTX_set_max_proto_version(ctx, version.toUShort())
        trust(TestCertificate.certificatePem)
        SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, null)

        ssl = SSL_new(ctx) ?: error("SSL_new failed")
        SSL_set_fd(ssl, socket.fd)
        offer(alpn)

        val deadline = Clock.monotonicMs() + TIMEOUT_MS
        while (true) {
            BoringSsl.clearError()
            val rc = SSL_connect(ssl)
            if (rc == 1) {
                break
            }

            if (!retryable(rc, deadline)) {
                close()
                throw IOException("TLS handshake failed: ${BoringSsl.lastError()}")
            }
        }
    }

    /** What ALPN settled on, or "" when nothing. */
    val applicationProtocol: String
        get() = BoringSsl.alpnSelected(ssl.rawValue.toLong()) ?: ""

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        val deadline = Clock.monotonicMs() + TIMEOUT_MS
        var written = 0
        while (written < length) {
            BoringSsl.clearError()
            val n = bytes.usePinned { SSL_write(ssl, it.addressOf(offset + written), length - written) }
            if (n > 0) {
                written += n
            } else if (!retryable(n, deadline)) {
                throw IOException("TLS write failed: ${BoringSsl.lastError()}")
            }
        }
    }

    fun write(text: String) = write(text.encodeToByteArray())

    /** Up to [length] bytes; -1 once the server has closed the session. */
    fun read(into: ByteArray, offset: Int, length: Int): Int {
        val deadline = Clock.monotonicMs() + TIMEOUT_MS
        while (true) {
            BoringSsl.clearError()
            val n = into.usePinned { SSL_read(ssl, it.addressOf(offset), length) }
            if (n > 0) {
                return n
            }

            val error = SSL_get_error(ssl, n)
            if (error == SSL_ERROR_ZERO_RETURN || (n == 0 && error != SSL_ERROR_WANT_READ)) {
                return -1
            }

            if (!retryable(n, deadline)) {
                throw IOException("TLS read failed: ${BoringSsl.lastError()}")
            }
        }
    }

    fun readFully(into: ByteArray, offset: Int, length: Int) {
        var got = 0
        while (got < length) {
            val n = read(into, offset + got, length - got)
            if (n < 0) {
                throw IOException("the session ended after $got of $length bytes")
            }

            got += n
        }
    }

    override fun close() {
        SSL_shutdown(ssl)
        SSL_free(ssl)
        SSL_CTX_free(ctx)
        socket.close()
    }

    private fun trust(pem: String) {
        val bytes = pem.encodeToByteArray()
        bytes.usePinned {
            val bio = BIO_new_mem_buf(it.addressOf(0), bytes.size.toLong())
            val certificate = PEM_read_bio_X509(bio, null, null, null) ?: error("the test certificate does not parse")
            X509_STORE_add_cert(SSL_CTX_get_cert_store(ctx), certificate)
            X509_free(certificate)
            BIO_free(bio)
        }
    }

    // The wire list: each protocol as a length byte and its name.
    private fun offer(alpn: List<String>) {
        val wire = ByteArray(alpn.sumOf { 1 + it.length })
        var at = 0
        for (protocol in alpn) {
            wire[at] = protocol.length.toByte()
            protocol.encodeToByteArray().copyInto(wire, at + 1)
            at += 1 + protocol.length
        }

        wire.usePinned {
            check(SSL_set_alpn_protos(ssl, it.addressOf(0).reinterpret(), wire.size.convert()) == 0) { "SSL_set_alpn_protos failed" }
        }
    }

    // A blocking socket wants more only after an interruption or its receive timeout, so a want is retried until
    // the operation's deadline, and then fails.
    private fun retryable(rc: Int, deadline: Long): Boolean {
        val error = SSL_get_error(ssl, rc)
        val wants = error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE
        return wants && Clock.monotonicMs() < deadline
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
