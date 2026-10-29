package kron

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** [TcpOptions.extraPorts] on the incremental ring: a listener per port, each connection told the port it came in on. */
class IncrementalPortsTest {
    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    private fun incrementalUnsupported(): Boolean {
        val parts = kernelRelease().split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (major > 6 || (major == 6 && minor >= 12)) {
            return false
        }

        println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
        return true
    }

    // Answers whatever arrives with the port its connection was accepted on, then lets the connection go.
    private suspend fun answerPort(conn: TcpConnection) {
        val item = RecvItem()
        val snapshot = conn.read()
        while (conn.tryGetItem(snapshot, item)) {
            conn.returnBuffer(item)
        }

        conn.write(conn.listenerPort.toString().encodeToByteArray())
        conn.flush()
    }

    // Ports free right now: each bound by a probe on port 0, all held at once so they differ, then released.
    private fun freePorts(count: Int): IntArray {
        val probes = IntArray(count) { -1 }
        try {
            for (i in 0 until count) {
                probes[i] = Sockets.listen(0, 1, reusePort = false)
            }

            return IntArray(count) { Sockets.localPort(probes[it]) }
        } finally {
            probes.filter { it >= 0 }.forEach {
                Native.close(it)
            }
        }
    }

    // An incremental reactor on a port the kernel picks plus [extra] fixed ones. A fixed port can be taken between the
    // probe and the reactor's bind, by anything on the machine: that start fails, and is retried on new ports.
    private fun startOnFreePorts(extra: Int): KronServer {
        var lastBindFailure: Throwable? = null

        repeat(5) {
            val config = ServerConfig(
                reactorCount = 1,
                ringEntries = 256,
                tcp = TcpOptions(port = 0, extraPorts = freePorts(extra)),
                incremental = IncrementalOptions(maxConnections = 8, recvSlots = 4, recvBufferSize = 4096),
            )

            val ready = Latch(1)
            val fault = AtomicReference<Throwable?>(null)
            val server = KronServer(
                config,
                onStart = {
                    ready.countDown()
                },
                handler = ::answerPort,
            )
            server.reactors[0].onFault = { _, e ->
                fault.store(e)
                ready.countDown()
            }

            server.start()
            if (!ready.await(10_000)) {
                server.close()
                error("the reactor neither started nor faulted")
            }

            val failure = fault.load() ?: return server
            server.close()
            if (failure.message?.startsWith("bind(") != true) {
                throw failure
            }
            lastBindFailure = failure
        }

        throw IllegalStateException("no free ports in five tries", lastBindFailure)
    }

    @Test
    fun `every listener is served on the incremental ring - and listenerPort names the port dialed`() {
        if (incrementalUnsupported()) {
            return
        }

        val server = startOnFreePorts(extra = 2)
        try {
            val ports = server.reactors[0].listenPorts
            assertEquals(3, ports.size, "listeners: ${ports.toList()}")
            assertEquals(3, ports.toSet().size, "listeners on the same port: ${ports.toList()}")

            // Twice around, so each listener's multishot accept is seen to go on serving.
            for (round in 0 until 2) {
                for (port in ports) {
                    TestSocket().use { client ->
                        client.setReceiveTimeout(5_000)
                        client.connect(port)
                        client.write("which port?")

                        assertContentEquals(
                            port.toString().encodeToByteArray(),
                            client.readToEnd(),
                            "round $round: the connection dialed to $port",
                        )
                    }
                }
            }
        } finally {
            server.close()
        }
    }
}
