package kron

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** [RingTimer] at its short end: a sub-millisecond wait, and waits of zero and less. */
class RingTimerTest {
    // Runs [waits] in a handler, on the reactor thread, and returns what it recorded.
    private fun onReactor(waits: suspend (RingTimer) -> List<String>): List<String> {
        val recorded = AtomicReference<List<String>?>(null)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val notes = try {
                waits(RingTimer(conn.reactor))
            } catch (e: Throwable) {
                listOf("threw ${e::class.simpleName}: ${e.message}")
            }

            recorded.store(notes)
            conn.write("done".encodeToByteArray())
            conn.flush()
        }

        TestServer(testConfig(), handler).use { server ->
            server.connect(receiveTimeoutMs = 10_000).use { client ->
                assertEquals("done", client.readBytes(4).decodeToString(), "the waits never finished")
            }
        }

        return assertNotNull(recorded.load())
    }

    @Test
    fun `a 200 us delayNanoseconds completes - never sooner than asked`() {
        val notes = onReactor { timer ->
            List(20) {
                val mark = TimeSource.Monotonic.markNow()
                val result = timer.delayNanoseconds(200_000)
                val waited = mark.elapsedNow().inWholeNanoseconds

                "result ${if (RingTimer.expired(result)) "expired" else result}, at least 200 us: ${waited >= 200_000} ($waited ns)"
            }
        }

        for (note in notes) {
            assertTrue(note.startsWith("result expired, at least 200 us: true"), "the waits: $notes")
        }
    }

    @Test
    fun `a zero delay completes - and so does a negative one`() {
        val notes = onReactor { timer ->
            listOf(timer.delay(0), timer.delay(0), timer.delayNanoseconds(0), timer.delayNanoseconds(-1), timer.delay(-5))
                .map { if (it == RingTimer.ETIME) "ETIME" else "$it" }
        }

        assertEquals(List(5) { "ETIME" }, notes)
    }
}
