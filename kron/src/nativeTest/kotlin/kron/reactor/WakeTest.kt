package kron

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.readlink

/**
 * The reactor's wake and its post queue: handoffs from other threads share one eventfd write until the loop's next
 * pass, a post that races the drain is never stranded, a posted task that throws does not end the reactor, and a
 * dispatcher in handlerContext brings a handler's foreign suspensions back to it.
 */
class WakeTest {
    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = Clock.monotonicMs() + timeoutMs
        while (Clock.monotonicMs() < deadline) {
            if (condition()) {
                return true
            }

            sleepMs(5)
        }

        return condition()
    }

    // ---- A posted task that throws ----------------------------------------------------------------------------

    @Test
    fun `a posted task that throws does not end the reactor`() {
        val reached = AtomicInt(0)

        TestServer(testConfig(), ::echo).use { server ->
            server.reactor.post {
                reached.store(1)
                throw IllegalStateException("a posted task threw on the reactor thread (test)")
            }

            // Proof the task ran: a reactor that had dropped it would look just as alive below.
            assertTrue(eventually(5_000) { reached.load() == 1 }, "the posted task never ran")

            repeat(3) { round ->
                server.connect().use { client ->
                    client.write("alive $round")
                    assertEquals("alive $round", client.readBytes("alive $round".length).decodeToString())
                }
            }
        }
    }

    // ---- A post racing the drain ------------------------------------------------------------------------------

    @Test
    fun `a post that races the drain is either run by it or keeps its wake`() {
        // post() queues, then writes the eventfd only if the pending flag was clear; a pass clears the flag, then
        // drains. A post the drain misses must find the flag clear and wake the loop itself, or it waits for an
        // unrelated wake - up to a tick. Too narrow to hit through a running loop, so the two halves race directly,
        // on a reactor that never runs, from the state a post landing mid-drain leaves: flag set, queue empty.
        if (Native.allowedCpus().size < 2) {
            println("skipped: the race needs two CPUs, this thread may use ${Native.allowedCpus().size}")
            return
        }

        val reactor = Reactor(0, ServerConfig(reactorCount = 1))
        val go = AtomicLong(0)
        val posted = AtomicLong(0)
        val checked = AtomicLong(0)
        val ran = AtomicInt(0)
        val sink = AtomicInt(0)
        val task: () -> Unit = {
            ran.store(1)
        }

        // The other thread posts once per race, as soon as it is let go.
        val threads = TestThreads()
        threads.start {
            var t = 1L
            while (true) {
                var seen = go.load()
                while (seen < t) {
                    seen = go.load()
                }
                if (seen == Long.MAX_VALUE) {
                    return@start
                }

                reactor.post(task)
                posted.store(t)

                while (checked.load() < t && go.load() != Long.MAX_VALUE) {
                    // the race is checked before the next one
                }
                t++
            }
        }

        var races = 0L
        var missed = 0L
        var stranded = 0L
        val headStart = Random(1)
        val budgetEnds = Clock.monotonicMs() + 3_000
        try {
            while (races < 2_000_000 && Clock.monotonicMs() < budgetEnds) {
                val t = ++races
                reactor.wakePending.store(1)
                ran.store(0)
                go.store(t)

                // A random head start for the poster, so its post lands on every part of the drain.
                var spin = 0
                for (i in headStart.nextInt(0, 250) downTo 1) {
                    spin += i
                }

                reactor.clearWakePending()
                reactor.drainPostQ()
                while (posted.load() < t) {
                    check(Clock.monotonicMs() < budgetEnds + 10_000) { "the poster stopped at race $t" }
                }

                if (ran.load() == 0) {
                    missed++
                    if (reactor.wakePending.load() == 0) {
                        stranded++
                    }
                }

                reactor.drainPostQ()   // takes the post the racing drain left
                sink.store(spin)
                checked.store(t)
            }
        } finally {
            go.store(Long.MAX_VALUE)
            threads.joinAll()
        }

        // Vacuity guard: the post has to have landed both before and after the drain looked.
        assertTrue(missed in 1 until races, "the drain missed $missed of $races posts, so they never raced")
        assertEquals(0L, stranded, "posts the drain missed and left with the flag clear: queued, with no wake coming")
    }

    // ---- Handoffs sharing a wake ------------------------------------------------------------------------------

    // This process's descriptors that are eventfds.
    private fun eventfds(): Set<Int> {
        val found = HashSet<Int>()
        val dir = opendir("/proc/self/fd") ?: return found

        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val fd = entry.pointed.d_name.toKString().toIntOrNull() ?: continue
                if (linkOf("/proc/self/fd/$fd") == "anon_inode:[eventfd]") {
                    found.add(fd)
                }
            }
        } finally {
            closedir(dir)
        }

        return found
    }

    private fun linkOf(path: String): String? = memScoped {
        val link = allocArray<ByteVar>(256)
        val n = readlink(path, link, 255.convert())
        if (n < 0) {
            return null
        }

        link[n.toInt()] = 0.toByte()
        link.toKString()
    }

    // What an eventfd has counted since it was last read - one per write - from /proc, which leaves it unread.
    private fun eventfdCount(fd: Int): Long {
        val info = readFileBytes("/proc/self/fdinfo/$fd")?.decodeToString() ?: error("no fdinfo for $fd")
        val line = info.lineSequence().first { it.startsWith("eventfd-count:") }

        return line.substringAfter(':').trim().toLong(16)
    }

    private fun copyOf(item: RecvItem): RecvItem = RecvItem().also {
        it.set(item.address, item.length, item.bufferId, item.generation)
    }

    @Test
    fun `handoffs from another thread share one eventfd write until the loop's next pass`() {
        // A post or a buffer return from another thread wakes the reactor through its eventfd, but only the first
        // since the loop's last pass writes it; the rest ride on that write. Counted with the reactor held between
        // passes, from the eventfd's own counter, which the reactor drains only when it runs.
        val before = eventfds()
        val connection = AtomicReference<TcpConnection?>(null)
        val taken = AtomicReference<List<RecvItem>>(emptyList())
        val holding = AtomicInt(0)
        val released = AtomicInt(0)
        val ran = AtomicInt(0)

        // Holds the reactor thread on purpose, inside a completion: the pass that delivered it has cleared the flag,
        // and the next one cannot start until the test thread lets go.
        fun hold(n: Int) {
            holding.store(n)
            val deadline = Clock.monotonicMs() + 10_000
            while (released.load() < n && Clock.monotonicMs() < deadline) {
                sleepMs(1)
            }
        }

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            // The client's buffers, kept for the test thread to hand back.
            val item = RecvItem()
            val kept = ArrayList<RecvItem>()
            val snapshot = conn.read()
            while (conn.tryGetItem(snapshot, item)) {
                if (item.hasBuffer) {
                    kept.add(copyOf(item))
                }
            }

            taken.store(kept)
            connection.store(conn)
            hold(1)

            RingTimer(conn.reactor).delay(20)   // resumed in a later pass, once the burst was drained
            hold(2)

            echo(conn)
        }

        TestServer(testConfig(), handler).use { server ->
            val opened = eventfds() - before
            assertEquals(1, opened.size, "precondition: the reactor's wake is not the one new eventfd: $opened")
            val wake = opened.single()

            server.connect().use { client ->
                try {
                    client.write("hand these back")
                    assertTrue(eventually(5_000) { holding.load() == 1 }, "the handler never took the client's buffers")
                    val conn = connection.load()!!
                    val items = taken.load()
                    assertTrue(items.isNotEmpty(), "precondition: no buffer to hand back")

                    val beforeBurst = eventfdCount(wake)
                    repeat(100) {
                        server.reactor.post {
                            ran.addAndFetch(1)
                        }
                    }
                    for (item in items) {
                        conn.returnBuffer(item)
                    }
                    val burst = eventfdCount(wake) - beforeBurst
                    released.store(1)

                    assertTrue(eventually(5_000) { holding.load() == 2 }, "the handler never came back from its delay")
                    val beforeNext = eventfdCount(wake)
                    repeat(2) {
                        server.reactor.post {
                            ran.addAndFetch(1)
                        }
                    }
                    val nextPass = eventfdCount(wake) - beforeNext
                    released.store(2)

                    assertEquals(1L, burst, "eventfd writes for 100 posts and ${items.size} buffer returns in one pass")
                    assertEquals(1L, nextPass, "eventfd writes for two posts after the loop's next pass")

                    // None of them was lost on the way, and the buffers are back in use.
                    assertTrue(eventually(5_000) { ran.load() == 102 }, "only ${ran.load()} of 102 posts ran")
                    client.write("still")
                    assertEquals("still", client.readBytes(5).decodeToString())
                } finally {
                    released.store(2)
                }
            }
        }
    }

    // ---- Foreign suspensions and handlerContext ---------------------------------------------------------------

    // Resumes an intercepted continuation on [reactor]: inline when already there, posted from anywhere else. What a
    // host puts in handlerContext to keep a handler on its reactor.
    private class ReactorDispatcher(private val reactor: Reactor) :
        AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
        override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
            object : Continuation<T> {
                override val context: CoroutineContext get() = continuation.context

                override fun resumeWith(result: Result<T>) {
                    if (reactor.onReactorThread) {
                        continuation.resumeWith(result)
                    } else {
                        reactor.post {
                            continuation.resumeWith(result)
                        }
                    }
                }
            }
    }

    // A handler that suspends three times on something that is not kron's - each resumed from a new thread, through
    // the coroutine's own interceptor - and answers where it found itself after each: R on the reactor, o off it.
    private fun trailAfterForeignSuspensions(dispatch: Boolean): String {
        val threads = TestThreads()

        suspend fun foreign(): Unit = suspendCoroutineUninterceptedOrReturn { cont ->
            val resumable = cont.intercepted()
            threads.start {
                resumable.resume(Unit)
            }

            COROUTINE_SUSPENDED
        }

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val trail = StringBuilder()
            repeat(3) {
                foreign()
                trail.append(if (conn.reactor.onReactorThread) "R" else "o")
            }

            conn.write(trail.toString().encodeToByteArray())
            conn.flush()
        }

        val ready = Latch(1)
        val server = KronServer(
            testConfig(),
            onStart = {
                ready.countDown()
            },
            handler = handler,
        )
        if (dispatch) {
            server.reactors[0].handlerContext = ReactorDispatcher(server.reactors[0])
        }
        server.start()

        try {
            assertTrue(ready.await(10_000), "the reactor never started")

            TestSocket().use { client ->
                client.setReceiveTimeout(5_000)
                client.connect(server.reactors[0].listenPorts[0])
                return client.readBytes(3).decodeToString()
            }
        } finally {
            server.close()
            threads.joinAll()
        }
    }

    @Test
    fun `a dispatcher in handlerContext brings every foreign suspension back to the reactor`() {
        // The whole chain, not only the first: a handler that drifted off on the second would be as broken.
        assertEquals("RRR", trailAfterForeignSuspensions(dispatch = true))
    }

    @Test
    fun `control - without one a foreign suspension leaves the handler where it was resumed`() {
        assertEquals("ooo", trailAfterForeignSuspensions(dispatch = false))
    }
}
