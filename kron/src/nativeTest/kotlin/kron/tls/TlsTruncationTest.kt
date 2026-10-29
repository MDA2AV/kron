package kron.tls

import kron.Clock
import kron.IOException
import kron.PipeReader
import kron.TcpConnection
import kron.sleepMs
import kron.testConfig
import kron.tls.TlsPipeContractTest.TlsServer
import kron.tls.TlsPipeContractTest.WireClient
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stream that ended against one that was cut: close_notify, a bare FIN, and how each looks through the pipe. */
class TlsTruncationTest {
    private val endedPath = "/ended"
    private val cutPath = "/cut"

    // The shortest request either shape sends, so "the pipe saw nothing" cannot pass.
    private val requestBytes = 30

    // A body only this file writes: the answer is this test's server, past its handshake.
    private val responseBody = "truncation-probe"
    private val response =
        "HTTP/1.1 200 OK\r\nContent-Length: ${responseBody.length}\r\n\r\n$responseBody".encodeToByteArray()

    private val alert = 21
    private val applicationData = 23

    /** What one connection looked like when its inbound stream stopped. */
    private class Ending {
        var pipe = "nothing"           // what a caller holding only the pipe saw: a clean end, or a fault
        var sessionClosed = false      // TlsSession.closed, which the pipe does not carry
        var kernelRx = false
        var plaintext = 0              // request bytes that arrived, so a vacuous run shows

        override fun toString() = "pipe=$pipe sessionClosed=$sessionClosed kernelRx=$kernelRx plaintext=$plaintext"
    }

    private fun defaults() = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)

    // RX is programmed at the same handoff as TX, and refused on its own.
    private fun kernelRx() = TlsOptions(
        certificatePath = TestCertificate.certificate,
        keyPath = TestCertificate.key,
        kernelTx = true,
        kernelRx = true,
    )

    private fun skipWithoutKernelTls(): Boolean {
        if (kernelTlsAvailable()) {
            return false
        }

        println("skipped: the tls kernel module is not loaded (sudo modprobe tls)")
        return true
    }

    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = Clock.monotonicMs() + timeoutMs
        while (Clock.monotonicMs() < deadline) {
            if (condition()) {
                return true
            }

            sleepMs(5)
        }

        return condition()
    }

    // Reads until the blank line that ends the head; false when the stream stopped first.
    private suspend fun readHead(input: PipeReader, request: StringBuilder, ending: Ending): Boolean {
        while (true) {
            val result = input.read()
            request.append(result.buffer.toByteArray().decodeToString())
            ending.plaintext += result.buffer.length.toInt()
            input.advanceTo(result.buffer.length)

            if (request.contains("\r\n\r\n")) {
                return true
            }

            if (result.isCompleted) {
                ending.pipe = "clean-eof"
                return false
            }
        }
    }

    private suspend fun readToEnd(input: PipeReader, ending: Ending) {
        while (true) {
            val result = input.read()
            ending.plaintext += result.buffer.length.toInt()
            input.advanceTo(result.buffer.length)

            if (result.isCompleted) {
                ending.pipe = "clean-eof"
                return
            }
        }
    }

    // Reports under the request's path how the inbound stream stopped, through the pipe and in the session. It answers
    // before reading on, which tells the client the server is past its handshake: a close before it races the handoff.
    private suspend fun serveEnding(
        conn: TcpConnection,
        service: TlsService,
        reports: Map<String, AtomicReference<Ending?>>,
    ) {
        val session = service.accept(conn)
        val pipe = TlsDuplexPipe(conn, session)
        val ending = Ending()
        val request = StringBuilder()

        try {
            try {
                if (readHead(pipe.input, request, ending)) {
                    pipe.output.write(response)
                    pipe.output.flush()
                    readToEnd(pipe.input, ending)
                }
            } catch (e: IOException) {
                ending.pipe = "fault: ${e.message}"
            }

            ending.sessionClosed = session.closed
            ending.kernelRx = session.kernelRx
        } finally {
            pipe.close()

            request.split(' ').getOrNull(1)?.let { path ->
                reports[path]?.store(ending)
            }
        }
    }

    // One request and its answer, then close_notify and a FIN, or the FIN alone. Connected until the server reports: a
    // close while it still writes would send an RST, a third shape.
    private fun drive(port: Int, path: String, closeNotify: Boolean, report: AtomicReference<Ending?>): Ending {
        WireClient(port).use { client ->
            client.write("GET $path HTTP/1.1\r\nhost: localhost\r\n\r\n")

            val got = ByteArray(response.size)
            client.readFully(got)
            val answer = got.decodeToString()
            assertTrue(answer.contains(responseBody), "this test's server never answered $path: $answer")

            // The only difference between the two shapes on the wire.
            if (closeNotify) {
                client.closeNotify()
            }
            client.shutdownOutput()

            val reported = eventually(20_000) { report.load() != null }
            assertTrue(reported, "the server never reported how the stream at $path ended")
        }

        return report.load()!!
    }

    // Both closing shapes against one server, one connection after the other, so a failure belongs to its shape.
    private fun bothShapes(options: TlsOptions): Pair<Ending, Ending> {
        val ended = AtomicReference<Ending?>(null)
        val cut = AtomicReference<Ending?>(null)

        val reports = mapOf(endedPath to ended, cutPath to cut)
        val server = TlsServer(testConfig(), options) { conn, service ->
            serveEnding(conn, service, reports)
        }

        server.use {
            val first = drive(server.port, endedPath, closeNotify = true, ended)
            return first to drive(server.port, cutPath, closeNotify = false, cut)
        }
    }

    // The content type of every whole TLS record in [inbound] from [from]: TLS 1.2 keeps it in the clear.
    private fun recordTypes(inbound: ByteArray, from: Int = 0): List<Int> {
        val types = ArrayList<Int>()
        var at = from
        while (at + 5 <= inbound.size) {
            val length = ((inbound[at + 3].toInt() and 0xFF) shl 8) or (inbound[at + 4].toInt() and 0xFF)
            if (at + 5 + length > inbound.size) {
                break   // a partial record: the connection ended mid-frame
            }

            types.add(inbound[at].toInt() and 0xFF)
            at += 5 + length
        }

        return types
    }

    private fun describe(types: List<Int>) = if (types.isEmpty()) "no whole records at all" else "record types $types"

    @Test
    fun `control - close_notify and a bare FIN reach the session differently`() {
        val (ended, cut) = bothShapes(defaults())

        val requested = ended.plaintext >= requestBytes && cut.plaintext >= requestBytes
        assertTrue(requested, "a request never reached the pipe: $ended / $cut")

        // Otherwise the two shapes below are the same connection twice and prove nothing.
        assertTrue(ended.sessionClosed, "the peer sent close_notify and the session did not record it: $ended")
        assertTrue(!cut.sessionClosed, "nothing sent close_notify on the cut stream, yet the session saw one: $cut")
    }

    @Test
    fun `the pipe reports both endings alike and the session is what tells them apart`() {
        // By design: faulting the cut stream would fault every client that closes without close_notify, and a TLS
        // fault is reported (garbage after the handshake faults the reader). The session carries the difference.
        val (ended, cut) = bothShapes(defaults())

        val requested = ended.plaintext >= requestBytes && cut.plaintext >= requestBytes
        assertTrue(requested, "a request never reached the pipe: $ended / $cut")
        assertEquals("clean-eof", ended.pipe)
        assertEquals("clean-eof", cut.pipe)
        assertTrue(ended.sessionClosed && !cut.sessionClosed, "the session must tell them apart: $ended / $cut")
    }

    @Test
    fun `the server ends its own stream with close_notify`() {
        // Without it every response the server sends looks truncated to a client strict enough to check. TLS 1.2,
        // which leaves the alert's content type visible, so this is read off the records themselves.
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                val result = pipe.input.read()
                pipe.input.advanceTo(result.buffer.length)
                pipe.output.write(response)
                pipe.output.flush()
            } finally {
                pipe.close()   // the teardown whose close_notify is asserted
            }
        }

        TlsServer(testConfig(), defaults(), handler).use { server ->
            WireClient(server.port, version = BoringSsl.TLS1_2_VERSION).use { client ->
                client.write("GET /close HTTP/1.1\r\nhost: localhost\r\n\r\n")

                val answer = StringBuilder()
                val buffer = ByteArray(512)
                try {
                    while (true) {
                        val n = client.read(buffer)
                        if (n < 0) {
                            break
                        }
                        answer.append(buffer.decodeToString(0, n))
                    }
                } catch (e: IOException) {
                    // A stream the client finds broken still had its records captured.
                }

                assertTrue(answer.contains(responseBody), "never answered, so the teardown below is not this test's")

                val types = recordTypes(client.inbound)
                assertTrue(applicationData in types, "no application-data record arrived: ${describe(types)}")
                assertTrue(alert in types, "the server closed without a close_notify alert: ${describe(types)}")
            }
        }
    }

    @Test
    fun `a client's close_notify is answered with the server's own`() {
        // RFC 8446 6.1: each side sends close_notify before closing its write side, whichever closed first.
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                while (true) {
                    val result = pipe.input.read()
                    pipe.input.advanceTo(result.buffer.length)
                    if (result.isCompleted) {
                        break   // the client's close_notify
                    }
                }
            } finally {
                pipe.close()
            }
        }

        TlsServer(testConfig(), defaults(), handler).use { server ->
            WireClient(server.port, version = BoringSsl.TLS1_2_VERSION).use { client ->
                val handshake = client.handshakeInbound
                client.closeNotify()
                client.drainToEnd()

                val types = recordTypes(client.inbound, from = handshake)
                assertTrue(alert in types, "the server never answered the client's close_notify: ${describe(types)}")
            }
        }
    }

    @Test
    fun `under kernel RX - control - the kernel really took the read side`() {
        if (skipWithoutKernelTls()) {
            return
        }

        // The handoff is decided per connection, so kernelRx configured is not kernelRx done.
        val (ended, cut) = bothShapes(kernelRx())

        assertTrue(ended.kernelRx && cut.kernelRx, "the kTLS RX handoff did not happen: $ended / $cut")
        val requested = ended.plaintext >= requestBytes && cut.plaintext >= requestBytes
        assertTrue(requested, "a request never reached the pipe: $ended / $cut")
    }

    @Test
    fun `under kernel RX a peer's close_notify still reaches the session`() {
        if (skipWithoutKernelTls()) {
            return
        }

        // The kernel decrypts, so BoringSSL never sees the alert: the recv stops at it with EIO and the kernel keeps it
        // queued. TlsSession.closed has to read it from there, or every connection here reports itself truncated.
        val (ended, cut) = bothShapes(kernelRx())

        assertTrue(!cut.sessionClosed, "nothing sent close_notify on the cut stream: $cut")
        assertTrue(ended.sessionClosed, "close_notify under kTLS RX left TlsSession.closed false: $ended")
    }
}
