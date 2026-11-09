package kronos.tls

import kronos.TcpConnection
import kronos.sleepMs
import kronos.testConfig
import kronos.tls.TlsPipeContractTest.TlsServer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * StopParkedTest's calls from another thread after a stop, through TlsDuplexPipe (#29): the decrypting reader, and the
 * encrypting writer, which kTLS TX would replace with the TCP pipe's.
 */
class TlsStopParkedTest {
    private val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)

    // Starts [block] on this thread; what it returned, once it has.
    private fun <T> callHere(block: suspend () -> T): Result<T>? {
        val result = AtomicReference<Result<T>?>(null)
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result.store(it) })
        return result.load()
    }

    @Test
    fun `a TLS pipe's read and flush from another thread after the stop return completed`() {
        val held = AtomicReference<TcpConnection?>(null)
        val kept = AtomicReference<TlsDuplexPipe?>(null)
        val waiting = AtomicReference<Continuation<Unit>?>(null)

        val server = TlsServer(testConfig(), options) { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                held.store(conn)
                kept.store(pipe)
                suspendCoroutine<Unit> { waiting.store(it) }   // waiting on something outside kronos
            } finally {
                pipe.close()
            }
        }

        val client = TlsTestClient(server.port)
        try {
            var spins = 0
            while (waiting.load() == null && spins++ < 5_000) {
                sleepMs(1)
            }
            val handler = assertNotNull(waiting.load(), "the handshake never finished")

            server.close()   // stops the proactor and joins its thread
            val pipe = kept.load()!!

            val read = assertNotNull(callHere { pipe.input.read() }, "the pipe's read never returned").getOrThrow()
            assertTrue(read.isCompleted)

            pipe.output.write("x".encodeToByteArray())
            val flushed = assertNotNull(callHere { pipe.output.flush() }, "the pipe's flush never returned")
                .getOrThrow()
            assertTrue(flushed.isCompleted)
            assertEquals(0L, pipe.output.unflushedBytes)

            // The handler returns here, on this thread, closing its pipe; its release frees the connection.
            handler.resume(Unit)
            assertEquals(0L, held.load()!!.writeBuffer, "the write slab was not freed once the handler let go")
        } finally {
            client.close()
        }
    }
}
