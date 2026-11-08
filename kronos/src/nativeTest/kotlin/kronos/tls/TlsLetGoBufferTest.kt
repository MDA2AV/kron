package kronos.tls

import kronos.IOException
import kronos.LetGoDriver
import kronos.RecvItem
import kronos.TcpConnection
import kronos.tls.TlsPipeContractTest.TlsServer
import kronos.tls.TlsPipeContractTest.WireClient
import kotlin.test.Test

/**
 * Handlers that let go of a TLS connection with its client still sending: having answered, through the pipes or the
 * raw session, on each backend; or because the handshake failed, or never finished. Nobody decrypts what follows.
 */
class TlsLetGoBufferTest {
    private val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray()
    private val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray()

    private fun options(
        kernelTx: Boolean = false,
        kernelRx: Boolean = false,
        handshakeTimeoutMs: Int = 2_000,
    ) = TlsOptions(
        certificatePath = TestCertificate.certificate,
        keyPath = TestCertificate.key,
        kernelTx = kernelTx,
        kernelRx = kernelRx,
        handshakeTimeoutMs = handshakeTimeoutMs,
    )

    private fun skipWithoutKernelTls(): Boolean {
        if (kernelTlsAvailable()) {
            return false
        }

        println("skipped: the tls kernel module is not loaded (sudo modprobe tls)")
        return true
    }

    private fun runCases(
        handler: suspend (TcpConnection, TlsService) -> Unit,
        options: TlsOptions,
        letGoClient: (Int) -> LetGoDriver.Peer,
    ) {
        LetGoDriver.forEachCase { mode, ending ->
            TlsServer(LetGoDriver.config(mode, ending.readTimeoutMs), options, handler).use { server ->
                LetGoDriver.check(server.proactor, mode, ending, { letGoClient(server.port) }, { served(server.port) })
            }
        }
    }

    private fun served(port: Int): Boolean = LetGoDriver.answers {
        TlsTestClient(port).use { client ->
            client.write(request)

            val got = ByteArray(response.size)
            client.readFully(got, 0, got.size)

            // Through to the server's close_notify, so closing sends a FIN: with it unread the close is a reset, and a
            // kernel without io_uring fix a68ed2df7213 (in 7.0) can miss a reset that follows data closely.
            val rest = ByteArray(256)
            while (client.read(rest, 0, rest.size) >= 0) {
                // the end of the session
            }

            got.contentEquals(response)
        }
    }

    // Asked over TLS and answered, read through to the server's end, with the raw socket left for what follows.
    private fun answered(port: Int): LetGoDriver.Peer {
        val client = WireClient(port, receiveTimeoutMs = LetGoDriver.GRACE_MS.toInt())
        client.write(request)
        client.drainToEnd()
        return LetGoDriver.Peer(client.socket) { client.close() }
    }

    private val pipes: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
        var tls: TlsSession? = null
        try {
            tls = service.accept(conn)

            val pipe = TlsDuplexPipe(conn, tls, ownsSession = false)
            try {
                val read = pipe.input.read()
                pipe.input.advanceTo(read.buffer.length)

                pipe.output.write(response)
                pipe.output.flush()
            } finally {
                pipe.close()
            }
        } catch (e: IOException) {
            // A handshake that failed, as any handler would see it: it lets go on returning.
        } finally {
            tls?.close()
        }
    }

    private val rawSession: suspend (TcpConnection, TlsService) -> Unit = handler@{ conn, service ->
        var tls: TlsSession? = null
        try {
            tls = service.accept(conn)

            val item = RecvItem()
            var plaintext = tls.drainPlaintext()   // the request can ride in with the client's Finished
            while (plaintext == 0) {
                val snapshot = conn.read()
                while (plaintext == 0 && conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) {
                        plaintext += tls.decrypt(item.address, item.length)
                        conn.returnBuffer(item)
                    }
                }

                if (plaintext == 0 && (snapshot.isClosed || tls.closed)) {
                    return@handler
                }
            }

            tls.write(conn, response)
            conn.flush()
        } catch (e: IOException) {
            // A handshake that failed, as any handler would see it: it lets go on returning.
        } finally {
            tls?.close()
        }
    }

    @Test
    fun `answered through the pipes - everything comes back`() = runCases(pipes, options(), ::answered)

    @Test
    fun `answered through the raw session - everything comes back`() = runCases(rawSession, options(), ::answered)

    @Test
    fun `a failed handshake - everything comes back`() = runCases(pipes, options()) { port ->
        LetGoDriver.refused(port, request)
    }

    @Test
    fun `a handshake that timed out - everything comes back`() {
        runCases(pipes, options(handshakeTimeoutMs = 200)) { port ->
            // A record header promising more than ever arrives.
            LetGoDriver.refused(port, byteArrayOf(0x16, 0x03, 0x01, 0x01, 0x00, 0x01))
        }
    }

    @Test
    fun `kernel TX - answered through the pipes - everything comes back`() {
        if (!skipWithoutKernelTls()) {
            runCases(pipes, options(kernelTx = true), ::answered)
        }
    }

    @Test
    fun `kernel TX - answered through the raw session - everything comes back`() {
        if (!skipWithoutKernelTls()) {
            runCases(rawSession, options(kernelTx = true), ::answered)
        }
    }

    @Test
    fun `kernel TX and RX - answered through the pipes - everything comes back`() {
        if (!skipWithoutKernelTls()) {
            runCases(pipes, options(kernelTx = true, kernelRx = true), ::answered)
        }
    }
}
