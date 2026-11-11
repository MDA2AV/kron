package kronos.tls

import kotlinx.cinterop.useContents
import kronos.KronosServer
import kronos.Latch
import kronos.testConfig
import platform.linux.mallinfo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A TLS service's end of life: the proactor it started on frees its context, the ALPN list and the kTLS handoff's
 * buffer when it stops. Nothing did, so a host starting servers again in one process kept every certificate and key.
 */
class TlsServiceTeardownTest {
    private val options = TlsOptions(
        certificatePath = TestCertificate.certificate,
        keyPath = TestCertificate.key,
        handshakeTimeoutMs = 0,
    )

    @Test
    fun `a TLS server started and stopped a hundred times leaves the native heap where it was`() {
        repeat(10) {
            startAndStop()   // BoringSSL's one-time setup
        }

        val before = heapInUse()
        repeat(100) {
            startAndStop()
        }
        val grown = heapInUse() - before

        assertTrue(grown < 64 * 1024, "a hundred TLS servers started and stopped left ${grown / 1024} KiB behind")
    }

    // The service is not kept: its proactor is all that holds it.
    private fun startAndStop() {
        val ready = Latch(1)
        KronosServer(
            testConfig(),
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
