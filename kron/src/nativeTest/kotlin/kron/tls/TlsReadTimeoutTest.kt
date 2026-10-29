package kron.tls

import kron.Clock
import kron.IOException
import kron.RingTimer
import kron.TcpConnection
import kron.TcpOptions
import kron.sleepMs
import kron.testConfig
import kron.tls.TlsPipeContractTest.TlsServer
import kron.tls.TlsPipeContractTest.WireClient
import kotlin.concurrent.atomics.AtomicInt
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The read timeout through TLS, on BoringSSL and the kernel, and a handler letting go with its send in flight. */
class TlsReadTimeoutTest {
    // Swept in ~250 ms steps: a silent peer goes between 500 and 750 ms after its read parked.
    private val readTimeoutMs = 500L

    private val request = "GET / HTTP/1.1\r\n\r\n".encodeToByteArray()

    private fun options(kernel: Boolean) = TlsOptions(
        certificatePath = TestCertificate.certificate,
        keyPath = TestCertificate.key,
        kernelTx = kernel,
        kernelRx = kernel,
    )

    private fun config(readTimeoutMs: Long) =
        testConfig(TcpOptions(port = 0, readTimeoutMs = readTimeoutMs), recvSlots = 256, recvBufferSize = 4096)

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

    // Starts [block] apart from the handler, which goes on while it waits.
    private fun detached(block: suspend () -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) {})
    }

    private fun busyHandlerAnswers(kernel: Boolean) {
        val body = "slow-but-here"
        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".encodeToByteArray()

        // Busy for four read timeouts with no read parked: the sweep has no peer to time out.
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                val result = pipe.input.read()
                pipe.input.advanceTo(result.buffer.length)

                RingTimer(conn.reactor).delay(4 * readTimeoutMs)

                pipe.output.write(response)
                pipe.output.flush()
            } finally {
                pipe.close()
            }
        }

        TlsServer(config(readTimeoutMs), options(kernel), handler).use { server ->
            TlsTestClient(server.port).use { client ->
                client.write(request)
                val got = ByteArray(response.size)
                client.readFully(got, 0, got.size)
                assertContentEquals(response, got)
            }
        }
    }

    private fun silentPeerClosed(kernel: Boolean) {
        val woke = AtomicInt(0)   // 1: the read ended with the stream, 2: it woke to something else

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                val result = pipe.input.read()
                woke.store(if (result.isCompleted && result.buffer.isEmpty) 1 else 2)
                pipe.input.advanceTo(result.buffer.length)
            } finally {
                pipe.close()
            }
        }

        TlsServer(config(readTimeoutMs), options(kernel), handler).use { server ->
            TlsTestClient(server.port).use { _ ->
                val ended = eventually(5_000) { woke.load() != 0 }
                assertTrue(ended, "the handler's read was never ended: nothing timed out the silent peer")
                assertEquals(1, woke.load(), "the handler woke, but not to the end of the stream")
            }
        }
    }

    // Writes more than the peer will take, gives up on the flush after 300 ms and lets go with the send in flight. The
    // teardown close_notify is a raw send: inside a send the ring still has, it would split a record, so it waits.
    private fun letGoMidFlush(parkRead: Boolean) {
        val total = 16 * 1024 * 1024
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                val result = pipe.input.read()
                pipe.input.advanceTo(result.buffer.length)
                if (parkRead) {
                    detached {
                        pipe.input.read()   // a next request that never comes
                    }
                }

                val chunk = ByteArray(64 * 1024)
                var written = 0
                while (written < total) {
                    pipe.output.write(chunk)
                    written += chunk.size
                }

                detached {
                    pipe.output.flush()
                }
                RingTimer(conn.reactor).delay(300)
            } finally {
                pipe.close()
            }
        }

        TlsServer(config(readTimeoutMs = 10_000), options(kernel = false), handler).use { server ->
            WireClient(server.port, receiveBuffer = 4096).use { client ->
                client.write(request)
                if (!parkRead) {
                    client.closeNotify()   // ours first; the server never reads it
                }
                sleepMs(1_500)   // not reading: the send is still in flight when the handler lets go

                var got = 0L
                var end = "the end of the stream"
                val buffer = ByteArray(256 * 1024)
                try {
                    while (true) {
                        val n = client.read(buffer)
                        if (n < 0) {
                            break
                        }
                        got += n
                    }
                } catch (e: IOException) {
                    end = e.message ?: "IOException"
                }

                assertEquals(total.toLong(), got, "received $got of $total bytes, then $end")
            }
        }
    }

    @Test
    fun `a handler busy for longer than the read timeout still answers`() {
        busyHandlerAnswers(kernel = false)
    }

    @Test
    fun `under kernel TLS a handler busy for longer than the read timeout still answers`() {
        if (skipWithoutKernelTls()) {
            return
        }

        busyHandlerAnswers(kernel = true)
    }

    @Test
    fun `a peer that never sends its request is closed by the read timeout`() {
        silentPeerClosed(kernel = false)
    }

    @Test
    fun `under kernel TLS a peer that never sends its request is closed by the read timeout`() {
        if (skipWithoutKernelTls()) {
            return
        }

        silentPeerClosed(kernel = true)
    }

    @Test
    fun `a response still sending when its handler lets go reaches a slow reader whole`() {
        letGoMidFlush(parkRead = false)
    }

    @Test
    fun `a response still sending when its handler lets go with a read parked reaches a slow reader whole`() {
        letGoMidFlush(parkRead = true)
    }
}
