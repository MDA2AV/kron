package kron.tls

import kron.FlushResult
import kron.IOException
import kron.Native
import kron.PipeReader
import kron.PipeWriter
import kron.ReadBuffer
import kron.ReadResult
import kron.RecvBufferHolder
import kron.RecvItem
import kron.RecvSnapshot
import kron.SliceChain
import kron.TcpConnection
import kron.TcpPipeWriter
import kron.checkFromIndexSize
import kron.copyFromArray
import kron.copyMemory
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A TLS connection as a [PipeReader] and a [PipeWriter], the twin of ioxide's TlsConnectionDualPipe. Each half is
 * what the handshake achieved for this connection, not a choice the caller makes:
 *
 *                   kernel (kTLS)               BoringSSL
 *     read          TlsDecryptingPipeReader     TlsDecryptingPipeReader
 *     write         TcpPipeWriter               TlsEncryptingPipeWriter
 *
 * Under kTLS TX the plaintext connection's own writer is right as it is: the kernel makes the records. The read side
 * is the decrypting reader either way; under kTLS RX the session hands back plaintext where the kernel left it, and
 * the reader copies it once. Make it on the reactor thread, after [TlsService.accept], and [close] it when done.
 */
class TlsDuplexPipe(
    val connection: TcpConnection,
    val session: TlsSession,
    private val ownsSession: Boolean = true,
) {
    val input: PipeReader = TlsDecryptingPipeReader(connection, session)

    val output: PipeWriter = if (session.kernelTx) {
        TcpPipeWriter(connection)
    } else {
        TlsEncryptingPipeWriter(connection, session)
    }

    /** Completes both halves and, when it owns the session, closes it: close_notify, then the session freed. */
    fun close() {
        input.complete()
        output.complete()

        if (ownsSession) {
            session.close()
        }
    }
}

/**
 * [PipeReader] over a TLS connection's plaintext. Nothing runs in the background: a read decrypts whatever
 * ciphertext the ring queued for this connection, and when that is not enough it parks on the connection and
 * decrypts what arrives. Each ring buffer goes back as soon as its record is decrypted, and the plaintext lands in a
 * buffer this reader owns, presented as one segment. What the handshake's last flight carried in is served first.
 *
 * Reactor thread for everything but [read], which carries itself over from another thread.
 */
class TlsDecryptingPipeReader(
    private val connection: TcpConnection,
    private val session: TlsSession,
) : PipeReader, RecvBufferHolder {
    // The owned plaintext: [start, end) of capacity bytes at plain; 0 once released.
    private var plain = Native.malloc(INITIAL_CAPACITY.toLong())
    private var capacity = INITIAL_CAPACITY
    private var start = 0
    private var end = 0

    private val chain = SliceChain()
    private val buffer = ReadBuffer(chain)
    private val result = ReadResult(buffer)
    private val item = RecvItem()

    private var examinedBytes = 0L  // of the unconsumed bytes, how many the caller already examined

    private var completed = false
    private var cancelRequested = false
    private var ended = false        // nothing more will arrive: the connection closed, or the peer sent close_notify
    private var failure: IOException? = null

    // The caller parked in read; resumed when a record brings new plaintext.
    private var waiter: Continuation<ReadResult>? = null

    // What this reader parks on the connection's read: resumed inline by the reactor's recv completion.
    private val recvReady = object : Continuation<RecvSnapshot> {
        override val context: CoroutineContext get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<RecvSnapshot>) = onRecvReady(result.getOrThrow())
    }

    private val heldBytes: Long get() = (end - start).toLong()

    init {
        check(connection.reactor.onReactorThread) { "Make a TLS pipe on its connection's reactor thread." }

        // So the reactor frees the plaintext buffer at recycle if the handler never completes this reader.
        connection.bufferHolder = this

        // The client's first request often rides in with its Finished flight.
        val early = session.drainPlaintext()
        if (early > 0) {
            append(session.plaintext, early)
        }
    }

    override suspend fun read(): ReadResult {
        if (!connection.reactor.onReactorThread) {
            return readOffReactor()
        }

        return suspendCoroutineUninterceptedOrReturn { readOrPark(it) }
    }

    private fun readOrPark(cont: Continuation<ReadResult>): Any {
        check(!completed) { "Reading is not allowed after the reader was completed." }

        if (cancelRequested) {
            cancelRequested = false
            return build(canceled = true)
        }

        // Unexamined plaintext, or an ended stream, complete at once.
        if (heldBytes > examinedBytes || ended || failure != null) {
            return syncResult()
        }

        // Everything held was examined: wait for new records.
        while (true) {
            val r = connection.readOrPark(recvReady)
            if (r === COROUTINE_SUSPENDED) {
                waiter = cont
                return COROUTINE_SUSPENDED
            }

            if (ingest(r as RecvSnapshot) || ended || cancelRequested || failure != null) {
                return syncResult()
            }
            // Ciphertext that completed no record, or a wake with nothing new: arm again.
        }
    }

    private fun syncResult(): ReadResult {
        val error = failure
        if (error != null) {
            throw error
        }

        val canceled = cancelRequested
        cancelRequested = false
        return build(canceled)
    }

    private suspend fun readOffReactor(): ReadResult = suspendCoroutine { cont ->
        connection.reactor.post {
            try {
                val r = readOrPark(cont)
                if (r !== COROUTINE_SUSPENDED) {
                    cont.resume(r as ReadResult)
                }
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    // The connection's read completed while a caller was parked - inline on the reactor.
    private fun onRecvReady(snapshot: RecvSnapshot) {
        if (!ingest(snapshot) && !ended && !cancelRequested && failure == null) {
            // Nothing new: re-arm without completing the caller.
            while (true) {
                val r = connection.readOrPark(recvReady)
                if (r === COROUTINE_SUSPENDED) {
                    return
                }

                if (ingest(r as RecvSnapshot) || ended || cancelRequested || failure != null) {
                    break
                }
            }
        }

        val cont = waiter ?: return   // completed while parked: the plaintext buffer goes at recycle
        waiter = null

        val error = failure
        if (error != null) {
            cont.resumeWithException(error)
            return
        }

        val canceled = cancelRequested
        cancelRequested = false
        cont.resume(build(canceled))
    }

    // Decrypts a snapshot's records into the owned buffer, handing each ring buffer straight back. True if any
    // plaintext was added.
    private fun ingest(snapshot: RecvSnapshot): Boolean {
        var any = false
        while (connection.tryGetItem(snapshot, item)) {
            if (!item.hasBuffer) {
                continue
            }

            try {
                if (failure == null && plain != 0L) {
                    val n = session.decrypt(item.address, item.length)
                    if (n > 0) {
                        append(session.plaintext, n)   // before the buffer goes back: under kTLS RX it is the plaintext
                        any = true
                    }
                }
            } catch (e: IOException) {
                failure = e
            } finally {
                connection.returnBuffer(item)
            }
        }

        if (snapshot.isClosed || session.closed) {
            ended = true
        }

        if (connection.recvOverflowed && failure == null) {
            failure = IOException(
                "The connection's receive queue overflowed (TcpOptions.recvQueueEntries): data was dropped and the connection closed.",
            )
        }

        return any
    }

    private fun append(address: Long, count: Int) {
        if (end + count > capacity) {
            makeRoom(count)
        }

        copyMemory(address, plain + end, count.toLong())
        end += count
    }

    // Moves the live bytes to the front, and grows when that is still not enough.
    private fun makeRoom(count: Int) {
        val live = end - start
        if (live + count <= capacity) {
            copyMemory(plain + start, plain, live.toLong())
            start = 0
            end = live
            return
        }

        var grown = capacity * 2
        while (grown < live + count) {
            grown *= 2
        }

        val bigger = Native.malloc(grown.toLong())
        copyMemory(plain + start, bigger, live.toLong())
        Native.free(plain)

        plain = bigger
        capacity = grown
        start = 0
        end = live
    }

    private fun build(canceled: Boolean): ReadResult {
        while (chain.count > 0) {
            chain.removeHead()
        }

        if (heldBytes > 0) {
            chain.add(plain + start, end - start, -1)
        }

        buffer.capture(heldBytes)
        return result.set(ended, canceled)
    }

    override fun tryRead(): ReadResult? {
        check(!completed) { "Reading is not allowed after the reader was completed." }

        if (cancelRequested) {
            cancelRequested = false
            return build(canceled = true)
        }

        if (heldBytes > examinedBytes || ended || failure != null) {
            return syncResult()
        }

        return null
    }

    override fun advanceTo(consumed: Long, examined: Long) {
        require(consumed in 0..heldBytes) { "consumed ($consumed) outside the buffer (0..$heldBytes)" }
        val examinedAtLeast = maxOf(examined, consumed)

        start += consumed.toInt()
        if (start == end) {
            start = 0
            end = 0
        }

        examinedBytes = minOf(examinedAtLeast - consumed, heldBytes)
        buffer.clear()
    }

    /** Sets the flag only: a parked read sees it when it next wakes. */
    override fun cancelPendingRead() {
        cancelRequested = true
    }

    override fun complete() {
        if (completed) {
            return
        }

        completed = true
        releaseHeldBuffers()
    }

    /**
     * Frees the plaintext buffer. Called by [complete], and by the reactor at recycle for a reader its handler never
     * completed; idempotent. Terminal, so a reader kept past its handler fails instead of reading the recycled
     * connection's next tenant.
     */
    override fun releaseHeldBuffers() {
        completed = true
        ended = true

        while (chain.count > 0) {
            chain.removeHead()
        }

        if (plain != 0L) {
            Native.free(plain)
            plain = 0L
        }

        start = 0
        end = 0
        examinedBytes = 0
        buffer.clear()
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}

/**
 * [PipeWriter] for a session the kernel does not encrypt for: plaintext is staged here, BoringSSL turns it into
 * records on flush, and the records go into the connection's write slab. The staging buffer is the one copy kTLS
 * TX avoids - SSL_write needs the plaintext contiguous, and the records it makes land in the slab.
 *
 * The session belongs to the reactor, so a flush or a completion from another thread is handed to it rather than
 * encrypting where it was called.
 */
class TlsEncryptingPipeWriter(
    private val connection: TcpConnection,
    private val session: TlsSession,
    initialCapacity: Int = 16 * 1024,
) : PipeWriter {
    // [0, staged) of capacity bytes at staging; 0 once completed.
    private var staging = Native.malloc(initialCapacity.toLong())
    private var capacity = initialCapacity
    private var staged = 0

    private var completed = false
    private var cancelRequested = false
    private var waiter: Continuation<FlushResult>? = null

    private val flushDone = object : Continuation<Unit> {
        override val context: CoroutineContext get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) = onFlushDone()
    }

    override val unflushedBytes: Long get() = staged.toLong()

    override fun getBuffer(sizeHint: Int): Long {
        checkWritable()
        ensureCapacity(staged + maxOf(sizeHint, 1))

        return staging + staged
    }

    override fun advance(count: Int) {
        checkWritable()
        require(count >= 0 && count <= capacity - staged) { "advance($count) past the writable ${capacity - staged} bytes" }

        staged += count
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        checkWritable()
        checkFromIndexSize(offset, length, source.size)
        ensureCapacity(staged + length)

        copyFromArray(source, offset, staging + staged, length)
        staged += length
    }

    private fun checkWritable() = check(!completed) { "Writing is not allowed after the writer was completed." }

    private fun ensureCapacity(need: Int) {
        if (need <= capacity) {
            return
        }

        var grown = capacity * 2
        while (grown < need) {
            grown *= 2
        }

        val bigger = Native.malloc(grown.toLong())
        copyMemory(staging, bigger, staged.toLong())
        Native.free(staging)

        staging = bigger
        capacity = grown
    }

    override suspend fun flush(): FlushResult {
        if (!connection.reactor.onReactorThread) {
            return flushOffReactor()
        }

        return suspendCoroutineUninterceptedOrReturn { flushOrPark(it) }
    }

    private fun flushOrPark(cont: Continuation<FlushResult>): Any {
        if (cancelRequested) {
            cancelRequested = false
            return FlushResult.of(canceled = true, completed = completed || connection.isClosed)
        }

        // Encrypts what is staged into the slab; on a closed connection the connection drops it, so nothing grows.
        if (staged > 0) {
            session.write(connection, staging, staged)
            staged = 0
        }

        if (connection.flushOrPark(flushDone) === COROUTINE_SUSPENDED) {
            waiter = cont
            return COROUTINE_SUSPENDED
        }

        return FlushResult.of(canceled = false, completed = completed || connection.isClosed)
    }

    private suspend fun flushOffReactor(): FlushResult = suspendCoroutine { cont ->
        connection.reactor.post {
            try {
                val r = flushOrPark(cont)
                if (r !== COROUTINE_SUSPENDED) {
                    cont.resume(r as FlushResult)
                }
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    // The connection's flush completed - inline on the reactor. A closed connection reads as completed, so a
    // write-until-completed loop stops against a peer that has gone.
    private fun onFlushDone() {
        val cont = waiter ?: return
        waiter = null

        val canceled = cancelRequested
        cancelRequested = false
        cont.resume(FlushResult.of(canceled, completed || connection.isClosed))
    }

    override fun cancelPendingFlush() {
        cancelRequested = true
    }

    /** Done writing; sends nothing by itself, so flush first. From another thread it is handed to the reactor. */
    override fun complete() {
        if (!connection.reactor.onReactorThread) {
            connection.reactor.post {
                complete()
            }
            return
        }

        if (completed) {
            return
        }

        completed = true
        staged = 0

        if (staging != 0L) {
            Native.free(staging)
            staging = 0L
        }
    }
}
