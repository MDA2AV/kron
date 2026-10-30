package kron

import kotlin.concurrent.atomics.AtomicInt

// What the JVM tests took from java.util.concurrent and java.net.Socket, on both platforms: the client below makes
// the same system calls on each, kron's own and those of TestSys.

/** A count-down latch, polled: the waits in these tests are short. */
class Latch(count: Int) {
    private val remaining = AtomicInt(count)

    val count: Int get() = remaining.load()

    fun countDown() {
        while (true) {
            val c = remaining.load()
            if (c == 0 || remaining.compareAndSet(c, c - 1)) {
                return
            }
        }
    }

    fun await(timeoutMs: Long): Boolean {
        val deadline = Clock.monotonicMs() + timeoutMs
        while (remaining.load() > 0) {
            if (Clock.monotonicMs() >= deadline) {
                return false
            }

            sleepMs(1)
        }

        return true
    }
}

/** The calls a client socket makes that kron does not: each returns the result, or a negative errno. */
internal expect object TestSys {
    fun connect(fd: Int, address: Long, length: Int): Int

    fun send(fd: Int, buffer: Long, length: Long, flags: Int): Long

    fun recv(fd: Int, buffer: Long, length: Long, flags: Int): Long

    /** ioctl(TIOCOUTQ): the bytes written and not yet acknowledged by the peer. */
    fun unacknowledged(fd: Int): Int
}

/** A thread running [block], started; joined by [joinTestThread]. */
internal expect fun startTestThread(block: () -> Unit): Long

internal expect fun joinTestThread(handle: Long)

/**
 * A blocking IPv4 client socket. A failed call throws [IOException] naming the errno; an interrupted one is
 * retried, as java.net.Socket does - a thread that created rings is interrupted when the kernel tears them down.
 */
class TestSocket : AutoCloseable {
    val fd: Int = Native.socket(Sockets.AF_INET, Sockets.SOCK_STREAM, 0).also {
        check(it >= 0) { "socket failed with errno ${-it}" }
    }

    fun connect(port: Int, host: String = "127.0.0.1") {
        val addr = Native.malloc(SOCKADDR_IN_SIZE)
        try {
            zeroMemory(addr, SOCKADDR_IN_SIZE)
            putShort(addr, Sockets.AF_INET.toShort())
            putShort(addr + 2, (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toShort())   // htons
            val octets = host.split('.').map { it.toInt() }
            for (i in 0 until 4) {
                putByte(addr + 4 + i, octets[i].toByte())   // network order
            }

            val rc = TestSys.connect(fd, addr, SOCKADDR_IN_SIZE.toInt())
            if (rc != 0) {
                throw IOException("connect failed with errno ${-rc}")
            }
        } finally {
            Native.free(addr)
        }
    }

    /** A read that waits longer than this fails with EAGAIN (11). */
    fun setReceiveTimeout(milliseconds: Int) {
        val tv = Native.malloc(TIMEVAL_SIZE)
        try {
            putLong(tv, (milliseconds / 1000).toLong())
            putLong(tv + 8, (milliseconds % 1000 * 1000).toLong())
            Native.setsockopt(fd, Sockets.SOL_SOCKET, SO_RCVTIMEO, tv, TIMEVAL_SIZE.toInt())
        } finally {
            Native.free(tv)
        }
    }

    /** The kernel's receive buffer, before [connect]: small, so a peer's send backs up soon. */
    fun setReceiveBuffer(bytes: Int) {
        Sockets.setInt(fd, Sockets.SOL_SOCKET, Sockets.SO_RCVBUF, bytes)
    }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }

        val buffer = Native.malloc(bytes.size.toLong())
        try {
            copyFromArray(bytes, 0, buffer, bytes.size)
            var offset = 0
            while (offset < bytes.size) {
                val n = TestSys.send(fd, buffer + offset, (bytes.size - offset).toLong(), Sockets.MSG_NOSIGNAL)
                if (n < 0) {
                    if (n == -EINTR.toLong()) {
                        continue
                    }

                    throw IOException("send failed with errno ${-n}")
                }

                offset += n.toInt()
            }
        } finally {
            Native.free(buffer)
        }
    }

    fun write(text: String) = write(text.encodeToByteArray())

    /** One byte, or -1 once the peer has ended the stream. */
    fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    /** Up to [length] bytes; -1 once the peer has ended the stream. */
    fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }

        val buffer = Native.malloc(length.toLong())
        try {
            while (true) {
                val n = TestSys.recv(fd, buffer, length.toLong(), 0)
                if (n >= 0) {
                    copyToArray(buffer, into, offset, n.toInt())
                    return if (n == 0L) -1 else n.toInt()
                }

                if (n != -EINTR.toLong()) {
                    throw IOException("recv failed with errno ${-n}")
                }
            }
        } finally {
            Native.free(buffer)
        }
    }

    /** Exactly [count] bytes, or fewer if the stream ends first. */
    fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = read(bytes, got, count - got)
            if (n < 0) {
                return bytes.copyOf(got)
            }

            got += n
        }

        return bytes
    }

    /** Everything until the peer ends the stream. */
    fun readToEnd(): ByteArray {
        val out = ArrayList<Byte>()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = read(chunk, 0, chunk.size)
            if (n < 0) {
                return out.toByteArray()
            }

            for (i in 0 until n) {
                out.add(chunk[i])
            }
        }
    }

    /**
     * Waits until the peer's kernel has acknowledged everything written. A listener's kernel acknowledges data
     * only on a connection it has queued for accept, so after this the server side exists, accepted or not.
     */
    fun awaitAcknowledged(timeoutMs: Long): Boolean {
        val deadline = Clock.monotonicMs() + timeoutMs
        while (true) {
            val outstanding = TestSys.unacknowledged(fd)
            if (outstanding < 0) {
                throw IOException("ioctl(TIOCOUTQ) failed with errno ${-outstanding}")
            }

            if (outstanding == 0) {
                return true
            }

            if (Clock.monotonicMs() >= deadline) {
                return false
            }

            sleepMs(1)
        }
    }

    fun shutdownOutput() {
        Native.shutdown(fd, Sockets.SHUT_WR)
    }

    override fun close() {
        Native.close(fd)
    }

    private companion object {
        const val SOCKADDR_IN_SIZE = 16L
        const val TIMEVAL_SIZE = 16L
        const val SO_RCVTIMEO = 20
        const val EINTR = 4
    }
}

/** Threads a test starts, joined by [joinAll]. */
class TestThreads {
    private val started = MpscQueue<Long>()

    /** Runs [block] on a new thread. */
    fun start(block: () -> Unit) {
        val thread = startTestThread(block)
        check(thread > 0) { "starting a test thread failed with errno ${-thread}" }
        started.offer(thread)
    }

    fun joinAll() {
        while (true) {
            joinTestThread(started.poll() ?: return)
        }
    }
}

/** One reactor on a port the kernel picks, running [handler], started and listening. */
class TestServer(config: ServerConfig, handler: suspend (TcpConnection) -> Unit) : AutoCloseable {
    private val ready = Latch(1)
    private val server = KronServer(
        config,
        onStart = {
            ready.countDown()
        },
        handler = handler,
    ).start()

    init {
        check(ready.await(10_000)) { "the reactor never started" }
    }

    val reactor: Reactor get() = server.reactors[0]
    val port: Int = server.reactors[0].listenPorts[0]

    fun connect(receiveTimeoutMs: Int = 5_000): TestSocket = TestSocket().also {
        it.setReceiveTimeout(receiveTimeoutMs)
        it.connect(port)
    }

    override fun close() = server.close()
}

/** A one-reactor config for tests: small rings and buffers unless a test needs otherwise. */
fun testConfig(
    tcp: TcpOptions = TcpOptions(port = 0),
    recvSlots: Int = 64,
    recvBufferSize: Int = 4096,
): ServerConfig = ServerConfig(
    reactorCount = 1,
    ringEntries = 256,
    recvBufferSize = recvBufferSize,
    recvSlots = recvSlots,
    tcp = tcp,
)

/** Writes back everything it receives, straight from the recv buffers, until the peer is gone. */
suspend fun echo(conn: TcpConnection) {
    val item = RecvItem()
    while (true) {
        val snapshot = conn.read()
        while (conn.tryGetItem(snapshot, item)) {
            if (item.hasBuffer) {
                conn.write(item.address, item.length)
            }

            conn.returnBuffer(item)
        }

        conn.flush()
        if (snapshot.isClosed) {
            return
        }
    }
}
