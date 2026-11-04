package kronos

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ServerConfig.pinProactors]: what each proactor's thread may run on, read from its own onStart. */
class PinTest {
    // The affinity of each proactor's thread, as it saw it in onStart.
    private fun proactorAffinities(config: ServerConfig): List<IntArray?> {
        val seen = List(config.proactorCount) { AtomicReference<IntArray?>(null) }
        val ready = Latch(config.proactorCount)

        val server = KronosServer(
            config,
            onStart = { proactor ->
                seen[proactor.id].store(Native.allowedCpus())
                ready.countDown()
            },
            handler = {},
        ).start()

        try {
            assertTrue(ready.await(10_000), "the proactors never started")
        } finally {
            server.close()
        }

        return seen.map { it.load() }
    }

    @Test
    fun `pinned proactors each run alone on the next CPU the starting thread may use`() {
        val allowed = Native.allowedCpus()
        if (allowed.size < 2) {
            println("skipped: needs two CPUs, this thread may use ${allowed.size}")
            return
        }

        val seen = proactorAffinities(ServerConfig(proactorCount = 2, pinProactors = true))

        assertContentEquals(intArrayOf(allowed[0]), seen[0])
        assertContentEquals(intArrayOf(allowed[1]), seen[1])
    }

    @Test
    fun `an unpinned proactor keeps every CPU of the starting thread`() {
        val seen = proactorAffinities(ServerConfig(proactorCount = 1))

        assertContentEquals(Native.allowedCpus(), seen[0])
    }

    @Test
    fun `pinning more proactors than CPUs is refused at start`() {
        val threads = TestThreads()
        val outcome = AtomicReference<String?>(null)

        // A thread of its own narrowed to one CPU, so the test thread keeps its affinity.
        threads.start {
            Native.pinCurrentThread(Native.allowedCpus()[0])

            val server = KronosServer(ServerConfig(proactorCount = 2, pinProactors = true), handler = {})
            try {
                server.start()
                server.close()
                outcome.store("started")
            } catch (e: IllegalStateException) {
                outcome.store(e.message)
            }
        }

        threads.joinAll()

        assertEquals("pinProactors needs a CPU per proactor: 2 proactors, 1 allowed", outcome.load())
    }
}
