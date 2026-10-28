package playground

import kron.IOException
import kron.KronServer
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions
import kron.tls.TlsDuplexPipe
import kron.tls.TlsService

/**
 * tls-pipe - tls-raw through the TLS pipe: the same response per request, read through [TlsDuplexPipe]'s reader -
 * consuming only what was answered and examining everything, so a request cut across records waits for the rest -
 * and written through its writer, which BoringSSL encrypts on flush. The pair of TLS samples measures what the pipe
 * costs, as tcp-raw and tcp-pipe do for plain TCP.
 *
 *     ./gradlew :playground:linkReleaseExecutableLinuxX64
 *     playground/build/bin/linuxX64/releaseExecutable/playground.kexe tls-pipe
 *     curl -k https://127.0.0.1:8443/
 *
 * The certificate and the kernel TLS knobs are [TlsSetup]'s.
 */
object TlsPipe {
    private val END = "\r\n\r\n".encodeToByteArray()

    fun run() {
        val port = Env.port(8443)
        val reactors = Env.reactors(Env.processors())
        val bodyBytes = Env.body(8 * 1024)   // TLS cost is per byte, so a 2-byte "ok" would hide it

        val config = ServerConfig(
            reactorCount = reactors,
            ringEntries = 8192,
            recvBufferSize = 32 * 1024,
            recvSlots = 4096,
            tcp = TcpOptions(
                port = port,
                listenBacklog = 1024,
                writeSlabSize = 16 * 1024,
                poolMax = 1024,
                recvQueueEntries = 64,
            ),
        )

        val (tlsOptions, setup) = TlsSetup.options("tls-pipe")

        val fill = "kron-tls-pipe-payload ".encodeToByteArray()
        val body = ByteArray(bodyBytes) { fill[it % fill.size] }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: $bodyBytes\r\n\r\n"
        val response = head.encodeToByteArray() + body

        // One TLS context per reactor, made on its thread when it starts.
        val services = arrayOfNulls<TlsService>(reactors)

        suspend fun handle(conn: TcpConnection) {
            var pipe: TlsDuplexPipe? = null
            try {
                val session = services[conn.reactor.id]!!.accept(conn)
                val p = TlsDuplexPipe(conn, session)
                pipe = p

                while (true) {
                    val result = p.input.read()

                    // One response per complete request head; a partial one stays for the next read.
                    var consumed = 0L
                    while (true) {
                        val at = result.buffer.indexOf(END, consumed)
                        if (at < 0) {
                            break
                        }

                        consumed = at + END.size
                        p.output.write(response)
                    }

                    if (p.output.unflushedBytes > 0) {
                        p.output.flush()
                    }

                    p.input.advanceTo(consumed, result.buffer.length)
                    if (result.isCompleted) {
                        return
                    }
                }
            } catch (e: IOException) {
                log("[tls-pipe] connection failed: ${e.message}")
            } finally {
                pipe?.close()
            }
        }

        val server = KronServer(
            config,
            onStart = { reactor ->
                services[reactor.id] = TlsService.start(reactor, tlsOptions)
            },
            handler = ::handle,
        ).start()

        log("[tls-pipe] $reactors reactors on :$port, $bodyBytes-byte body, $setup")
        server.join()
    }
}
