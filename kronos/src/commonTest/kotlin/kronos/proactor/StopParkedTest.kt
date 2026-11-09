package kronos

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a stop does to what is waiting on the proactor (#29). A handler parked in a read, a flush or a RingTimer wait
 * gets to unwind, its finally run; a call from another thread after the stop returns; and what was queued for a
 * socket the stop closes goes to that socket's client, not to whatever takes its number next.
 */
class StopParkedTest {
    // A handler parked on the proactor, and the server stopped under it: true once its finally ran.
    private fun stopUnder(
        handler: suspend (TcpConnection, Latch) -> Unit,
        client: (Int) -> TestSocket = { port -> connect(port) },
    ): Boolean {
        val parked = Latch(1)
        val finished = Latch(1)

        val server = TestServer(testConfig()) { conn ->
            try {
                handler(conn, parked)
            } finally {
                finished.countDown()
            }
        }

        val socket = client(server.port)
        try {
            assertTrue(parked.await(5_000), "the handler never parked")
            sleepMs(100)   // a flush's send reaches the kernel, and the handler's suspension the proactor's loop

            server.close()   // stops the proactor and joins its thread: the teardown is over when this returns
            return finished.count == 0
        } finally {
            socket.close()
        }
    }

    private fun connect(port: Int): TestSocket = TestSocket().also {
        it.setReceiveTimeout(5_000)
        it.connect(port)
    }

    @Test
    fun `a handler parked in a read runs its finally`() {
        val finallyRan = stopUnder({ conn, parked ->
            parked.countDown()
            conn.read()
        })

        assertTrue(finallyRan, "the handler is still parked in its read, its finally not run")
    }

    @Test
    fun `a handler parked in a RingTimer wait runs its finally - the wait cancelled`() {
        val result = AtomicInt(0)
        val finallyRan = stopUnder({ conn, parked ->
            parked.countDown()
            result.store(RingTimer(conn.proactor).delay(600_000))
        })

        assertTrue(finallyRan, "the handler is still parked in its wait, its finally not run")
        assertEquals(RingTimer.ECANCELED, result.load())
    }

    @Test
    fun `a handler parked in a flush its client holds up runs its finally`() {
        val finallyRan = stopUnder(
            { conn, parked ->
                conn.write(ByteArray(16 shl 20))   // far more than both sockets' buffers hold
                parked.countDown()
                conn.flush()
            },
            client = { port ->
                // Never reads, and takes little: the send stays parked in the kernel.
                TestSocket().also {
                    it.setReceiveBuffer(64 * 1024)
                    it.connect(port)
                }
            },
        )

        assertTrue(finallyRan, "the handler is still parked in its flush, its finally not run")
    }

    @Test
    fun `a send the last pass left queued goes to its own client - not to the socket that takes its number`() {
        NumberTaker().use { taker ->
            val resumed = Latch(1)
            val server = TestServer(fourOpSlots()) { conn ->
                awaitRequest(conn)

                // On the proactor thread the send is queued in this pass, and the stop ends the loop before it is
                // submitted.
                val fd = conn.clientFd
                conn.write(QUEUED)
                conn.proactor.stop()
                conn.flush()

                // Resumed by the stop with the socket closed: another socket takes the number, and the ring is entered.
                taker.take(fd)
                enterTheRing(conn.proactor)
                resumed.countDown()
            }

            val client = connect(server.port)
            try {
                client.write("hi")
                assertTrue(resumed.await(10_000), "the handler never resumed from its flush")
                server.close()

                taker.assertNothingReceived()
                assertContentEquals(QUEUED, client.readToEnd(), "what the client received")
            } finally {
                client.close()
                server.close()
            }
        }
    }

    @Test
    fun `a send a handler woken by the stop queues on another connection goes to that client - not to the socket that takes its number`() {
        NumberTaker().use { taker ->
            val parked = Latch(2)
            val resumed = Latch(1)
            val conns = ArrayList<TcpConnection>()   // proactor thread only

            val server = TestServer(fourOpSlots()) { conn ->
                awaitRequest(conn)
                conns.add(conn)
                parked.countDown()

                conn.read()   // until the stop closes it

                // The stop goes through its connections by descriptor: the lower one wakes with the other still open.
                val other = if (conns[0] === conn) conns[1] else conns[0]
                if (conn.clientFd < other.clientFd) {
                    val fd = other.clientFd
                    other.write(QUEUED)
                    other.flush()

                    taker.take(fd)
                    enterTheRing(conn.proactor)
                    resumed.countDown()
                }
            }

            val first = connect(server.port)
            val second = connect(server.port)
            try {
                first.write("hi")
                second.write("hi")
                assertTrue(parked.await(5_000), "the handlers never parked")

                server.close()
                assertEquals(0, resumed.count, "the woken handler never resumed from its flush on the other connection")

                taker.assertNothingReceived()
                assertContentEquals(QUEUED, first.readToEnd() + second.readToEnd(), "what the clients received")
            } finally {
                first.close()
                second.close()
                server.close()
            }
        }
    }

    // A handler waiting on something outside kronos, the server stopped under it: the calls are made from this thread
    // after the stop, then the handler is let go, here too.
    private fun afterTheStop(calls: (TcpConnection) -> Unit) {
        val held = AtomicReference<TcpConnection?>(null)
        val waiting = AtomicReference<Continuation<Unit>?>(null)

        val server = TestServer(testConfig()) { conn ->
            held.store(conn)
            suspendCoroutine<Unit> { waiting.store(it) }
        }

        val client = connect(server.port)
        try {
            var spins = 0
            while (waiting.load() == null && spins++ < 5_000) {
                sleepMs(1)
            }
            val handler = assertNotNull(waiting.load(), "the handler never ran")

            server.close()
            calls(held.load()!!)

            // The handler returns here, on this thread: its release is dropped with the proactor.
            handler.resume(Unit)
        } finally {
            client.close()
        }
    }

    // Starts [block] on this thread; what it returned, once it has.
    private fun <T> callHere(block: suspend () -> T): Result<T>? {
        val result = AtomicReference<Result<T>?>(null)
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result.store(it) })
        return result.load()
    }

    @Test
    fun `a read and a flush from another thread after the stop return - the connection closed`() {
        afterTheStop { conn ->
            val read = assertNotNull(callHere { conn.read() }, "read() on a stopped proactor never returned")
            assertTrue(read.getOrThrow().isClosed)

            conn.write("x".encodeToByteArray())
            assertNotNull(callHere { conn.flush() }, "flush() on a stopped proactor never returned").getOrThrow()
            assertEquals(0, conn.writtenBytes, "what was staged was kept")
        }
    }

    @Test
    fun `a pipe's read and flush from another thread after the stop return completed`() {
        afterTheStop { conn ->
            val pipe = TcpDuplexPipe(conn)

            val read = assertNotNull(callHere { pipe.input.read() }, "the pipe's read never returned").getOrThrow()
            assertTrue(read.isCompleted)
            pipe.input.complete()

            pipe.output.write("x".encodeToByteArray())
            val flushed = assertNotNull(callHere { pipe.output.flush() }, "the pipe's flush never returned")
                .getOrThrow()
            assertTrue(flushed.isCompleted)
            assertEquals(0L, pipe.output.unflushedBytes)
        }
    }

    @Test
    fun `a RingTimer wait and a raw submitTimeout from another thread after the stop complete cancelled`() {
        afterTheStop { conn ->
            val waited = assertNotNull(callHere { RingTimer(conn.proactor).delay(1) }, "delay() never returned")
            assertEquals(RingTimer.ECANCELED, waited.getOrThrow())

            val completed = AtomicInt(0)
            conn.proactor.submitTimeout(1_000_000) { completed.store(it) }
            assertEquals(RingTimer.ECANCELED, completed.load(), "submitTimeout's completion")
        }
    }

    @Test
    fun `a post from another thread after the stop is dropped`() {
        afterTheStop { conn ->
            val ran = AtomicInt(0)
            conn.proactor.post {
                ran.store(1)
            }

            sleepMs(50)
            assertEquals(0, ran.load())
        }
    }

    // A socket put on a descriptor number the stop freed, as the next accept or open anywhere in the process would
    // be: what the kernel then sends on that number reaches this pair's other end.
    private class NumberTaker : AutoCloseable {
        private val pair = TestSys.socketPair()
        private var number = -1
        private var dup2Result = 0

        // From the handler, on the proactor thread, so it throws nothing.
        fun take(number: Int) {
            this.number = number
            dup2Result = TestSys.dup2(pair[0], number)
        }

        fun assertNothingReceived() {
            assertEquals(number, dup2Result, "dup2 onto $number")

            val buffer = Native.malloc(256)
            try {
                val n = TestSys.recv(pair[1], buffer, 256, Sockets.MSG_DONTWAIT)
                assertEquals(
                    -Native.EAGAIN.toLong(), n,
                    "the socket that took number $number received bytes queued for the one the stop closed",
                )
            } finally {
                Native.free(buffer)
            }
        }

        override fun close() {
            if (number >= 0 && dup2Result == number) {
                Native.close(number)
            }

            Native.close(pair[0])
            Native.close(pair[1])
        }
    }

    private companion object {
        val QUEUED = "QUEUED-FOR-THE-CLOSED-SOCKET".encodeToByteArray()

        // With opSlots = 4 the fifth wait grows the op table, which first submits whatever is queued.
        fun fourOpSlots(): ServerConfig = ServerConfig(
            proactorCount = 1,
            ringEntries = 256,
            recvBufferSize = 4096,
            recvSlots = 64,
            opSlots = 4,
            tcp = TcpOptions(port = 0),
        )

        fun enterTheRing(proactor: Proactor) {
            repeat(8) {
                proactor.submitTimeout(600_000_000_000L) {}
            }
        }

        // The client's request, its buffers handed back.
        suspend fun awaitRequest(conn: TcpConnection) {
            val item = RecvItem()
            while (true) {
                val snapshot = conn.read()
                var received = false
                while (conn.tryGetItem(snapshot, item)) {
                    received = received || item.hasBuffer
                    conn.returnBuffer(item)
                }

                if (received || snapshot.isClosed) {
                    return
                }
            }
        }
    }
}
