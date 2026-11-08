package kronos

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The send timeout in the kernel: the listeners carry it as TCP_USER_TIMEOUT, which every accepted socket inherits.
 * It bounds what the sweep never sees stall, a stream of small flushes that each still fit in the socket's buffer,
 * to a peer that stopped reading. A peer that vanished is the other case it covers, but loopback can't fake one.
 */
class TcpUserTimeoutTest {
    @Test
    fun `a client that stops reading while small flushes still fit is closed at the send timeout`() {
        val stream = Stream(runMs = 12_000)
        TestServer(config(sendMs = 1_000), stream.handler).use { server ->
            stream.client(server.port, reads = false).use {
                assertTrue(stream.awaitEnd(20_000), "the handler never finished")
                assertTrue(stream.closed, "still open after ${stream.ranMs} ms of flushes to a client not reading")

                // Its window shuts only once its own buffer is full, and then has to stay shut for the timeout.
                assertTrue(stream.ranMs in 1_000..8_000, "closed after ${stream.ranMs} ms")
            }
        }
    }

    @Test
    fun `control - with the send timeout at 0 the same client stays open`() {
        // The proof that the test above measures the kernel's timeout and not some other teardown.
        val stream = Stream(runMs = 6_000)
        TestServer(config(sendMs = 0), stream.handler).use { server ->
            stream.client(server.port, reads = false).use {
                assertTrue(stream.awaitEnd(15_000), "the handler never finished")
                assertTrue(!stream.closed, "closed after ${stream.ranMs} ms with no send timeout")
            }
        }
    }

    @Test
    fun `control - a client that keeps reading through the same stream is left alone`() {
        val stream = Stream(runMs = 3_000)   // three send timeouts
        TestServer(config(sendMs = 1_000), stream.handler).use { server ->
            stream.client(server.port, reads = true).use { client ->
                // Through to the FIN the handler's return sends.
                val buffer = ByteArray(4096)
                var got = 0L
                while (true) {
                    val n = client.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        break
                    }

                    got += n
                }

                assertTrue(stream.awaitEnd(5_000), "the handler never finished")
                assertTrue(!stream.closed, "a client reading the whole time was cut off after ${stream.ranMs} ms")
                assertEquals(stream.flushed, got, "the client did not get every byte flushed")
            }
        }
    }

    private fun config(sendMs: Long) = testConfig(TcpOptions(port = 0, readTimeoutMs = 0, sendTimeoutMs = sendMs))

    // 100 bytes every 50 ms for [runMs], or until the connection closes: no flush ever waits on the socket's buffer.
    private class Stream(private val runMs: Long) {
        private val ended = AtomicLong(-1)
        private val wasClosed = AtomicInt(-1)
        private val sent = AtomicLong(0)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            val snapshot = conn.read()   // the request
            while (conn.tryGetItem(snapshot, item)) {
                conn.returnBuffer(item)
            }

            val timer = RingTimer(conn.proactor)
            val chunk = ByteArray(100)
            val start = Clock.monotonicMs()
            while (!conn.isClosed && Clock.monotonicMs() - start < runMs) {
                conn.write(chunk)
                conn.flush()
                sent.addAndFetch(chunk.size.toLong())
                timer.delay(50)
            }

            wasClosed.store(if (conn.isClosed) 1 else 0)
            ended.store(Clock.monotonicMs() - start)
        }

        val closed: Boolean get() = wasClosed.load() == 1
        val ranMs: Long get() = ended.load()
        val flushed: Long get() = sent.load()

        fun awaitEnd(timeoutMs: Long): Boolean {
            val deadline = Clock.monotonicMs() + timeoutMs
            while (ended.load() < 0) {
                if (Clock.monotonicMs() >= deadline) {
                    return false
                }

                sleepMs(10)
            }

            return true
        }

        fun client(port: Int, reads: Boolean): TestSocket = TestSocket().also {
            it.setReceiveBuffer(4096)   // before the connect, so the window the server sees is small
            if (reads) {
                it.setReceiveTimeout(10_000)
            }

            it.connect(port)
            it.write("go")   // the request; a client that doesn't read never reads anything after it
        }
    }
}
