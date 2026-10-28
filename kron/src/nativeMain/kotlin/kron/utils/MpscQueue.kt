package kron

import kotlin.concurrent.atomics.AtomicReference

/**
 * Unbounded multi-producer, single-consumer queue (Vyukov's intrusive list): what [Reactor.post] hands tasks
 * through, in place of the JVM's ConcurrentLinkedQueue. A node per item; posts are off the hot path.
 */
internal class MpscQueue<T : Any> {
    private class Node<T>(var value: T?) {
        val next = AtomicReference<Node<T>?>(null)
    }

    private val head = AtomicReference(Node<T>(null))   // producers swap themselves in here
    private var tail: Node<T> = head.load()               // the consumer's side: the last node taken

    fun offer(value: T) {
        val node = Node<T>(value)
        val prev = head.exchange(node)
        prev.next.store(node)
    }

    /** The next item, or null when the queue is empty (or a producer is mid-offer). The consumer only. */
    fun poll(): T? {
        val next = tail.next.load() ?: return null
        tail = next

        val value = next.value
        next.value = null
        return value
    }
}
