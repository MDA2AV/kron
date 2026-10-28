package ominous

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.MSG_NOSIGNAL
import platform.posix.SHUT_WR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVBUF
import platform.posix.SO_RCVTIMEO
import platform.posix.errno
import platform.posix.ioctl
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import ominous.sys.om_thread_join
import ominous.sys.om_thread_start

// What the JVM tests took from java.util.concurrent and java.net.Socket.

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
            usleep(1000u)
        }

        return true
    }
}

/**
 * A blocking IPv4 client socket. A failed call throws [IOException] naming the errno; an interrupted one is
 * retried, as java.net.Socket does - a thread that created rings is interrupted when the kernel tears them down.
 */
class TestSocket : AutoCloseable {
    val fd: Int = socket(AF_INET, SOCK_STREAM, 0).also {
        check(it >= 0) { "socket failed with errno $errno" }
    }

    fun connect(port: Int, host: String = "127.0.0.1") = memScoped {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toUShort()   // htons
        val octets = host.split('.').map { it.toUInt() }
        addr.sin_addr.s_addr =
            octets[0] or (octets[1] shl 8) or (octets[2] shl 16) or (octets[3] shl 24)   // network order

        if (platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            throw IOException("connect failed with errno $errno")
        }
    }

    /** A read that waits longer than this fails with EAGAIN (11). */
    fun setReceiveTimeout(milliseconds: Int) = memScoped {
        val tv = alloc<timeval>()
        tv.tv_sec = (milliseconds / 1000).convert()
        tv.tv_usec = (milliseconds % 1000 * 1000).convert()

        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    }

    /** The kernel's receive buffer, before [connect]: small, so a peer's send backs up soon. */
    fun setReceiveBuffer(bytes: Int) = memScoped {
        val size = alloc<IntVar>()
        size.value = bytes

        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, size.ptr, sizeOf<IntVar>().convert())
    }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }

        bytes.usePinned { pinned ->
            var offset = 0

            while (offset < bytes.size) {
                val n = send(fd, pinned.addressOf(offset), (bytes.size - offset).convert(), MSG_NOSIGNAL)
                if (n < 0) {
                    val e = errno
                    if (e == EINTR) {
                        continue
                    }
                    throw IOException("send failed with errno $e")
                }
                offset += n.toInt()
            }
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
        while (true) {
            val n = into.usePinned { recv(fd, it.addressOf(offset), length.convert(), 0) }
            if (n >= 0) {
                return if (n == 0L) -1 else n.toInt()
            }

            val e = errno
            if (e != EINTR) {
                throw IOException("recv failed with errno $e")
            }
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
            if (unacknowledgedBytes() == 0) {
                return true
            }
            if (Clock.monotonicMs() >= deadline) {
                return false
            }
            usleep(1000u)
        }
    }

    private fun unacknowledgedBytes(): Int = memScoped {
        val bytes = alloc<IntVar>()
        if (ioctl(fd, TIOCOUTQ, bytes.ptr) != 0) {
            throw IOException("ioctl(TIOCOUTQ) failed with errno $errno")
        }

        bytes.value
    }

    fun shutdownOutput() {
        shutdown(fd, SHUT_WR)
    }

    override fun close() {
        platform.posix.close(fd)
    }

    private companion object {
        // SIOCOUTQ: bytes written and not yet acknowledged (asm-generic/ioctls.h).
        const val TIOCOUTQ = 0x5411UL
    }
}

/** Threads a test starts, joined by [joinAll]. */
class TestThreads {
    private val started = MpscQueue<Long>()

    /** Runs [block] on a new thread. */
    fun start(block: () -> Unit) {
        val ref = StableRef.create(block)
        val thread = om_thread_start(threadEntry, ref.asCPointer(), "test")
        check(thread > 0) { "pthread_create failed with errno ${-thread}" }
        started.offer(thread)
    }

    fun joinAll() {
        while (true) {
            om_thread_join(started.poll() ?: return)
        }
    }
}

private val threadEntry = staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
    val ref = argument!!.asStableRef<() -> Unit>()
    val block = ref.get()
    ref.dispose()

    try {
        block()
    } catch (e: Throwable) {
        println("test thread failed: ${e.stackTraceToString()}")
    }

    null
}

/** One reactor on a port the kernel picks, running [handler], started and listening. */
class TestServer(config: ServerConfig, handler: suspend (TcpConnection) -> Unit) : AutoCloseable {
    private val ready = Latch(1)
    private val server = OminousServer(
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
