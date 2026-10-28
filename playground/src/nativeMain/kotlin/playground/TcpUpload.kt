package playground

import kron.IncrementalOptions
import kron.KronServer
import kron.RingTimer
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpDuplexPipe
import kron.TcpOptions
import kotlin.time.TimeSource

/**
 * upload - a request body streamed through the pipe to a reader as slow as you make it, on the incremental mode
 * that makes that safe. Each connection has a small ring of its own, so a reader that falls behind runs only that
 * ring dry and its connection pauses: the rest of the body waits in the kernel and TCP slows the client. On the
 * shared ring the same reader would hold buffers every connection draws from, and be closed once its receive queue
 * filled.
 *
 *     ./gradlew :playground:linkReleaseExecutableLinuxX64
 *     playground/build/bin/linuxX64/releaseExecutable/playground.kexe tcp-upload
 *     head -c 100M /dev/zero > /tmp/body && curl --data-binary @/tmp/body http://127.0.0.1:8080/
 *
 * PLAYGROUND_UPLOAD_DELAY_MS is the reader's pause after each chunk it takes, 0 by default. Set it to 1 and the upload
 * goes at the reader's pace, the connection open throughout. The answer says how many bytes arrived and how long they
 * took. A body is sized by its Content-Length; a chunked one is refused.
 */
object TcpUpload {
    private val END = "\r\n\r\n".encodeToByteArray()
    private val CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".encodeToByteArray()

    fun run() {
        val port = Env.port(8080)
        val reactors = Env.reactors(12)
        val delayMs = Env.int("PLAYGROUND_UPLOAD_DELAY_MS", 0).toLong()

        val config = ServerConfig(
            reactorCount = reactors,
            ringEntries = 8192,
            tcp = TcpOptions(
                port = port,
                listenBacklog = 1024,
                writeSlabSize = 16 * 1024,
                poolMax = 1024,
                recvQueueEntries = 64,
            ),

            // A ring per connection: how much of a body can wait unread before the connection pauses.
            incremental = IncrementalOptions(
                maxConnections = Env.int("PLAYGROUND_INC_CONNS", 4096),
                recvSlots = Env.int("PLAYGROUND_INC_SLOTS", 16),
                recvBufferSize = Env.int("PLAYGROUND_INC_BUFSIZE", 4096),
            ),
        )

        suspend fun handle(conn: TcpConnection) {
            val pipe = TcpDuplexPipe(conn)
            val timer = RingTimer(conn.reactor)

            try {
                while (true) {
                    val head = readHead(pipe) ?: return

                    val length = contentLength(head)
                    if (length < 0) {
                        respond(pipe, "411 Length Required", "send the body with a Content-Length\n")
                        return
                    }

                    // curl asks before sending a large body; the answer is to go ahead.
                    if (head.contains("100-continue", ignoreCase = true)) {
                        pipe.output.write(CONTINUE)
                        pipe.output.flush()
                    }

                    // The body, taken as it arrives and at the reader's pace: nothing waits beyond the ring.
                    val started = TimeSource.Monotonic.markNow()
                    var left = length
                    while (left > 0) {
                        val result = pipe.input.read()
                        val take = minOf(left, result.buffer.length)
                        pipe.input.advanceTo(take)
                        left -= take

                        if (left > 0 && result.isCompleted) {
                            return   // the client left mid-body
                        }

                        if (delayMs > 0) {
                            timer.delay(delayMs)
                        }
                    }

                    val ms = started.elapsedNow().inWholeMilliseconds
                    respond(pipe, "200 OK", "received $length bytes in $ms ms\n")
                }
            } finally {
                pipe.input.complete()
            }
        }

        val server = KronServer(config, handler = ::handle).start()
        log("[upload] $reactors reactors on :$port, reader pause ${delayMs} ms per chunk (Linux 6.12+)")
        server.join()
    }

    // The request head, up to its blank line and consumed; null when the client leaves first.
    private suspend fun readHead(pipe: TcpDuplexPipe): String? {
        while (true) {
            val result = pipe.input.read()

            val end = result.buffer.indexOf(END)
            if (end >= 0) {
                val head = result.buffer.toByteArray(0, end.toInt()).decodeToString()
                pipe.input.advanceTo(end + END.size)
                return head
            }

            if (result.isCompleted) {
                return null
            }

            // Not all there yet: nothing consumed and everything examined, so the next read waits for more.
            pipe.input.advanceTo(0, result.buffer.length)
        }
    }

    // The Content-Length header's value, or -1 without one.
    private fun contentLength(head: String): Long {
        for (line in head.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                return line.substring(colon + 1).trim().toLongOrNull() ?: -1
            }
        }

        return -1
    }

    private suspend fun respond(pipe: TcpDuplexPipe, status: String, body: String) {
        val bytes = body.encodeToByteArray()
        val head = "HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\n\r\n"

        pipe.output.write(head.encodeToByteArray() + bytes)
        pipe.output.flush()
    }
}
