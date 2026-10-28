package playground

import kron.IncrementalOptions
import kron.KronServer
import kron.RecvItem
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions

/**
 * incremental - tcp-raw with one thing changed, the buffer-ring mode: the Kotlin twin of Playground/Tcp/Incremental.
 * The handler is tcp-raw's to the byte; setting [ServerConfig.incremental] is the whole difference.
 *
 *     ./gradlew :playground:linkReleaseExecutableLinuxX64
 *     playground/build/bin/linuxX64/releaseExecutable/playground.kexe tcp-incremental
 *     curl http://127.0.0.1:8080/
 *
 * The shared mode gives every connection buffers from one big ring. Incremental mode (IOU_PBUF_RING_INC, Linux
 * 6.12+) gives each connection a small ring of its own, which the kernel fills one recv after another. Many idle
 * connections then cost a few small buffers each, and one whose reader falls behind pauses alone instead of holding
 * buffers the others need - tcp-upload shows that. The price is a ring registered per connection, so it pays with
 * many connections or slow readers, not at high request rates.
 */
object TcpIncremental {
    fun run() {
        val port = Env.port(8080)
        val reactors = Env.reactors(12)
        val bodyBytes = Env.body(2)

        // The per-connection ring - this block is the mode. Each connection gets incRecvSlots buffers of
        // incRecvBufferSize bytes, so a reactor holds at most incMaxConnections x incRecvSlots x incRecvBufferSize.
        val incMaxConnections = Env.int("PLAYGROUND_INC_CONNS", 4096)     // per reactor; accepts past it are shed
        val incRecvSlots = Env.int("PLAYGROUND_INC_SLOTS", 16)            // per connection, a power of two
        val incRecvBufferSize = Env.int("PLAYGROUND_INC_BUFSIZE", 4096)   // bytes per buffer, filled across recvs

        val config = ServerConfig(
            reactorCount = reactors,       // io_uring rings/threads - one per core
            ringEntries = 8192,            // SQ depth per ring (the CQ is twice that)
            tcp = TcpOptions(
                port = port,
                listenBacklog = 1024,      // accept-queue depth per SO_REUSEPORT listener
                writeSlabSize = 16 * 1024, // per-connection write buffer; doubles when outgrown
                poolMax = 1024,            // pooled connection objects kept per reactor
                recvQueueEntries = 64,     // per-connection recv completion queue depth
                readTimeoutMs = 60_000,    // close a connection whose read waits this long on a silent peer; 0 = off
                sendTimeoutMs = 60_000,    // close a connection whose flush takes this long to go out; 0 = off
            ),

            // Selecting the mode is setting this. The shared ring's knobs, recvBufferSize and recvSlots, go unused.
            incremental = IncrementalOptions(
                maxConnections = incMaxConnections,
                recvSlots = incRecvSlots,
                recvBufferSize = incRecvBufferSize,
            ),
        )

        // One pre-encoded response, written for every request. PLAYGROUND_BODY sizes the body (2 = "ok").
        val body = if (bodyBytes == 2) {
            "ok".encodeToByteArray()
        } else {
            ByteArray(bodyBytes) { if (it == bodyBytes - 1) '\n'.code.toByte() else 'x'.code.toByte() }
        }
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n"
            .encodeToByteArray() + body

        // tcp-raw's handler, unchanged: reading is the same in both modes, so a handler written for the shared ring
        // runs here as it is.
        suspend fun handle(conn: TcpConnection) {
            val item = RecvItem()

            while (true) {
                val snapshot = conn.read()

                while (conn.tryGetItem(snapshot, item)) {
                    conn.returnBuffer(item)
                }

                conn.write(response)
                conn.flush()

                if (snapshot.isClosed) {
                    return
                }
            }
        }

        val server = KronServer(config, handler = ::handle).start()
        log("[incremental] $reactors reactors on :$port, ${incRecvSlots}x$incRecvBufferSize B per connection (Linux 6.12+)")
        server.join()
    }
}
