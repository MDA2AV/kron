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
import kron.PipeReader
import kron.Reactor
import kron.RecvItem
import kron.RingTimer
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions
import kron.TestSocket
import kron.boringssl.BIO
import kron.boringssl.BIO_ctrl_pending
import kron.boringssl.BIO_new
import kron.boringssl.BIO_read
import kron.boringssl.BIO_s_mem
import kron.boringssl.BIO_write
import kron.boringssl.ERR_clear_error
import kron.boringssl.SSL
import kron.boringssl.SSL_CTX
import kron.boringssl.SSL_CTX_free
import kron.boringssl.SSL_CTX_new
import kron.boringssl.SSL_CTX_set_max_proto_version
import kron.boringssl.SSL_CTX_set_min_proto_version
import kron.boringssl.SSL_ERROR_WANT_READ
import kron.boringssl.SSL_ERROR_ZERO_RETURN
import kron.boringssl.SSL_do_handshake
import kron.boringssl.SSL_free
import kron.boringssl.SSL_get_error
import kron.boringssl.SSL_new
import kron.boringssl.SSL_read
import kron.boringssl.SSL_set_bio
import kron.boringssl.SSL_set_connect_state
import kron.boringssl.SSL_shutdown
import kron.boringssl.SSL_write
import kron.boringssl.TLS_client_method
import kron.sleepMs
import kron.testConfig
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.IPPROTO_TCP
import platform.posix.TCP_NODELAY
import platform.posix.errno
import platform.posix.recv
import platform.posix.setsockopt
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TlsDuplexPipe]'s contract past one request and its answer: cut records, reads on demand, faults and ends. */
class TlsPipeContractTest {
    private val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)
    private val end = "\r\n\r\n".encodeToByteArray()
    private val request = "GET / HTTP/1.1\r\nHost: test\r\n\r\n".encodeToByteArray()
    private val response = "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\npipe-tls-ok".encodeToByteArray()

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

    // Answers only a whole head: a pipe that ended early, or returned the same partial head forever, gets no answer.
    private suspend fun answerWholeRequest(conn: TcpConnection, service: TlsService) {
        val pipe = TlsDuplexPipe(conn, service.accept(conn))
        try {
            while (true) {
                val result = pipe.input.read()
                if (result.buffer.indexOf(end) >= 0) {
                    pipe.input.advanceTo(result.buffer.length)
                    pipe.output.write(response)
                    pipe.output.flush()
                    return
                }

                pipe.input.advanceTo(0, result.buffer.length)   // nothing consumed, all examined: wait for more
                if (result.isCompleted) {
                    return
                }
            }
        } finally {
            pipe.close()
        }
    }

    // Where the head ends once it is all in, consuming nothing; -1 when the stream ended first.
    private suspend fun readHead(input: PipeReader): Long {
        while (true) {
            val result = input.read()
            val at = result.buffer.indexOf(end)
            if (at >= 0) {
                return at + end.size
            }

            input.advanceTo(0, result.buffer.length)
            if (result.isCompleted) {
                return -1
            }
        }
    }

    @Test
    fun `a TLS record cut into 1- 7- 64- and 1024-byte TCP writes is reassembled`() {
        // Cut on the wire, not into records: a partial record has to wait across recvs, as a quiet peer does.
        TlsServer(testConfig(), options, ::answerWholeRequest).use { server ->
            for (size in intArrayOf(1, 7, 64, 1024)) {
                WireClient(server.port, chunk = size).use { client ->
                    client.write(request)
                    val got = ByteArray(response.size)
                    client.readFully(got)
                    assertContentEquals(response, got, "$size-byte writes")
                }
            }
        }
    }

    @Test
    fun `a handler that leaves a body unread still closes its pipe and returns`() {
        // A rejected upload, answered from the head: closing the pipe has to return with the body still queued.
        val closed = AtomicInt(0)
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            var answered = false
            try {
                val head = readHead(pipe.input)
                if (head >= 0) {
                    pipe.input.advanceTo(head)
                    pipe.output.write("HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\nheaders-only".encodeToByteArray())
                    pipe.output.flush()
                    answered = true
                }
            } finally {
                pipe.close()
                if (answered) {
                    closed.store(1)
                }
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            TlsTestClient(server.port).use { client ->
                val length = 32 * 1024
                client.write("POST /upload HTTP/1.1\r\nhost: localhost\r\ncontent-length: $length\r\n\r\n")
                client.write(ByteArray(length))

                val got = ByteArray(256)
                val n = client.read(got, 0, got.size)
                assertTrue(n > 0 && got.decodeToString(0, n).contains("headers-only"), "the upload was not answered")
                assertTrue(eventually(20_000) { closed.load() == 1 }, "the handler never got past closing its pipe")
            }
        }
    }

    @Test
    fun `nothing is decrypted until the handler reads`() {
        // The body lands while the handler reads nothing: it waits as ciphertext where a pump would have decrypted it.
        val bodyBytes = 96 * 1024
        val report = AtomicReference<Pair<Long, Long>?>(null)

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                val head = readHead(pipe.input)
                if (head >= 0) {
                    pipe.input.advanceTo(head)   // the head only
                    pipe.output.write("HTTP/1.1 100 Continue\r\n\r\n".encodeToByteArray())
                    pipe.output.flush()

                    // The client sends the body on the 100 at once; on loopback it is here well within this.
                    RingTimer(conn.reactor).delay(500)

                    val waiting = pipe.input.tryRead()
                    val held = waiting?.buffer?.length ?: 0L
                    if (waiting != null) {
                        pipe.input.advanceTo(0)
                    }

                    var body = 0L
                    while (body < bodyBytes) {
                        val result = pipe.input.read()
                        body += result.buffer.length
                        pipe.input.advanceTo(result.buffer.length)
                        if (result.isCompleted) {
                            break
                        }
                    }

                    pipe.output.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndone".encodeToByteArray())
                    pipe.output.flush()
                    report.store(held to body)
                }
            } finally {
                pipe.close()
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            TlsTestClient(server.port).use { client ->
                client.write("POST /upload HTTP/1.1\r\ncontent-length: $bodyBytes\r\nexpect: 100-continue\r\n\r\n")

                val got = ByteArray(256)
                val n = client.read(got, 0, got.size)
                assertTrue(n > 0 && got.decodeToString(0, n).contains(" 100 "), "no 100 Continue")

                client.write(ByteArray(bodyBytes))
                val m = client.read(got, 0, got.size)
                assertTrue(m > 0 && got.decodeToString(0, m).contains("done"), "the upload was not answered")
            }

            assertTrue(eventually(10_000) { report.load() != null }, "the handler never reported")
        }

        val (held, body) = report.load()!!
        assertEquals(0L, held, "bytes of the body decrypted before the handler read any of it")
        assertEquals(bodyBytes.toLong(), body)
    }

    @Test
    fun `serving through the pipe never leaves the reactor thread`() {
        val checks = AtomicInt(0)
        val drift = AtomicReference("")

        fun observe(conn: TcpConnection, where: String) {
            checks.addAndFetch(1)
            if (!conn.reactor.onReactorThread) {
                drift.store(drift.load() + "off the reactor $where; ")
            }
        }

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val session = service.accept(conn)
            observe(conn, "after accept")

            val pipe = TlsDuplexPipe(conn, session)
            try {
                val result = pipe.input.read()
                observe(conn, "after read")
                pipe.input.advanceTo(result.buffer.length)

                pipe.output.write(response)
                pipe.output.flush()
                observe(conn, "after flush")
            } finally {
                pipe.close()
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            TlsTestClient(server.port).use { client ->
                client.write(request)
                val got = ByteArray(response.size)
                client.readFully(got, 0, got.size)
                assertContentEquals(response, got)
            }

            // A handler that never ran would leave no drift and pass vacuously.
            assertTrue(eventually(5_000) { checks.load() == 3 }, "expected three observations, got ${checks.load()}")
        }

        assertEquals("", drift.load())
    }

    @Test
    fun `a cancel does not wake a parked read - the data does and the read reports the cancel on the reactor`() {
        // Posted, as everything but read is the reactor's. It only sets the flag, which the read reports when it wakes.
        val reader = AtomicReference<PipeReader?>(null)
        val outcome = AtomicReference<String?>(null)

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                reader.store(pipe.input)
                val result = pipe.input.read()
                val thread = if (conn.reactor.onReactorThread) "reactor" else "off the reactor"
                val data = result.buffer.toByteArray().decodeToString()
                outcome.store("canceled=${result.isCanceled} data=$data on $thread")
                pipe.input.advanceTo(result.buffer.length)
            } finally {
                pipe.close()
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            TlsTestClient(server.port).use { client ->
                assertTrue(eventually(5_000) { reader.load() != null }, "the handler never read")

                // Runs on the reactor's next pass, after the turn in which the read parked.
                val canceled = AtomicInt(0)
                server.reactor.post {
                    reader.load()!!.cancelPendingRead()
                    canceled.store(1)
                }
                assertTrue(eventually(5_000) { canceled.load() == 1 }, "the reactor never ran the cancel")

                sleepMs(200)
                assertNull(outcome.load(), "the cancel woke the parked read")

                client.write("hello")
                assertTrue(eventually(10_000) { outcome.load() != null }, "the handler's read never completed")
            }
        }

        assertEquals("canceled=true data=hello on reactor", outcome.load())
    }

    @Test
    fun `garbage after the handshake faults the reader rather than ending it cleanly`() {
        // A protocol error reaches the reader as one: a clean end would make a corrupt stream look like a polite close.
        val reading = AtomicInt(0)
        val reason = AtomicReference<String?>(null)
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            reading.store(1)
            try {
                while (true) {
                    val result = pipe.input.read()
                    pipe.input.advanceTo(result.buffer.length)
                    if (result.isCompleted) {
                        reason.store("a clean end")
                        break
                    }
                }
            } catch (e: IOException) {
                reason.store("the reader threw: ${e.message}")
            } finally {
                pipe.close()
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            WireClient(server.port).use { client ->
                // Sent with the client's Finished, the garbage would fail the handshake's own decrypt instead.
                assertTrue(eventually(5_000) { reading.load() == 1 }, "the handshake never completed")

                // Under the session, straight onto the socket: a record header no TLS 1.3 peer accepts, then noise.
                client.writeRaw(byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x10) + ByteArray(16) { 0xAB.toByte() })

                // Connected until the server has read it, so what it reports is the record and not the disconnect.
                assertTrue(eventually(10_000) { reason.load() != null }, "the reader observed nothing")
            }
        }

        val got = reason.load()!!
        assertTrue(got.startsWith("the reader threw: TLS decrypt failed"), "expected a decrypt fault, got: $got")
    }

    @Test
    fun `one-byte records share a segment instead of taking one each`() {
        // The peer picks the record size: a segment per record would hold far more than the peer sent.
        val records = 4_000
        val held = AtomicReference<Pair<Long, Int>?>(null)

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                // Examines everything and consumes nothing, to the end of the stream.
                while (true) {
                    val result = pipe.input.read()
                    if (result.isCompleted) {
                        held.store(result.buffer.length to result.buffer.segmentCount)
                        pipe.input.advanceTo(result.buffer.length)
                        break
                    }

                    pipe.input.advanceTo(0, result.buffer.length)
                }
            } finally {
                pipe.close()
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            TlsTestClient(server.port).use { client ->
                val one = "x".encodeToByteArray()
                repeat(records) {
                    client.write(one)   // one SSL_write, one record
                }
            }   // close sends close_notify

            assertTrue(eventually(30_000) { held.load() != null }, "the handler never reached the end of the stream")
        }

        val (bytes, segments) = held.load()!!
        assertEquals(records.toLong(), bytes)
        assertTrue(segments <= 8, "$records one-byte records took $segments segments")
    }

    @Test
    fun `a recv-queue overflow ends the read with an error and not a clean end`() {
        // The overflow dropped ciphertext: a truncation, which TLS must not report as a clean end.
        val parked = AtomicReference<TcpConnection?>(null)
        val gate = AtomicInt(0)
        val outcome = AtomicReference<String?>(null)

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                val first = pipe.input.read()
                pipe.input.advanceTo(first.buffer.length)

                // Reads nothing until the test opens the gate.
                parked.store(conn)
                val timer = RingTimer(conn.reactor)
                while (gate.load() == 0) {
                    timer.delay(10)
                }

                try {
                    while (true) {
                        val result = pipe.input.read()
                        pipe.input.advanceTo(result.buffer.length)
                        if (result.isCompleted) {
                            outcome.store("a clean end")
                            break
                        }
                    }
                } catch (e: IOException) {
                    outcome.store("an error: ${e.message}")
                }
            } finally {
                pipe.close()
            }
        }

        val config = ServerConfig(
            reactorCount = 1,
            ringEntries = 256,
            recvBufferSize = 64,
            recvSlots = 256,
            tcp = TcpOptions(port = 0, writeSlabSize = 16 * 1024, poolMax = 8, recvQueueEntries = 16),
        )

        TlsServer(config, options, handler).use { server ->
            TlsTestClient(server.port).use { client ->
                client.write("hi")
                assertTrue(eventually(10_000) { parked.load() != null }, "the handler never parked")
                val conn = parked.load()!!

                client.write(ByteArray(4096))   // 64-byte buffers: 65 deliveries into a 16-entry queue
                assertTrue(eventually(10_000) { conn.recvOverflowed }, "the queue never overflowed")
                gate.store(1)

                assertTrue(eventually(10_000) { outcome.load() != null }, "the handler never finished reading")
            }
        }

        val got = outcome.load()!!
        assertTrue(got.startsWith("an error"), "the overflow reached the handler as $got")
    }

    @Test
    fun `closing a session twice is a no-op`() {
        // Teardown paths can legitimately reach close twice; the second must not free the SSL again.
        val served = AtomicInt(0)
        val secondClose = AtomicReference<String?>(null)
        val answer = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray()

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val session = service.accept(conn)
            var probed = false
            try {
                // One request and its answer through the session itself, so it is known good before its afterlife.
                val carry = StringBuilder()
                fun take(count: Int) {
                    for (i in 0 until count) {
                        carry.append(Memory.getByte(session.plaintext + i).toInt().toChar())
                    }
                }

                take(session.drainPlaintext())
                val item = RecvItem()
                while (!carry.contains("\r\n\r\n")) {
                    val snapshot = conn.read()
                    while (conn.tryGetItem(snapshot, item)) {
                        if (item.hasBuffer) {
                            take(session.decrypt(item.address, item.length))
                        }
                        conn.returnBuffer(item)
                    }

                    if (snapshot.isClosed || session.closed) {
                        break
                    }
                }

                if (carry.contains("\r\n\r\n")) {
                    session.write(conn, answer)
                    conn.flush()
                    served.store(1)

                    session.close()
                    probed = true
                    val thrown = runCatching { session.close() }.exceptionOrNull()
                    secondClose.store(thrown?.let { it::class.simpleName } ?: "none")
                }
            } finally {
                if (!probed) {
                    session.close()
                }
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            TlsTestClient(server.port).use { client ->
                client.write(request)
                val got = ByteArray(answer.size)
                client.readFully(got, 0, got.size)
                assertContentEquals(answer, got)
            }

            assertTrue(eventually(5_000) { secondClose.load() != null }, "the second close never ran")
        }

        assertEquals(1, served.load(), "the session never served a request")
        assertEquals("none", secondClose.load())
    }

    /** One reactor serving TLS on a port the kernel picks: [handler] gets each connection and the reactor's service. */
    internal class TlsServer(
        config: ServerConfig,
        options: TlsOptions,
        handler: suspend (TcpConnection, TlsService) -> Unit,
    ) : AutoCloseable {
        private val ready = Latch(1)
        private var service: TlsService? = null   // the reactor's, set before its first accept

        private val server = KronServer(
            config,
            onStart = { reactor ->
                service = TlsService.start(reactor, options)
                ready.countDown()
            },
            handler = { conn ->
                handler(conn, service!!)
            },
        ).start()

        init {
            check(ready.await(10_000)) { "the reactor never started" }
        }

        val reactor: Reactor get() = server.reactors[0]
        val port: Int = server.reactors[0].listenPorts[0]

        override fun close() = server.close()
    }

    /**
     * A BoringSSL client over memory BIOs, so a test owns the wire under the session: [corked] holds everything
     * after the ClientHello until [uncork], [chunk] cuts every send into sends of that many bytes, and [inbound]
     * keeps every byte the server sent. It trusts any certificate, and [close] sends nothing: [closeNotify] and
     * [shutdownOutput] are the test's to choose. A failure or a receive timeout throws [IOException].
     */
    internal class WireClient(
        port: Int,
        version: Short = BoringSsl.TLS1_3_VERSION,
        receiveBuffer: Int = 0,
        private val chunk: Int = 0,
        private val corked: Boolean = false,
        receiveTimeoutMs: Int = 10_000,
    ) : AutoCloseable {
        val socket = TestSocket()

        private val ctx: CPointer<SSL_CTX>
        private val ssl: CPointer<SSL>
        private val rbio: CPointer<BIO>
        private val wbio: CPointer<BIO>

        private val held = Bytes()
        private val tap = Bytes()
        private val scratch = ByteArray(64 * 1024)
        private var helloSent = false

        /** How many bytes the server had sent when the handshake completed. */
        val handshakeInbound: Int

        init {
            if (receiveBuffer > 0) {
                socket.setReceiveBuffer(receiveBuffer)   // before connect, so it is the window advertised
            }
            socket.setReceiveTimeout(receiveTimeoutMs)

            // Every send leaves as the test cut it, not coalesced behind an unacknowledged one.
            memScoped {
                val one = alloc<IntVar>()
                one.value = 1
                setsockopt(socket.fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
            }
            socket.connect(port)

            ctx = SSL_CTX_new(TLS_client_method()) ?: error("SSL_CTX_new failed")
            SSL_CTX_set_min_proto_version(ctx, version.toUShort())
            SSL_CTX_set_max_proto_version(ctx, version.toUShort())

            ssl = SSL_new(ctx) ?: error("SSL_new failed")
            rbio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
            wbio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
            SSL_set_bio(ssl, rbio, wbio)   // the SSL owns both from here
            SSL_set_connect_state(ssl)

            try {
                handshake()
            } catch (e: Throwable) {
                close()
                throw e
            }

            handshakeInbound = tap.size
        }

        /** Bytes written since the ClientHello and still held by the cork. */
        val heldBytes: Int get() = held.size

        /** Every byte the server sent so far, as it came off the wire. */
        val inbound: ByteArray get() = tap.toByteArray()

        private fun handshake() {
            while (true) {
                ERR_clear_error()
                val rc = SSL_do_handshake(ssl)
                pumpOut()
                if (rc == 1) {
                    return
                }

                if (SSL_get_error(ssl, rc) != SSL_ERROR_WANT_READ) {
                    throw IOException("TLS handshake failed: ${BoringSsl.lastError()}")
                }
                if (fill() < 0) {
                    throw IOException("the server closed the connection during the handshake")
                }
            }
        }

        /** Encrypts [length] bytes, one record per 16 KiB, and sends them - or holds them, corked. */
        fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            if (length == 0) {
                return
            }

            ERR_clear_error()
            val n = bytes.usePinned { SSL_write(ssl, it.addressOf(offset), length) }
            if (n != length) {
                throw IOException("TLS write failed: ${BoringSsl.lastError()}")
            }

            pumpOut()
        }

        fun write(text: String) = write(text.encodeToByteArray())

        /** Up to [length] bytes of plaintext, waiting for them; -1 once the server ended its stream, either way. */
        fun read(into: ByteArray, offset: Int = 0, length: Int = into.size - offset): Int {
            while (true) {
                ERR_clear_error()
                val n = into.usePinned { SSL_read(ssl, it.addressOf(offset), length) }
                if (n > 0) {
                    return n
                }

                when (SSL_get_error(ssl, n)) {
                    SSL_ERROR_ZERO_RETURN -> return -1
                    SSL_ERROR_WANT_READ -> if (fill() < 0) {
                        return -1
                    }
                    else -> throw IOException("TLS read failed: ${BoringSsl.lastError()}")
                }
            }
        }

        fun readFully(into: ByteArray, offset: Int = 0, length: Int = into.size - offset) {
            var got = 0
            while (got < length) {
                val n = read(into, offset + got, length - got)
                if (n < 0) {
                    throw IOException("the stream ended after $got of $length bytes")
                }

                got += n
            }
        }

        /** Sends close_notify; corked, it is held like the rest. */
        fun closeNotify() {
            ERR_clear_error()
            SSL_shutdown(ssl)
            pumpOut()
        }

        /** A bare FIN: no close_notify ahead of it. */
        fun shutdownOutput() = socket.shutdownOutput()

        /** Straight onto the socket, under the session. */
        fun writeRaw(bytes: ByteArray) = socket.write(bytes)

        /** What the server still sends, undecrypted, into [inbound] until it ends or resets the stream. */
        fun drainToEnd() {
            try {
                while (fill() > 0) {
                    // into the tap
                }
            } catch (e: IOException) {
                // A reset ends it too; what arrived is in the tap.
            }
        }

        /** Sends everything the cork holds, in one send unless [chunk] cuts it. */
        fun uncork() {
            send(held.array, 0, held.size)
            held.drop(held.size)
        }

        /** Sends the first [count] held bytes and keeps holding the rest. */
        fun uncorkPart(count: Int) {
            send(held.array, 0, count)
            held.drop(count)
        }

        // What the session wrote: the first flight (the ClientHello) always goes out, the rest only when not corked.
        private fun pumpOut() {
            while (true) {
                val pending = BIO_ctrl_pending(wbio).toInt()
                if (pending <= 0) {
                    return
                }

                val bytes = ByteArray(pending)
                val n = bytes.usePinned { BIO_read(wbio, it.addressOf(0), pending) }
                if (n <= 0) {
                    return
                }

                if (corked && helloSent) {
                    held.append(bytes, 0, n)
                } else {
                    helloSent = true
                    send(bytes, 0, n)
                }
            }
        }

        private fun send(bytes: ByteArray, offset: Int, length: Int) {
            val piece = if (chunk > 0) chunk else maxOf(length, 1)
            var at = offset
            while (at < offset + length) {
                val n = minOf(piece, offset + length - at)
                socket.write(bytes.copyOfRange(at, at + n))
                at += n
            }
        }

        // Feeds what the socket has to the session, waiting for it: the bytes fed, or -1 at the end of the stream.
        private fun fill(): Int {
            while (true) {
                val n = scratch.usePinned { recv(socket.fd, it.addressOf(0), scratch.size.convert(), 0) }
                if (n > 0) {
                    val count = n.toInt()
                    tap.append(scratch, 0, count)
                    scratch.usePinned { BIO_write(rbio, it.addressOf(0), count) }
                    return count
                }

                if (n == 0L) {
                    return -1
                }

                val e = errno
                if (e == EINTR) {
                    continue
                }
                if (e == EAGAIN) {
                    throw IOException("nothing from the server within the receive timeout")
                }
                throw IOException("recv failed with errno $e")
            }
        }

        override fun close() {
            SSL_free(ssl)   // and both BIOs
            SSL_CTX_free(ctx)
            socket.close()
        }

        // A growable run of bytes.
        private class Bytes {
            var array = ByteArray(4096)
                private set
            var size = 0
                private set

            fun append(source: ByteArray, offset: Int, length: Int) {
                if (size + length > array.size) {
                    array = array.copyOf(maxOf(array.size * 2, size + length))
                }

                source.copyInto(array, size, offset, offset + length)
                size += length
            }

            fun drop(count: Int) {
                array.copyInto(array, 0, count, size)
                size -= count
            }

            fun toByteArray(): ByteArray = array.copyOf(size)
        }
    }
}
