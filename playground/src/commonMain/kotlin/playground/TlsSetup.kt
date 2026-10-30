package playground

import kron.tls.TlsOptions

/**
 * The TLS samples' shared settings: PLAYGROUND_TLS_CERT and PLAYGROUND_TLS_KEY name a PEM certificate and key, and
 * without them a self-signed certificate for localhost is made at startup. PLAYGROUND_KTLS_TX=1 hands encryption
 * to the kernel, PLAYGROUND_KTLS_RX=1 decryption as well.
 */
object TlsSetup {
    /** The options, and a line saying where the certificate came from and who does the crypto. */
    fun options(sample: String): Pair<TlsOptions, String> {
        val certPath = Env.string("PLAYGROUND_TLS_CERT")
        val keyPath = Env.string("PLAYGROUND_TLS_KEY")
        if ((certPath == null) != (keyPath == null)) {
            log("[$sample] set both PLAYGROUND_TLS_CERT and PLAYGROUND_TLS_KEY, or neither for a self-signed certificate")
            exit(2)
        }

        val kernelTx = Env.bool("PLAYGROUND_KTLS_TX", false)
        val kernelRx = Env.bool("PLAYGROUND_KTLS_RX", false)
        val options = if (certPath != null && keyPath != null) {
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

        val mode = when {
            kernelRx -> "kernel tx+rx"
            kernelTx -> "kernel tx"
            else -> "BoringSSL both ways"
        }
        val source = certPath ?: "self-signed for localhost"
        return options to "cert $source, $mode"
    }
}
