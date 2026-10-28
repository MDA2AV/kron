package kron

/**
 * A connection's own provided-buffer ring, in incremental mode (IOU_PBUF_RING_INC): the kernel appends one recv
 * after another into the same buffer until it is full, so one buffer holds many slices. A buffer goes back to the
 * kernel only when both are done with it: the kernel, which says so by leaving F_BUF_MORE off a completion, and the
 * connection's reader, which hands back every slice it was given.
 *
 * The memory outlives the connection's lives in the pool; only the kernel registration is per life. Reactor thread
 * only.
 */
internal class ConnectionRing(ring: Ring, entries: Int, bufferSize: Int) {
    private val buffers = BufferRing(ring, entries, bufferSize, incremental = true)

    // Per buffer: where its next slice lands, the slices handed out and not yet back, and whether the kernel has
    // finished appending to it.
    private val offsets = IntArray(entries)
    private val slicesOut = IntArray(entries)
    private val kernelDone = BooleanArray(entries)

    val bgid: Int get() = buffers.bgid

    /** Registers the ring, every buffer empty, for a new life of its connection: 0, or the kernel's negative errno. */
    fun register(bgid: Int): Int {
        offsets.fill(0)
        slicesOut.fill(0)
        kernelDone.fill(false)

        return buffers.register(bgid)
    }

    fun unregister() = buffers.unregister()

    fun close() = buffers.close()

    /**
     * A recv of [length] bytes into buffer [bid], and where they landed. [kernelMore] is the completion's
     * F_BUF_MORE, and it alone decides whether the kernel is done: a multishot recv ended by a full completion
     * queue keeps it set, the buffer staying at the ring's head part filled for the recv that is armed next.
     */
    fun received(bid: Int, length: Int, kernelMore: Boolean): Long {
        val address = buffers.bufferAddr(bid) + offsets[bid]
        offsets[bid] += length
        if (!kernelMore) {
            kernelDone[bid] = true
        }

        return address
    }

    /** One more slice of [bid] handed to the connection's reader. */
    fun sliceOut(bid: Int) {
        slicesOut[bid]++
    }

    /**
     * One slice of [bid] handed back. True when that freed the buffer and it went back to the kernel: every slice
     * is back, and the kernel is done appending to it.
     */
    fun sliceReturned(bid: Int): Boolean {
        // A return with nothing out: a buffer the kernel may be filling must not be offered a second time.
        if (slicesOut[bid] == 0) {
            return false
        }

        slicesOut[bid]--
        if (slicesOut[bid] > 0 || !kernelDone[bid]) {
            return false
        }

        offsets[bid] = 0
        kernelDone[bid] = false
        buffers.add(bid)
        buffers.publish()
        return true
    }
}
