package kron.tls

import kron.Clock
import kron.IncrementalOptions
import kron.KronServer
import kron.Latch
import kron.Reactor
import kron.RingTimer
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions
import kron.TestThreads
import kron.kernelRelease
import kron.sleepMs
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TLS on BoringSSL both ways over each connection's own incremental buffer ring, read at the handler's own pace. */
class TlsIncrementalTest {
    private val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)

    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    private fun incrementalUnsupported(): Boolean {
        val parts = kernelRelease().split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (major > 6 || (major == 6 && minor >= 12)) {
            return false
        }

        println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
        return true
    }

    // Byte [i] of the upload, from its position, so a byte lost, repeated or out of place shows.
    private fun byteAt(i: Int): Byte = ((i * 131) xor (i ushr 11)).toByte()

    // Runs [block] on the reactor thread and waits for its answer.
    private fun <T> onReactor(reactor: Reactor, block: () -> T): T {
        val result = AtomicReference<T?>(null)
        val done = Latch(1)

        reactor.post {
            result.store(block())
            done.countDown()
        }

        assertTrue(done.await(10_000), "the reactor never ran the task")
        @Suppress("UNCHECKED_CAST")
        return result.load() as T
    }

    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = Clock.monotonicMs() + timeoutMs
        while (Clock.monotonicMs() < deadline) {
            if (condition()) {
                return true
            }

            sleepMs(10)
        }

        return condition()
    }

    @Test
    fun `a 4 MiB upload read slowly through the TLS pipe arrives intact - while a second TLS connection is served`() {
        if (incrementalUnsupported()) {
            return
        }

        val services = arrayOfNulls<TlsService>(1)
        val accepted = AtomicInt(0)
        val released = AtomicInt(0)
        val seen = AtomicReference<String?>(null)

        // The first connection: reads once, holds until released while its peer floods, then reads the rest with a
        // timer wait between reads, checking every byte. Answers "ok" once the whole upload arrived intact on a
        // connection that never closed.
        suspend fun slowReader(conn: TcpConnection, session: TlsSession) {
            val pipe = TlsDuplexPipe(conn, session)
            val timer = RingTimer(conn.reactor)
            var got = 0
            var reads = 0
            var wrong: String? = null
            var endedEarly = false

            try {
                while (got < UPLOAD) {
                    val result = pipe.input.read()
                    reads++

                    val bytes = result.buffer.toByteArray()
                    for (k in bytes.indices) {
                        if (wrong == null && bytes[k] != byteAt(got + k)) {
                            wrong = "byte ${got + k}"
                        }
                    }
                    got += bytes.size
                    pipe.input.advanceTo(result.buffer.length)

                    if (result.isCompleted || conn.isClosed) {
                        endedEarly = got < UPLOAD
                        break
                    }

                    if (reads == 1) {
                        while (released.load() == 0) {
                            timer.delay(5)
                        }
                    }
                    timer.delay(1)
                }

                val backend = if (session.kernelTx || session.kernelRx) "kernel TLS" else "BoringSSL both ways"
                seen.store("$got of $UPLOAD bytes in $reads reads on $backend, first wrong: $wrong, ended early: $endedEarly")

                val intact = got == UPLOAD && wrong == null && !endedEarly && backend == "BoringSSL both ways"
                pipe.output.write((if (intact) "ok" else "no").encodeToByteArray())
                pipe.output.flush()
                pipe.input.read()   // until the client is done
            } finally {
                pipe.close()
            }
        }

        // Every later connection: echoes each line.
        suspend fun echoLines(conn: TcpConnection, session: TlsSession) {
            val pipe = TlsDuplexPipe(conn, session)
            try {
                while (true) {
                    val result = pipe.input.read()
                    val end = result.buffer.indexOf('\n'.code.toByte())
                    if (end >= 0) {
                        pipe.output.write(result.buffer.toByteArray(0, (end + 1).toInt()))
                        pipe.output.flush()
                        pipe.input.advanceTo(end + 1)
                    } else {
                        pipe.input.advanceTo(0, result.buffer.length)
                    }

                    if (result.isCompleted) {
                        return
                    }
                }
            } finally {
                pipe.close()
            }
        }

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val first = accepted.addAndFetch(1) == 1
            val session = services[0]!!.accept(conn)
            if (first) {
                slowReader(conn, session)
            } else {
                echoLines(conn, session)
            }
        }

        // Four 4 KiB buffers per connection: a sixty-fourth of the upload, so the ring runs dry again and again.
        val config = ServerConfig(
            reactorCount = 1,
            ringEntries = 256,
            tcp = TcpOptions(port = 0),
            incremental = IncrementalOptions(maxConnections = 8, recvSlots = 4, recvBufferSize = 4096),
        )

        val ready = Latch(1)
        val server = KronServer(
            config,
            onStart = { reactor ->
                services[0] = TlsService.start(reactor, options)
                ready.countDown()
            },
            handler = handler,
        ).start()

        val threads = TestThreads()
        val uploaded = AtomicReference<String?>(null)
        val uploadDone = Latch(1)

        try {
            assertTrue(ready.await(10_000), "the reactor never started")
            val reactor = server.reactors[0]
            val port = reactor.listenPorts[0]

            // Its own thread: the write blocks for as long as the server holds back.
            threads.start {
                try {
                    TlsTestClient(port).use { client ->
                        client.write(ByteArray(UPLOAD) { byteAt(it) })

                        val answer = ByteArray(2)
                        client.readFully(answer, 0, answer.size)
                        uploaded.store(answer.decodeToString())
                    }
                } catch (e: Throwable) {
                    uploaded.store("failed: ${e.message}")
                } finally {
                    uploadDone.countDown()
                }
            }

            assertTrue(
                eventually(10_000) { onReactor(reactor) { reactor.tcp!!.starvedRecvs } > 0 },
                "the first connection's ring never ran dry, so it was never paused",
            )

            // Handshake and all, a second connection is served while the first waits on its dry ring.
            TlsTestClient(port).use { client ->
                val line = "served while the first waits\n".encodeToByteArray()
                client.write(line)

                val echoed = ByteArray(line.size)
                client.readFully(echoed, 0, echoed.size)
                assertEquals(line.decodeToString(), echoed.decodeToString())
            }
            assertEquals(0, released.load())

            released.store(1)
            assertTrue(uploadDone.await(60_000), "the upload never finished; the server saw: ${seen.load()}")
            assertEquals("ok", uploaded.load(), "the upload's answer; the server saw: ${seen.load()}")
        } finally {
            server.close()
            threads.joinAll()
        }
    }

    private companion object {
        const val UPLOAD = 4 * 1024 * 1024
    }
}
