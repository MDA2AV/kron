package kron

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference

/**
 * Unbounded multi-producer, single-consumer queue (Vyukov's intrusive list): what [Reactor.post] hands tasks
 * through, in place of the JVM's ConcurrentLinkedQueue. A node per item; posts are off the hot path.
 */
internal class MpscQueue<T : Any> {
    // A volatile field rather than an AtomicReference: one object a node, not two
    private class Node<T>(var value: T?) {
        @Volatile
        var next: Node<T>? = null
    }

    private val head = AtomicReference(Node<T>(null))   // producers swap themselves in here
    private var tail: Node<T> = head.load()               // the consumer's side: the last node taken

    fun offer(value: T) {
        val node = Node<T>(value)
        val prev = head.exchange(node)
        prev.next = node
    }

    /** The next item, or null when the queue is empty (or a producer is mid-offer). The consumer only. */
    fun poll(): T? {
        val taken = tail
        val next = taken.next ?: return null
        // Unlinked once passed: on the JVM, a taken node that reached the old generation would otherwise keep every
        // node after it alive, all of them copied by each young collection and promoted.
        taken.next = null
        tail = next

        val value = next.value
        next.value = null
        return value
    }
}
