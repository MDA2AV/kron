package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pipe reader against the PipeReader contract and the buffers it reads in place: what advanceTo's consumed and
 * examined mean, what it refuses, and a recv-queue overflow, which dropped data, ending a read with an error.
 */
class PipeReaderContractTest {
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

    // Serves one connection with [handler], which reports one line; the client sends [chunks], pausing between them,
    // and the report comes back.
    private fun reportFrom(
        chunks: List<String>,
        pauseMs: Long = 0,
        handler: suspend (TcpPipeReader, AtomicReference<String?>) -> Unit,
    ): String? {
        val report = AtomicReference<String?>(null)

        val serve: suspend (TcpConnection) -> Unit = { conn ->
            val reader = TcpPipeReader(conn)
            try {
                handler(reader, report)
            } catch (e: Throwable) {
                report.store("${e::class.simpleName}: ${e.message}")
            } finally {
                reader.complete()
            }
        }

        TestServer(testConfig(), serve).use { server ->
            server.connect().use { client ->
                for ((i, chunk) in chunks.withIndex()) {
                    if (i > 0) {
                        sleepMs(pauseMs)
                    }

                    client.write(chunk)
                }

                assertTrue(eventually(10_000) { report.load() != null }, "the handler never reported")
            }
        }

        return report.load()
    }

    // Reads until at least [length] bytes are held, examining all of them on the way.
    private suspend fun readAtLeast(reader: TcpPipeReader, length: Long): ReadResult {
        var result = reader.read()
        while (result.buffer.length < length && !result.isCompleted) {
            reader.advanceTo(0, result.buffer.length)
            result = reader.read()
        }

        return result
    }

    // "refused" for advanceTo's range check, and what happened instead otherwise.
    private fun refusal(advance: () -> Unit): String = try {
        advance()
        "accepted"
    } catch (e: IllegalArgumentException) {
        "refused"
    } catch (e: Throwable) {
        "${e::class.simpleName}: ${e.message}"
    }

    @Test
    fun `a read after everything was examined waits for new bytes and returns them after the held ones`() {
        // Having examined everything held, read() must not come back until there is something new: one that ignored
        // examined would hand back the same bytes at once, and a read-until-a-whole-frame loop would spin.
        val seen = reportFrom(listOf("AB", "C"), pauseMs = 300) { reader, report ->
            val first = reader.read()
            val firstSeen = first.buffer.text()
            reader.advanceTo(0, first.buffer.length)   // consumed nothing, examined everything

            val second = reader.read()   // "AB" again here would be a read that did not wait
            val secondSeen = second.buffer.text()
            reader.advanceTo(second.buffer.length)

            report.store("$firstSeen:$secondSeen")
        }

        // The unconsumed bytes come back with the new one after them, not dropped.
        assertEquals("AB:ABC", seen)
    }

    @Test
    fun `consuming part of one recv leaves the rest readable at once`() {
        // Head and body in one recv, and only the head consumed: what is held then starts mid-buffer, and the body,
        // never examined, has to be readable without waiting for bytes that already arrived.
        val seen = reportFrom(listOf("HEAD|BODY")) { reader, report ->
            val first = readAtLeast(reader, 9)
            val whole = first.buffer.text()
            reader.advanceTo(5, 5)

            val again = reader.tryRead()
            if (again == null) {
                report.store("parked on bytes already held (saw '$whole')")
                return@reportFrom
            }

            val body = again.buffer.text()
            reader.advanceTo(again.buffer.length)
            report.store("$whole|$body")
        }

        assertEquals("HEAD|BODY|BODY", seen)
    }

    @Test
    fun `advanceTo refuses a count outside what is held - past its end and inside its consumed part`() {
        // Offsets count from the start of what is held now. Accepted, any of these would move the byte counters past
        // what is held, and the next read would park on bytes that already arrived or hand back ones consumed.
        // Examined past the end is clamped rather than refused: it only says that everything held was examined.
        val seen = reportFrom(listOf("HEAD|BODY")) { reader, report ->
            val first = readAtLeast(reader, 9)
            val pastEnd = refusal {
                reader.advanceTo(first.buffer.length + 1)
            }

            reader.advanceTo(5)   // the head; the body stays, mid-buffer
            val rest = reader.read()

            val insideConsumed = refusal {
                reader.advanceTo(-3)   // where the head's third byte was
            }
            val pastRest = refusal {
                reader.advanceTo(rest.buffer.length + 1)
            }

            val body = rest.buffer.text()   // still all there after the refusals
            reader.advanceTo(rest.buffer.length)

            report.store("$pastEnd|$insideConsumed|$pastRest|$body")
        }

        assertEquals("refused|refused|refused|BODY", seen)
    }

    @Test
    fun `a recv-queue overflow ends the read with an error - not a clean end`() {
        // The overflow dropped data, so the close after it is not the peer's: reported as a clean end, a handler would
        // take a truncated stream for a whole one.
        val parked = AtomicReference<TcpConnection?>(null)
        val gate = AtomicInt(0)
        val outcome = AtomicReference<String?>(null)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val reader = TcpPipeReader(conn)
            val timer = RingTimer(conn.reactor)
            try {
                val first = reader.read()
                reader.advanceTo(first.buffer.length)
                parked.store(conn)

                // Not reading while the flood arrives.
                val deadline = Clock.monotonicMs() + 10_000
                while (gate.load() == 0 && Clock.monotonicMs() < deadline) {
                    timer.delay(5)
                }

                while (true) {
                    val read = reader.read()
                    reader.advanceTo(read.buffer.length)
                    if (read.isCompleted) {
                        outcome.store("a clean end")
                        break
                    }
                }
            } catch (e: IOException) {
                outcome.store("an error: ${e.message}")
            } finally {
                reader.complete()
            }
        }

        // 64-byte buffers into an 8-entry queue: the flood below is 64 deliveries.
        val config = testConfig(TcpOptions(port = 0, recvQueueEntries = 8), recvSlots = 256, recvBufferSize = 64)
        TestServer(config, handler).use { server ->
            server.connect().use { client ->
                client.write(ByteArray(64))
                assertTrue(eventually(10_000) { parked.load() != null }, "the handler never parked")
                val conn = parked.load()!!

                client.write(ByteArray(4096))
                assertTrue(eventually(10_000) { conn.recvOverflowed }, "the queue never overflowed")
                gate.store(1)

                assertTrue(eventually(10_000) { outcome.load() != null }, "the handler never finished reading")
            }
        }

        val seen = outcome.load()!!
        assertTrue(seen.startsWith("an error"), "the overflow reached the handler as $seen")
    }
}

private fun ReadBuffer.text(): String = toByteArray().decodeToString()
