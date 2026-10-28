package kron

import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A wait that runs on the reactor's ring (IORING_OP_TIMEOUT): the deadline travels in the submission, the
 * kernel holds it, and the completion resumes the waiter inline on the reactor thread. Nothing is armed on
 * the side and no syscall is made for it - the SQE leaves with whatever batch the reactor was sending.
 *
 * One wait at a time per timer: hold one per connection, or per thing that waits. No allocation per wait.
 */
class RingTimer(private val reactor: Reactor) {
    private var waiter: Continuation<Int>? = null

    // Called on the reactor thread with the CQE's result.
    private val completion = RingCompletion { result ->
        val cont = waiter
        waiter = null
        cont?.resume(result)
    }

    /**
     * Suspends for [milliseconds], returning the ring's result: [ETIME] when the wait ran its course (the
     * success case), any other negative value an errno.
     */
    suspend fun delay(milliseconds: Long): Int = delayNanoseconds(milliseconds * 1_000_000L)

    /** As [delay], in nanoseconds; at or below zero waits the shortest time the kernel takes. */
    suspend fun delayNanoseconds(nanoseconds: Long): Int {
        if (!reactor.onReactorThread) {
            return delayOffReactor(nanoseconds)
        }

        return suspendCoroutineUninterceptedOrReturn { cont ->
            check(waiter == null) { "RingTimer already waiting" }

            // The waiter is set after the submit, so a submit that throws leaves the timer free. The completion
            // cannot come in between: it is only dispatched on the loop's next pass.
            reactor.submitTimeout(nanoseconds, completion)
            waiter = cont
            COROUTINE_SUSPENDED
        }
    }

    // A check that fails in the posted task goes back to the caller: the reactor survives a task that throws, so
    // nothing else would ever resume it.
    private suspend fun delayOffReactor(nanoseconds: Long): Int = suspendCoroutine { cont ->
        reactor.post {
            try {
                check(waiter == null) { "RingTimer already waiting" }
                reactor.submitTimeout(nanoseconds, completion)
                waiter = cont
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    companion object {
        /** io_uring's report for a timeout that expired normally. */
        const val ETIME = -62

        /** True for a result that is the timer expiring rather than an error. */
        fun expired(result: Int): Boolean = result == ETIME || result == 0
    }
}
