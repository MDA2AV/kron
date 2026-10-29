package kron

import kron.Reactor.Companion.KIND_CANCEL
import kron.Reactor.Companion.KIND_TCP_ACCEPT
import kron.Reactor.Companion.KIND_TCP_RECV
import kron.Reactor.Companion.KIND_TCP_SEND
import kron.Reactor.Companion.tag

/**
 * A reactor's TCP side: SO_REUSEPORT listeners with multishot accept, the shared provided-buffer group every
 * connection's multishot recv draws from, the fd-indexed connection table, and the send and teardown paths.
 * Reactor thread only, except [returnBuffer].
 */
internal class TcpTransport(val reactor: Reactor, private val config: ServerConfig, private val options: TcpOptions) {
    private val sendOpFlags = if (options.sendWaitAll) Sockets.MSG_WAITALL else 0

    // ---- Listeners --------------------------------------------------------------------------------------

    // -1, not 0: opening can throw partway, and the teardown that follows must not close stdin.
    private var listenFds = IntArray(0)
    internal var listenPorts = IntArray(0)
        private set

    fun openListeners() {
        val ports = intArrayOf(options.port) + options.extraPorts
        listenFds = IntArray(ports.size) { -1 }
        listenPorts = ports.copyOf()

        for (i in ports.indices) {
            listenFds[i] = Sockets.listen(ports[i], options.listenBacklog)
            if (ports[i] == 0) {
                listenPorts[i] = Sockets.localPort(listenFds[i])
            }
        }
    }

    fun armAccepts() {
        for (fd in listenFds) {
            submitAccept(fd)
        }
    }

    // Teardown's: a reactor that stopped accepting has closed them already, and their numbers may be connections'
    // by now.
    fun closeListeners() {
        if (!accepting) {
            return
        }

        for (fd in listenFds) {
            if (fd >= 0) {
                // Shut down first: the armed accept holds the socket, which the ring's async teardown frees ~50 ms
                // after close() has returned - listening, and holding the port, until then.
                Native.shutdown(fd, Sockets.SHUT_RDWR)
                Native.close(fd)
            }
        }
    }

    // False once stopAccepting ran: no accept is re-armed, and the listeners are closed.
    private var accepting = true

    /**
     * Closes the listeners for good, the connections already accepted untouched. Each multishot accept is
     * cancelled, not only its descriptor closed: the armed request holds the socket open, and would go on
     * accepting. A connection the kernel accepted before the cancel landed is still served.
     */
    fun stopAccepting() {
        if (!accepting) {
            return
        }

        accepting = false
        acceptPaused.clear()

        for (fd in listenFds) {
            if (fd >= 0) {
                submitCancel(tag(KIND_TCP_ACCEPT, 0, fd))
                Native.close(fd)
            }
        }
    }

    private fun portOf(listenFd: Int): Int {
        for (i in listenFds.indices) {
            if (listenFds[i] == listenFd) {
                return listenPorts[i]
            }
        }

        return listenPorts[0]
    }

    // ---- Provided buffers: one group shared by the reactor's connections, or a ring per connection ------

    // The shared group; null in incremental mode, where each connection has a ConnectionRing.
    private var bufRing: BufferRing? = null

    private val incremental = config.incremental

    // Set by every buffer return, so the loop re-arms recvs parked on -ENOBUFS - including when the return
    // happened inline during dispatch, before the batch's trailing ENOBUFS was even seen.
    private var buffersReturned = false

    // Off-reactor returns: buffer ids of the shared group, or in incremental mode a connection's slice packed
    // fd << 32 | gen << 16 | bid. The reactor applies them all, then publishes once.
    private val returnQ = MpscLongQueue(1 shl 14)

    fun initBufferRing() {
        if (incremental != null) {
            initIncremental(incremental)
            return
        }

        val shared = BufferRing(reactor.ring, config.recvSlots, config.recvBufferSize)
        val rc = shared.register(BGID)
        if (rc < 0) {
            shared.close()
            throw IllegalStateException(
                "IORING_REGISTER_PBUF_RING failed with errno ${-rc} (bgid $BGID, ${config.recvSlots} entries)"
            )
        }

        bufRing = shared
    }

    /** Hands back a buffer [conn] took in its life [gen]: to the shared group, or to the connection's own ring. */
    fun returnBuffer(conn: TcpConnection, bid: Int, gen: Int) {
        val value = if (incremental == null) bid.toLong() else packSlice(conn.clientFd, gen, bid)
        if (reactor.onReactorThread) {
            applyReturn(value)
            return
        }

        while (!returnQ.offer(value)) {
            spinWait()
        }

        // Without the wake a queued return waits for an unrelated CQE; if the buffers run dry meanwhile, recvs
        // fail with ENOBUFS.
        reactor.wake()
    }

    // A shared buffer, back from the reactor's own recv path.
    fun returnBufferDirect(bid: Int) {
        val ring = bufRing!!
        ring.add(bid)
        ring.publish()
        buffersReturned = true
    }

    fun drainReturnQ() {
        var value = returnQ.poll()
        while (value >= 0) {
            applyReturn(value)
            value = returnQ.poll()
        }
    }

    private fun applyReturn(value: Long) {
        if (incremental == null) {
            returnBufferDirect(value.toInt())
            return
        }

        // The generation also turns away a slice of the fd's previous tenant, whose ring has since been reset.
        val fd = (value ushr 32).toInt()
        val gen = ((value ushr 16) and 0xFFFF).toInt()
        val bid = (value and 0xFFFF).toInt()
        val ring = connAt(fd, gen)?.ring ?: return
        if (ring.sliceReturned(bid)) {
            buffersReturned = true
        }
    }

    private fun packSlice(fd: Int, gen: Int, bid: Int): Long =
        (fd.toLong() shl 32) or ((gen and 0xFFFF).toLong() shl 16) or (bid.toLong() and 0xFFFF)

    // ---- Incremental mode: a ring per connection --------------------------------------------------------

    // Free buffer group ids for connections' rings, 2 up to maxConnections + 1: the shared group's 1 stays unused.
    private var freeGids = IntArray(0)
    private var freeGidCount = 0
    private var shedReported = false

    private fun initIncremental(options: IncrementalOptions) {
        // Refused at start rather than at the first accept: a kernel before 6.12 has no IOU_PBUF_RING_INC.
        val probe = BufferRing(reactor.ring, 1, 64, incremental = true)
        val rc = probe.register(BGID)
        probe.close()
        if (rc == -Native.EINVAL) {
            throw IllegalStateException(
                "incremental receive mode needs Linux 6.12 or newer (IOU_PBUF_RING_INC); this is ${kernelRelease()}"
            )
        }
        if (rc < 0) {
            throw IllegalStateException("registering an incremental buffer ring failed with errno ${-rc}")
        }

        freeGids = IntArray(options.maxConnections) { options.maxConnections + 1 - it }
        freeGidCount = options.maxConnections
    }

    // Gives [conn] a ring of its own, registered: false when the connection has to be shed instead - at the cap,
    // or short of locked memory - so the reactor goes on.
    private fun setupConnectionRing(conn: TcpConnection, options: IncrementalOptions): Boolean {
        if (freeGidCount == 0) {
            reportShed("at the cap of ${options.maxConnections} connections (IncrementalOptions.maxConnections)")
            return false
        }

        val gid = freeGids[--freeGidCount]
        val ring = conn.ring ?: ConnectionRing(reactor.ring, options.recvSlots, options.recvBufferSize).also {
            conn.ring = it
        }

        val rc = ring.register(gid)
        if (rc < 0) {
            freeGids[freeGidCount++] = gid
            if (rc != -Native.ENOMEM) {
                throw IllegalStateException("registering a connection's buffer ring failed with errno ${-rc}")
            }

            // The kernel pins the ring against RLIMIT_MEMLOCK: the host's limit, not a fault.
            reportShed("registering its buffer ring failed with ENOMEM (raise RLIMIT_MEMLOCK, `ulimit -l`)")
            return false
        }

        return true
    }

    // Once until an accept is served again: a full reactor sheds every connection that arrives meanwhile.
    private fun reportShed(why: String) {
        if (!shedReported) {
            shedReported = true
            printErr("[r${reactor.id}] connection shed: $why")
        }
    }

    // ---- Connection table -------------------------------------------------------------------------------

    // Indexed by fd: dense small ints, an array beats a map per CQE.
    private var connections = arrayOfNulls<TcpConnection>(4096)
    private val pool = ArrayDeque<TcpConnection>()

    // Every connection life on this reactor takes the next value, so two lives never share a generation (#416).
    private var generations = 0

    // Accepted and not yet recycled: a connection whose handler outlives its table slot still counts.
    internal var openConnections = 0
        private set

    fun nextGeneration(): Int = ++generations

    fun connAt(fd: Int, gen: Int): TcpConnection? {
        val conns = connections
        val conn = if (fd >= 0 && fd < conns.size) conns[fd] else null
        return if (conn != null && (conn.generation and 0xFFFF) == gen) conn else null
    }

    private fun track(fd: Int, conn: TcpConnection) {
        if (fd >= connections.size) {
            var length = connections.size
            while (length <= fd) {
                length *= 2
            }
            connections = connections.copyOf(length)
        }

        connections[fd] = conn
    }

    // ---- Submissions ------------------------------------------------------------------------------------

    private fun submitAccept(listenFd: Int) {
        IoUring.prep(
            reactor.getSqeOrFlush(), IoUring.OP_ACCEPT, listenFd, 0L, 0, 0L, tag(KIND_TCP_ACCEPT, 0, listenFd),
            ioprio = IoUring.ACCEPT_MULTISHOT, opFlags = Sockets.SOCK_CLOEXEC,
        )
    }

    // From [bgid]: the shared group, or the connection's own ring.
    private fun submitRecv(fd: Int, gen: Int, bgid: Int) {
        IoUring.prep(
            reactor.getSqeOrFlush(), IoUring.OP_RECV, fd, 0L, 0, 0L, tag(KIND_TCP_RECV, gen, fd),
            flags = IoUring.IOSQE_BUFFER_SELECT, ioprio = IoUring.RECV_MULTISHOT, bufIndex = bgid,
        )
    }

    private fun submitSend(fd: Int, gen: Int, address: Long, length: Int, flags: Int) {
        IoUring.prep(
            reactor.getSqeOrFlush(), IoUring.OP_SEND, fd, address, length, 0L, tag(KIND_TCP_SEND, gen, fd),
            opFlags = flags,
        )
    }

    // By exact user_data, so a dead connection's multishot recv can neither keep taking buffers nor race the
    // fd's next tenant.
    private fun submitCancel(target: Long) {
        IoUring.prep(reactor.getSqeOrFlush(), IoUring.OP_ASYNC_CANCEL, -1, target, 0, 0L, tag(KIND_CANCEL, 0, 0))
    }

    // The connection's flush, straight to the SQ: it leaves with the reactor's next enter.
    fun submitFlush(conn: TcpConnection) {
        if (!conn.claimFlush()) {
            return   // released before it reached the kernel
        }

        submitSend(conn.clientFd, conn.generation and 0xFFFF, conn.writeBuffer, conn.writeInFlight, conn.sendFlags)
    }

    // ---- Accept -----------------------------------------------------------------------------------------

    // Listeners whose accept stopped for lack of descriptors or memory; the sweep re-arms them each tick.
    private val acceptPaused = ArrayList<Int>()
    private var acceptPauseReported = false

    fun onAccept(listenFd: Int, res: Int, flags: Int) {
        val more = flags and IoUring.CQE_F_MORE != 0

        if (res >= 0) {
            acceptPauseReported = false

            val conn = pool.removeLastOrNull()?.setFd(res)
                ?: TcpConnection(this, res, options.writeSlabSize, options.recvQueueEntries)

            if (incremental != null && !setupConnectionRing(conn, incremental)) {
                // Shed: never tracked nor handed to a handler, so straight back to the pool.
                pool.addLast(conn)
                Native.close(res)
                if (!more) {
                    submitAccept(listenFd)
                }

                return
            }
            shedReported = false

            track(res, conn)
            openConnections++
            conn.initRefs()
            conn.listenerPort = portOf(listenFd)
            conn.sendFlags = sendOpFlags
            submitRecv(res, conn.generation and 0xFFFF, conn.ring?.bgid ?: BGID)

            // Inline, until its first suspension: the recv above is not submitted yet, so its first read parks.
            reactor.startHandler(conn)
        } else if (!accepting) {
            // The cancel, or the closed listener's end: expected, and not re-armed.
            return
        } else if (res == -Native.EMFILE || res == -Native.ENFILE || res == -Native.ENOBUFS || res == -Native.ENOMEM) {
            // The connection stays in the backlog, so an accept re-armed now fails again at once and spins the
            // reactor; once a tick instead, reported once until an accept succeeds.
            if (!acceptPauseReported) {
                acceptPauseReported = true
                printErr(
                    "[r${reactor.id}] accept failed ($res): out of file descriptors or memory, " +
                        "retrying every ${Reactor.TICK_MS} ms"
                )
            }
            if (!more) {
                acceptPaused.add(listenFd)
            }

            return
        } else {
            printErr("[r${reactor.id}] accept error: $res")
        }

        if (!more && accepting) {
            submitAccept(listenFd)
        }
    }

    // ---- Receive ----------------------------------------------------------------------------------------

    // Connections whose multishot recv ended on -ENOBUFS, packed gen << 32 | fd.
    private val recvStarved = ArrayList<Long>()

    fun onRecv(fd: Int, gen: Int, res: Int, flags: Int) {
        if (incremental != null) {
            onRecvIncremental(fd, gen, res, flags)
            return
        }

        val hasBuf = flags and IoUring.CQE_F_BUFFER != 0
        val bid = if (hasBuf) flags ushr IoUring.CQE_BUFFER_SHIFT else -1

        val conn = connAt(fd, gen)

        if (res == -Native.ENOBUFS) {
            // The group ran dry, not a peer error: the multishot ended, so park the connection until buffers
            // come back.
            if (conn != null) {
                conn.readParkedMs = reactor.nowMs   // the peer sent; the wait is ours, so the clock restarts
                recvStarved.add((gen.toLong() shl 32) or (fd.toLong() and 0xFFFF_FFFFL))
            }

            return
        }

        if (res <= 0) {
            // Peer EOF or recv error: the reactor owns teardown.
            if (hasBuf) {
                returnBufferDirect(bid)
            }
            if (conn != null) {
                closeFromRecv(conn, fd)
            }

            return
        }

        if (conn == null) {
            // A stale CQE from the fd's previous tenant.
            if (hasBuf) {
                returnBufferDirect(bid)
            }

            return
        }

        val address = if (hasBuf) bufRing!!.bufferAddr(bid) else 0L
        if (!conn.complete(res, bid, address)) {
            closeFromRecvOverflow(conn, fd, gen)
            return
        }

        if (flags and IoUring.CQE_F_MORE == 0) {
            submitRecv(fd, gen, BGID)
        }
    }

    // Incremental mode: the slice lands at its buffer's running offset in the connection's own ring.
    private fun onRecvIncremental(fd: Int, gen: Int, res: Int, flags: Int) {
        val conn = connAt(fd, gen)

        if (res == -Native.ENOBUFS) {
            // The connection's own ring ran dry: a body larger than the ring while its reader still holds the
            // buffers. Only this connection waits - its bytes stay in the kernel and TCP slows its peer - until a
            // buffer comes back and the loop re-arms the recv.
            if (conn != null) {
                conn.readParkedMs = reactor.nowMs   // the peer sent; the wait is ours, so the clock restarts
                recvStarved.add((gen.toLong() shl 32) or (fd.toLong() and 0xFFFF_FFFFL))
            }

            return
        }

        if (res <= 0) {
            // Peer EOF or recv error: the reactor owns teardown, and the ring is reset whole at recycle.
            if (conn != null) {
                closeFromRecv(conn, fd)
            }

            return
        }

        // A stale CQE from the fd's previous tenant: its ring is already gone.
        val ring = conn?.ring ?: return

        val bid = flags ushr IoUring.CQE_BUFFER_SHIFT
        val address = ring.received(bid, res, kernelMore = flags and IoUring.CQE_F_BUF_MORE != 0)
        if (!conn.complete(res, bid, address)) {
            closeFromRecvOverflow(conn, fd, gen)
            return
        }

        if (flags and IoUring.CQE_F_MORE == 0) {
            submitRecv(fd, gen, ring.bgid)
        }
    }

    // Once per loop pass, and only when a buffer came back since the last one, so a parked connection
    // cannot spin the loop.
    fun rearmStarvedRecvs() {
        if (recvStarved.isEmpty() || !buffersReturned) {
            return
        }

        buffersReturned = false

        for (packed in recvStarved) {
            val fd = packed.toInt()
            val gen = (packed ushr 32).toInt()
            val conn = connAt(fd, gen)
            if (conn != null) {
                submitRecv(fd, gen, conn.ring?.bgid ?: BGID)
            }
        }
        recvStarved.clear()
    }

    /** Connections whose recv is parked on -ENOBUFS, waiting for a buffer. Reactor thread. */
    internal val starvedRecvs: Int get() = recvStarved.size

    // Recv-side teardown. With a send in the kernel the reactor keeps its ref and the table slot, so the slab
    // cannot be recycled under it; finishSend lets go.
    private fun closeFromRecv(conn: TcpConnection, fd: Int) {
        conn.suppressFin()

        if (conn.flushSubmitted) {
            conn.closeAfterSend = true
            conn.markClosed()
            return
        }

        connections[fd] = null
        conn.markClosed()
        conn.releaseReactorRef()
    }

    // The multishot recv is still armed, so it is cancelled too.
    private fun closeFromRecvOverflow(conn: TcpConnection, fd: Int, gen: Int) {
        submitCancel(tag(KIND_TCP_RECV, gen, fd))
        closeFromRecv(conn, fd)
    }

    // ---- Send -------------------------------------------------------------------------------------------

    fun onSend(fd: Int, gen: Int, res: Int) {
        val conn = connAt(fd, gen) ?: return   // stale: never touch the fd's new tenant

        var sent = res
        if (sent <= 0) {
            // Failed: nothing more goes out.
            submitCancel(tag(KIND_TCP_RECV, gen, fd))   // the multishot recv is still armed
            conn.closeAfterSend = true
            conn.suppressFin()
            conn.markClosed()
            conn.writeInFlight = conn.writeHead
            sent = 0
        }
        conn.writeHead += sent

        if (conn.writeHead < conn.writeInFlight) {
            // Partial send (rare with MSG_WAITALL): resubmit the rest.
            submitSend(fd, gen, conn.writeBuffer + conn.writeHead, conn.writeInFlight - conn.writeHead, conn.sendFlags)
            return
        }

        finishSend(conn, fd)
    }

    // The kernel is done with the slab: complete the flush, then any teardown that waited for it.
    private fun finishSend(conn: TcpConnection, fd: Int) {
        conn.completeFlush()

        if (conn.closeAfterSend) {
            connections[fd] = null
            conn.releaseReactorRef()
        }
    }

    // ---- Recycle ----------------------------------------------------------------------------------------

    private val scratchItem = RecvItem()

    // At refcount 0, on the reactor thread.
    fun recycle(conn: TcpConnection) {
        val fd = conn.clientFd
        conn.markClosed()
        submitCancel(tag(KIND_TCP_RECV, conn.generation and 0xFFFF, fd))   // before clear() bumps the generation

        // Before drainRecv, which only sees what is still queued: an adapter that pulled buffers out holds
        // the only record of them.
        conn.releaseHeldRecvBuffers()
        conn.drainRecv(scratchItem)

        Native.close(fd)

        // Its own ring, in incremental mode: unregistered now, its gid free for the next accept. The cancel above
        // goes in with the next enter, ahead of any task work there that could still select from the ring.
        val ring = conn.ring
        if (ring != null) {
            freeGids[freeGidCount++] = ring.bgid
            ring.unregister()
        }

        conn.clear()
        openConnections--

        if (pool.size < options.poolMax) {
            pool.addLast(conn)
        } else {
            conn.dispose()
        }
    }

    // ---- Sweep ------------------------------------------------------------------------------------------

    /**
     * The clocks on a connection's lifecycle, once a tick: a connection waiting on a silent peer is reaped,
     * and so is one whose send the peer stopped draining; a FIN left behind a flush goes out.
     */
    fun sweep() {
        for (listenFd in acceptPaused) {
            submitAccept(listenFd)
        }
        acceptPaused.clear()

        val now = Clock.monotonicMs()
        val conns = connections

        for (fd in conns.indices) {
            val conn = conns[fd] ?: continue
            if (conn.sweepClosed) {
                continue
            }

            // Sending, so not waiting on the peer: only the send clock applies.
            if (conn.flushOutstanding) {
                conn.flushSeenMs = now
                if (options.sendTimeoutMs > 0 && now - conn.flushArmedMs > options.sendTimeoutMs) {
                    sweepClose(conn)
                }

                continue
            }

            // A FIN left behind a flush - by a handler that let go, or by shutdownWrite - goes now.
            if ((conn.handlerReleased || conn.finWanted) && !conn.finWasSent) {
                conn.sendFin()
            }

            if (options.readTimeoutMs > 0) {
                val since = conn.waitingOnPeerSince()
                if (since >= 0 && now - since > options.readTimeoutMs) {
                    sweepClose(conn)
                }
            }
        }
    }

    // shutdown() is what the peer sees, and also what releases the connection: it ends the recv a silent
    // peer never completes, or the send a stalled one holds, and those completions run the teardown that
    // gets the refcount right once the kernel is done with the slab. markClosed wakes a parked read now.
    private fun sweepClose(conn: TcpConnection) {
        conn.sweepClosed = true
        conn.suppressFin()   // this shutdown is the FIN
        Native.shutdown(conn.clientFd, Sockets.SHUT_RDWR)
        conn.markClosed()
    }

    // ---- Teardown ---------------------------------------------------------------------------------------

    // Live connections' sockets are closed, never their objects: a handler may still write to them.
    fun closeAcceptedSockets() {
        val conns = connections

        for (fd in conns.indices) {
            val conn = conns[fd] ?: continue

            // A handler letting go later must not shut down whatever socket gets the number next.
            conn.suppressFin()
            Native.close(fd)
            conns[fd] = null
        }
    }

    // Before the ring's descriptor is closed: unregistering is synchronous, closing is not.
    fun unregisterBufferRing() {
        bufRing?.unregister()
    }

    // After the ring is closed: the kernel can no longer write into these.
    fun freeMemory() {
        while (true) {
            pool.removeLastOrNull()?.dispose() ?: break
        }

        bufRing?.close()
        bufRing = null
    }

    companion object {
        const val BGID = 1
    }
}
