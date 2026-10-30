package playground

actual object SelfSigned {
    // Made natively through BoringSSL's key and X509 calls, which the JVM binding does not carry.
    actual fun generate(): Pair<String, String> =
        throw UnsupportedOperationException("On the JVM, set PLAYGROUND_TLS_CERT and PLAYGROUND_TLS_KEY to a PEM pair")
}
