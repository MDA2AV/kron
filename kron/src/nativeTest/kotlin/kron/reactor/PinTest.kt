package kron

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ServerConfig.pinReactors]: what each reactor's thread may run on, read from its own onStart. */
class PinTest {
    // The affinity of each reactor's thread, as it saw it in onStart.
    private fun reactorAffinities(config: ServerConfig): List<IntArray?> {
        val seen = List(config.reactorCount) { AtomicReference<IntArray?>(null) }
        val ready = Latch(config.reactorCount)

        val server = KronServer(
            config,
            onStart = { reactor ->
                seen[reactor.id].store(Native.allowedCpus())
                ready.countDown()
            },
            handler = {},
        ).start()

        try {
            assertTrue(ready.await(10_000), "the reactors never started")
        } finally {
            server.close()
        }

        return seen.map { it.load() }
    }

    @Test
    fun `pinned reactors each run alone on the next CPU the starting thread may use`() {
        val allowed = Native.allowedCpus()
        if (allowed.size < 2) {
            println("skipped: needs two CPUs, this thread may use ${allowed.size}")
            return
        }

        val seen = reactorAffinities(ServerConfig(reactorCount = 2, pinReactors = true))

        assertContentEquals(intArrayOf(allowed[0]), seen[0])
        assertContentEquals(intArrayOf(allowed[1]), seen[1])
    }

    @Test
    fun `an unpinned reactor keeps every CPU of the starting thread`() {
        val seen = reactorAffinities(ServerConfig(reactorCount = 1))

        assertContentEquals(Native.allowedCpus(), seen[0])
    }

    @Test
    fun `pinning more reactors than CPUs is refused at start`() {
        val threads = TestThreads()
        val outcome = AtomicReference<String?>(null)

        // A thread of its own narrowed to one CPU, so the test thread keeps its affinity.
        threads.start {
            Native.pinCurrentThread(Native.allowedCpus()[0])

            val server = KronServer(ServerConfig(reactorCount = 2, pinReactors = true), handler = {})
            try {
                server.start()
                server.close()
                outcome.store("started")
            } catch (e: IllegalStateException) {
                outcome.store(e.message)
            }
        }

        threads.joinAll()

        assertEquals("pinReactors needs a CPU per reactor: 2 reactors, 1 allowed", outcome.load())
    }
}
