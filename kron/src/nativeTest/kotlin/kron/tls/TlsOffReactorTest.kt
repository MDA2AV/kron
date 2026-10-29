package kron.tls

import kron.Clock
import kron.IOException
import kron.Memory
import kron.RingTimer
import kron.TcpConnection
import kron.TestThreads
import kron.sleepMs
import kron.testConfig
import kron.tls.TlsPipeContractTest.TlsServer
import kron.tls.TlsPipeContractTest.WireClient
import platform.posix.sched_yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A TLS handler that writes from a thread other than its reactor, while the reactor decrypts on the same session. */
class TlsOffReactorTest {
    private val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)

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

    // Runs [block] as a coroutine on this thread and waits for it: a writer thread's flush, which the reactor carries
    // out and resumes from its own thread.
    private fun <T> blockOn(timeoutMs: Long, block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        block.startCoroutine(Continuation(EmptyCoroutineContext) {
            result.store(it)
        })

        val deadline = Clock.monotonicMs() + timeoutMs
        while (true) {
            val done = result.load()
            if (done != null) {
                return done.getOrThrow()
            }

            if (Clock.monotonicMs() >= deadline) {
                throw IOException("no answer within $timeoutMs ms")
            }

            sched_yield()
        }
    }

    // Waits on the ring until [condition] holds, at most [timeoutMs]: a handler waiting for a thread it started.
    private suspend fun waitFor(conn: TcpConnection, timeoutMs: Long, condition: () -> Boolean): Boolean {
        val timer = RingTimer(conn.reactor)
        var waitedMs = 0L
        while (!condition()) {
            if (waitedMs >= timeoutMs) {
                return false
            }

            timer.delay(5)
            waitedMs += 5
        }

        return true
    }

    private fun append(list: AtomicReference<String>, text: String) {
        while (true) {
            val current = list.load()
            if (list.compareAndSet(current, "$current$text; ")) {
                return
            }
        }
    }

    @Test
    fun `a flush from another thread while the reactor decrypts keeps the session intact`() {
        val total = 16 * 1024 * 1024
        val chunk = 1024
        val pattern = ByteArray(251 + chunk) { (it % 251).toByte() }

        val threads = TestThreads()
        val faults = AtomicReference("")
        val handlerDone = AtomicInt(0)

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            val output = pipe.output
            val stop = AtomicInt(0)
            val writerDone = AtomicInt(0)
            var writing = false

            try {
                threads.start {
                    try {
                        var sent = 0L
                        while (sent < total && stop.load() == 0) {
                            Memory.copy(pattern, (sent % 251).toInt(), output.getBuffer(chunk), chunk)
                            output.advance(chunk)
                            blockOn(10_000) {
                                output.flush()
                            }
                            sent += chunk
                        }
                    } catch (e: Throwable) {
                        append(faults, "writer: ${e::class.simpleName}: ${e.message}")
                    }

                    writerDone.store(1)
                }
                writing = true

                while (true) {
                    val result = pipe.input.read()
                    pipe.input.advanceTo(result.buffer.length)
                    if (result.isCompleted) {
                        break
                    }
                }
            } catch (e: Throwable) {
                append(faults, "reader: ${e::class.simpleName}: ${e.message}")
            } finally {
                // The writer must be out of the session before it is freed under it.
                stop.store(1)
                if (writing && !waitFor(conn, 30_000) { writerDone.load() == 1 }) {
                    append(faults, "the writer never stopped")
                }

                pipe.close()
                handlerDone.store(1)
            }
        }

        var received = 0L
        var clientFault: String? = null
        try {
            TlsServer(testConfig(), options, handler).use { server ->
                WireClient(server.port).use { client ->
                    val up = ByteArray(16 * 1024)
                    val buffer = ByteArray(64 * 1024)
                    var sentUp = 0L

                    try {
                        while (received < total && clientFault == null) {
                            // Keeps the reactor decrypting while the writer encrypts: 16 KiB up per 64 KiB down.
                            if (sentUp * 4 <= received) {
                                client.write(up)
                                sentUp += up.size
                            }

                            val n = client.read(buffer)
                            if (n < 0) {
                                clientFault = "the server closed the connection"
                                break
                            }

                            for (i in 0 until n) {
                                if (buffer[i] != ((received + i) % 251).toByte()) {
                                    clientFault = "byte ${received + i} arrived corrupted"
                                    break
                                }
                            }
                            received += n
                        }
                    } catch (e: IOException) {
                        clientFault = "${e::class.simpleName}: ${e.message}"
                    }

                    if (clientFault == null) {
                        client.closeNotify()
                    }

                    val finished = eventually(10_000) { handlerDone.load() == 1 }
                    assertTrue(finished, "the handler never finished after the client's close_notify")
                }
            }
        } finally {
            threads.joinAll()
        }

        val server = faults.load().ifEmpty { "ok" }
        assertTrue(
            clientFault == null && faults.load().isEmpty(),
            "received $received of $total bytes; client: ${clientFault ?: "ok"}; server: $server",
        )
    }

    @Test
    fun `a session write from another thread is refused rather than racing the reactor`() {
        val threads = TestThreads()
        val outcome = AtomicReference<String?>(null)

        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val session = service.accept(conn)
            val done = AtomicInt(0)
            try {
                threads.start {
                    outcome.store(
                        try {
                            session.write(conn, "written from another thread".encodeToByteArray())
                            "no exception"
                        } catch (e: Throwable) {
                            e::class.simpleName ?: "an exception"
                        },
                    )
                    done.store(1)
                }

                waitFor(conn, 10_000) { done.load() == 1 }   // the thread is out of the session before it is freed
            } finally {
                session.close()
            }
        }

        try {
            TlsServer(testConfig(), options, handler).use { server ->
                TlsTestClient(server.port).use { _ ->
                    assertTrue(eventually(10_000) { outcome.load() != null }, "the handler never reported")
                }
            }
        } finally {
            threads.joinAll()
        }

        assertEquals("IllegalStateException", outcome.load())
    }
}
