package kronos

import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A connection whose own ring is dry once its handler has gone, whichever came first. Nothing will read what fills the
 * ring, and with its recv parked on -ENOBUFS there is no completion for the sweep's shutdown to end, and no recv to see
 * the peer's FIN.
 */
class IncrementalDryRingTest {
    private fun config(slots: Int, bufferBytes: Int, readTimeoutMs: Long, cap: Int = 8) = ServerConfig(
        proactorCount = 1,
        ringEntries = 256,
        tcp = TcpOptions(port = 0, readTimeoutMs = readTimeoutMs, sendTimeoutMs = 300),
        incremental = IncrementalOptions(maxConnections = cap, recvSlots = slots, recvBufferSize = bufferBytes),
    )

    private fun unsupported(): Boolean {
        if (!LetGoDriver.incrementalSupported) {
            println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
        }

        return !LetGoDriver.incrementalSupported
    }

    private suspend fun consume(conn: TcpConnection) {
        val item = RecvItem()
        val snapshot = conn.read()
        while (conn.tryGetItem(snapshot, item)) {
            conn.returnBuffer(item)
        }
    }

    // Sends [sent] once the handler has let go, and leaves. Whether the connection ended.
    private fun closedAfterItsClientLeft(sent: Int, slots: Int, bufferBytes: Int, readTimeoutMs: Long = 300): Boolean {
        TestServer(config(slots, bufferBytes, readTimeoutMs)) { conn -> consume(conn) }.use { server ->
            val client = LetGoDriver.connect(server.port)
            try {
                client.write("hi")
                assertEquals(-1, client.read(), "no FIN after the handler let go")
                client.write(ByteArray(sent))
            } finally {
                client.close()
            }

            return LetGoDriver.eventually { LetGoDriver.openConnections(server.proactor) == 0 }
        }
    }

    // A handler that waits until a 2 KiB ring is dry, has what it holds taken by [hold], and returns. Whether the
    // connection ended once its client left, after checking the handler did hold the ring.
    private fun closedAfterLettingGoOfADryRing(readTimeoutMs: Long, hold: suspend (TcpConnection) -> Int): Boolean {
        val held = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            RingTimer(conn.proactor).delay(250)
            held.store(hold(conn))
        }

        TestServer(config(slots = 2, bufferBytes = 1024, readTimeoutMs), handler).use { server ->
            val client = LetGoDriver.connect(server.port)
            try {
                client.write(ByteArray(64 * 1024))   // past the 2 KiB ring
                sleepMs(400)
            } finally {
                client.close()
            }

            val closed = LetGoDriver.eventually { LetGoDriver.openConnections(server.proactor) == 0 }
            assertTrue(held.load() >= 2 * 1024, "the handler held only ${held.load()} bytes: this proved nothing")
            return closed
        }
    }

    // Takes everything, and returns without handing it back: this breaks tryGetItem's contract.
    private suspend fun takeRaw(conn: TcpConnection): Int {
        val item = RecvItem()
        val snapshot = conn.read()
        var bytes = 0
        while (conn.tryGetItem(snapshot, item)) {
            bytes += item.length
        }

        return bytes
    }

    // The same through a pipe reader, which may hold: nothing obliges a handler to complete it.
    private suspend fun holdInAReader(conn: TcpConnection): Int = TcpPipeReader(conn).read().buffer.length.toInt()

    @Test
    fun `a connection whose ring ran dry after its handler let go is closed once its client leaves`() {
        if (unsupported()) {
            return
        }

        assertTrue(
            closedAfterItsClientLeft(sent = 64 * 1024, slots = 2, bufferBytes = 1024),
            "the connection is still open after its client left",
        )
    }

    @Test
    fun `a connection whose ring ran dry after its handler let go is closed once its client leaves - with no read timeout`() {
        if (unsupported()) {
            return
        }

        assertTrue(
            closedAfterItsClientLeft(sent = 64 * 1024, slots = 2, bufferBytes = 1024, readTimeoutMs = 0),
            "the connection is still open after its client left",
        )
    }

    @Test
    fun `control - a connection whose ring never ran dry is closed when its client leaves`() {
        if (unsupported()) {
            return
        }

        assertTrue(
            closedAfterItsClientLeft(sent = 1024, slots = 16, bufferBytes = 4096),
            "the connection is still open after its client left",
        )
    }

    @Test
    fun `a handler that let go holding its dry ring raw is closed by the read timeout`() {
        if (unsupported()) {
            return
        }

        assertTrue(
            closedAfterLettingGoOfADryRing(readTimeoutMs = 300, ::takeRaw),
            "the connection is still open, many read timeouts after its client left",
        )
    }

    @Test
    fun `a handler that let go holding its dry ring in a reader is closed once its client leaves`() {
        if (unsupported()) {
            return
        }

        assertTrue(
            closedAfterLettingGoOfADryRing(readTimeoutMs = 300, ::holdInAReader),
            "the connection is still open after its client left",
        )
    }

    @Test
    fun `a handler that let go holding its dry ring in a reader is closed once its client leaves - with no read timeout`() {
        if (unsupported()) {
            return
        }

        assertTrue(
            closedAfterLettingGoOfADryRing(readTimeoutMs = 0, ::holdInAReader),
            "the connection is still open after its client left",
        )
    }

    private val answerRefused =
        "HTTP/1.1 413 Payload Too Large\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()

    // Eight clients send [sent] to a handler that refuses and returns, then leave, using up a cap of 8 if their
    // connections stay. Whether the next client reached a handler.
    private fun nextClientServedAfterUploadsOf(sent: Int): Boolean {
        val handled = AtomicInt(0)
        val refuse: suspend (TcpConnection) -> Unit = { conn ->
            handled.addAndFetch(1)
            consume(conn)
            conn.write(answerRefused)
            conn.flush()
        }

        TestServer(config(slots = 16, bufferBytes = 4096, readTimeoutMs = 300), refuse).use { server ->
            repeat(8) {
                val client = LetGoDriver.connect(server.port)
                client.write(ByteArray(sent))
                sleepMs(50)
                client.close()
            }
            sleepMs(2_000)

            val before = handled.load()
            val next = LetGoDriver.connect(server.port)
            next.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
            sleepMs(200)
            next.close()

            return handled.load() == before + 1
        }
    }

    @Test
    fun `refused uploads do not use up the connection cap`() {
        if (unsupported()) {
            return
        }

        assertTrue(
            nextClientServedAfterUploadsOf(256 * 1024),
            "the next client was shed, every group id taken by clients long gone",
        )
    }

    @Test
    fun `control - uploads that fit the ring leave the connection cap free`() {
        if (unsupported()) {
            return
        }

        assertTrue(nextClientServedAfterUploadsOf(1024), "the next client was shed, with uploads that fit the ring")
    }
}
