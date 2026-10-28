package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals

/** Full garbage collections, forced from the test thread while reactors move data and hold handlers parked in read. */
@OptIn(NativeRuntimeApi::class)
class GcTest {
    private val collections = AtomicInt(0)
    private val handlersEnded = AtomicInt(0)

    private fun counted(handler: suspend (TcpConnection) -> Unit): suspend (TcpConnection) -> Unit = { conn ->
        try {
            handler(conn)
        } finally {
            handlersEnded.addAndFetch(1)
        }
    }

    // echo, through the pipe: each segment copied into the output, consumed, flushed, until the peer is gone.
    private suspend fun pipeEcho(conn: TcpConnection) {
        val pipe = TcpDuplexPipe(conn)
        try {
            while (true) {
                val result = pipe.input.read()
                val buffer = result.buffer
                for (i in 0 until buffer.segmentCount) {
                    val length = buffer.segmentLength(i)
                    Memory.copy(buffer.segmentAddress(i), pipe.output.getBuffer(length), length.toLong())
                    pipe.output.advance(length)
                }

                pipe.input.advanceTo(buffer.length)
                if (pipe.output.unflushedBytes > 0) {
                    pipe.output.flush()
                }
                if (result.isCompleted) {
                    return
                }
            }
        } finally {
            pipe.input.complete()
            pipe.output.complete()
        }
    }

    // The byte at [offset] of connection [seed]'s stream: its own sequence, so bytes lost, repeated, reordered or
    // crossed from another connection show.
    private fun byteAt(seed: Int, offset: Long): Byte = ((offset * 131 + seed * 7919 + (offset ushr 11)) and 0xFF).toByte()

    // Sends [seed]'s stream in chunks of many sizes and reads each back before the next, so neither side's buffers
    // fill; goes on past TOTAL until three collections have run during it. True when every byte came back as sent.
    private fun transfer(port: Int, seed: Int, deadline: Long): Boolean {
        TestSocket().use { socket ->
            socket.setReceiveTimeout(20_000)
            socket.connect(port)

            val startedAt = collections.load()
            var offset = 0L
            var round = 0
            while (offset < TOTAL || collections.load() - startedAt < 3) {
                if (Clock.monotonicMs() > deadline) {
                    return false
                }

                val size = CHUNKS[round++ % CHUNKS.size]
                val chunk = ByteArray(size) { byteAt(seed, offset + it) }
                socket.write(chunk)
                if (!socket.readBytes(size).contentEquals(chunk)) {
                    return false
                }

                offset += size
            }
        }

        return true
    }

    // A connection whose handler has echoed one message and gone back to wait in read.
    private fun parkedOn(port: Int, i: Int): TestSocket {
        val socket = TestSocket()
        try {
            socket.setReceiveTimeout(10_000)
            socket.connect(port)
            socket.write("hello $i")
            check(socket.readBytes("hello $i".length).decodeToString() == "hello $i") { "parked connection $i was not served" }
            return socket
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }

    @Test
    fun `transfers and parked handlers come through repeated collections intact`() {
        val threads = TestThreads()
        val finished = AtomicInt(0)
        val intact = AtomicInt(0)
        val parked = ArrayList<TestSocket>()

        val raw = TestServer(testConfig(recvSlots = 256), counted(::echo))
        val piped = try {
            TestServer(testConfig(recvSlots = 256), counted(::pipeEcho))
        } catch (e: Throwable) {
            raw.close()
            throw e
        }

        try {
            for (i in 0 until PARKED_RAW) {
                parked.add(parkedOn(raw.port, i))
            }
            for (i in 0 until PARKED_PIPE) {
                parked.add(parkedOn(piped.port, PARKED_RAW + i))
            }

            val go = Latch(1)
            val deadline = Clock.monotonicMs() + 60_000
            for (i in 0 until RAW + PIPE) {
                val port = if (i < RAW) raw.port else piped.port
                threads.start {
                    try {
                        if (go.await(10_000) && transfer(port, i, deadline)) {
                            intact.addAndFetch(1)
                        }
                    } finally {
                        finished.addAndFetch(1)
                    }
                }
            }

            go.countDown()
            while (finished.load() < RAW + PIPE && Clock.monotonicMs() < deadline) {
                GC.collect()
                collections.addAndFetch(1)
            }

            assertEquals(RAW + PIPE, finished.load(), "transfers finished")
            assertEquals(RAW + PIPE, intact.load(), "transfers that got every byte back as sent")

            // Parked through every collection, the handlers still answer.
            parked.forEachIndexed { i, socket ->
                val message = "again $i"
                socket.write(message)
                assertEquals(message, socket.readBytes(message.length).decodeToString(), "parked connection $i")
            }

            parked.forEach {
                it.close()
            }
            parked.clear()

            // And every handler ends once its peer has gone.
            val total = RAW + PIPE + PARKED_RAW + PARKED_PIPE
            val until = Clock.monotonicMs() + 10_000
            while (handlersEnded.load() < total && Clock.monotonicMs() < until) {
                sleepMs(5)
            }
            assertEquals(total, handlersEnded.load(), "handlers that ended")
        } finally {
            parked.forEach {
                it.close()
            }
            raw.close()
            piped.close()
            threads.joinAll()
        }
    }

    private companion object {
        const val RAW = 32
        const val PIPE = 8
        const val PARKED_RAW = 4
        const val PARKED_PIPE = 2
        const val TOTAL = 1L shl 20
        val CHUNKS = intArrayOf(1, 7, 1500, 4096, 4097, 9000, 16384, 65536, 32768, 333)
    }
}
