package kronos.tls

import kotlinx.cinterop.CPointer
import kronos.KronosServer
import kronos.Latch
import kronos.Native
import kronos.RecvItem
import kronos.ServerConfig
import kronos.Sockets
import kronos.TcpConnection
import kronos.TcpOptions
import kronos.boringssl.SSL_SESSION
import kronos.boringssl.SSL_SESSION_free
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Session resumption across the proactors of one port (#38): each proactor had a context of its own, so a ticket
 * resumed only on the proactor that issued it, while the kernel picks the proactor by hashing the connection.
 */
class TlsResumptionTest {
    @Test
    fun `a ticket resumes on any proactor of the port - not only the one that issued it`() {
        val proactors = 4
        val returns = 20

        // One instance for every proactor, as a server hands them out.
        val options = TlsOptions(certificatePath = TestCertificate.certificate, keyPath = TestCertificate.key)
        val services = arrayOfNulls<TlsService>(proactors)
        val served = Array(proactors) { AtomicInt(0) }   // which proactors served a connection

        val (server, port) = startOnOnePort(proactors) { config, ready ->
            KronosServer(
                config,
                onStart = { proactor ->
                    services[proactor.id] = TlsService.start(proactor, options)
                    ready.countDown()
                },
                handler = { conn ->
                    served[conn.proactor.id].store(1)
                    echo(conn, services[conn.proactor.id]!!)
                },
            )
        }

        try {
            // TLS 1.2, where a session is resumable once its handshake is done; 1.3 resumes from the same ticket keys.
            var session: CPointer<SSL_SESSION>? = TlsTestClient(port, version = BoringSsl.TLS1_2_VERSION).use {
                exchange(it)
                it.session()
            }

            var resumed = 0
            repeat(returns) {
                TlsTestClient(port, version = BoringSsl.TLS1_2_VERSION, session = session).use { client ->
                    exchange(client)
                    resumed += if (client.resumed) 1 else 0

                    SSL_SESSION_free(session)
                    session = client.session()
                }
            }
            SSL_SESSION_free(session)

            assertTrue(served.count { it.load() == 1 } > 1, "every connection reached one proactor, so this shows nothing")

            // A ticket good only where it was issued resumes about one return in four here.
            assertEquals(returns, resumed, "$resumed of $returns returns to a $proactors-proactor port resumed")
        } finally {
            server.close()
        }
    }

    private fun exchange(client: TlsTestClient) {
        client.write("hi")
        val got = ByteArray(2)
        client.readFully(got, 0, got.size)
    }

    // Writes back what it decrypts until the client goes.
    private suspend fun echo(conn: TcpConnection, service: TlsService) {
        var tls: TlsSession? = null
        val item = RecvItem()
        try {
            val session = service.accept(conn)
            tls = session

            while (true) {
                val snapshot = conn.read()
                var wrote = false
                while (conn.tryGetItem(snapshot, item)) {
                    if (item.hasBuffer) {
                        val n = session.decrypt(item.address, item.length)
                        if (n > 0) {
                            session.write(conn, session.plaintext, n)
                            wrote = true
                        }
                    }
                    conn.returnBuffer(item)
                }

                if (wrote) {
                    conn.flush()
                }
                if (snapshot.isClosed || session.closed) {
                    return
                }
            }
        } catch (_: kronos.IOException) {
            // the client went mid-handshake: nothing to assert here
        } finally {
            tls?.close()
        }
    }

    // [count] proactors on one port a probe found free, retried if something takes it before they bind.
    private fun startOnOnePort(
        count: Int,
        build: (ServerConfig, Latch) -> KronosServer,
    ): Pair<KronosServer, Int> {
        var lastFailure: Throwable? = null
        repeat(5) {
            val probe = Sockets.listen(0, 1, reusePort = false)
            val port = Sockets.localPort(probe)
            Native.close(probe)

            val ready = Latch(count)
            val server = build(ServerConfig(proactorCount = count, ringEntries = 256, tcp = TcpOptions(port = port)), ready)
            try {
                server.start()
                if (ready.await(10_000)) {
                    return server to port
                }
                server.close()
            } catch (e: Throwable) {
                lastFailure = e
                server.close()
            }
        }

        throw IllegalStateException("no free port for $count proactors", lastFailure)
    }
}
