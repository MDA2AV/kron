package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.SOL_SOCKET
import platform.posix.SO_SNDBUF
import platform.posix.setsockopt

/**
 * One waiter at a time, and nothing lost around it: a second read or flush is refused without displacing the one
 * waiting, a wake with nothing new parks a pipe read again for the same caller, a flush on a closed connection
 * leaves the one the kernel holds alone, and a flush started inside the last one's completion is its own.
 *
 * ioxide races these against an arm made off the reactor. Here a read or flush from another thread is posted to the
 * reactor before it touches anything, so arm and completion never run at once; what is left is their order on that
 * one thread, driven through a real connection.
 */
class ParkTest {
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

    // Starts [block] as a coroutine of its own, on this thread until its first suspension: its outcome lands in the
    // returned reference once it has one.
    private fun <T> launch(block: suspend () -> T): AtomicReference<Result<T>?> {
        val outcome = AtomicReference<Result<T>?>(null)
        block.startCoroutine(Continuation(EmptyCoroutineContext) {
            outcome.store(it)
        })

        return outcome
    }

    // A handler's bounded wait for [done], on the ring.
    private suspend fun waitUntil(reactor: Reactor, timeoutMs: Long, done: () -> Boolean): Boolean {
        val timer = RingTimer(reactor)
        val deadline = Clock.monotonicMs() + timeoutMs
        while (!done()) {
            if (Clock.monotonicMs() >= deadline) {
                return false
            }

            timer.delay(5)
        }

        return true
    }

    private fun <T> outcome(launched: AtomicReference<Result<T>?>): String =
        launched.load()?.fold({ "$it" }, { "threw: ${it.message}" }) ?: "never"

    // Caps a server socket's send buffer, so a send waits on its peer reading whatever the host's tcp_wmem allows.
    private fun capSendBuffer(fd: Int) = memScoped {
        val size = alloc<IntVar>()
        size.value = 4096

        check(setsockopt(fd, SOL_SOCKET, SO_SNDBUF, size.ptr, sizeOf<IntVar>().convert()) == 0) { "SO_SNDBUF refused" }
    }

    private inline fun refusal(call: () -> Unit): String = try {
        call()
        "accepted"
    } catch (e: IllegalStateException) {
        "refused: ${e.message}"
    }

    // Serves one connection with [handler], which reports one line once the client has done [drive].
    private fun reportFrom(
        handler: suspend (TcpConnection, AtomicReference<String?>) -> Unit,
        receiveBuffer: Int = 0,
        drive: (TestSocket) -> Unit,
    ): String? {
        val report = AtomicReference<String?>(null)

        val serve: suspend (TcpConnection) -> Unit = { conn ->
            try {
                handler(conn, report)
            } catch (e: Throwable) {
                report.store("${e::class.simpleName}: ${e.message}")
            }
        }

        TestServer(testConfig(), serve).use { server ->
            TestSocket().use { client ->
                if (receiveBuffer > 0) {
                    client.setReceiveBuffer(receiveBuffer)   // the server's send backs up soon
                }
                client.setReceiveTimeout(10_000)
                client.connect(server.port)

                drive(client)
                assertTrue(eventually(10_000) { report.load() != null }, "the handler never reported")
            }
        }

        return report.load()
    }

    // ---- Reads ------------------------------------------------------------------------------------------------

    @Test
    fun `a second read refused while one is parked leaves the parked one to complete`() {
        // The one refused must not have taken the parked one's place on its way to the refusal, or the close that
        // follows resumes nobody.
        val seen = reportFrom({ conn, report ->
            val first = launch { conn.read().isClosed }   // the client has sent nothing
            val second = refusal {
                conn.read()
            }

            waitUntil(conn.reactor, 10_000) { first.load() != null }
            report.store("$second|closed=${outcome(first)}")
        }) { client ->
            client.shutdownOutput()   // the FIN completes the parked read, after the handler's first run
        }

        assertEquals("refused: read() is already waiting.|closed=true", seen)
    }

    @Test
    fun `a second pipe read refused while one is parked leaves the parked one to complete`() {
        val seen = reportFrom({ conn, report ->
            val reader = TcpPipeReader(conn)
            try {
                val first = launch { reader.read().isCompleted }
                val second = refusal {
                    reader.read()
                }

                waitUntil(conn.reactor, 10_000) { first.load() != null }
                report.store("$second|completed=${outcome(first)}")
            } finally {
                reader.complete()
            }
        }) { client ->
            client.shutdownOutput()
        }

        assertEquals("refused: read() is already waiting.|completed=true", seen)
    }

    @Test
    fun `a wake with nothing new parks the pipe read again without losing its caller`() {
        // wakeRead delivers whatever is queued, possibly nothing; with nothing new the reader parks again for the same
        // caller, who is still waiting and must be the one the next bytes reach.
        val woken = AtomicInt(0)

        val seen = reportFrom({ conn, report ->
            val reader = TcpPipeReader(conn)
            try {
                val read = launch { reader.read().buffer.toByteArray().decodeToString() }
                conn.wakeRead()   // nothing is queued: the client has not sent yet
                val afterWake = if (read.load() == null) "parked" else "completed by the wake"
                woken.store(1)

                waitUntil(conn.reactor, 10_000) { read.load() != null }
                report.store("$afterWake|${outcome(read)}")
            } finally {
                reader.complete()
            }
        }) { client ->
            assertTrue(eventually(10_000) { woken.load() == 1 }, "the handler never woke its read")
            client.write("hi")
        }

        assertEquals("parked|hi", seen)
    }

    // ---- Flushes ----------------------------------------------------------------------------------------------

    @Test
    fun `a second pipe flush refused while the kernel holds one leaves that one to complete`() {
        val inKernel = AtomicInt(0)
        val body = pattern(LARGE)

        val seen = reportFrom({ conn, report ->
            capSendBuffer(conn.clientFd)
            val writer = TcpPipeWriter(conn)
            writer.write(body)   // more than the socket buffers hold of a peer that is not reading yet
            val first = launch { writer.flush().isCompleted }
            val second = refusal {
                writer.flush()
            }
            inKernel.store(1)

            waitUntil(conn.reactor, 10_000) { first.load() != null }
            report.store("$second|completed=${outcome(first)}")
        }, receiveBuffer = 4096) { client ->
            assertTrue(eventually(10_000) { inKernel.load() == 1 }, "the handler never flushed")
            assertContentEquals(body, client.readBytes(body.size))   // the send completes as this drains it
        }

        assertEquals("refused: flush() is already in progress.|completed=false", seen)
    }

    @Test
    fun `a pipe flush after the peer closed - while the kernel holds an earlier one - returns at once and leaves that one to complete`() {
        // The peer's FIN closes the connection, which does not release a flush the kernel holds: only its send's
        // completion may. A flush made meanwhile, with nothing staged, returns at once as completed - without taking
        // the place of the one still waiting.
        val secondReturned = AtomicInt(0)
        val body = pattern(LARGE)

        val seen = reportFrom({ conn, report ->
            capSendBuffer(conn.clientFd)
            val writer = TcpPipeWriter(conn)
            writer.write(body)
            val first = launch { writer.flush().isCompleted }

            conn.read()   // the peer's FIN
            val second = writer.flush()
            val returned = if (first.load() == null) "at once" else "after the first"
            secondReturned.store(1)

            waitUntil(conn.reactor, 10_000) { first.load() != null }
            report.store("$returned completed=${second.isCompleted}|completed=${outcome(first)}")
        }, receiveBuffer = 4096) { client ->
            client.shutdownOutput()
            assertTrue(eventually(10_000) { secondReturned.load() == 1 }, "the flush on the closed connection never returned")
            assertContentEquals(body, client.readBytes(body.size))   // and now the first send completes
        }

        assertEquals("at once completed=true|completed=true", seen)
    }

    @Test
    fun `a flush started inside the last one's completion is neither refused nor resumed in its place`() {
        // Each flush returns inline from its send's completion, and the loop stages and starts the next right there:
        // the completion must have let go of the in-progress guard and of its waiter before it resumed the caller.
        val rounds = 200
        val chunks = List(rounds) { "chunk ${it.toString().padStart(3, '0')}|".encodeToByteArray() }

        val seen = reportFrom({ conn, report ->
            val writer = TcpPipeWriter(conn)
            var returned = 0
            var refused = 0

            for (chunk in chunks) {
                writer.write(chunk)
                try {
                    writer.flush()
                    returned++
                } catch (e: IllegalStateException) {
                    refused++
                }
            }

            report.store("$returned returned, $refused refused")
        }) { client ->
            val expected = chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }
            assertContentEquals(expected, client.readBytes(expected.size))
        }

        assertEquals("$rounds returned, 0 refused", seen)
    }

    private fun pattern(size: Int) = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }

    private companion object {
        // Far past what the capped socket buffers hold, the server's and the client's, so a send waits on the client
        // reading it.
        const val LARGE = 1024 * 1024
    }
}
