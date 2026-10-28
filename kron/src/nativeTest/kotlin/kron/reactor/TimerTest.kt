package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.readlink

/**
 * RingTimer: a wait elapses, reports expiry rather than an error, one timer serves a connection's whole life,
 * overlapping waits keep their own deadlines, and a wait keeps its deadline when the op table grows before it reached
 * the kernel. And the reactor's ticker: a long pass does not push the next tick a whole interval out.
 */
class TimerTest {
    // Answers each line "<ms>" after that many milliseconds - or "errno <n>" for a wait that ended any other way than
    // expiring - holding one timer for the connection's life and arming it again per request, as a caller is meant to.
    private val delayHandler: suspend (TcpConnection) -> Unit = { conn ->
        val timer = RingTimer(conn.reactor)
        val item = RecvItem()
        var pending = ""

        while (true) {
            val snapshot = conn.read()
            while (conn.tryGetItem(snapshot, item)) {
                if (item.hasBuffer) {
                    val bytes = ByteArray(item.length)
                    item.copyTo(bytes)
                    pending += bytes.decodeToString()
                }
                conn.returnBuffer(item)
            }

            while (true) {
                val end = pending.indexOf('\n')
                if (end < 0) {
                    break
                }

                val ms = pending.substring(0, end).toLong()
                pending = pending.substring(end + 1)

                val result = timer.delay(ms)
                conn.write((if (RingTimer.expired(result)) "$ms\n" else "errno $result\n").encodeToByteArray())
                conn.flush()
            }

            if (snapshot.isClosed) {
                break
            }
        }
    }

    // Asks for a wait of [ms]: the answer, and how long it took to come.
    private fun ask(client: TestSocket, ms: Int): Pair<String, Long> {
        val start = Clock.monotonicMs()
        client.write("$ms\n")

        val answer = StringBuilder()
        while (true) {
            val c = client.read()
            if (c < 0 || c == '\n'.code) {
                break
            }

            answer.append(c.toChar())
        }

        return answer.toString() to Clock.monotonicMs() - start
    }

    @Test
    fun `a wait elapses for as long as it was asked`() {
        TestServer(testConfig(), delayHandler).use { server ->
            server.connect().use { client ->
                val (answer, took) = ask(client, 50)

                assertEquals("50", answer)
                assertTrue(took >= 50, "answered in $took ms, short of the 50 ms asked for")
            }
        }
    }

    @Test
    fun `a wait that runs its course reports expiry - not an error`() {
        TestServer(testConfig(), delayHandler).use { server ->
            server.connect().use { client ->
                // "errno <n>" if the completion was anything but the timer expiring.
                assertEquals("5", ask(client, 5).first)
            }
        }
    }

    @Test
    fun `one timer serves a connection's whole life`() {
        TestServer(testConfig(), delayHandler).use { server ->
            server.connect().use { client ->
                val start = Clock.monotonicMs()
                repeat(5) { round ->
                    assertEquals("10", ask(client, 10).first, "round $round")
                }

                // Five waits of 10 ms, one after another on one connection.
                val took = Clock.monotonicMs() - start
                assertTrue(took >= 50, "five 10 ms waits took $took ms, so some did not happen")
            }
        }
    }

    @Test
    fun `overlapping waits keep their own deadlines`() {
        val delays = intArrayOf(80, 10, 40)
        val results = List(delays.size) { AtomicReference<Pair<String, Long>?>(null) }
        val threads = TestThreads()

        TestServer(testConfig(), delayHandler).use { server ->
            try {
                for (i in delays.indices) {
                    threads.start {
                        server.connect().use { client ->
                            results[i].store(ask(client, delays[i]))
                        }
                    }
                }
            } finally {
                threads.joinAll()
            }
        }

        for (i in delays.indices) {
            val (answer, took) = results[i].load() ?: fail("the ${delays[i]} ms wait was never answered")

            // Its own value came back, not another connection's.
            assertEquals("${delays[i]}", answer)
            assertTrue(took >= delays[i], "the ${delays[i]} ms wait answered in $took ms")
        }
    }

    // ---- The op table growing under a queued wait -------------------------------------------------------------

    private val pinnedSlots = ServerConfig(
        reactorCount = 1,
        ringEntries = 256,
        recvBufferSize = 4096,
        recvSlots = 64,
        opSlots = OP_SLOTS,
        tcp = TcpOptions(port = 0),
    )

    // A 20 ms wait, then [others] 1 ms waits in the same pass, then a poll until all of them are done; answers "<the
    // 20 ms wait's result> <its ms> <how many of the others expired>". A wait holds its op slot until its completion,
    // which the loop dispatches only once this handler suspends, so all of them hold one at once.
    private fun queueThenGrow(others: Int): suspend (TcpConnection) -> Unit = { conn ->
        val reactor = conn.reactor
        var firstResult: Int? = null
        var firstMs = -1L
        var expired = 0
        var outstanding = others + 1

        // Each a coroutine of its own, run up to its wait's submission. Never throws: it completes inside the
        // reactor's dispatch.
        fun wait(ms: Long, done: (Int) -> Unit) {
            val waiting: suspend () -> Int = {
                RingTimer(reactor).delay(ms)
            }

            waiting.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
                done(result.getOrDefault(Int.MIN_VALUE))
                outstanding--
            })
        }

        val armedAt = Clock.monotonicMs()
        wait(20) { result ->
            firstResult = result
            firstMs = Clock.monotonicMs() - armedAt
        }
        repeat(others) {
            wait(1) { result ->
                if (RingTimer.expired(result)) {
                    expired++
                }
            }
        }

        // Armed after the burst, so it fires; a wait that never does is reported, not hung on.
        val poll = RingTimer(reactor)
        while (outstanding > 0 && Clock.monotonicMs() - armedAt < 5_000) {
            poll.delay(5)
        }

        conn.write("${firstResult ?: "never"} $firstMs $expired".encodeToByteArray())
        conn.flush()
    }

    private fun queueThenGrowAt(others: Int): Triple<String, Long, Int> {
        TestServer(pinnedSlots, queueThenGrow(others)).use { server ->
            server.connect(receiveTimeoutMs = 30_000).use { client ->
                val fields = client.readToEnd().decodeToString().split(' ')
                return Triple(fields[0], fields[1].toLong(), fields[2].toInt())
            }
        }
    }

    @Test
    fun `a wait queued before the op table grows keeps its deadline`() {
        // Past the first OP_SLOTS waits the table grows and its deadline block moves - always, as the new block is
        // allocated before the old is freed - while the 20 ms wait's submission is still queued and points into the
        // old one. It keeps its deadline only if that queue reaches the kernel before the old block is freed.
        val others = OP_SLOTS + 36
        val (first, ms, expired) = queueThenGrowAt(others)

        assertEquals(
            "${RingTimer.ETIME}", first,
            if (first == "never") {
                "the 20 ms wait queued before the growth had still not fired 5 s later"
            } else {
                "the 20 ms wait queued before the growth completed with $first after $ms ms, not ETIME"
            },
        )
        assertTrue(ms >= 20, "the 20 ms wait queued before the growth expired after $ms ms")
        assertEquals(others, expired)
    }

    @Test
    fun `control - the same burst within the op table's first slots keeps every deadline`() {
        // With the 20 ms wait and the poll, short of the table: it never grows, so nothing moves under the queue.
        val others = OP_SLOTS - 4
        val (first, ms, expired) = queueThenGrowAt(others)

        assertEquals("${RingTimer.ETIME}", first)
        assertTrue(ms >= 20, "the 20 ms wait expired after $ms ms")
        assertEquals(others, expired)
    }

    // ---- The ticker -------------------------------------------------------------------------------------------

    // This thread's id: /proc/thread-self links to <pid>/task/<tid>.
    private fun threadId(): Int = memScoped {
        val link = allocArray<ByteVar>(64)
        val n = readlink("/proc/thread-self", link, 63.convert())
        check(n > 0) { "readlink(/proc/thread-self) failed" }
        link[n.toInt()] = 0.toByte()

        link.toKString().substringAfterLast('/').toInt()
    }

    private fun hex(field: String): Long = field.removePrefix("0x").toLong(16)

    // The timeout a thread is parked with in io_uring_enter, from /proc/self/task/<tid>/syscall and the getevents arg
    // it points at, in the ring's own memory. Null when the thread is not in a timed wait, or left it while read.
    private fun parkedWaitMs(tid: Int): Long? {
        val path = "/proc/self/task/$tid/syscall"
        val first = readFileBytes(path)?.decodeToString() ?: return null

        // io_uring_enter(fd, to_submit, min_complete, flags, arg, argsz), and IORING_ENTER_EXT_ARG in its flags.
        val fields = first.trim().split(' ')
        if (fields.size < 7 || fields[0] != "426" || hex(fields[4]) and 8L == 0L) {
            return null
        }

        // io_uring_getevents_arg { u64 sigmask; u32 sigmask_sz; u32 min_wait_usec; u64 ts }
        val ts = getLong(hex(fields[5]) + 16)
        val ms = getLong(ts) * 1000 + getLong(ts + 8) / 1_000_000

        return if (readFileBytes(path)?.decodeToString() == first) ms else null
    }

    @Test
    fun `the wait after a pass that ran into the tick interval asks only for what is left of it`() {
        // The wait is bounded by the next tick, less the clock. Read before the pass's work, that clock is as old as
        // the work: after a pass that took 150 of the 250 ms, the reactor would ask for the whole 250 again, and the
        // tick came 150 ms late. Asserted on the timeout the parked reactor handed the kernel, not on timing.
        if (readFileBytes("/proc/self/syscall") == null) {
            println("skipped: this kernel has no /proc/<pid>/syscall to read a parked wait from")
            return
        }

        val tid = AtomicInt(0)
        val runs = AtomicInt(0)
        val ready = Latch(1)

        val server = KronServer(
            ServerConfig(reactorCount = 1, ringEntries = 256),
            onStart = { reactor ->
                tid.store(threadId())
                reactor.addTicker {
                    // Every other run stands in for a long completion batch or handler.
                    if (runs.load() % 2 == 1) {
                        sleepMs(WORK_MS)
                    }

                    runs.addAndFetch(1)
                }

                ready.countDown()
            },
            handler = {},
        ).start()

        try {
            assertTrue(ready.await(10_000), "the reactor never started")

            // A wait sampled while runs stays at n is the one after run n, counting from 1: the even ones are long.
            val afterLong = ArrayList<Long>()
            val afterShort = ArrayList<Long>()
            val deadline = Clock.monotonicMs() + 20_000
            while ((afterLong.isEmpty() || afterShort.isEmpty()) && Clock.monotonicMs() < deadline) {
                val n = runs.load()
                val asked = parkedWaitMs(tid.load())
                if (asked != null && n > 0 && runs.load() == n) {
                    (if (n % 2 == 0) afterLong else afterShort).add(asked)
                }

                sleepMs(1)
            }

            assertTrue(
                afterShort.isNotEmpty() && afterLong.isNotEmpty(),
                "the reactor was not seen parked after both kinds of run (${afterShort.size} short, ${afterLong.size} long)",
            )

            // Control: after a short run the next tick is a whole interval away, and the probe sees that.
            assertTrue(
                afterShort.min() > Reactor.TICK_MS / 2,
                "after a short run the wait asked for ${afterShort.min()} ms, so the probe is not reading it",
            )

            // +1 is the wait's own rounding up, and the rest covers the clock's.
            assertTrue(
                afterLong.max() <= Reactor.TICK_MS - WORK_MS + 10,
                "after a $WORK_MS ms run the wait asked for ${afterLong.max()} ms with the tick due in " +
                    "${Reactor.TICK_MS - WORK_MS}: it was computed from the clock read before the run",
            )
        } finally {
            server.close()
        }
    }

    private companion object {
        // Small, so the bursts that straddle it stay small.
        const val OP_SLOTS = 64

        const val WORK_MS = 150L
    }
}
