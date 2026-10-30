package kron

import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Recv buffers a pipe reader still holds when its connection is recycled, or when it is completed: they go back to
 * the reactor's group either way, so a handler that returns early strands none.
 */
class RecvBufferReclaimTest {
    // Small enough that a buffer stranded per connection empties it well inside the run.
    private val smallGroup = ServerConfig(
        reactorCount = 1,
        ringEntries = 256,
        recvBufferSize = 1024,
        recvSlots = RECV_SLOTS,
        tcp = TcpOptions(port = 0, writeSlabSize = 4096, poolMax = 64, recvQueueEntries = 16),
    )

    // Opens CONNECTIONS connections in turn, each sending [request] and expecting a one-byte answer: how many were
    // answered. A buffer stranded per connection empties the group after about RECV_SLOTS of them, and the rest go
    // unanswered.
    private fun driveConnections(port: Int, request: String = "x", delayMs: Long = 0): Int {
        var answered = 0

        for (i in 0 until CONNECTIONS) {
            try {
                TestSocket().use { client ->
                    client.setReceiveTimeout(2_000)
                    client.connect(port)
                    if (delayMs > 0) {
                        sleepMs(delayMs)   // the handler reaches the state under test first
                    }

                    client.write(request)
                    if (client.read() < 0) {
                        return answered
                    }
                }
            } catch (e: IOException) {
                return answered   // the group is empty: this connection can no longer be read from
            }

            answered++
        }

        return answered
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

    @Test
    fun `a handler that never completes its reader does not strand its buffers`() {
        // The leaky shape on purpose: read, which takes a buffer into the reader's chain, answer, and return without
        // complete() or advanceTo(). Every connection walks off with at least one buffer.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val reader = TcpPipeReader(conn)
            val result = reader.read()

            if (!result.buffer.isEmpty) {
                conn.write(DOT)
                conn.flush()
            }
        }

        TestServer(smallGroup, handler).use { server ->
            // Without the reclaim at recycle this stops at about RECV_SLOTS.
            assertEquals(CONNECTIONS, driveConnections(server.port))
        }
    }

    @Test
    fun `completing one reader does not unregister a live one`() {
        // The recycle reclaims from the one holder a connection keeps: a reader that holds nothing, completed after a
        // second one took its place, must not evict that live holder.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val first = TcpPipeReader(conn)
            val second = TcpPipeReader(conn)   // now the holder
            val result = second.read()

            if (!result.buffer.isEmpty) {
                conn.write(DOT)
                conn.flush()
            }

            first.complete()   // holds nothing, and must not evict second, which never completes
        }

        TestServer(smallGroup, handler).use { server ->
            assertEquals(CONNECTIONS, driveConnections(server.port))
        }
    }

    @Test
    fun `completing a reader with a read still parked does not strand what lands after`() {
        // complete() does not disarm a parked read: the next recv resumes it, and the reader takes in buffers after it
        // was completed, which the recycle must still reclaim.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val reader = TcpPipeReader(conn)
            val parked = launch { reader.read() }   // the client has not sent yet
            reader.complete()

            // The client's bytes land in the completed reader.
            if (waitUntil(conn.reactor, 5_000) { parked.load() != null }) {
                val result = parked.load()!!.getOrNull()
                if (result != null && !result.buffer.isEmpty) {
                    conn.write(DOT)
                    conn.flush()
                }
            }
        }

        TestServer(smallGroup, handler).use { server ->
            assertEquals(CONNECTIONS, driveConnections(server.port, delayMs = 30))
        }
    }

    @Test
    fun `a reader stopped mid-slice does not strand its buffer`() {
        // ioxide's Stream adapter shape, on the pipe reader: one byte of eight consumed, the rest of the slice held,
        // and the reader neither drained nor completed.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val reader = TcpPipeReader(conn)
            val result = reader.read()

            if (!result.buffer.isEmpty) {
                reader.advanceTo(1)
                conn.write(DOT)
                conn.flush()
            }
        }

        TestServer(smallGroup, handler).use { server ->
            assertEquals(CONNECTIONS, driveConnections(server.port, request = "xxxxxxxx"))
        }
    }

    @Test
    fun `completing a reader hands its buffers back at once - without waiting for recycle`() {
        // One connection, open for the whole run, so the recycle never comes and only complete() can give a buffer
        // back. A fresh reader per round, each taking one byte of eight and completing with the rest still held.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            while (true) {
                val reader = TcpPipeReader(conn)
                val result = reader.read()
                if (result.buffer.isEmpty) {
                    break   // the peer is gone
                }

                reader.advanceTo(1)
                reader.complete()

                conn.write(DOT)
                conn.flush()
            }
        }

        TestServer(smallGroup, handler).use { server ->
            server.connect(receiveTimeoutMs = 2_000).use { client ->
                var answered = 0
                try {
                    while (answered < CONNECTIONS) {
                        client.write("xxxxxxxx")
                        if (client.read() < 0) {
                            break
                        }

                        answered++
                    }
                } catch (e: IOException) {
                    // the group is empty, and this connection can no longer be read from
                }

                // Many times the group, on one connection that never recycled.
                assertEquals(CONNECTIONS, answered)
            }
        }
    }

    @Test
    fun `control - completing the reader returns its buffers too`() {
        // The path that already worked, on the same tiny group: if this fails, something else drains the group and
        // the tests above prove nothing.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val reader = TcpPipeReader(conn)
            try {
                val result = reader.read()
                if (!result.buffer.isEmpty) {
                    conn.write(DOT)
                    conn.flush()
                }
            } finally {
                reader.complete()
            }
        }

        TestServer(smallGroup, handler).use { server ->
            assertEquals(CONNECTIONS, driveConnections(server.port))
        }
    }

    private companion object {
        const val RECV_SLOTS = 16

        // Several times the group, so a leak cannot hide behind slack.
        const val CONNECTIONS = 64

        val DOT = ".".encodeToByteArray()
    }
}
