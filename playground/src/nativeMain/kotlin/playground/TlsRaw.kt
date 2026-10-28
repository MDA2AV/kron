package playground

import kron.IOException
import kron.KronServer
import kron.Memory
import kron.RecvItem
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions
import kron.tls.TlsOptions
import kron.tls.TlsService
import kron.tls.TlsSession
import kotlin.system.exitProcess

/**
 * tls-raw - TLS on BoringSSL both ways (or kernel TLS with the knobs), the Kotlin twin of Playground/Tls/OpenSsl: one
 * response per request, a request being anything ending in "\r\n\r\n" once decrypted.
 *
 *     ./gradlew :playground:linkReleaseExecutableLinuxX64
 *     playground/build/bin/linuxX64/releaseExecutable/playground.kexe tls-raw
 *     curl -k https://127.0.0.1:8443/
 *
 * PLAYGROUND_TLS_CERT and PLAYGROUND_TLS_KEY name a PEM certificate and key; without them it serves a self-signed
 * certificate made at startup. PLAYGROUND_KTLS_TX=1 hands encryption to the kernel, PLAYGROUND_KTLS_RX=1 decryption
 * as well.
 */
object TlsRaw {
    fun run() {
        val port = Env.port(8443)
        val reactors = Env.reactors(Env.processors())
        val bodyBytes = Env.body(8 * 1024)   // TLS cost is per byte, so a 2-byte "ok" would hide it
        val certPath = Env.string("PLAYGROUND_TLS_CERT")
        val keyPath = Env.string("PLAYGROUND_TLS_KEY")
        if ((certPath == null) != (keyPath == null)) {
            log("[tls-raw] set both PLAYGROUND_TLS_CERT and PLAYGROUND_TLS_KEY, or neither for a self-signed certificate")
            exitProcess(2)
        }

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

        val kernelTx = Env.bool("PLAYGROUND_KTLS_TX", false)
        val kernelRx = Env.bool("PLAYGROUND_KTLS_RX", false)
        val tlsOptions = if (certPath != null && keyPath != null) {
            TlsOptions(
                certificatePath = certPath,
                keyPath = keyPath,
                alpn = listOf("http/1.1"),
                kernelTx = kernelTx,
                kernelRx = kernelRx,
            )
        } else {
            val (certificate, key) = SelfSigned.generate()
            TlsOptions(
                certificatePem = certificate,
                keyPem = key,
                alpn = listOf("http/1.1"),
                kernelTx = kernelTx,
                kernelRx = kernelRx,
            )
        }

        val fill = "kron-ktls-payload ".encodeToByteArray()
        val body = ByteArray(bodyBytes) { fill[it % fill.size] }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: $bodyBytes\r\n\r\n"
        val response = head.encodeToByteArray() + body

        // One TLS context per reactor, made on its thread when it starts.
        val services = arrayOfNulls<TlsService>(reactors)

        suspend fun handle(conn: TcpConnection) {
            var tls: TlsSession? = null
            val carry = Carry()
            val item = RecvItem()
            try {
                val session = services[conn.reactor.id]!!.accept(conn)
                tls = session

                // What arrived with the handshake's last flight.
                val early = session.drainPlaintext()
                carry.append(session.plaintext, early)
                if (answer(conn, session, carry, response)) {
                    conn.flush()
                }

                while (true) {
                    val snapshot = conn.read()
                    while (conn.tryGetItem(snapshot, item)) {
                        if (item.hasBuffer) {
                            val n = session.decrypt(item.address, item.length)   // sets plaintext: read it after
                            carry.append(session.plaintext, n)
                            conn.returnBuffer(item)
                        }
                    }

                    if (answer(conn, session, carry, response)) {
                        conn.flush()
                    }

                    if (snapshot.isClosed || session.closed) {
                        return
                    }
                }
            } catch (e: IOException) {
                log("[tls-raw] connection failed: ${e.message}")
            } finally {
                tls?.close()
            }
        }

        val server = KronServer(
            config,
            onStart = { reactor -> services[reactor.id] = TlsService.start(reactor, tlsOptions) },
            handler = ::handle,
        ).start()

        val mode = when {
            tlsOptions.kernelRx -> "kernel tx+rx"
            tlsOptions.kernelTx -> "kernel tx"
            else -> "BoringSSL both ways"
        }
        val source = certPath ?: "self-signed for localhost"
        log("[tls-raw] $reactors reactors on :$port, $bodyBytes-byte body, cert $source, $mode")
        server.join()
    }

    // Writes one response per complete request head in the carry; whether there was any to send.
    private fun answer(conn: TcpConnection, tls: TlsSession, carry: Carry, response: ByteArray): Boolean {
        var wrote = false
        while (true) {
            val end = carry.indexOfHeadEnd()
            if (end < 0) {
                return wrote
            }

            carry.consume(end + 4)
            tls.write(conn, response)
            wrote = true
        }
    }

    // Decrypted bytes not yet answered: a request can arrive split across records.
    private class Carry {
        private var buffer = ByteArray(8 * 1024)
        private var length = 0

        fun append(address: Long, count: Int) {
            if (count == 0) {
                return
            }

            if (buffer.size - length < count) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
            }

            Memory.copy(address, buffer, length, count)
            length += count
        }

        fun indexOfHeadEnd(): Int {
            for (i in 0..length - 4) {
                if (buffer[i] == CR && buffer[i + 1] == LF && buffer[i + 2] == CR && buffer[i + 3] == LF) {
                    return i
                }
            }

            return -1
        }

        fun consume(count: Int) {
            buffer.copyInto(buffer, 0, count, length)
            length -= count
        }

        private companion object {
            const val CR = '\r'.code.toByte()
            const val LF = '\n'.code.toByte()
        }
    }
}
