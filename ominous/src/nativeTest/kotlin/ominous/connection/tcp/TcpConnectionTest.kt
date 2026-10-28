package ominous

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A connection's life end to end, against a real reactor over loopback. */
class TcpConnectionTest {
    private fun pattern(size: Int, seed: Int = 0) = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    @Test
    fun `a connection echoes many requests of every size`() {
        TestServer(testConfig(), ::echo).use { server ->
            server.connect().use { client ->
                for (round in 0 until 200) {
                    val message = pattern(1 + round * 37 % 6000, round)
                    client.write(message)
                    assertContentEquals(message, client.readBytes(message.size), "round $round")
                }
            }
        }
    }

    @Test
    fun `many connections at once are each served their own bytes`() {
        TestServer(testConfig(), ::echo).use { server ->
            val clients = List(64) { server.connect() }
            try {
                clients.forEachIndexed { i, c -> c.write("client $i") }
                clients.forEachIndexed { i, c ->
                    assertEquals("client $i", c.readBytes("client $i".length).decodeToString())
                }
            } finally {
                clients.forEach { it.close() }
            }
        }
    }

    @Test
    fun `a large response leaves in full and in order - with and without MSG_WAITALL`() {
        for (waitAll in listOf(true, false)) {
            val body = pattern(8 * 1024 * 1024, if (waitAll) 1 else 2)
            val handler: suspend (TcpConnection) -> Unit = { conn ->
                conn.read()   // the request; the slab grows from 16 KiB to hold the whole body
                conn.write(body)
                conn.flush()
            }
            TestServer(testConfig(TcpOptions(port = 0, sendWaitAll = waitAll)), handler).use { server ->
                TestSocket().use { client ->
                    client.setReceiveBuffer(4096)   // the send backs up, so without WAITALL it completes short
                    client.setReceiveTimeout(10_000)
                    client.connect(server.port)
                    client.write("go")
                    assertContentEquals(body, client.readBytes(body.size), "sendWaitAll = $waitAll")
                }
            }
        }
    }

    @Test
    fun `recvs parked on ENOBUFS resume once the buffers come back`() {
        // Four buffers for the whole reactor, and a handler that holds all four before giving any back: the kernel
        // runs dry on every lap, and the stream gets through only if each return re-arms the parked recv.
        val total = 256 * 1024
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val held = ArrayList<Int>()
            val item = RecvItem()
            var received = 0
            while (received < total) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) held.add(item.bufferId)
                    received += item.length
                }
                if (held.size >= 4 || received >= total) {
                    for (bid in held) conn.returnBuffer(bid)
                    held.clear()
                }
                if (snapshot.isClosed) break
            }
            conn.write("$received".encodeToByteArray())
            conn.flush()
        }
        TestServer(testConfig(recvSlots = 4, recvBufferSize = 4096), handler).use { server ->
            server.connect(receiveTimeoutMs = 10_000).use { client ->
                client.write(pattern(total))
                assertEquals("$total", client.readBytes("$total".length).decodeToString())
            }
        }
    }

    @Test
    fun `a recv queue overflow closes the connection and hands back every buffer`() {
        // Two queue entries, and a handler that reads only after its peer has sent three separate chunks: the third
        // overflows. The handler leaves the queued buffers to the recycle. Eight buffers for the reactor, so eight
        // rounds that each kept even one would leave none for the echo after them, on the same reactor.
        val overflowed = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            RingTimer(conn.reactor).delay(150)
            val item = RecvItem()
            var snapshot = conn.read()
            if (snapshot.isClosed && conn.recvOverflowed) {
                overflowed.addAndFetch(1)
            } else {
                while (true) {
                    while (conn.tryGetItem(snapshot, item)) {
                        if (item.hasBuffer) conn.write(item.address, item.length)
                        conn.returnBuffer(item)
                    }
                    conn.flush()
                    if (snapshot.isClosed) break
                    snapshot = conn.read()
                }
            }
        }
        val config = testConfig(TcpOptions(port = 0, recvQueueEntries = 2), recvSlots = 8, recvBufferSize = 4096)
        TestServer(config, handler).use { server ->
            repeat(8) { round ->
                server.connect().use { client ->
                    repeat(3) {
                        client.write("chunk $it")
                        sleepMs(20)   // each its own completion
                    }
                    assertEquals(-1, runCatching { client.read() }.getOrDefault(-1), "round $round: the connection stayed open")
                }
            }
            assertEquals(8, overflowed.load(), "rounds that saw the overflow")

            server.connect().use { client ->
                client.write("still served")
                assertEquals("still served", client.readBytes(12).decodeToString())
            }
        }
    }

    @Test
    fun `a silent peer is closed after the read timeout - unless the handler opts out`() {
        TestServer(testConfig(TcpOptions(port = 0, readTimeoutMs = 300)), ::echo).use { server ->
            server.connect().use { client ->
                val start = Clock.monotonicMs()
                assertEquals(-1, client.read(), "the silent connection was not closed")
                val waited = Clock.monotonicMs() - start
                assertTrue(waited in 250..2_000, "closed after $waited ms, for a 300 ms timeout")
            }
        }

        val optedOut: suspend (TcpConnection) -> Unit = { conn ->
            conn.disableReadTimeout()
            echo(conn)
        }
        TestServer(testConfig(TcpOptions(port = 0, readTimeoutMs = 300)), optedOut).use { server ->
            server.connect(receiveTimeoutMs = 1_000).use { client ->
                val timedOut = runCatching { client.read() }.exceptionOrNull()
                assertTrue(timedOut is IOException, "the opted-out connection was closed: ${timedOut ?: "read returned"}")
                client.write("x")
                assertEquals("x", client.readBytes(1).decodeToString())
            }
        }
    }

    @Test
    fun `a peer that stops reading is closed after the send timeout`() {
        val flushReturnedAfter = AtomicLong(-1)
        val closedAfterFlush = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            conn.read()
            conn.write(ByteArray(64 * 1024 * 1024))
            val start = Clock.monotonicMs()
            conn.flush()
            flushReturnedAfter.store(Clock.monotonicMs() - start)
            if (conn.isClosed) closedAfterFlush.store(1)
        }
        TestServer(testConfig(TcpOptions(port = 0, sendTimeoutMs = 300)), handler).use { server ->
            TestSocket().use { client ->
                client.setReceiveBuffer(4096)
                client.connect(server.port)
                client.write("go")   // and never read
                val deadline = Clock.monotonicMs() + 5_000
                while (flushReturnedAfter.load() < 0 && Clock.monotonicMs() < deadline) sleepMs(10)
            }
        }
        val after = flushReturnedAfter.load()
        assertTrue(after in 250..3_000, "the stalled flush returned after $after ms, for a 300 ms timeout")
        assertEquals(1, closedAfterFlush.load(), "the flush returned with the connection still open")
    }

    @Test
    fun `calls made off the reactor thread are carried to it`() {
        val threads = TestThreads()
        val seen = AtomicReference("")
        // Raw, so the handler always continues on the new thread: a safe continuation resumed before it suspends
        // would carry on synchronously where it was.
        suspend fun hop(): Unit = suspendCoroutineUninterceptedOrReturn { cont ->
            threads.start { cont.resume(Unit) }
            COROUTINE_SUSPENDED
        }
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val trail = StringBuilder()
            val item = RecvItem()
            hop()
            trail.append(if (conn.reactor.onReactorThread) "R" else "o")
            val snapshot = conn.read()   // posted to the reactor; resumes there
            trail.append(if (conn.reactor.onReactorThread) "R" else "o")
            val bytes = StringBuilder()
            while (conn.tryGetItem(snapshot, item)) {
                for (i in 0 until item.length) bytes.append(item[i].toInt().toChar())
                conn.returnBuffer(item)
            }
            hop()
            trail.append(if (conn.reactor.onReactorThread) "R" else "o")
            conn.write(bytes.toString().encodeToByteArray())
            conn.flush()   // posted; resumes on the reactor once the kernel has the bytes
            trail.append(if (conn.reactor.onReactorThread) "R" else "o")
            hop()
            seen.store(trail.toString())
            conn.decRef()   // posted
        }
        try {
            TestServer(testConfig(), handler).use { server ->
                server.connect().use { client ->
                    client.write("hop")
                    assertEquals("hop", client.readBytes(3).decodeToString())
                    assertEquals(-1, client.read(), "no FIN after the off-reactor decRef")
                }
            }
        } finally {
            threads.joinAll()
        }
        assertEquals("oRoR", seen.load(), "thread before and after read, write and flush (o = off, R = reactor)")
    }

    @Test
    fun `the pipe reader waits for more when its parser examined everything`() {
        val request = "GET / HTTP/1.1\r\n\r\n".encodeToByteArray()
        val end = "\r\n\r\n".encodeToByteArray()
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray()
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val pipe = TcpDuplexPipe(conn)
            try {
                while (true) {
                    val result = pipe.input.read()
                    var consumed = 0L
                    while (true) {
                        val at = result.buffer.indexOf(end, consumed)
                        if (at < 0) break
                        consumed = at + end.size
                        pipe.output.write(response)
                    }
                    if (pipe.output.unflushedBytes > 0) pipe.output.flush()
                    pipe.input.advanceTo(consumed, result.buffer.length)
                    if (result.isCompleted) break
                }
            } finally {
                pipe.input.complete()
            }
        }
        TestServer(testConfig(), handler).use { server ->
            server.connect().use { client ->
                // One request in three fragments: a single response, once the last one lands.
                client.write(request.copyOfRange(0, 5))
                sleepMs(20)
                client.write(request.copyOfRange(5, 12))
                sleepMs(20)
                client.write(request.copyOfRange(12, request.size))
                assertContentEquals(response, client.readBytes(response.size))

                // Two in one send: two responses.
                client.write(request + request)
                assertContentEquals(response + response, client.readBytes(response.size * 2))
            }
        }
    }

    @Test
    fun `shutdownWrite sends a FIN after the response and keeps reading`() {
        val afterFin = AtomicReference("")
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            conn.read().let { s -> while (conn.tryGetItem(s, item)) conn.returnBuffer(item) }
            conn.write("bye".encodeToByteArray())
            conn.flush()
            conn.shutdownWrite()
            val more = StringBuilder()
            while (true) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    for (i in 0 until item.length) more.append(item[i].toInt().toChar())
                    conn.returnBuffer(item)
                }
                if (snapshot.isClosed) break
            }
            afterFin.store(more.toString())
        }
        TestServer(testConfig(), handler).use { server ->
            server.connect().use { client ->
                client.write("hello")
                assertEquals("bye", client.readBytes(3).decodeToString())
                assertEquals(-1, client.read(), "no FIN after the response")
                client.write("more")
                client.shutdownOutput()
                val deadline = Clock.monotonicMs() + 5_000
                while (afterFin.load().isEmpty() && Clock.monotonicMs() < deadline) sleepMs(5)
            }
        }
        assertEquals("more", afterFin.load(), "what the handler read after its FIN")
    }

    @Test
    fun `a handler that lets go early leaves its peer a FIN`() {
        val handlerReturned = AtomicInt(0)
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            conn.read()
            conn.write("x".encodeToByteArray())
            conn.flush()
            conn.decRef()
            RingTimer(conn.reactor).delay(500)   // still running when the peer sees the FIN
            handlerReturned.store(1)
        }
        TestServer(testConfig(), handler).use { server ->
            server.connect().use { client ->
                client.write("go")
                assertEquals("x", client.readBytes(1).decodeToString())
                assertEquals(-1, client.read(), "no FIN after decRef")
                if (handlerReturned.load() != 0) fail("the FIN waited for the handler to return")
            }
        }
    }
}
