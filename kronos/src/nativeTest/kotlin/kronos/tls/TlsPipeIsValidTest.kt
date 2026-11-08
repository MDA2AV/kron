package kronos.tls

import kronos.TcpConnection
import kronos.sleepMs
import kronos.testConfig
import kronos.tls.TlsPipeContractTest.TlsServer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

/** PipeIsValidTest through TLS: the decrypting reader, and the writer, encrypting or the kernel's (#23). */
class TlsPipeIsValidTest {
    private val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray()
    private val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray()
    private val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)

    // The pipe's answer, then each half's.
    private fun validity(pipe: TlsDuplexPipe) = "${pipe.isValid()} ${pipe.input.isValid()} ${pipe.output.isValid()}"

    @Test
    fun `a kept TLS pipe is not valid once the pooled connection serves the next client`() {
        // One proactor: the handlers run one after another on its thread.
        val firstConn = AtomicReference<TcpConnection?>(null)
        val kept = AtomicReference<TlsDuplexPipe?>(null)
        val seen = AtomicReference<List<String>>(emptyList())
        fun see(what: String) = seen.store(seen.load() + what)

        TlsServer(testConfig(), options) { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                val old = kept.load()
                if (old == null) {
                    firstConn.store(conn)
                    kept.store(pipe)
                } else {
                    // The kept pipe was closed, its session freed: asking reads the connection, nothing else.
                    see("same object: ${conn === firstConn.load()}")
                    see("kept: ${validity(old)}")
                }
                see("own: ${validity(pipe)}")

                val read = pipe.input.read()
                pipe.input.advanceTo(read.buffer.length)
                pipe.output.write(response)
                pipe.output.flush()
            } finally {
                pipe.close()
            }
        }.use { server ->
            repeat(2) {
                TlsTestClient(server.port).use { client ->
                    client.write(request)
                    val got = ByteArray(response.size)
                    client.readFully(got, 0, got.size)
                    assertEquals(response.decodeToString(), got.decodeToString())

                    // Through to the server's close_notify, so closing sends a FIN: with it unread the close is a
                    // reset, and a kernel without io_uring fix a68ed2df7213 (in 7.0) can miss a reset that follows
                    // data closely, which would keep the connection out of the pool.
                    val rest = ByteArray(256)
                    while (client.read(rest, 0, rest.size) >= 0) {
                        // the end of the session
                    }
                }

                // Time for the proactor to see the close and put the connection back in its pool.
                sleepMs(100)
            }
        }

        assertEquals(
            listOf("own: true true true", "same object: true", "kept: false false false", "own: true true true"),
            seen.load(),
        )
    }
}
