package kronos

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a connection holds once its handler has let go. Nobody will read it, and its buffers come back only at
 * teardown, which takes a recv completion, which takes a buffer. So it has to give them up, and the read timeout has
 * to end it even with no recv running. On the shared ring that decides whether anyone else can receive.
 */
class LetGoBufferTest {
    private val big = ByteArray(8 * 1024 * 1024)   // far past what a 4 KiB window and the socket buffers hold
    private val pong = "pong".encodeToByteArray()

    private class LetGoList {
        private val connections = AtomicReference<List<TcpConnection>>(emptyList())

        val all: List<TcpConnection> get() = connections.load()

        fun add(conn: TcpConnection) = connections.store(all + conn)

        fun clear() = connections.store(emptyList())
    }

    private fun server(config: ServerConfig, letGo: LetGoList) = TestServer(config) { conn -> handle(conn, letGo) }

    private fun shared(slots: Int, readTimeoutMs: Long, sendTimeoutMs: Long = 0) = ServerConfig(
        proactorCount = 1,
        ringEntries = 256,
        recvBufferSize = 4096,
        recvSlots = slots,
        tcp = TcpOptions(port = 0, recvQueueEntries = 64, readTimeoutMs = readTimeoutMs, sendTimeoutMs = sendTimeoutMs),
    )

    // 'R' lets go, 'W' lets go with a read left parked, 'P' answers, 'E' echoes from here on, 'B' parks in a flush its
    // peer won't drain, 'F' lets go with that flush in flight, and 'G' waits it out and returns with its input unread.
    private suspend fun handle(conn: TcpConnection, letGo: LetGoList) {
        val item = RecvItem()
        var echoing = false
        while (true) {
            val snapshot = conn.read()

            var marker = -1
            while (conn.tryGetItem(snapshot, item)) {
                if (item.hasBuffer) {
                    marker = item[0].toInt()
                    echoing = echoing || marker == 'E'.code
                    if (echoing) {
                        conn.write(item.address, item.length)
                    }

                    conn.returnBuffer(item)
                }
            }

            if (echoing) {
                conn.flush()
                if (snapshot.isClosed) {
                    return
                }

                continue
            }

            when (marker) {
                'F'.code -> {
                    conn.write(big)
                    LetGoDriver.detached { conn.flush() }
                    return
                }

                'G'.code -> {
                    conn.write(big)
                    conn.flush()
                    return
                }

                'R'.code -> {
                    letGo.add(conn)
                    return
                }

                'W'.code -> {
                    LetGoDriver.detached { TcpPipeReader(conn).read() }
                    return
                }

                'B'.code -> {
                    conn.write(big)
                    conn.flush()
                }

                'P'.code -> {
                    conn.write(pong)
                    conn.flush()
                }
            }

            if (snapshot.isClosed) {
                return
            }
        }
    }

    // Connected, with its handler gone: the FIN of the release has arrived.
    private fun letGoPeer(port: Int): TestSocket {
        val peer = LetGoDriver.connect(port)
        peer.write("R")
        assertEquals(-1, peer.read(), "no FIN after the handler let go")
        return peer
    }

    // A buffer each, each seen to arrive, so the ring ends with exactly [deliveries] gone and no recv having noticed.
    private fun holdBuffers(
        port: Int,
        letGo: LetGoList,
        deliveries: Int,
        peerCount: Int,
        peers: MutableList<TestSocket>,
    ) {
        repeat(peerCount) {
            peers.add(letGoPeer(port))
        }

        val first = peers.size - peerCount
        val one = byteArrayOf('x'.code.toByte())
        for (sent in 1..deliveries) {
            peers[first + (sent - 1) % peerCount].write(one)
            awaitQueued(letGo, sent)
        }
    }

    private fun awaitQueued(letGo: LetGoList, expected: Int) {
        val deadline = Clock.monotonicMs() + LetGoDriver.GRACE_MS
        while (letGo.all.sumOf { it.queuedRecvs } != expected) {
            assertTrue(Clock.monotonicMs() < deadline, "message $expected never reached its queue")
            sleepMs(0)
        }
    }

    private fun asked(socket: TestSocket): Boolean = LetGoDriver.answers {
        socket.write("P")
        socket.readBytes(pong.size).contentEquals(pong)
    }

    private fun askedOn(port: Int): Boolean = LetGoDriver.answers {
        val next = LetGoDriver.connect(port)
        try {
            asked(next)
        } finally {
            next.close()
        }
    }

    // Peers that sent [sendsEach] apart and left, many read timeouts ago. Whether a new client was served, and how
    // many of them are still open.
    private fun thenLeave(sendsEach: Int): Pair<Boolean, Int> {
        val letGo = LetGoList()
        server(shared(slots = 256, readTimeoutMs = 300), letGo).use { server ->
            val peers = List(5) { letGoPeer(server.port) }
            repeat(sendsEach) {
                peers.forEach { it.write("x") }
                sleepMs(1)   // apart, so each is a recv of its own
            }

            peers.forEach { it.close() }
            sleepMs(2_000)

            val served = askedOn(server.port)
            LetGoDriver.eventually { LetGoDriver.openConnections(server.proactor) == 0 }
            return served to LetGoDriver.openConnections(server.proactor)
        }
    }

    @Test
    fun `shared ring - connections whose handlers let go holding every buffer are closed and a new client is served`() {
        val (served, open) = thenLeave(sendsEach = 60)
        assertTrue(served, "the new client was never answered, with $open connections still open")
        assertEquals(0, open, "connections outlived their clients")
    }

    @Test
    fun `shared ring - control - the same with room left in the ring`() {
        val (served, open) = thenLeave(sendsEach = 20)
        assertTrue(served, "the new client was never answered, with $open connections still open")
        assertEquals(0, open, "connections outlived their clients")
    }

    // 66 peers, so 4096 buffers stay inside each one's 64-entry queue. No read timeout, so nothing but the buffers ends
    // these connections, and nothing limits how long the sends may take.
    private fun servedAtDefaultSizes(deliveries: Int): Boolean {
        val letGo = LetGoList()
        val config = ServerConfig(proactorCount = 1, tcp = TcpOptions(port = 0, readTimeoutMs = 0))
        assertEquals(4096, config.recvSlots, "this test is about the default ring")

        server(config, letGo).use { server ->
            val peers = ArrayList<TestSocket>()
            try {
                holdBuffers(server.port, letGo, deliveries, peerCount = 66, peers)
                return askedOn(server.port)
            } finally {
                peers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `shared ring - at the default sizes 4096 one-byte sends on let-go connections do not stop a new client`() {
        assertTrue(
            servedAtDefaultSizes(4096),
            "the new client was never answered: let-go connections kept every buffer",
        )
    }

    @Test
    fun `shared ring - control - at the default sizes with four buffers left`() {
        assertTrue(servedAtDefaultSizes(4092), "the new client was never answered, with buffers left in the ring")
    }

    @Test
    fun `shared ring - a starved recv takes back what let-go connections hold - with no read timeout and their peers still connected`() {
        val letGo = LetGoList()
        server(shared(slots = 16, readTimeoutMs = 0), letGo).use { server ->
            val peers = ArrayList<TestSocket>()
            try {
                holdBuffers(server.port, letGo, deliveries = 16, peerCount = 2, peers)
                assertTrue(
                    askedOn(server.port),
                    "the new client was never answered: let-go connections kept every buffer",
                )
            } finally {
                peers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `shared ring - a starved recv takes back what a let-go connection's reader holds`() {
        val slots = 16
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val reader = TcpPipeReader(conn)
            while (true) {
                val read = reader.read()
                if (read.buffer.isEmpty || read.buffer[0L] != 'P'.code.toByte()) {
                    break   // with what it read still in the reader, neither advanced nor completed
                }

                reader.advanceTo(read.buffer.length)
                conn.write(pong)
                conn.flush()
            }
        }

        TestServer(shared(slots, readTimeoutMs = 0), handler).use { server ->
            val peers = List(slots) { letGoPeer(server.port) }   // one send, so one buffer
            try {
                assertTrue(askedOn(server.port), "the new client was never answered: the readers kept every buffer")
            } finally {
                peers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `shared ring - a read left parked when its handler let go strands nothing it takes later`() {
        val slots = 16
        val letGo = LetGoList()
        server(shared(slots, readTimeoutMs = 0), letGo).use { server ->
            val peers = ArrayList<TestSocket>()
            val parked = ArrayList<TestSocket>()
            try {
                repeat(slots) {
                    val socket = LetGoDriver.connect(server.port)
                    socket.write("W")
                    assertEquals(-1, socket.read(), "no FIN after the handler let go")
                    parked.add(socket)
                }

                // Starved once, so the proactor asks these connections for what they hold, with their reads parked.
                holdBuffers(server.port, letGo, deliveries = slots, peerCount = 2, peers)
                assertTrue(
                    askedOn(server.port),
                    "the new client was never answered: let-go connections kept every buffer",
                )

                // Those reads take a buffer each, which only their holders know about.
                parked.forEach {
                    it.write("x")
                    sleepMs(1)
                }

                parked.forEach { it.close() }
                parked.clear()

                assertTrue(
                    askedOn(server.port),
                    "the last client was never answered: parked reads stranded their buffers",
                )
            } finally {
                peers.forEach { it.close() }
                parked.forEach { it.close() }
            }
        }
    }

    @Test
    fun `shared ring - the read timeout closes a let-go connection parked on a ring that others hold`() {
        val letGo = LetGoList()
        server(shared(slots = 256, readTimeoutMs = 500), letGo).use { server ->
            val parked = letGoPeer(server.port)
            val holders = ArrayList<TestSocket>()
            try {
                // Their handlers are parked in a flush with no send timeout, and keep what their peers send.
                repeat(5) {
                    val holder = LetGoDriver.connect(server.port, receiveBuffer = 4096)
                    holder.write("B")
                    holders.add(holder)
                }
                sleepMs(200)

                repeat(60) {
                    holders.forEach { it.write("x") }
                    sleepMs(1)   // apart, so each is a recv of its own
                }

                parked.write("x")   // on the dry ring: its recv parks, with nothing of its own to give back

                assertTrue(
                    LetGoDriver.eventually { LetGoDriver.openConnections(server.proactor) == holders.size },
                    "${LetGoDriver.openConnections(server.proactor)} of ${holders.size + 1} open, many timeouts later",
                )
            } finally {
                parked.close()
                holders.forEach { it.close() }
            }
        }
    }

    // Asks for an answer too big for a client that isn't reading, and doesn't wait: no FIN comes while it is flushing.
    private fun asking(port: Int, marker: String): LetGoDriver.Peer {
        val client = LetGoDriver.connect(port, receiveBuffer = 4096)
        client.write(marker)
        sleepMs(50)
        return LetGoDriver.Peer(client)
    }

    // The handlers that end this way have no read clock running: their flush holds it off.
    private val endedBySendTimeout =
        LetGoDriver.Ending("they stay, until the send timeout", leave = false, readTimeoutMs = 0, ends = true)

    private fun endedBySendTimeout(marker: String) {
        for (mode in LetGoDriver.modes) {
            LetGoDriver.naming(mode.name) {
                val letGo = LetGoList()
                server(LetGoDriver.config(mode, readTimeoutMs = 0), letGo).use { server ->
                    LetGoDriver.check(
                        server.proactor,
                        mode,
                        endedBySendTimeout,
                        { asking(server.port, marker) },
                        { askedOn(server.port) },
                    )
                }
            }
        }
    }

    @Test
    fun `a handler that let go with a flush in flight is ended by the send timeout - with its recv parked`() {
        endedBySendTimeout("F")
    }

    @Test
    fun `a handler the send timeout wakes - and that returns with its input unread - gives it all back`() {
        endedBySendTimeout("G")
    }

    // Several clients at once, each checking its own bytes come back: a buffer in the ring twice would mix them.
    private fun echoesIntact(port: Int) {
        val failure = AtomicReference<String?>(null)
        val threads = TestThreads()

        repeat(4) { c ->
            threads.start {
                try {
                    val client = LetGoDriver.connect(port)
                    try {
                        client.write("E")
                        assertEquals('E'.code, client.read(), "the echo of the marker")

                        val sent = ByteArray(512) { ('a'.code + c).toByte() }
                        repeat(200) { round ->
                            client.write(sent)
                            val got = client.readBytes(sent.size)
                            assertTrue(got.contentEquals(sent), "round $round came back with another client's bytes")
                        }
                    } finally {
                        client.close()
                    }
                } catch (e: Throwable) {
                    failure.store(e.message ?: e.toString())
                }
            }
        }

        threads.joinAll()
        assertNull(failure.load())
    }

    @Test
    fun `after let-go connections gave their buffers back connections on them are served intact`() {
        for (mode in LetGoDriver.modes) {
            LetGoDriver.naming(mode.name) {
                val slots = 16
                val letGo = LetGoList()
                val config = if (mode === LetGoDriver.SHARED) {
                    shared(slots, readTimeoutMs = 300)
                } else {
                    ServerConfig(
                        proactorCount = 1,
                        ringEntries = 256,
                        tcp = TcpOptions(port = 0, recvQueueEntries = 64, readTimeoutMs = 300, sendTimeoutMs = 0),
                        incremental = IncrementalOptions(maxConnections = 32, recvSlots = 4, recvBufferSize = 4096),
                    )
                }

                server(config, letGo).use { server ->
                    // Round after round, with what the last one left, so anything lost or returned twice shows.
                    repeat(3) { round ->
                        val peers = ArrayList<TestSocket>()
                        try {
                            if (mode === LetGoDriver.SHARED) {
                                holdBuffers(server.port, letGo, deliveries = slots, peerCount = 2, peers)
                            } else {
                                peers.add(letGoPeer(server.port))
                                peers[0].write(ByteArray(256 * 1024))
                            }

                            assertTrue(askedOn(server.port), "round $round: the new client was never answered")
                        } finally {
                            peers.forEach { it.close() }
                        }

                        assertTrue(
                            LetGoDriver.eventually { LetGoDriver.openConnections(server.proactor) == 0 },
                            "round $round: a let-go connection is still open after its client left",
                        )
                        letGo.clear()

                        echoesIntact(server.port)
                    }
                }
            }
        }
    }

    @Test
    fun `incremental - a client that keeps uploading after its handler let go is read to its close - not reset`() {
        if (!LetGoDriver.incrementalSupported) {
            println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
            return
        }

        val letGo = LetGoList()
        val config = ServerConfig(
            proactorCount = 1,
            ringEntries = 256,
            tcp = TcpOptions(port = 0, readTimeoutMs = 30_000),
            incremental = IncrementalOptions(maxConnections = 8),
        )

        server(config, letGo).use { server ->
            val client = letGoPeer(server.port)
            try {
                client.setSendTimeout(LetGoDriver.GRACE_MS.toInt())

                val chunk = ByteArray(64 * 1024)
                repeat(256) {
                    client.write(chunk)   // 16 MiB: far past the ring, the queue and the socket buffers
                }
            } finally {
                client.close()
            }

            assertTrue(
                LetGoDriver.eventually { LetGoDriver.openConnections(server.proactor) == 0 },
                "the connection is still open after its client left",
            )
        }
    }
}
