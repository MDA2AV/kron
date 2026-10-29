package kron

import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.errno
import platform.posix.opendir
import platform.posix.readdir
import kotlin.concurrent.atomics.AtomicInt
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * While the kernel may still read a connection's write slab, the connection is not recycled and its handler is not
 * let go to write into it: io_uring copies from the slab as the socket drains, not when the send is submitted.
 */
class SendInFlightTest {
    // Both clocks off: a sweep firing mid-run would end the send by another route.
    private val config = ServerConfig(
        reactorCount = 1,
        ringEntries = 256,
        recvBufferSize = 16 * 1024,
        recvSlots = 256,
        tcp = TcpOptions(port = 0, writeSlabSize = 16 * 1024, poolMax = 64, readTimeoutMs = 0, sendTimeoutMs = 0),
    )

    // ---- Helpers ----------------------------------------------------------------------------------------------

    // An operation the handler starts now and waits for later, as a C# handler holds a ValueTask: a coroutine of its
    // own, run inline until it suspends and resumed by the reactor. Reactor thread only.
    private class Pending(block: suspend () -> Unit) {
        var isCompleted = false
            private set

        private var failure: Throwable? = null
        private var waiter: Continuation<Unit>? = null

        init {
            block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
                failure = result.exceptionOrNull()
                isCompleted = true
                waiter?.let {
                    waiter = null
                    it.resume(Unit)
                }
            })
        }

        suspend fun await() {
            if (!isCompleted) {
                suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    waiter = cont
                    COROUTINE_SUSPENDED
                }
            }

            failure?.let { throw it }
        }
    }

    // A server answering every '\n' with one response from [next], each written and flushed alone; [next] gets the
    // connection's last response, null before its first.
    private fun pipelined(next: (ByteArray?) -> ByteArray): suspend (TcpConnection) -> Unit = { conn ->
        val item = RecvItem()
        var last: ByteArray? = null

        while (true) {
            val snapshot = conn.read()
            var requests = 0
            while (conn.tryGetItem(snapshot, item)) {
                for (i in 0 until item.length) {
                    if (item[i] == NEWLINE) {
                        requests++
                    }
                }
                conn.returnBuffer(item)
            }

            repeat(requests) {
                val response = next(last)
                last = response
                conn.write(response)
                conn.flush()
            }

            if (snapshot.isClosed) {
                break
            }
        }
    }

    private fun response(mark: Byte): ByteArray =
        "HTTP/1.1 200 OK\r\nContent-Length: $BODY_BYTES\r\n\r\n".encodeToByteArray() + ByteArray(BODY_BYTES) { mark }

    // Response [index]'s body byte: above ASCII, so no header byte can pass for it, and unlike its neighbours'.
    private fun marker(index: Int): Byte = (0x80 + index % 128).toByte()

    // Pipelines REQUESTS requests into a window too small to take the answers, stops reading so the server parks
    // with a send in the kernel, then half-closes.
    private fun parkThenHalfClose(port: Int): TestSocket {
        val client = TestSocket()
        try {
            client.setReceiveBuffer(4096)
            client.setReceiveTimeout(20_000)
            client.connect(port)

            client.write(ByteArray(REQUESTS) { NEWLINE })
            sleepMs(1_500)           // the server fills the window and parks
            client.shutdownOutput()  // FIN: recv-side teardown
            sleepMs(200)

            return client
        } catch (e: Throwable) {
            client.close()
            throw e
        }
    }

    // The precondition, asserted rather than assumed: a peer that got every response means nothing was in flight at
    // the FIN, and the checks after it would pass without exercising anything.
    private fun assertParked(received: ByteArray) {
        val whole = response(FIRST_MARKER.toByte()).size * REQUESTS
        assertTrue(
            received.size < whole,
            "the server's send never parked: ${received.size} of $whole bytes arrived, so nothing was in flight at " +
                "the FIN and this test proved nothing",
        )
    }

    // Reads until the end of the stream, a failure, or [stopAfter] bytes.
    private fun drain(client: TestSocket, stopAfter: Int): ByteArray {
        val chunk = ByteArray(64 * 1024)
        var received = ByteArray(64 * 1024)
        var length = 0

        while (length < stopAfter) {
            val n = try {
                client.read(chunk, 0, chunk.size)
            } catch (e: IOException) {
                break
            }
            if (n < 0) {
                break
            }

            if (length + n > received.size) {
                received = received.copyOf(maxOf(received.size * 2, length + n))
            }
            chunk.copyInto(received, length, 0, n)
            length += n
        }

        return received.copyOf(length)
    }

    // Descriptors this process holds, once the count stops moving: a recycle lands on the reactor's schedule.
    private fun stableFdCount(): Int {
        var previous = fdCount()
        val deadline = Clock.monotonicMs() + 5_000

        while (Clock.monotonicMs() < deadline) {
            sleepMs(100)
            val current = fdCount()
            if (current == previous) {
                return current
            }
            previous = current
        }

        return previous
    }

    private fun fdCount(): Int {
        val dir = opendir("/proc/self/fd") ?: error("opendir(/proc/self/fd) failed with errno $errno")
        try {
            var count = 0
            while (true) {
                val name = readdir(dir)?.pointed?.d_name?.toKString() ?: break
                if (name != "." && name != "..") {
                    count++
                }
            }

            return count
        } finally {
            closedir(dir)
        }
    }

    // ---- Tests ------------------------------------------------------------------------------------------------

    @Test
    fun `a connection torn down with its send in flight does not leak its slab to the next peer`() {
        // Each connection's responses carry a marker of its own, claimed on its first request: one foreign byte is
        // proof that another connection wrote into the slab this one's send was still reading.
        val markers = AtomicInt(0)
        val handler = pipelined { last ->
            last ?: response((FIRST_MARKER + markers.fetchAndAdd(1)).toByte())
        }

        TestServer(config, handler).use { server ->
            // Connection A parks a send, then half-closes to drive the recv-side teardown.
            parkThenHalfClose(server.port).use { first ->
                // Connection B arrives while A's send is still in the kernel.
                server.connect(receiveTimeoutMs = 20_000).use { second ->
                    second.write("\n\n\n")
                    drain(second, 3 * BODY_BYTES / 2)
                }

                val received = drain(first, Int.MAX_VALUE)
                assertParked(received)

                var foreign = 0
                var firstAt = -1
                for (i in received.indices) {
                    val b = received[i].toInt() and 0xFF
                    if (b >= FIRST_MARKER && b != FIRST_MARKER) {
                        foreign++
                        if (firstAt < 0) {
                            firstAt = i
                        }
                    }
                }

                assertTrue(
                    foreign == 0,
                    "another connection's response bytes reached the first peer: $foreign foreign bytes, first at " +
                        "offset $firstAt of ${received.size}",
                )
            }
        }
    }

    @Test
    fun `a handler that keeps writing after the peer half-closes does not tear the response being sent`() {
        // A marker per response, so a later response's bytes inside an earlier one show up.
        val index = AtomicInt(0)
        val handler = pipelined {
            response(marker(index.fetchAndAdd(1)))
        }

        TestServer(config, handler).use { server ->
            parkThenHalfClose(server.port).use { client ->
                val received = drain(client, Int.MAX_VALUE)
                assertParked(received)

                val head = response(0).copyOf(response(0).size - BODY_BYTES)
                val stride = head.size + BODY_BYTES
                for (i in received.indices) {
                    val at = i % stride
                    val expected = if (at < head.size) head[at] else marker(i / stride)
                    if (received[i] != expected) {
                        fail(
                            "response ${i / stride} was overwritten while it was being sent: byte $at is " +
                                "${received[i].toInt() and 0xFF}, expected ${expected.toInt() and 0xFF}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a send completing while the handler is parked elsewhere still recycles`() {
        // The teardown waits for the send, and the handler is away on an unrelated wait when that send completes - so
        // the completion alone has to let the connection go. One that is never let go never closes its fd, so
        // descriptors climb once per round.
        val doneWhileAway = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            val snapshot = conn.read()
            while (conn.tryGetItem(snapshot, item)) {
                conn.returnBuffer(item)
            }

            // One write far past the slab, which the peer's 4 KiB window cannot drain.
            conn.write(ByteArray(PARKING_BYTES))
            val flush = Pending {
                conn.flush()
            }

            RingTimer(conn.reactor).delay(HANDLER_PARK_MS)
            if (flush.isCompleted) {
                doneWhileAway.addAndFetch(1)
            }
            flush.await()
        }

        TestServer(config, handler).use { server ->
            round(server.port)   // warm up: the pool and the slab settle before the baseline
            val baseline = stableFdCount()
            doneWhileAway.store(0)

            repeat(5) {
                round(server.port)
            }

            val leaked = stableFdCount() - baseline
            assertTrue(doneWhileAway.load() > 0, "no send completed while its handler was away: this proved nothing")
            assertTrue(
                leaked <= 0,
                "descriptors grew by $leaked over 5 rounds: a connection whose send completed while its handler was " +
                    "parked elsewhere was never recycled",
            )
        }
    }

    // One connection through the whole window: park the send, half-close while the handler is away, then read so the
    // parked send completes inside it.
    private fun round(port: Int) {
        TestSocket().use { client ->
            client.setReceiveBuffer(4096)
            client.setReceiveTimeout(20_000)
            client.connect(port)

            client.write("\n")
            sleepMs(250)             // the send fills the window and parks
            client.shutdownOutput()  // FIN: recv-side teardown, the handler still away
            sleepMs(100)

            // The whole send, so it completes while the handler is still away.
            assertTrue(drain(client, PARKING_BYTES).size == PARKING_BYTES, "the parked send never completed")

            // Past the handler's delay, so its release and the recycle have run before the count.
            sleepMs(HANDLER_PARK_MS)
        }
    }

    private companion object {
        const val BODY_BYTES = 8000

        // The first connection's body byte. Above ASCII, so no header byte can collide.
        const val FIRST_MARKER = 0xA1

        // Enough pipelined responses to outrun the server's socket send queue, not only the peer's 4 KiB window: 6 MiB,
        // past the 4 MiB the kernel grows a send buffer to by default (tcp_wmem). Each test asserts the send really
        // parked rather than trusting this margin.
        const val REQUESTS = 800

        // Big enough to outrun the server's socket send buffer, so one send parks.
        const val PARKING_BYTES = 4 * 1024 * 1024

        // How long the handler stays parked on something that is not this connection.
        const val HANDLER_PARK_MS = 900L

        const val NEWLINE = '\n'.code.toByte()
    }
}
