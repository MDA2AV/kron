package kron.tls

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kron.Clock
import kron.IOException
import kron.IncrementalOptions
import kron.PipeReader
import kron.Reactor
import kron.ServerConfig
import kron.TcpConnection
import kron.TcpOptions
import kron.TestSocket
import kron.kernelRelease
import kron.sleepMs
import kron.testConfig
import kron.tls.TlsPipeContractTest.TlsServer
import kron.tls.TlsPipeContractTest.WireClient
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.closedir
import platform.posix.errno
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.recv
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue

/** The kTLS RX handoff when the client's first records leave in the same send as its last handshake record. */
class KernelRxHandoffTest {
    // One recv takes at most this much, so where each recv of a corked burst ends is set by it and not by timing.
    private val recvBufferSize = 4096

    // A TLS 1.3 AES-GCM record on the wire: the 5-byte header, the plaintext, its content type byte and a 16-byte tag.
    private val recordOverhead = 5 + 1 + 16

    private enum class Burst {
        LARGE,     // a 40,000-byte request: the first recv ends inside its first record
        ALIGNED,   // a first record that ends exactly where the first recv does, then a second one
        PAUSED,    // the send stops halfway through the first record for a moment, then goes on
    }

    private class Outcome(val kernelRx: Boolean, val intact: Boolean, val received: Int, val detail: String) {
        override fun toString() = "kernelRx=$kernelRx intact=$intact received=$received B $detail"
    }

    private fun options(kernelRx: Boolean, handshakeTimeoutMs: Int = 10_000) = TlsOptions(
        certificatePath = TestCertificate.certificate,
        keyPath = TestCertificate.key,
        kernelTx = kernelRx,
        kernelRx = kernelRx,
        handshakeTimeoutMs = handshakeTimeoutMs,
    )

    private fun shared(recvQueueEntries: Int = 64, recvSlots: Int = 64) = testConfig(
        TcpOptions(port = 0, recvQueueEntries = recvQueueEntries),
        recvSlots = recvSlots,
        recvBufferSize = recvBufferSize,
    )

    private fun incremental() = ServerConfig(
        reactorCount = 1,
        ringEntries = 256,
        tcp = TcpOptions(port = 0, writeSlabSize = 16 * 1024, poolMax = 8, recvQueueEntries = 64),
        incremental = IncrementalOptions(maxConnections = 16, recvSlots = 16, recvBufferSize = recvBufferSize),
    )

    // kotlin.test has no assumptions: a test the machine cannot run says so and passes.
    private fun skipWithoutKernelTls(): Boolean {
        if (kernelTlsAvailable()) {
            return false
        }

        println("skipped: the tls kernel module is not loaded (sudo modprobe tls)")
        return true
    }

    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    private fun skipWithoutIncremental(): Boolean {
        val parts = kernelRelease().split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (major > 6 || (major == 6 && minor >= 12)) {
            return false
        }

        println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
        return true
    }

    private fun expected(index: Int): Byte = (index * 7 + 13).toByte()

    // The request in the burst's records: its first 4 bytes carry its length and the rest follow [expected].
    private fun records(burst: Burst, handshakeBytes: Int): List<ByteArray> {
        val sizes = when (burst) {
            Burst.LARGE -> intArrayOf(16 * 1024, 16 * 1024, 40_000 - 32 * 1024)
            Burst.PAUSED -> intArrayOf(2_000, 64)
            Burst.ALIGNED -> intArrayOf(recvBufferSize - handshakeBytes - recordOverhead, 64)
        }

        val message = ByteArray(sizes.sum())
        for (i in 0 until 4) {
            message[i] = (message.size ushr (24 - 8 * i)).toByte()
        }
        for (i in 4 until message.size) {
            message[i] = expected(i)
        }

        val pieces = ArrayList<ByteArray>()
        var offset = 0
        for (size in sizes) {
            pieces.add(message.copyOfRange(offset, offset + size))
            offset += size
        }

        return pieces
    }

    private suspend fun readMessage(input: PipeReader, session: TlsSession): Outcome {
        var total = -1
        var seen = 0
        var firstWrong = -1

        while (true) {
            val result = input.read()
            for (b in result.buffer.toByteArray()) {
                if (seen < 4) {
                    total = (total.coerceAtLeast(0) shl 8) or (b.toInt() and 0xFF)
                } else if (b != expected(seen) && firstWrong < 0) {
                    firstWrong = seen
                }
                seen++
            }
            input.advanceTo(result.buffer.length)

            if ((seen >= 4 && seen >= total) || result.isCompleted) {
                break
            }
        }

        val detail = "(expected $total B, first wrong byte at $firstWrong)"
        return Outcome(session.kernelRx, seen == total && firstWrong < 0, seen, detail)
    }

    // Resumes on the reactor's next pass, as ioxide's Task.Yield does through the reactor's context.
    private suspend fun yieldTo(reactor: Reactor) = suspendCoroutine<Unit> { cont ->
        reactor.post {
            cont.resume(Unit)
        }
    }

    // One connection: the handshake, the burst in a single send, and what the handler made of it. The client holds
    // everything after its ClientHello until then, and stays connected until the handler reports.
    private fun serve(
        options: TlsOptions,
        burst: Burst,
        lateHandshake: Boolean = false,
        config: ServerConfig = shared(),
    ): Outcome {
        val report = AtomicReference<Outcome?>(null)
        val handler: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            var session: TlsSession? = null
            var pipe: TlsDuplexPipe? = null
            try {
                if (lateHandshake) {
                    yieldTo(conn.reactor)
                }

                session = service.accept(conn)
                pipe = TlsDuplexPipe(conn, session)
                report.compareAndSet(null, readMessage(pipe.input, session))
            } catch (e: Exception) {
                report.compareAndSet(null, Outcome(session?.kernelRx ?: false, false, 0, "fault: ${e.message}"))
            } finally {
                if (pipe != null) {
                    pipe.close()   // and the session
                } else {
                    session?.close()
                }
            }
        }

        TlsServer(config, options, handler).use { server ->
            WireClient(server.port, corked = true).use { client ->
                // What the handshake left held: the client's last handshake records.
                val handshakeBytes = client.heldBytes
                var firstRecord = 0
                for (record in records(burst, handshakeBytes)) {
                    client.write(record)   // one record each: none is over 16 KiB
                    if (firstRecord == 0) {
                        firstRecord = client.heldBytes - handshakeBytes
                    }
                }

                if (burst == Burst.PAUSED) {
                    client.uncorkPart(handshakeBytes + firstRecord / 2)
                    sleepMs(200)
                }
                client.uncork()

                val deadline = Clock.monotonicMs() + 20_000
                while (report.load() == null && Clock.monotonicMs() < deadline) {
                    sleepMs(5)
                }
            }
        }

        return report.load() ?: Outcome(false, false, 0, "$burst: the server never reported the request")
    }

    // Open descriptors, once two samples 100 ms apart agree: a connection recycling late moves the count.
    private fun stableFdCount(): Int {
        var previous = fdCount()
        val deadline = Clock.monotonicMs() + 5_000
        while (Clock.monotonicMs() < deadline) {
            sleepMs(100)
            val current = fdCount()
            if (current == previous) {
                return current
            }
            previous = current
        }

        return previous
    }

    private fun fdCount(): Int {
        val dir = opendir("/proc/self/fd") ?: error("opendir(/proc/self/fd) failed")
        var entries = 0
        try {
            while (readdir(dir) != null) {
                entries++
            }
        } finally {
            closedir(dir)
        }

        return entries
    }

    // Reads until the server closes - a reset counts - and false when it never did within the receive timeout.
    private fun readsToEnd(socket: TestSocket): Boolean {
        val buffer = ByteArray(256)
        while (true) {
            val n = buffer.usePinned { recv(socket.fd, it.addressOf(0), buffer.size.convert(), 0) }
            if (n == 0L) {
                return true
            }

            if (n < 0) {
                val e = errno
                if (e != EINTR) {
                    return e != EAGAIN
                }
            }
        }
    }

    @Test
    fun `control - the same bursts arrive intact when BoringSSL decrypts`() {
        // So a failure below is about the handoff and not about the client.
        for (burst in listOf(Burst.LARGE, Burst.ALIGNED, Burst.PAUSED)) {
            val outcome = serve(options(kernelRx = false), burst)
            assertTrue(outcome.intact, "$burst: the request did not arrive as sent: $outcome")
        }
    }

    @Test
    fun `a request larger than one recv sent with the client's last handshake record is decrypted by the kernel`() {
        if (skipWithoutKernelTls()) {
            return
        }

        val outcome = serve(options(kernelRx = true), Burst.LARGE)
        assertTrue(outcome.intact, "the request did not arrive as sent: $outcome")
        assertTrue(outcome.kernelRx, "the handshake did not hand the read side to the kernel: $outcome")
    }

    @Test
    fun `a record behind the one that fills the first recv is decrypted and not handed over as ciphertext`() {
        if (skipWithoutKernelTls()) {
            return
        }

        val outcome = serve(options(kernelRx = true), Burst.ALIGNED)
        assertTrue(outcome.intact, "the request did not arrive as sent: $outcome")
        assertTrue(outcome.kernelRx, "the handshake did not hand the read side to the kernel: $outcome")
    }

    @Test
    fun `a burst with more completions than the receive queue holds does not overflow it during the handoff`() {
        if (skipWithoutKernelTls()) {
            return
        }

        // The handoff keeps reading while it waits for the end of the batch.
        val config = shared(recvQueueEntries = 4, recvSlots = 256)
        val outcome = serve(options(kernelRx = true), Burst.LARGE, config = config)
        assertTrue(outcome.intact, "the request did not arrive as sent: $outcome")
        assertTrue(outcome.kernelRx, "the handshake did not hand the read side to the kernel: $outcome")
    }

    @Test
    fun `a record cut by a pause in the client's send is waited for and then decrypted by the kernel`() {
        if (skipWithoutKernelTls()) {
            return
        }

        // The handshake's last read ends inside a record; the handoff waits for its rest.
        val outcome = serve(options(kernelRx = true), Burst.PAUSED)
        assertTrue(outcome.intact, "the request did not arrive as sent: $outcome")
        assertTrue(outcome.kernelRx, "the handshake did not hand the read side to the kernel: $outcome")
    }

    @Test
    fun `incremental mode - both bursts are decrypted by the kernel`() {
        if (skipWithoutKernelTls() || skipWithoutIncremental()) {
            return
        }

        // A ring per connection takes the other completion path, with its running buffer offsets.
        for (burst in listOf(Burst.LARGE, Burst.ALIGNED)) {
            val outcome = serve(options(kernelRx = true), burst, config = incremental())
            assertTrue(outcome.intact, "$burst: the request did not arrive as sent: $outcome")
            assertTrue(outcome.kernelRx, "$burst: the handshake did not hand the read side to the kernel: $outcome")
        }
    }

    @Test
    fun `a handshake started after the handler's first suspension is decrypted by the kernel too`() {
        if (skipWithoutKernelTls()) {
            return
        }

        // Nothing in the handoff depends on when the handler starts the handshake.
        val outcome = serve(options(kernelRx = true), Burst.LARGE, lateHandshake = true)
        assertTrue(outcome.intact, "the request did not arrive as sent: $outcome")
        assertTrue(outcome.kernelRx, "the handshake did not hand the read side to the kernel: $outcome")
    }

    @Test
    fun `a client that stops or closes halfway through a record after its handshake is dropped`() {
        if (skipWithoutKernelTls()) {
            return
        }

        // A cut record holds the handoff until its rest arrives: the handshake timeout or a FIN has to end it.
        val handshakeOnly: suspend (TcpConnection, TlsService) -> Unit = { conn, service ->
            try {
                service.accept(conn).close()
            } catch (e: IOException) {
                // Failing is what these connections are for.
            }
        }

        TlsServer(shared(), options(kernelRx = true, handshakeTimeoutMs = 3_000), handshakeOnly).use { server ->
            val baseline = stableFdCount()

            for (closes in listOf(false, true)) {
                WireClient(server.port, corked = true, receiveTimeoutMs = 10_000).use { client ->
                    val handshakeBytes = client.heldBytes
                    client.write(ByteArray(1_000))
                    client.uncorkPart(handshakeBytes + (client.heldBytes - handshakeBytes) / 2)
                    if (closes) {
                        client.shutdownOutput()
                    }

                    val start = Clock.monotonicMs()
                    assertTrue(readsToEnd(client.socket), "closes=$closes: the server never dropped the connection")
                    val elapsed = Clock.monotonicMs() - start

                    // Under the 3 s handshake timeout when the client closes: its FIN ends the wait at once.
                    val bound = if (closes) 2_000 else 8_000
                    assertTrue(elapsed < bound, "closes=$closes: dropped only after $elapsed ms")
                }
            }

            val after = stableFdCount()
            assertTrue(after <= baseline, "descriptors went from $baseline to $after: a dropped connection was kept")
        }
    }
}
