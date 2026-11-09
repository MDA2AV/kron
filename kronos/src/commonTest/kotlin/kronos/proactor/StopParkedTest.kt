package kronos

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a stop does to what is waiting on the proactor (#29). A handler parked in a read, a flush or a RingTimer wait
 * gets to unwind, its finally run and its connection freed; a call from another thread after the stop returns; and a
 * handler that lets go after the stop frees its connection then.
 */
class StopParkedTest {
    // A handler parked on the proactor, and the server stopped under it: true once its finally ran.
    private fun stopUnder(
        handler: suspend (TcpConnection, Latch) -> Unit,
        client: (Int) -> TestSocket = { port -> connect(port) },
    ): Stopped {
        val held = AtomicReference<TcpConnection?>(null)
        val parked = Latch(1)
        val finished = Latch(1)

        val server = TestServer(testConfig()) { conn ->
            held.store(conn)
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
            return Stopped(held.load()!!, finished.count == 0)
        } finally {
            socket.close()
        }
    }

    private class Stopped(val conn: TcpConnection, val finallyRan: Boolean)

    private fun connect(port: Int): TestSocket = TestSocket().also {
        it.setReceiveTimeout(5_000)
        it.connect(port)
    }

    @Test
    fun `a handler parked in a read runs its finally and its connection is freed`() {
        val stopped = stopUnder({ conn, parked ->
            parked.countDown()
            conn.read()
        })

        assertTrue(stopped.finallyRan, "the handler is still parked in its read, its finally not run")
        assertEquals(0L, stopped.conn.writeBuffer, "the write slab was not freed")
    }

    @Test
    fun `a handler parked in a RingTimer wait runs its finally - the wait cancelled`() {
        val result = AtomicInt(0)
        val stopped = stopUnder({ conn, parked ->
            parked.countDown()
            result.store(RingTimer(conn.proactor).delay(600_000))
        })

        assertTrue(stopped.finallyRan, "the handler is still parked in its wait, its finally not run")
        assertEquals(RingTimer.ECANCELED, result.load())
        assertEquals(0L, stopped.conn.writeBuffer, "the write slab was not freed")
    }

    @Test
    fun `a handler parked in a flush its client holds up runs its finally`() {
        val stopped = stopUnder(
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

        assertTrue(stopped.finallyRan, "the handler is still parked in its flush, its finally not run")
        assertEquals(0L, stopped.conn.writeBuffer, "the write slab was not freed")
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
            val conn = held.load()!!
            calls(conn)

            // The handler returns here, on this thread: its release, refused a post, frees the connection.
            handler.resume(Unit)
            assertEquals(0L, conn.writeBuffer, "the write slab was not freed once the handler let go")
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
    fun `a RingTimer wait from another thread after the stop returns cancelled and a raw submitTimeout throws`() {
        afterTheStop { conn ->
            val waited = assertNotNull(callHere { RingTimer(conn.proactor).delay(1) }, "delay() never returned")
            assertEquals(RingTimer.ECANCELED, waited.getOrThrow())

            assertFailsWith<IllegalStateException> {
                conn.proactor.submitTimeout(1_000_000) {}
            }
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
}
