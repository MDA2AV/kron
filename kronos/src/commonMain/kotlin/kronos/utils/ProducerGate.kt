package kronos

import kotlin.concurrent.atomics.AtomicInt

/**
 * The producers of a queue in flight, and the way to turn new ones away: once [close] has returned, every producer that
 * got in has left, so what it queued is there for the consumer's last drain, and every later one is refused.
 */
internal class ProducerGate {
    // Producers in flight, with the sign bit once closed.
    private val state = AtomicInt(0)

    val isClosed: Boolean get() = state.load() < 0

    /** False once closed. Otherwise the caller is in until [leave], and [close] waits for it. */
    fun enter(): Boolean {
        while (true) {
            val inFlight = state.load()
            if (inFlight < 0) {
                return false
            }

            // A compare-and-set, not an increment: a producer turned away leaves the count alone, so a stream of them
            // cannot keep [close] waiting.
            if (state.compareAndSet(inFlight, inFlight + 1)) {
                return true
            }
        }
    }

    fun leave() {
        state.addAndFetch(-1)
    }

    /** Turns producers away from here on, and returns once those already in have left. Idempotent. */
    fun close() {
        while (true) {
            val inFlight = state.load()
            if (inFlight < 0 || state.compareAndSet(inFlight, inFlight or Int.MIN_VALUE)) {
                break
            }
        }

        while (state.load() != Int.MIN_VALUE) {
            spinWait()
        }
    }
}
