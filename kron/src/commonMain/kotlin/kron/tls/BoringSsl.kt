package kron.tls

/**
 * The BoringSSL surface TLS needs, with pointers as Longs: on Kotlin/Native through cinterop, BoringSSL linked into
 * the binary; on the JVM through the Foreign Function & Memory API, from BoringSSL built as a shared library. A
 * function that fails returns what the C call does; [lastError] says why.
 */
internal expect object BoringSsl {
    val SSL_FILETYPE_PEM: Int
    val SSL_ERROR_WANT_READ: Int
    val SSL_ERROR_WANT_WRITE: Int
    val SSL_ERROR_ZERO_RETURN: Int
    val TLS1_2_VERSION: Short
    val TLS1_3_VERSION: Short
    val TLS_AES_128_GCM_SHA256: Short
    val SSL_TLSEXT_ERR_OK: Int
    val SSL_TLSEXT_ERR_NOACK: Int
    val SSL_OP_CIPHER_SERVER_PREFERENCE: Int
    val BIO_CTRL_INFO: Int
    fun serverMethod(): Long
    fun ctxNew(method: Long): Long
    fun ctxFree(ctx: Long)
    fun useCertificateChainFile(ctx: Long, path: String): Int
    fun usePrivateKeyFile(ctx: Long, path: String): Int
    fun checkPrivateKey(ctx: Long): Int
    fun useCertificate(ctx: Long, x509: Long): Int
    fun usePrivateKey(ctx: Long, pkey: Long): Int
    fun addExtraChainCert(ctx: Long, x509: Long): Int
    fun setMinProtoVersion(ctx: Long, version: Short): Int
    fun setMaxProtoVersion(ctx: Long, version: Short): Int
    fun setTls13Ciphers(ctx: Long, ids: ShortArray): Int
    fun tls13SuiteId(name: String): Short?
    fun setCipherList(ctx: Long, list: String): Int
    fun setNumTickets(ctx: Long, count: Long): Int
    fun setOptions(ctx: Long, options: Int): Int
    fun setKeylogCallback(ctx: Long, callback: Long)
    fun setAlpnSelectCallback(ctx: Long, callback: Long, arg: Long)
    fun setDefaultPasswordCallback(ctx: Long, callback: Long)
    fun setSessionIdContext(ctx: Long, id: Long, length: Long): Int
    fun sslNew(ctx: Long): Long
    fun sslFree(ssl: Long)
    fun setAcceptState(ssl: Long)
    fun setBio(ssl: Long, rbio: Long, wbio: Long)
    fun shutdown(ssl: Long): Int
    fun inInit(ssl: Long): Boolean
    fun version(ssl: Long): Int
    fun currentCipherId(ssl: Long): Int
    fun alpnSelected(ssl: Long): String?
    fun accept(ssl: Long): Int
    fun read(ssl: Long, buffer: Long, length: Int): Int
    fun write(ssl: Long, buffer: Long, length: Int): Int
    fun write(ssl: Long, source: ByteArray, offset: Int, length: Int): Int
    fun memoryBio(): Long
    fun memoryBuffer(address: Long, length: Long): Long
    fun bioFree(bio: Long): Int
    fun bioWrite(bio: Long, data: Long, length: Int): Int
    fun bioRead(bio: Long, data: Long, length: Int): Int
    fun bioPending(bio: Long): Long
    fun bioCtrl(bio: Long, cmd: Int, larg: Long, parg: Long): Long
    fun readX509(bio: Long): Long
    fun readPrivateKey(bio: Long): Long
    fun x509Free(x509: Long)
    fun pkeyFree(pkey: Long)
    fun clearError()
    fun errorIsKeyMismatch(): Boolean
    fun errorIsEndOfPem(): Boolean
    fun lastError(): String
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun cString(address: Long): String
}
