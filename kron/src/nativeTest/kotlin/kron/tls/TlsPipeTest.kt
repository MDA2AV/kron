package kron.tls

import kron.KronServer
import kron.Latch
import kron.Memory
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions
import kron.TcpPipeWriter
import kron.TestThreads
import kron.sleepMs
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TlsDuplexPipe] end to end: a BoringSSL client against a reactor whose handler reads and writes through it. */
class TlsPipeTest {
    private val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)
    private val end = "\r\n\r\n".encodeToByteArray()
    private val request = "GET / HTTP/1.1\r\n\r\n".encodeToByteArray()
    private val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray()

    private fun withTlsServer(
        options: TlsOptions,
        handle: suspend (TcpConnection, TlsSession) -> Unit,
        body: (port: Int) -> Unit,
    ) {
        val services = arrayOfNulls<TlsService>(1)
        val ready = Latch(1)

        suspend fun serve(conn: TcpConnection) {
            val session = services[0]!!.accept(conn)
            handle(conn, session)
        }

        val server = KronServer(
            ServerConfig(reactorCount = 1, tcp = TcpOptions(port = 0)),
            onStart = { reactor ->
                services[0] = TlsService.start(reactor, options)
                ready.countDown()
            },
            handler = ::serve,
        ).start()

        try {
            assertTrue(ready.await(10_000), "the reactor never started")
            body(server.reactors[0].listenPorts[0])
        } finally {
            server.close()
        }
    }

    // Answers every "\r\n\r\n"-terminated request: what is consumed is only what was answered, and everything is
    // examined, so a partial request waits for its next record.
    private suspend fun answerRequests(pipe: TlsDuplexPipe) {
        while (true) {
            val result = pipe.input.read()

            var consumed = 0L
            while (true) {
                val at = result.buffer.indexOf(end, consumed)
                if (at < 0) {
                    break
                }

                consumed = at + end.size
                pipe.output.write(response)
            }

            if (pipe.output.unflushedBytes > 0) {
                pipe.output.flush()
            }

            pipe.input.advanceTo(consumed, result.buffer.length)
            if (result.isCompleted) {
                return
            }
        }
    }

    @Test
    fun `the TLS pipe waits across records for a whole request and answers pipelined ones`() {
        val handler: suspend (TcpConnection, TlsSession) -> Unit = { conn, session ->
            val pipe = TlsDuplexPipe(conn, session)
            try {
                answerRequests(pipe)
            } finally {
                pipe.close()
            }
        }

        withTlsServer(options, handler) { port ->
            TlsTestClient(port).use { client ->
                // One request in three records: a single response, once the last one lands.
                client.write(request, 0, 5)
                sleepMs(20)
                client.write(request, 5, 7)
                sleepMs(20)
                client.write(request, 12, request.size - 12)

                val one = ByteArray(response.size)
                client.readFully(one, 0, one.size)
                assertContentEquals(response, one)

                // Two in one write: two responses.
                client.write(request + request)
                val two = ByteArray(response.size * 2)
                client.readFully(two, 0, two.size)
                assertContentEquals(response + response, two)
            }
        }
    }

    @Test
    fun `a large response written through the pipe's buffer leaves in full and in order`() {
        val size = 1024 * 1024
        val body = ByteArray(size) { (it * 31 + 7).toByte() }

        val handler: suspend (TcpConnection, TlsSession) -> Unit = { conn, session ->
            val pipe = TlsDuplexPipe(conn, session)
            try {
                val result = pipe.input.read()
                pipe.input.advanceTo(result.buffer.length)

                // Through getBuffer and advance, 64 KiB at a time.
                var written = 0
                while (written < size) {
                    val chunk = minOf(64 * 1024, size - written)
                    val address = pipe.output.getBuffer(chunk)
                    Memory.copy(body, written, address, chunk)
                    pipe.output.advance(chunk)
                    written += chunk
                }

                pipe.output.flush()
                pipe.input.read()   // until the client is done
            } finally {
                pipe.close()
            }
        }

        withTlsServer(options, handler) { port ->
            TlsTestClient(port).use { client ->
                client.write("go")
                val got = ByteArray(size)
                client.readFully(got, 0, size)
                assertContentEquals(body, got)
            }
        }
    }

    @Test
    fun `reads and flushes from another thread are carried to the reactor`() {
        val threads = TestThreads()
        val trail = AtomicReference("")

        // Raw, so the handler always continues on the new thread.
        suspend fun hop(): Unit = suspendCoroutineUninterceptedOrReturn { cont ->
            threads.start {
                cont.resume(Unit)
            }
            COROUTINE_SUSPENDED
        }

        val handler: suspend (TcpConnection, TlsSession) -> Unit = { conn, session ->
            val pipe = TlsDuplexPipe(conn, session)
            try {
                val steps = StringBuilder()

                hop()
                steps.append(if (conn.reactor.onReactorThread) "R" else "o")
                val result = pipe.input.read()   // posted; resumes on the reactor
                steps.append(if (conn.reactor.onReactorThread) "R" else "o")
                pipe.input.advanceTo(result.buffer.length)

                hop()
                steps.append(if (conn.reactor.onReactorThread) "R" else "o")
                pipe.output.write(response)
                pipe.output.flush()   // posted: the session is the reactor's
                steps.append(if (conn.reactor.onReactorThread) "R" else "o")

                trail.store(steps.toString())
                pipe.input.read()   // until the client is done
            } finally {
                pipe.close()
            }
        }

        try {
            withTlsServer(options, handler) { port ->
                TlsTestClient(port).use { client ->
                    client.write(request)
                    val got = ByteArray(response.size)
                    client.readFully(got, 0, got.size)
                    assertContentEquals(response, got)
                }
            }
        } finally {
            threads.joinAll()
        }

        assertEquals("oRoR", trail.load(), "thread before and after read and flush (o = off, R = reactor)")
    }

    @Test
    fun `the peer's close_notify completes the read`() {
        val completed = AtomicInt(0)
        val handler: suspend (TcpConnection, TlsSession) -> Unit = { conn, session ->
            val pipe = TlsDuplexPipe(conn, session)
            try {
                while (true) {
                    val result = pipe.input.read()
                    pipe.input.advanceTo(result.buffer.length)
                    if (result.isCompleted) {
                        completed.store(1)
                        break
                    }
                }
            } finally {
                pipe.close()
            }
        }

        withTlsServer(options, handler) { port ->
            TlsTestClient(port).use { client ->
                client.write("bye")
            }   // close sends close_notify

            val deadline = kron.Clock.monotonicMs() + 5_000
            while (completed.load() == 0 && kron.Clock.monotonicMs() < deadline) {
                sleepMs(5)
            }
        }

        assertEquals(1, completed.load(), "the read never completed after the client's close_notify")
    }

    @Test
    fun `under kernel TX the pipe writes through the connection's own writer`() {
        if (!kernelTlsAvailable()) {
            println("skipped: the tls kernel module is not loaded (sudo modprobe tls)")
            return
        }

        val writerKind = AtomicReference("")
        val handler: suspend (TcpConnection, TlsSession) -> Unit = { conn, session ->
            val pipe = TlsDuplexPipe(conn, session)
            try {
                writerKind.store(if (pipe.output is TcpPipeWriter) "tcp" else "encrypting")
                answerRequests(pipe)
            } finally {
                pipe.close()
            }
        }

        val kernel = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key, kernelTx = true)
        withTlsServer(kernel, handler) { port ->
            TlsTestClient(port).use { client ->
                client.write(request)
                val got = ByteArray(response.size)
                client.readFully(got, 0, got.size)
                assertContentEquals(response, got)
            }
        }

        assertEquals("tcp", writerKind.load())
    }
}
