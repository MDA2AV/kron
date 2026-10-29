package kron.tls

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kron.Clock
import kron.IOException
import kron.KronServer
import kron.Latch
import kron.Memory
import kron.RecvItem
import kron.TcpConnection
import kron.TcpOptions
import kron.TestSocket
import kron.boringssl.BIO
import kron.boringssl.BIO_ctrl_pending
import kron.boringssl.BIO_new
import kron.boringssl.BIO_read
import kron.boringssl.BIO_s_mem
import kron.boringssl.BIO_write
import kron.boringssl.SSL
import kron.boringssl.SSL_CTX
import kron.boringssl.SSL_CTX_free
import kron.boringssl.SSL_CTX_new
import kron.boringssl.SSL_CTX_set_max_proto_version
import kron.boringssl.SSL_CTX_set_min_proto_version
import kron.boringssl.SSL_ERROR_WANT_READ
import kron.boringssl.SSL_connect
import kron.boringssl.SSL_free
import kron.boringssl.SSL_get_error
import kron.boringssl.SSL_new
import kron.boringssl.SSL_read
import kron.boringssl.SSL_set_bio
import kron.boringssl.SSL_set_connect_state
import kron.boringssl.SSL_write
import kron.boringssl.TLS_client_method
import kron.sleepMs
import kron.testConfig
import platform.posix.IPPROTO_TCP
import platform.posix.TCP_NODELAY
import platform.posix.setsockopt
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TLS chaos: bad records, cleartext, cut-off handshakes and split records, and a clean TLS request answered after each. */
class TlsChaosTest {
    @Test
    fun `garbage after a valid handshake faults cleanly and the server keeps serving`() {
        TlsChaosServer().use { server ->
            ChaosTlsClient(server.port).use { client ->
                // Under the TLS layer, straight onto the socket: an application-data record no key decrypts.
                client.socket.write(byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x10) + ByteArray(16) { 0xAB.toByte() })

                // Faulted, not waiting for a record that will never decrypt: the server ends the connection.
                assertTrue(ended(client.socket), "the server kept a connection whose record failed to decrypt")
            }

            assertTrue(server.failures().any { it.startsWith("TLS decrypt failed") }, "faults: ${server.failures()}")
            server.assertHealthy()
        }
    }

    @Test
    fun `cleartext sprayed at a TLS port is rejected and the server survives`() {
        TlsChaosServer().use { server ->
            TestSocket().use { raw ->
                raw.setReceiveTimeout(5_000)
                raw.connect(server.port)

                // Reads as a record of content type 'G' (0x47): the handshake fails, and the reactor goes on.
                raw.write("GET / HTTP/1.1\r\nHost: chaos\r\n\r\n")
                assertTrue(ended(raw), "the server kept a connection that spoke cleartext")
            }

            assertTrue(server.failures().any { it.startsWith("TLS handshake failed") }, "faults: ${server.failures()}")
            server.assertHealthy()
        }
    }

    @Test
    fun `handshakes that die halfway do not wedge the server`() {
        TlsChaosServer().use { server ->
            // A handshake record header (type 22, version TLS 1.0) claiming 42 bytes, ten of them sent, then a close.
            val partialHello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2a) + ByteArray(10)
            repeat(20) {
                TestSocket().use { raw ->
                    raw.connect(server.port)
                    raw.write(partialHello)
                }
            }

            assertTrue(server.awaitEnded(20), "${server.ended} of 20 cut-off handshakes were let go")
            server.assertHealthy()
        }
    }

    @Test
    fun `a full handshake then an immediate close with no request`() {
        TlsChaosServer().use { server ->
            repeat(10) {
                ChaosTlsClient(server.port).use { _ ->
                    // Handshake done; dropped without a request and without close_notify.
                }
            }

            assertTrue(server.awaitEnded(10), "${server.ended} of 10 idle sessions were let go")
            server.assertHealthy()
        }
    }

    @Test
    fun `a byte-split TLS record still yields exactly one response`() {
        TlsChaosServer().use { server ->
            // Every byte its own send, the handshake's too, with a pause so the reactor's recvs land mid-record.
            ChaosTlsClient(server.port, chunk = 1).use { client ->
                client.write(REQUEST)
                sleepMs(1_200)

                assertEquals(1, client.readUntilQuiet().occurrences(OK_LINE))
            }

            server.assertHealthy()
        }
    }

    @Test
    fun `a request split across many TLS records yields one response`() {
        TlsChaosServer().use { server ->
            ChaosTlsClient(server.port).use { client ->
                // Each piece its own record, and each record its own recv on the server.
                val piece = maxOf(1, REQUEST.size / 5)
                var at = 0
                while (at < REQUEST.size) {
                    client.write(REQUEST.copyOfRange(at, minOf(at + piece, REQUEST.size)))
                    at += piece
                    sleepMs(30)
                }
                sleepMs(1_200)

                assertEquals(1, client.readUntilQuiet().occurrences(OK_LINE))
            }

            server.assertHealthy()
        }
    }
}

private val REQUEST = "GET / HTTP/1.1\r\nHost: chaos\r\n\r\n".encodeToByteArray()
private val TLS_OK = "HTTP/1.1 200 X\r\nContent-Type: text/plain\r\nContent-Length: 6\r\n\r\ntls-ok".encodeToByteArray()
private const val OK_LINE = "HTTP/1.1 200"

/**
 * One reactor terminating TLS on BoringSSL both ways, ioxide's default: each request decrypted, framed at CRLFCRLF
 * and answered "tls-ok". A handler that fails records why and lets the connection go.
 */
private class TlsChaosServer : AutoCloseable {
    private val services = arrayOfNulls<TlsService>(1)
    private val faults = AtomicReference<List<String>>(emptyList())
    private val handlersEnded = AtomicInt(0)
    private val server: KronServer

    val port: Int

    init {
        val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)
        val ready = Latch(1)
        server = KronServer(
            testConfig(TcpOptions(port = 0, poolMax = 64), recvSlots = 256),
            onStart = { reactor ->
                services[0] = TlsService.start(reactor, options)
                ready.countDown()
            },
            handler = ::serve,
        ).start()

        check(ready.await(10_000)) { "the reactor never started" }
        port = server.reactors[0].listenPorts[0]
    }

    val ended: Int get() = handlersEnded.load()

    fun failures(): List<String> = faults.load()

    fun awaitEnded(count: Int): Boolean {
        val deadline = Clock.monotonicMs() + 10_000
        while (handlersEnded.load() < count && Clock.monotonicMs() < deadline) {
            sleepMs(5)
        }

        return handlersEnded.load() >= count
    }

    // A fresh, clean TLS request is still answered.
    fun assertHealthy() {
        ChaosTlsClient(port).use { client ->
            client.write(REQUEST)
            assertEquals("tls-ok", client.readResponseBody(), "a fresh TLS request after the assault")
        }
    }

    private suspend fun serve(conn: TcpConnection) {
        var session: TlsSession? = null
        val item = RecvItem()
        val carry = PlainHead()

        try {
            val tls = services[0]!!.accept(conn)
            session = tls

            // A request that rode in with the client's Finished is already decrypted: answered before any wait.
            val early = tls.drainPlaintext()
            if (early > 0) {
                carry.append(tls.plaintext, early)
            }

            var peerGone = false
            while (true) {
                // Per request, not per record: a request split across records is still one answer.
                val requests = carry.takeRequests()
                repeat(requests) {
                    tls.write(conn, TLS_OK)
                }
                if (requests > 0) {
                    conn.flush()
                }

                if (peerGone || tls.closed) {
                    return
                }

                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    if (!item.hasBuffer) {
                        continue
                    }

                    try {
                        val n = tls.decrypt(item.address, item.length)
                        if (n > 0) {
                            carry.append(tls.plaintext, n)
                        }
                    } finally {
                        conn.returnBuffer(item)
                    }
                }

                peerGone = snapshot.isClosed
            }
        } catch (e: IOException) {
            faults.store(faults.load() + (e.message ?: e.toString()))
        } finally {
            session?.close()
            handlersEnded.addAndFetch(1)
        }
    }

    override fun close() = server.close()
}

// Plaintext carried across records until a CRLFCRLF ends each request.
private class PlainHead {
    private var bytes = ByteArray(4096)
    private var size = 0

    fun append(address: Long, length: Int) {
        if (size + length > bytes.size) {
            bytes = bytes.copyOf(maxOf(bytes.size * 2, size + length))
        }

        Memory.copy(address, bytes, size, length)
        size += length
    }

    // How many whole requests the carried bytes hold; those are dropped and the rest kept.
    fun takeRequests(): Int {
        var requests = 0
        var start = 0
        var i = 0
        while (i + 3 < size) {
            if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) {
                requests++
                i += 4
                start = i
            } else {
                i++
            }
        }

        bytes.copyInto(bytes, 0, start, size)
        size -= start
        return requests
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
    }
}

/**
 * A TLS 1.3 client on BoringSSL memory BIOs over a [TestSocket], so the test decides what reaches the wire and in
 * what pieces: with [chunk], every send goes out [chunk] bytes at a time, a millisecond apart. Trusts any
 * certificate: what is under test is the server's survival, not the chain.
 */
private class ChaosTlsClient(port: Int, private val chunk: Int = 0) : AutoCloseable {
    val socket = TestSocket()
    private val ctx: CPointer<SSL_CTX>
    private val ssl: CPointer<SSL>
    private val rbio: CPointer<BIO>
    private val wbio: CPointer<BIO>
    private val wire = ByteArray(16 * 1024)

    init {
        socket.setReceiveTimeout(6_000)
        socket.connect(port)

        // NODELAY stops the sender batching the pieces; the pause between them stops the receiver coalescing.
        memScoped {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(socket.fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
        }

        ctx = SSL_CTX_new(TLS_client_method()) ?: error("SSL_CTX_new failed")
        SSL_CTX_set_min_proto_version(ctx, BoringSsl.TLS1_3_VERSION.toUShort())
        SSL_CTX_set_max_proto_version(ctx, BoringSsl.TLS1_3_VERSION.toUShort())

        ssl = SSL_new(ctx) ?: error("SSL_new failed")
        rbio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
        wbio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
        SSL_set_bio(ssl, rbio, wbio)   // the SSL owns both from here
        SSL_set_connect_state(ssl)

        try {
            while (true) {
                BoringSsl.clearError()
                val rc = SSL_connect(ssl)
                send()
                if (rc == 1) {
                    break
                }

                if (SSL_get_error(ssl, rc) != SSL_ERROR_WANT_READ) {
                    throw IOException("TLS handshake failed: ${BoringSsl.lastError()}")
                }
                if (!receive()) {
                    throw IOException("the server ended the connection during the handshake")
                }
            }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** One SSL_write - one record for a request this small - sent as [chunk] says. */
    fun write(plaintext: ByteArray) {
        BoringSsl.clearError()
        val n = plaintext.usePinned { SSL_write(ssl, it.addressOf(0), plaintext.size) }
        check(n == plaintext.size) { "SSL_write took $n of ${plaintext.size}: ${BoringSsl.lastError()}" }
        send()
    }

    /** The plaintext that arrives until the line is quiet for 600 ms or the server ends the connection. */
    fun readUntilQuiet(): String {
        socket.setReceiveTimeout(600)

        val seen = StringBuilder()
        val plain = ByteArray(16 * 1024)
        while (true) {
            BoringSsl.clearError()
            val n = plain.usePinned { SSL_read(ssl, it.addressOf(0), plain.size) }
            if (n > 0) {
                seen.append(plain.decodeToString(0, n))
            } else if (SSL_get_error(ssl, n) != SSL_ERROR_WANT_READ || !receive()) {
                return seen.toString()
            }
        }
    }

    /** One response's body: the head read to its blank line, then Content-Length bytes. */
    fun readResponseBody(): String {
        val seen = StringBuilder()
        val plain = ByteArray(16 * 1024)
        while (true) {
            val text = seen.toString()
            val headEnd = text.indexOf("\r\n\r\n")
            if (headEnd >= 0) {
                val length = text.substringAfter("Content-Length: ").substringBefore("\r\n").toInt()
                if (text.length >= headEnd + 4 + length) {
                    return text.substring(headEnd + 4, headEnd + 4 + length)
                }
            }

            BoringSsl.clearError()
            val n = plain.usePinned { SSL_read(ssl, it.addressOf(0), plain.size) }
            if (n > 0) {
                seen.append(plain.decodeToString(0, n))
            } else if (SSL_get_error(ssl, n) != SSL_ERROR_WANT_READ || !receive()) {
                throw IOException("the connection ended inside the response: $seen")
            }
        }
    }

    // Everything BoringSSL has written, onto the socket.
    private fun send() {
        while (BIO_ctrl_pending(wbio).toLong() > 0) {
            val n = wire.usePinned { BIO_read(wbio, it.addressOf(0), wire.size) }
            if (n <= 0) {
                return
            }

            if (chunk <= 0) {
                socket.write(wire.copyOf(n))
                continue
            }

            // A pause between pieces, so the server's recvs land mid-record.
            var at = 0
            while (at < n) {
                socket.write(wire.copyOfRange(at, minOf(at + chunk, n)))
                at += chunk
                sleepMs(1)
            }
        }
    }

    // What the socket has, into BoringSSL: false once the server ended the connection or the read timed out.
    private fun receive(): Boolean {
        val n = try {
            socket.read(wire, 0, wire.size)
        } catch (e: IOException) {
            -1
        }
        if (n <= 0) {
            return false
        }

        wire.usePinned { BIO_write(rbio, it.addressOf(0), n) }
        return true
    }

    // No close_notify: a client that just goes.
    override fun close() {
        SSL_free(ssl)
        SSL_CTX_free(ctx)
        socket.close()
    }
}

// The client's view of a connection the server ended: whatever it sent last (an alert), then the end of the
// stream or a reset - not a read that timed out.
private fun ended(socket: TestSocket): Boolean {
    val sink = ByteArray(4096)
    while (true) {
        val n = try {
            socket.read(sink, 0, sink.size)
        } catch (e: IOException) {
            return e.message?.endsWith("errno 104") == true
        }
        if (n < 0) {
            return true
        }
    }
}

private fun String.occurrences(of: String): Int {
    var count = 0
    var at = indexOf(of)
    while (at >= 0) {
        count++
        at = indexOf(of, at + of.length)
    }

    return count
}
