package kronos.tls

import kotlinx.cinterop.useContents
import kronos.KronosServer
import kronos.Latch
import kronos.ServerConfig
import kronos.TcpOptions
import platform.linux.mallinfo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A TLS service's end of life: the proactor it started on frees its kTLS handoff's buffer when it stops, and the last
 * of the proactors sharing one options instance frees their context and its ALPN list. Nothing did, so a host
 * starting servers again in one process kept every certificate and key.
 */
class TlsServiceTeardownTest {
    private val options = TlsOptions(
        certificatePath = TestCertificate.certificate,
        keyPath = TestCertificate.key,
        handshakeTimeoutMs = 0,
    )

    @Test
    fun `a TLS server of two proactors started and stopped a hundred times leaves the native heap where it was`() {
        repeat(10) {
            startAndStop()   // BoringSSL's one-time setup
        }

        val before = heapInUse()
        repeat(100) {
            startAndStop()
        }
        val grown = heapInUse() - before

        assertTrue(grown < 64 * 1024, "a hundred TLS servers of two proactors started and stopped left ${grown / 1024} KiB behind")
    }

    // Two proactors sharing the options' context, their services not kept: the proactors are all that hold them.
    private fun startAndStop() {
        val ready = Latch(2)
        KronosServer(
            ServerConfig(proactorCount = 2, ringEntries = 256, recvSlots = 64, recvBufferSize = 4096, tcp = TcpOptions(port = 0)),
            onStart = { proactor ->
                TlsService.start(proactor, options)
                ready.countDown()
            },
            handler = { },
        ).start().use {
            assertTrue(ready.await(10_000), "the proactor never started")
        }
    }

    // What malloc has handed out and not had back, over every arena: BoringSSL allocates through it.
    private fun heapInUse(): Long = mallinfo().useContents { uordblks.toLong() + hblkhd.toLong() }
}
