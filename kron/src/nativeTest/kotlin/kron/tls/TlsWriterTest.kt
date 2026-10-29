package kron.tls

import kron.Clock
import kron.PipeReader
import kron.PipeWriter
import kron.RingTimer
import kron.TcpConnection
import kron.sleepMs
import kron.testConfig
import kron.tls.TlsPipeContractTest.TlsServer
import kron.tls.TlsPipeContractTest.WireClient
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [TlsEncryptingPipeWriter]'s end of the [PipeWriter] contract with the connection's send still in flight: the state a
 * handler reaches when it gives a peer that stopped reading a deadline, and tears down anyway.
 */
class TlsWriterTest {
    private val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)
    private val end = "\r\n\r\n".encodeToByteArray()

    /** What the handler saw when it completed the writer or closed the pipe. */
    private class Outcome(val flushPending: Boolean, val staged: Long, val error: String)

    // A flush started apart from the handler, which goes on while it is in flight. Reactor thread only.
    private class Detached {
        var done = false
    }

    private fun detachedFlush(output: PipeWriter): Detached {
        val flush = Detached()
        suspend { output.flush() }.startCoroutine(Continuation(EmptyCoroutineContext) {
            flush.done = true
        })

        return flush
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

    private suspend fun readHead(input: PipeReader): Boolean {
        while (true) {
            val result = input.read()
            if (result.buffer.indexOf(end) >= 0) {
                input.advanceTo(result.buffer.length)
                return true
            }

            input.advanceTo(0, result.buffer.length)
            if (result.isCompleted) {
                return false
            }
        }
    }

    // Writes 256 KiB at a time to a peer that reads nothing, until a flush is still out after 2 s: the state under
    // test. How much a loopback pair absorbs first is the box's, so a flush that came back costs another chunk. Null
    // when 16 MiB drained and none stayed out. Waited on the ring, so the handler never leaves the reactor.
    private suspend fun parkAFlush(conn: TcpConnection, output: PipeWriter): Detached? {
        val chunk = ByteArray(256 * 1024)
        val timer = RingTimer(conn.reactor)

        repeat(64) {
            output.write(chunk)
            val flush = detachedFlush(output)

            var waitedMs = 0
            while (waitedMs < 2_000 && !flush.done) {
                timer.delay(10)
                waitedMs += 10
            }

            if (!flush.done) {
                return flush
            }
        }

        return null
    }

    // Asks for a response and then never reads a byte of it: the peer that leaves a server's flush in flight. A small
    // window, set before the connect so it is the one advertised, parks the send within a few megabytes. Connected
    // until the server reports: closing would end the send and dissolve the state.
    private fun requestAndStopReading(port: Int, report: AtomicReference<Outcome?>) {
        WireClient(port, receiveBuffer = 4096).use { client ->
            client.write("GET / HTTP/1.1\r\nHost: test\r\n\r\n")
            eventually(60_000) { report.load() != null }
        }
    }

    @Test
    fun `complete does not throw while the connection's flush is in flight`() {
        // complete is a notification, called from teardown: with plaintext staged and the connection's send still out
        // it must neither throw nor touch the slab the kernel is reading.
        val report = AtomicReference<Outcome?>(null)
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            try {
                if (readHead(pipe.input)) {
                    val parked = parkAFlush(conn, pipe.output)
                    if (parked == null) {
                        report.store(Outcome(false, 0, "the peer drained 16 MiB; no flush ever stayed in flight"))
                    } else {
                        // Staged and deliberately not flushed.
                        pipe.output.write("bye".encodeToByteArray())
                        val staged = pipe.output.unflushedBytes

                        // Nothing suspends from here to complete, and a flush completes only on this thread: what is
                        // read here is still true inside complete.
                        val flushPending = !parked.done
                        val error = try {
                            pipe.output.complete()
                            ""
                        } catch (e: Throwable) {
                            "${e::class.simpleName}: ${e.message}"
                        }

                        report.store(Outcome(flushPending, staged, error))
                    }
                }
            } catch (e: Throwable) {
                val broke = "the reproduction broke first: ${e::class.simpleName}: ${e.message}"
                report.compareAndSet(null, Outcome(false, 0, broke))
            } finally {
                pipe.close()
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            requestAndStopReading(server.port, report)
        }

        val outcome = assertNotNull(report.load(), "the handler never reached the complete under test")

        // Not vacuous: a flush really still in flight, and plaintext really staged.
        assertTrue(outcome.flushPending, "no flush was in flight when the writer was completed: ${outcome.error}")
        assertTrue(outcome.staged > 0, "nothing was staged, so complete had nothing to drop: ${outcome.staged} B")
        assertEquals("", outcome.error, "complete threw out of a teardown that may not fail")
    }

    @Test
    fun `closing the pipe does not throw while the connection's flush is in flight`() {
        // The handler flushed everything it wrote and gave up on the last flush: its teardown runs against the send it
        // left in flight, with nothing staged.
        val report = AtomicReference<Outcome?>(null)
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            val pipe = TlsDuplexPipe(conn, service.accept(conn))
            var closed = false
            try {
                if (readHead(pipe.input)) {
                    val parked = parkAFlush(conn, pipe.output)
                    if (parked == null) {
                        report.store(Outcome(false, 0, "the peer drained 16 MiB; no flush ever stayed in flight"))
                    } else {
                        val staged = pipe.output.unflushedBytes
                        val flushPending = !parked.done

                        closed = true
                        val error = try {
                            pipe.close()
                            ""
                        } catch (e: Throwable) {
                            "${e::class.simpleName}: ${e.message}"
                        }

                        report.store(Outcome(flushPending, staged, error))
                    }
                }
            } catch (e: Throwable) {
                val broke = "the reproduction broke first: ${e::class.simpleName}: ${e.message}"
                report.compareAndSet(null, Outcome(false, 0, broke))
            } finally {
                if (!closed) {
                    pipe.close()
                }
            }
        }

        TlsServer(testConfig(), options, handler).use { server ->
            requestAndStopReading(server.port, report)
        }

        val outcome = assertNotNull(report.load(), "the handler never reached the close under test")

        // Not vacuous either way: a flush really still in flight, and nothing staged.
        assertTrue(outcome.flushPending, "no flush was in flight when the pipe was closed: ${outcome.error}")
        assertEquals(0L, outcome.staged, "plaintext was staged")
        assertEquals("", outcome.error, "closing the pipe threw against a flush the handler left in flight")
    }
}
