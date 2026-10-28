package kron.tls

import kron.Clock
import kron.IOException
import kron.KronServer
import kron.Latch
import kron.Reactor
import kron.RecvItem
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions
import kron.TestSocket
import kron.sleepMs
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsTest {
    private val certPath = TestCertificate.certificate
    private val keyPath = TestCertificate.key

    // What the server saw of the session, for the test thread.
    private class Observed {
        @Volatile var kernelTx = false
        @Volatile var kernelRx = false
        @Volatile var alpn: String? = null
        @Volatile var version = 0
        @Volatile var failure: String? = null
    }

    private fun withEchoServer(options: TlsOptions, body: (port: Int, observed: Observed) -> Unit) {
        val observed = Observed()
        val services = arrayOfNulls<TlsService>(1)
        val ready = Latch(1)
        val item = RecvItem()

        suspend fun echo(conn: TcpConnection) {
            var tls: TlsSession? = null
            try {
                val session = services[0]!!.accept(conn)
                tls = session
                observed.kernelTx = session.kernelTx
                observed.kernelRx = session.kernelRx
                observed.alpn = session.negotiatedAlpn
                observed.version = session.negotiatedProtocolVersion

                val early = session.drainPlaintext()
                if (early > 0) {
                    session.write(conn, session.plaintext, early)
                    conn.flush()
                }

                while (true) {
                    val snapshot = conn.read()
                    var wrote = false
                    while (conn.tryGetItem(snapshot, item)) {
                        if (!item.hasBuffer) {
                            continue
                        }

                        val n = session.decrypt(item.address, item.length)
                        if (n > 0) {
                            session.write(conn, session.plaintext, n)
                            wrote = true
                        }
                        conn.returnBuffer(item)
                    }

                    if (wrote) {
                        conn.flush()
                    }

                    if (snapshot.isClosed || session.closed) {
                        return
                    }
                }
            } catch (e: IOException) {
                observed.failure = e.message
            } finally {
                tls?.close()
            }
        }

        val config = ServerConfig(reactorCount = 1, tcp = TcpOptions(port = 0))
        val server = KronServer(
            config,
            onStart = { reactor ->
                services[0] = TlsService.start(reactor, options)
                ready.countDown()
            },
            handler = ::echo,
        ).start()

        try {
            assertTrue(ready.await(10_000), "the reactor never started")
            body(server.reactors[0].listenPorts[0], observed)
        } finally {
            server.close()
        }
    }

    // Sends size random bytes and reads them back, 16 KiB at a time: one TLS session cannot be written and read from
    // two threads, so the chunks keep both directions from filling and stalling.
    private fun echo(client: TlsTestClient, size: Int) {
        val data = Random(size).nextBytes(size)
        val got = ByteArray(size)

        var done = 0
        while (done < size) {
            val chunk = minOf(16 * 1024, size - done)
            client.write(data, done, chunk)
            client.readFully(got, done, chunk)
            done += chunk
        }

        assertContentEquals(data, got)
    }

    // kotlin.test has no assumptions: a test the machine cannot run says so and passes.
    private fun skipWithoutKernelTls(): Boolean {
        if (kernelTlsAvailable()) {
            return false
        }

        println("skipped: the tls kernel module is not loaded (sudo modprobe tls)")
        return true
    }

    @Test
    fun `BoringSSL carries data both ways`() {
        withEchoServer(TlsOptions(certificatePath = certPath, keyPath = keyPath)) { port, observed ->
            TlsTestClient(port).use { client ->
                echo(client, 5)
                echo(client, 256 * 1024)
            }

            assertFalse(observed.kernelTx)
            assertFalse(observed.kernelRx)
            assertEquals("http/1.1", observed.alpn)
            assertEquals(0x0304, observed.version)
        }
    }

    @Test
    fun `kernel TX encrypts what the handler writes as plaintext`() {
        if (skipWithoutKernelTls()) {
            return
        }

        withEchoServer(TlsOptions(certificatePath = certPath, keyPath = keyPath, kernelTx = true)) { port, observed ->
            TlsTestClient(port).use { client ->
                echo(client, 5)
                echo(client, 1024 * 1024)
            }

            assertTrue(observed.kernelTx)
            assertFalse(observed.kernelRx)
        }
    }

    @Test
    fun `kernel TX and RX carry data both ways`() {
        if (skipWithoutKernelTls()) {
            return
        }

        val options = TlsOptions(certificatePath = certPath, keyPath = keyPath, kernelTx = true, kernelRx = true)
        withEchoServer(options) { port, observed ->
            TlsTestClient(port).use { client ->
                echo(client, 5)
                echo(client, 1024 * 1024)
            }

            assertTrue(observed.kernelTx)
            assertTrue(observed.kernelRx)
        }
    }

    @Test
    fun `TLS 1_2 is served on BoringSSL and refused under kernel TX`() {
        withEchoServer(TlsOptions(certificatePath = certPath, keyPath = keyPath)) { port, observed ->
            TlsTestClient(port, version = BoringSsl.TLS1_2_VERSION).use { client ->
                echo(client, 100)
            }

            assertEquals(0x0303, observed.version)
        }

        if (skipWithoutKernelTls()) {
            return
        }

        withEchoServer(TlsOptions(certificatePath = certPath, keyPath = keyPath, kernelTx = true)) { port, _ ->
            assertFailsWith<IOException> {
                TlsTestClient(port, version = BoringSsl.TLS1_2_VERSION).close()
            }
        }
    }

    @Test
    fun `a client offering no protocol this port serves continues without ALPN`() {
        withEchoServer(TlsOptions(certificatePath = certPath, keyPath = keyPath)) { port, observed ->
            TlsTestClient(port, alpn = listOf("h2")).use { client ->
                assertEquals("", client.applicationProtocol)
                echo(client, 10)
            }

            assertNull(observed.alpn)
        }
    }

    @Test
    fun `a handshake that never starts is closed by the sweep`() {
        val options = TlsOptions(certificatePath = certPath, keyPath = keyPath, handshakeTimeoutMs = 300)
        withEchoServer(options) { port, observed ->
            TestSocket().use { raw ->
                raw.setReceiveTimeout(5_000)
                raw.connect(port)

                val started = Clock.monotonicMs()
                assertEquals(-1, raw.read())   // the server shut it down
                val ms = Clock.monotonicMs() - started
                assertTrue(ms in 250..3_000, "closed after $ms ms")
            }

            sleepMs(100)
            assertEquals("connection closed during TLS handshake", observed.failure)
        }
    }

    @Test
    fun `a certificate and key with a byte-order mark load`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val cert = "${TestCertificate.directory}/bom.crt"
        val key = "${TestCertificate.directory}/bom.key"
        writeFile(cert, bom + TestCertificate.certificatePem.encodeToByteArray())
        writeFile(key, bom + TestCertificate.keyPem.encodeToByteArray())

        try {
            withEchoServer(TlsOptions(certificatePath = cert, keyPath = key)) { port, _ ->
                TlsTestClient(port).use { client ->
                    echo(client, 10)
                }
            }
        } finally {
            platform.posix.unlink(cert)
            platform.posix.unlink(key)
        }
    }

    @Test
    fun `settings that cannot hold are refused at start`() {
        val reactor = Reactor(0, ServerConfig())

        fun refused(options: TlsOptions) {
            assertFailsWith<IllegalArgumentException> {
                TlsService.start(reactor, options)
            }
        }

        refused(TlsOptions(certificatePath = certPath, keyPath = keyPath, kernelRx = true))
        refused(
            TlsOptions(
                certificatePath = certPath,
                keyPath = keyPath,
                kernelTx = true,
                minProtocolVersion = TlsProtocolVersion.TLS12,
            ),
        )
        refused(TlsOptions(certificatePath = certPath, keyPath = keyPath, kernelTx = true, cipherSuites = "TLS_AES_256_GCM_SHA384"))
        refused(TlsOptions(certificatePath = certPath, certificatePem = "x", keyPath = keyPath))
        refused(TlsOptions(keyPath = keyPath))
        refused(TlsOptions(certificatePath = certPath, keyPath = keyPath, handshakeTimeoutMs = -1))

        // A key that is not the certificate's is named at start, not left for every handshake to fail on.
        val mismatch = assertFailsWith<IOException> {
            TlsService.start(reactor, TlsOptions(certificatePath = certPath, keyPem = TestCertificate.otherKeyPem()))
        }
        assertTrue(mismatch.message!!.contains("do not go together"), mismatch.message)
    }
}
