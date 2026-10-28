package kron

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** [TcpConnection]'s write side as its docs promise: getBuffer and advance, the byte counts, native writes, a gone peer. */
class WriteContractTest {
    // A 4 KiB slab to start from, so modest writes outgrow it.
    private val config = testConfig(TcpOptions(port = 0, writeSlabSize = SLAB))

    private fun pattern(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) xor (it ushr 9)).toByte() }

    // Takes everything [snapshot] holds and hands it back.
    private fun discard(conn: TcpConnection, snapshot: RecvSnapshot) {
        val item = RecvItem()
        while (conn.tryGetItem(snapshot, item)) {
            conn.returnBuffer(item)
        }
    }

    // Runs [handler] for one client that sends "go" and reads [expected] back; returns what the handler recorded.
    private fun exchange(expected: ByteArray, handler: suspend (TcpConnection, MutableList<String>) -> Unit): List<String> {
        val recorded = AtomicReference<List<String>?>(null)

        TestServer(config, { conn ->
            val notes = ArrayList<String>()
            try {
                handler(conn, notes)
            } catch (e: Throwable) {
                notes.add("threw ${e::class.simpleName}: ${e.message}")
            } finally {
                recorded.store(notes)
            }
        }).use { server ->
            server.connect(receiveTimeoutMs = 10_000).use { client ->
                client.write("go")
                assertContentEquals(expected, client.readBytes(expected.size), "what the client received")
                assertEquals(-1, client.read(), "the handler did not end")
            }
        }

        return assertNotNull(recorded.load(), "the handler never ran")
    }

    @Test
    fun `getBuffer larger than the slab grows it - and the bytes arrive`() {
        val body = pattern(100_000, 1)

        val notes = exchange(body) { conn, notes ->
            discard(conn, conn.read())

            notes.add("writable ${conn.writableBytes}")
            val address = conn.getBuffer(body.size)
            notes.add("writable after getBuffer(${body.size}) >= it: ${conn.writableBytes >= body.size}")

            Memory.copy(body, 0, address, body.size)
            conn.advance(body.size)
            notes.add("written ${conn.writtenBytes}")
            conn.flush()
        }

        assertEquals(listOf("writable $SLAB", "writable after getBuffer(100000) >= it: true", "written 100000"), notes)
    }

    @Test
    fun `advance past what getBuffer handed out throws - and commits nothing`() {
        val body = pattern(SLAB, 2)

        val notes = exchange(body) { conn, notes ->
            discard(conn, conn.read())

            val address = conn.getBuffer(10)
            val handedOut = conn.writableBytes
            notes.add("handed out $handedOut")

            for (count in listOf(handedOut + 1, Int.MAX_VALUE, -1)) {
                val refused = runCatching { conn.advance(count) }.exceptionOrNull()
                notes.add("advance($count): ${refused?.let { it::class.simpleName } ?: "taken"}")
            }
            notes.add("written ${conn.writtenBytes}")

            // The control: exactly what was handed out is taken.
            Memory.copy(body, 0, address, handedOut)
            conn.advance(handedOut)
            notes.add("written ${conn.writtenBytes}")
            conn.flush()
        }

        assertEquals(
            listOf(
                "handed out $SLAB",
                "advance(${SLAB + 1}): IllegalArgumentException",
                "advance(${Int.MAX_VALUE}): IllegalArgumentException",
                "advance(-1): IllegalArgumentException",
                "written 0",
                "written $SLAB",
            ),
            notes,
        )
    }

    @Test
    fun `writableBytes and writtenBytes track writes - growth and flushes included`() {
        val body = pattern(4100, 3)

        val notes = exchange(body) { conn, notes ->
            discard(conn, conn.read())
            fun note(step: String) = notes.add("$step: written ${conn.writtenBytes}, writable ${conn.writableBytes}")

            note("fresh")
            conn.write(body, 0, 1000)
            note("write 1000")

            Memory.copy(body, 1000, conn.getBuffer(100), 100)
            note("getBuffer(100)")
            conn.advance(100)
            note("advance 100")

            // 3000 more is past the 4 KiB slab: it doubles.
            conn.write(body, 1100, 3000)
            note("write 3000")

            conn.flush()
            note("flush")
        }

        assertEquals(
            listOf(
                "fresh: written 0, writable 4096",
                "write 1000: written 1000, writable 3096",
                "getBuffer(100): written 1000, writable 3096",
                "advance 100: written 1100, writable 2996",
                "write 3000: written 4100, writable 4092",
                "flush: written 0, writable 8192",   // the slab keeps its growth until the connection is recycled
            ),
            notes,
        )
    }

    @Test
    fun `write from native memory sends exactly its bytes - none or a slab's worth or past the slab`() {
        val body = pattern(20_000, 4)

        val notes = exchange(body) { conn, notes ->
            discard(conn, conn.read())

            val native = Native.malloc(body.size.toLong())
            try {
                Memory.copy(body, 0, native, body.size)

                conn.write(native, 0)
                conn.write(native, 1)
                conn.write(native + 1, SLAB - 1)   // the slab, exactly full
                notes.add("written ${conn.writtenBytes}, writable ${conn.writableBytes}")
                conn.write(native + SLAB, body.size - SLAB)

                val refused = runCatching { conn.write(native, -1) }.exceptionOrNull()
                notes.add("write(-1): ${refused?.let { it::class.simpleName } ?: "taken"}")
            } finally {
                Native.free(native)   // write copies: nothing it staged points here
            }

            notes.add("written ${conn.writtenBytes}")
            conn.flush()
        }

        assertEquals(listOf("written $SLAB, writable 0", "write(-1): IllegalArgumentException", "written ${body.size}"), notes)
    }

    @Test
    fun `a write and flush after the peer is gone are dropped - not thrown`() {
        val outcome = AtomicReference<String?>(null)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val result = runCatching {
                var snapshot = conn.read()
                while (!snapshot.isClosed) {
                    discard(conn, snapshot)
                    snapshot = conn.read()
                }
                discard(conn, snapshot)

                // Past the slab, so the drop is not the slab's limit at work.
                conn.write(ByteArray(64 * 1024))
                Memory.putByte(conn.getBuffer(1), 1)
                conn.advance(1)
                val staged = conn.writtenBytes

                conn.flush()
                val afterFlush = conn.writtenBytes
                conn.flush()

                "staged $staged, after flush $afterFlush, closed ${conn.isClosed}"
            }

            outcome.store(result.getOrElse { "threw ${it::class.simpleName}: ${it.message}" })
        }

        TestServer(config, handler).use { server ->
            server.connect().use { client ->
                client.write("bye")
            }

            val deadline = Clock.monotonicMs() + 5_000
            while (outcome.load() == null && Clock.monotonicMs() < deadline) {
                sleepMs(5)
            }
        }

        assertEquals("staged ${64 * 1024 + 1}, after flush 0, closed true", outcome.load())
    }

    private companion object {
        const val SLAB = 4096
    }
}
