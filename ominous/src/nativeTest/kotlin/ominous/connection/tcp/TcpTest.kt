package ominous

import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcpTest {
    // Connection A is recycled with its multishot recv still armed: its send fails after its own FIN, which wakes
    // nothing on the read side, so the recv's cancel is only queued when its fd is closed. The reactor is held
    // until B is in the listener's queue, so the next enter can accept B onto that fd ahead of A's -ECANCELED.
    // Returns what B got back for its "BBBB", and the fds A and B were accepted on.
    private fun nextConnectionOnTheSameFd(poolMax: Int): Triple<String, Int, Int> {
        val aStalled = Latch(1)
        val bConnected = Latch(1)
        val fds = AtomicIntArray(2)
        fds.storeAt(0, -1)
        fds.storeAt(1, -1)
        val ready = Latch(1)
        val config = ServerConfig(reactorCount = 1, tcp = TcpOptions(port = 0, poolMax = poolMax))
        val server = OminousServer(config, onStart = { ready.countDown() }) handler@{ conn ->
            val isB = aStalled.count == 0
            if (isB) fds.storeAt(1, conn.clientFd)

            val item = RecvItem()
            val first = conn.read()
            val head = StringBuilder()
            while (conn.tryGetItem(first, item)) {
                if (item.hasBuffer) for (i in 0 until item.length) head.append(item[i].toInt().toChar())
                conn.returnBuffer(item)
            }

            if (!isB && head.startsWith("AAAA")) {
                fds.storeAt(0, conn.clientFd)
                conn.shutdownWrite()
                conn.write(ByteArray(16))
                conn.flush()   // EPIPE: the reactor tears A down, its recv still armed

                // Resumed inline by the failed send, so this holds the reactor thread on purpose.
                aStalled.countDown()
                bConnected.await(5_000)
                return@handler
            }

            conn.write(head.toString().encodeToByteArray())
            conn.flush()
        }.start()
        try {
            assertTrue(ready.await(10_000), "the reactor never started")
            val port = server.reactors[0].listenPorts[0]
            TestSocket().use { a ->
                a.connect(port)
                a.write("AAAA")
                assertTrue(aStalled.await(5_000), "A's failed flush never returned")
                TestSocket().use { b ->
                    try {
                        b.setReceiveTimeout(5_000)
                        b.connect(port)
                        // Acknowledged, so B is in the listener's queue before the reactor moves on.
                        b.write("BBBB")
                        assertTrue(b.awaitAcknowledged(5_000), "B's bytes were never acknowledged")
                    } finally {
                        bConnected.countDown()
                    }
                    val got = StringBuilder()
                    try {
                        while (got.length < 4) {
                            val c = b.read()
                            if (c < 0) {
                                got.append("<EOF>")
                                break
                            }
                            got.append(c.toChar())
                        }
                    } catch (e: IOException) {
                        got.append("<${e.message}>")
                    }
                    return Triple(got.toString(), fds.loadAt(0), fds.loadAt(1))
                }
            }
        } finally {
            server.close()
        }
    }

    // Which of B's accept and A's -ECANCELED the kernel posts first within that enter varies, and here B's comes
    // first only about one round in 25, so one round rarely shows the bug: rounds repeat until one does.
    private fun firstRoundThatFails(poolMax: Int): Triple<String, Int, Int> {
        var round = Triple("", -1, -1)
        repeat(ROUNDS) {
            round = nextConnectionOnTheSameFd(poolMax)
            if (round.first != "BBBB" || round.second != round.third) return round
        }
        return round
    }

    @Test
    fun `a recycled connection's stale recv completion does not close the next connection on its fd - issue 416`() {
        // poolMax 0: every connection is a new object, so both lives start from the same generation.
        val (got, a, b) = firstRoundThatFails(poolMax = 0)
        assertEquals(a, b, "precondition: B was accepted on $b, not on A's $a")
        assertEquals("BBBB", got, "B, accepted on the fd A gave back, was closed before it was served")
    }

    @Test
    fun `control - the same stale completion misses a connection taken from the pool`() {
        // B reuses A's object, whose generation its recycle moved.
        val (got, a, b) = firstRoundThatFails(poolMax = 1024)
        assertEquals(a, b, "precondition: B was accepted on $b, not on A's $a")
        assertEquals("BBBB", got, "B was closed before it was served")
    }

    private companion object {
        const val ROUNDS = 150
    }
}
