package kron.ktor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kron.Reactor
import kotlin.coroutines.CoroutineContext

/**
 * Runs coroutines on [reactor]'s thread: inline when they are resumed there already, as kron resumes its own
 * reads and flushes, and posted to the reactor when they are resumed from any other thread - a timer, or work an
 * application moved to Dispatchers.IO and back.
 */
internal class ReactorDispatcher(private val reactor: Reactor) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !reactor.onReactorThread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        reactor.post { block.run() }
    }

    override fun toString(): String = "ReactorDispatcher(${reactor.id})"
}
