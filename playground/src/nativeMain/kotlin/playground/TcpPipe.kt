package playground

import kron.KronServer
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpDuplexPipe
import kron.TcpOptions

/**
 * pipe - Tcp/Raw through the pipe adapters, the Kotlin twin of Playground/Tcp/Pipe: the same response per read,
 * reading through [kron.TcpPipeReader] and writing through [kron.TcpPipeWriter], so the pair of samples
 * measures what the adapters cost.
 */
object TcpPipe {
    fun run() {
        val port = Env.port(8080)
        val reactors = Env.reactors(Env.processors())
        val bodyBytes = Env.body(2)

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
                recvQueueEntries = 64
            ),
        )

        val body = if (bodyBytes == 2) {
            "ok".encodeToByteArray()
        } else {
            ByteArray(bodyBytes) { if (it == bodyBytes - 1) '\n'.code.toByte() else 'x'.code.toByte() }
        }
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n"
            .encodeToByteArray() + body

        suspend fun handle(conn: TcpConnection) {
            val pipe = TcpDuplexPipe(conn)
            val reader = pipe.input
            val writer = pipe.output

            try {
                while (true) {
                    val result = reader.read()
                    reader.advanceTo(result.buffer.length)   // everything read is consumed

                    writer.write(response)
                    writer.flush()

                    if (result.isCompleted) {
                        return
                    }
                }
            } finally {
                reader.complete()
            }
        }

        val server = KronServer(config, handler = ::handle).start()
        log("[pipe] $reactors reactors on :$port, $bodyBytes-byte body")
        server.join()
    }
}
