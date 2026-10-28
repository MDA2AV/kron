package kron.tls

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kron.Clock
import kron.IOException
import kron.KronServer
import kron.Latch
import kron.MpscQueue
import kron.Native
import kron.Reactor
import kron.RecvItem
import kron.ServerConfig
import kron.Sockets
import kron.TcpConnection
import kron.TcpOptions
import kron.TestSocket
import kron.TestThreads
import kron.boringssl.ASN1_INTEGER_set
import kron.boringssl.BIO
import kron.boringssl.BIO_ctrl_pending
import kron.boringssl.BIO_free
import kron.boringssl.BIO_new
import kron.boringssl.BIO_new_mem_buf
import kron.boringssl.BIO_read
import kron.boringssl.BIO_s_mem
import kron.boringssl.BIO_write
import kron.boringssl.EVP_PKEY
import kron.boringssl.EVP_PKEY_free
import kron.boringssl.EVP_PKEY_get0_RSA
import kron.boringssl.EVP_RSA_gen
import kron.boringssl.EVP_aes_256_cbc
import kron.boringssl.EVP_sha256
import kron.boringssl.MBSTRING_ASC
import kron.boringssl.PEM_read_bio_PrivateKey
import kron.boringssl.PEM_write_bio_PrivateKey
import kron.boringssl.PEM_write_bio_RSAPrivateKey
import kron.boringssl.PEM_write_bio_X509
import kron.boringssl.SSL
import kron.boringssl.SSL_CIPHER_get_protocol_id
import kron.boringssl.SSL_CTX
import kron.boringssl.SSL_CTX_free
import kron.boringssl.SSL_CTX_new
import kron.boringssl.SSL_CTX_set_max_proto_version
import kron.boringssl.SSL_CTX_set_min_proto_version
import kron.boringssl.SSL_do_handshake
import kron.boringssl.SSL_free
import kron.boringssl.SSL_get_current_cipher
import kron.boringssl.SSL_get_error
import kron.boringssl.SSL_in_init
import kron.boringssl.SSL_new
import kron.boringssl.SSL_read
import kron.boringssl.SSL_set1_tls13_ciphers
import kron.boringssl.SSL_set_alpn_protos
import kron.boringssl.SSL_set_bio
import kron.boringssl.SSL_set_connect_state
import kron.boringssl.SSL_shutdown
import kron.boringssl.SSL_version
import kron.boringssl.SSL_write
import kron.boringssl.TLS_client_method
import kron.boringssl.X509_NAME_add_entry_by_txt
import kron.boringssl.X509_VERSION_3
import kron.boringssl.X509_free
import kron.boringssl.X509_get_serialNumber
import kron.boringssl.X509_get_subject_name
import kron.boringssl.X509_getm_notAfter
import kron.boringssl.X509_getm_notBefore
import kron.boringssl.X509_gmtime_adj
import kron.boringssl.X509_new
import kron.boringssl.X509_set_issuer_name
import kron.boringssl.X509_set_pubkey
import kron.boringssl.X509_set_version
import kron.boringssl.X509_sign
import kron.getByte
import kron.getLong
import kron.putByte
import kron.putLong
import kron.putShort
import kron.readFileBytes
import kron.sleepMs
import kron.testConfig
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.MSG_DONTWAIT
import platform.posix.MSG_PEEK
import platform.posix.errno
import platform.posix.recv
import platform.posix.unlink
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** ALPN on the TCP side: whose preference decides, what an offer the server cannot meet gets, and malformed offers. */
class AlpnTest {
    private val cert = TestCertificate.certificate
    private val key = TestCertificate.key

    // Every case reads both views of the negotiation: what the client was told, and - in the body - what the handler's
    // session holds, which is what a handler serving h2 and h1 on one port branches on.
    private fun <T> withAlpnServer(alpn: List<String>, body: (port: Int) -> T): T = withTlsServer(
        TlsOptions(certificatePath = cert, keyPath = key, alpn = alpn),
        { service, conn ->
            answerRequests(service, conn) { session ->
                "alpn=${session.negotiatedAlpn ?: "none"}"
            }
        },
    ) { port, _ ->
        body(port)
    }

    // A request over a client offering offer, null for no ALPN extension at all: what it was told, and the body.
    private fun ask(port: Int, offer: List<String>?): Pair<String?, String> =
        BioClient(port, alpn = offer).use { client ->
            assertTrue(client.handshake(), "the handshake was refused: ${client.refusal}")
            client.write(REQUEST)

            val (status, body) = client.readResponse()
            assertEquals(200, status)
            client.alpn to body
        }

    @Test
    fun `the server's preference order decides and not the client's`() {
        // The same two-protocol offer to two servers that differ only in list order: no order-independent selection -
        // client preference, a hardcoded h2, the first offered - can answer the pair differently.
        val offer = listOf("http/1.1", "h2")

        withAlpnServer(listOf("h2", "http/1.1")) { port ->
            val (told, body) = ask(port, offer)
            assertEquals("h2", told)
            assertEquals("alpn=h2", body, "the handler must see the same choice the client was told")
        }

        withAlpnServer(listOf("http/1.1", "h2")) { port ->
            val (told, body) = ask(port, offer)
            assertEquals("http/1.1", told)
            assertEquals("alpn=http/1.1", body, "the handler must see the same choice the client was told")
        }
    }

    @Test
    fun `a protocol the client did not offer is skipped and not imposed`() {
        // The walk continues past our favourite when the client lacks it: a client told a protocol it never offered
        // aborts.
        withAlpnServer(listOf("h2", "http/1.1")) { port ->
            val (told, body) = ask(port, listOf("http/1.1"))
            assertEquals("http/1.1", told)
            assertEquals("alpn=http/1.1", body)
        }
    }

    @Test
    fun `a client offering no ALPN at all is served and the server records none`() {
        // No extension means the select callback never runs: the handler's view must be null, not stale or defaulted.
        withAlpnServer(listOf("h2", "http/1.1")) { port ->
            val (told, body) = ask(port, null)
            assertNull(told)
            assertEquals("alpn=none", body, "a client that offered nothing must not be recorded as having negotiated")
        }
    }

    @Test
    fun `the handler's negotiatedAlpn matches what the client was told`() {
        withAlpnServer(listOf("h2", "http/1.1")) { port ->
            val (told, body) = ask(port, listOf("h2"))
            assertEquals("h2", told)
            assertEquals("alpn=h2", body, "the session must record the protocol the client was told")
        }
    }

    @Test
    fun `a 255-byte protocol name negotiates at the length limit`() {
        // The largest length the wire's one-byte prefix carries, and the largest the server's list takes.
        val big = "a".repeat(255)

        withAlpnServer(listOf(big)) { port ->
            val (told, body) = ask(port, listOf(big))
            assertEquals(big, told)
            assertEquals("alpn=$big", body, "the handler must see the full 255-byte name")
        }
    }

    @Test
    fun `an unusable protocol list is refused at start`() {
        // The wire cannot express these, so each is refused at start rather than left to fail per handshake. The
        // control is every other test here starting.
        fun refusedFor(alpn: List<String>): String? =
            refusal(TlsOptions(certificatePath = cert, keyPath = key, alpn = alpn))?.message

        assertTrue(
            refusedFor(emptyList())?.contains("At least one ALPN protocol") == true,
            "an empty list was not refused",
        )
        assertTrue(refusedFor(listOf(""))?.contains("must be 1..255") == true, "a zero-length name was not refused")
        assertTrue(
            refusedFor(listOf("a".repeat(256)))?.contains("must be 1..255") == true,
            "a 256-byte name was not refused",
        )
    }

    @Test
    fun `a truncated or empty offer entry is declined - never matched or overread`() {
        // BoringSSL validates the ClientHello's list while parsing it - an empty or truncated entry is a decode_error -
        // and hands the callback only what passed, so no handshake can deliver these. The select callback is called
        // directly instead, through the pointer BoringSSL is given: if its bounds are simplified away, only this
        // notices.
        val select = TlsCallbacks.alpnSelect.toCPointer<AlpnSelectFunction>()!!

        // The server speaks h2, laid out as the service hands it to the callback: a u16 total, then the entries.
        val wire = Native.malloc(5)
        putShort(wire, 3)
        putByte(wire + 2, 2)
        putByte(wire + 3, 'h'.code.toByte())
        putByte(wire + 4, '2'.code.toByte())

        val outSlot = Native.malloc(8)
        val outLength = Native.malloc(1)
        val offer = Native.malloc(64)

        // The callback's answer, then what it left in *out and *out_len, for the first length bytes of offered.
        fun run(offered: String, length: Int = offered.length): Triple<Int, Long, Int> {
            putLong(outSlot, -1)   // sentinels: a decline must leave both untouched
            putByte(outLength, 0xEE.toByte())
            for (i in offered.indices) {
                putByte(offer + i, offered[i].code.toByte())
            }

            val rc = select(
                null,
                outSlot.toCPointer<ByteVar>(),
                outLength.toCPointer<ByteVar>(),
                offer.toCPointer<ByteVar>(),
                length.toUInt(),
                wire.toCPointer<ByteVar>(),
            )
            return Triple(rc, getLong(outSlot), getByte(outLength).toInt() and 0xFF)
        }

        try {
            // On a thread of its own: a scan wedged on an empty entry ends this test, not the binary.
            val results = within(10_000, "the select callback") {
                listOf(
                    run("\u0003foo\u0002h2"),   // control: well-formed, h2 second
                    run("\u0005h2"),            // an entry claiming more bytes than the list holds
                    run("\u0003foo\u0009h"),    // truncated after a valid entry that does not match
                    run("\u0000\u0000\u0000"),  // zero-length entries only
                    run("\u0000\u0002h2"),      // a zero-length entry ahead of a real one
                    run(""),                    // an empty list, the degenerate bound
                    run("\u0002h2", 2),         // h2's length, with the '2' lying just past the list's end
                )
            }

            val ok = BoringSsl.SSL_TLSEXT_ERR_OK
            val noAck = BoringSsl.SSL_TLSEXT_ERR_NOACK

            // The control matches, and *out points at the name inside the client's buffer, not at its length byte.
            assertEquals(Triple(ok, offer + 5, 2), results[0])

            // Declined, and nothing written.
            assertEquals(Triple(noAck, -1L, 0xEE), results[1], "a declined offer must not have written a selection")

            // The scan stops at the bound.
            assertEquals(noAck, results[2].first)

            // Skipped without wedging, and nothing selected.
            assertEquals(noAck, results[3].first)

            // The match after an empty entry still points at its name.
            assertEquals(Triple(ok, offer + 2, 2), results[4])

            assertEquals(noAck, results[5].first)

            // The one truncation a missing bound would match: by reading the byte after the list.
            assertEquals(
                Triple(noAck, -1L, 0xEE),
                results[6],
                "an entry cut by the list's end must not match on what lies past it",
            )
        } finally {
            Native.free(wire)
            Native.free(outSlot)
            Native.free(outLength)
            Native.free(offer)
        }
    }

    @Test
    fun `a non-ASCII protocol name does not negotiate as a different protocol`() {
        // A name built from each char's low byte puts "h2" on the wire for U+0168 '2', and a server configured to speak
        // only that would ACK any client offering h2. Refusing the name at start is one right answer; serving it
        // without ever telling an h2 client h2 is the other.
        val exotic = "\u0168" + "2"

        val refused = refusal(TlsOptions(certificatePath = cert, keyPath = key, alpn = listOf(exotic)))
        if (refused != null) {
            // Narrow on purpose: an unrelated failure to start stays a failure.
            assertTrue(
                refused.message?.contains("ALPN") == true,
                "refused, but not for the protocol name: ${refused.message}",
            )
            return
        }

        withAlpnServer(listOf(exotic)) { port ->
            // Control first: the server is alive, so the probe below cannot pass against a dead port.
            assertEquals("alpn=none", ask(port, null).second)

            val told = BioClient(port, alpn = listOf("h2")).use { client ->
                if (client.handshake()) client.alpn else null   // declining to speak h2 satisfies the claim too
            }
            assertTrue(told != "h2", "a server configured to speak only 'U+0168 2' told an h2 client h2")
        }
    }
}

/** Building a context: what start refuses, the PEM dialects deployments hand over, and the posture read back. */
class TlsContextTest {
    private val cert = TestCertificate.certificate
    private val key = TestCertificate.key

    // Serves one TLS 1.3 request with this certificate and key, written to files as most deployments hand them over.
    private fun assertServedFromFiles(name: String, certificatePem: String, keyPem: String) {
        val certFile = "${TestCertificate.directory}/$name.crt"
        val keyFile = "${TestCertificate.directory}/$name.key"
        writeFile(certFile, certificatePem.encodeToByteArray())
        writeFile(keyFile, keyPem.encodeToByteArray())

        try {
            withTlsServer(TlsOptions(certificatePath = certFile, keyPath = keyFile)) { port, _ ->
                assertServed(port)
            }
        } finally {
            unlink(certFile)
            unlink(keyFile)
        }
    }

    // Why a certificate file and a key file are refused at start, or null when they start.
    private fun refusalOfFiles(certificatePem: String, keyPem: String): String? {
        val certFile = "${TestCertificate.directory}/pair.crt"
        val keyFile = "${TestCertificate.directory}/pair.key"
        writeFile(certFile, certificatePem.encodeToByteArray())
        writeFile(keyFile, keyPem.encodeToByteArray())

        try {
            return refusal(TlsOptions(certificatePath = certFile, keyPath = keyFile))?.message
        } finally {
            unlink(certFile)
            unlink(keyFile)
        }
    }

    @Test
    fun `a name that is not a TLS 1_3 suite is refused rather than accepted into a port that serves nobody`() {
        // The IANA spelling of a TLS 1.2 suite, as every compliance list and capture writes it: OpenSSL resolved it
        // over its whole cipher table and filed it in a list only TLS 1.3 reads, where it served nobody.
        val tls12Suite = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"

        val alone = refusal(TlsOptions(certificatePath = cert, keyPath = key, cipherSuites = tls12Suite))
        if (alone != null) {
            assertTrue(
                alone.message?.contains(tls12Suite) == true,
                "refused, but not for the suite named: ${alone.message}",
            )
        } else {
            // It started, so it has to serve someone, at either version.
            withTlsServer(TlsOptions(certificatePath = cert, keyPath = key, cipherSuites = tls12Suite)) { port, _ ->
                assertTrue(
                    handshakes(port, BoringSsl.TLS1_2_VERSION, BoringSsl.TLS1_3_VERSION),
                    "cipherSuites = '$tls12Suite' started and then refused every client",
                )
            }
        }

        // Beside a real 1.3 suite the port would handshake fine and silently offer one suite, not the two configured.
        val beside = refusal(
            TlsOptions(certificatePath = cert, keyPath = key, cipherSuites = "TLS_AES_256_GCM_SHA384:$tls12Suite"),
        )
        assertTrue(
            beside?.message?.contains(tls12Suite) == true,
            "'$tls12Suite' beside a real 1.3 suite was not refused for it: ${beside?.message}",
        )
    }

    @Test
    fun `control - a list of real TLS 1_3 suite names starts and the port serves it`() {
        // The guard on the refusal above: only a name that cannot be offered over TLS 1.3 is turned away.
        val options = TlsOptions(
            certificatePath = cert,
            keyPath = key,
            cipherSuites = "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384",
        )

        assertNull(refusal(options), "a list of real TLS 1.3 suites was refused")
        withTlsServer(options) { port, _ ->
            assertTrue(
                handshakes(port, BoringSsl.TLS1_3_VERSION, BoringSsl.TLS1_3_VERSION),
                "the port did not serve a TLS 1.3 client",
            )
        }
    }

    @Test
    fun `a PKCS1 key as openssl genrsa writes it is served`() {
        assertTrue(RsaFixture.pkcs1KeyPem.startsWith("-----BEGIN RSA PRIVATE KEY-----"), "control: not a PKCS#1 key")
        assertServedFromFiles("pkcs1", RsaFixture.certificatePem, RsaFixture.pkcs1KeyPem)
    }

    @Test
    fun `a certificate and key with CRLF line endings - a file that has been through Windows - are served`() {
        assertServedFromFiles(
            "crlf",
            TestCertificate.certificatePem.replace("\n", "\r\n"),
            TestCertificate.keyPem.replace("\n", "\r\n"),
        )
    }

    @Test
    fun `a certificate with the human-readable dump openssl x509 -text prints above it is served`() {
        val dump = "Certificate:\n    Issuer: CN=localhost\n    Subject: CN=localhost\n"
        assertServedFromFiles("dump", dump + TestCertificate.certificatePem, TestCertificate.keyPem)
    }

    @Test
    fun `an RSA certificate and key are served as well as EC ones`() {
        // The fixture every other test uses is EC, so this is the other algorithm.
        assertServedFromFiles("rsa", RsaFixture.certificatePem, RsaFixture.pkcs8KeyPem)
    }

    @Test
    fun `the same certificate served from memory behaves as it does from a path`() {
        // The two sources are documented as equivalent; this pins the certificate pair to that.
        val options = TlsOptions(
            certificatePem = readFileBytes(cert)!!.decodeToString(),
            keyPem = readFileBytes(key)!!.decodeToString(),
        )

        withTlsServer(options) { port, _ ->
            assertServed(port)
        }
    }

    @Test
    fun `a key of a different algorithm to the certificate is refused at start`() {
        // An RSA certificate beside an EC key: what moving a server from RSA to EC and updating one file produces. Left
        // to the handshakes, every one would fail with no shared cipher.
        val refused = refusalOfFiles(RsaFixture.certificatePem, TestCertificate.keyPem)
        assertTrue(refused?.contains("do not go together") == true, "refused with: $refused")
    }

    @Test
    fun `two unrelated RSA halves are refused at start too`() {
        val refused = refusalOfFiles(RsaFixture.certificatePem, RsaFixture.otherKeyPem)
        assertTrue(refused?.contains("do not go together") == true, "refused with: $refused")
    }

    @Test
    fun `an encrypted private key is refused at once without looking for a passphrase`() {
        // There is no passphrase option, so the only right outcome is a prompt refusal naming the key. The
        // certificate's own key is the one encrypted, so the encryption is the only thing to refuse it for.
        val encrypted = encryptedKeyPem()
        assertTrue(
            encrypted.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"),
            "control: the key was not written encrypted",
        )

        val keyFile = "${TestCertificate.directory}/enc.key"
        writeFile(keyFile, encrypted.encodeToByteArray())

        try {
            // The deadline is the assertion: a start that waits for a passphrase must fail here, not hang the binary.
            val fromFile = within(20_000, "refusing an encrypted key file") {
                refusal(TlsOptions(certificatePath = cert, keyPath = keyFile))?.message ?: "<accepted>"
            }
            assertTrue(fromFile.contains(keyFile), "the refusal should name the key file: $fromFile")

            val fromMemory = within(20_000, "refusing an encrypted key in memory") {
                val options = TlsOptions(certificatePem = TestCertificate.certificatePem, keyPem = encrypted)
                refusal(options)?.message ?: "<accepted>"
            }
            assertTrue(fromMemory.contains("keyPem"), "the refusal should name the key: $fromMemory")
        } finally {
            unlink(keyFile)
        }
    }

    @Test
    fun `the protocol and ciphersuite the handler reads are the ones negotiated`() {
        // Driven against a client pinned to each version: a pair of accessors is only shown to be read, rather than
        // constants that happen to match, by making the answer change.
        val handler: suspend (TlsService, TcpConnection) -> Unit = { service, conn ->
            var session: TlsSession? = null
            try {
                val tls = service.accept(conn)
                session = tls

                val body = "${tls.negotiatedProtocolVersion}|${tls.negotiatedCipherSuiteId}"
                tls.write(conn, "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".encodeToByteArray())
                conn.flush()

                conn.read()   // so the client's close is observed rather than raced
            } catch (e: IOException) {
                // A refused handshake; the client reports it.
            } finally {
                session?.close()
            }
        }

        withTlsServer(TlsOptions(certificatePath = cert, keyPath = key), handler) { port, _ ->
            // What the handler reported, and what the client itself settled on.
            fun posture(version: Short): Triple<String, Int, Int> =
                BioClient(port, version, version).use { client ->
                    assertTrue(client.handshake(), "the handshake was refused: ${client.refusal}")

                    val (status, body) = client.readResponse()
                    assertEquals(200, status)
                    Triple(body, client.version, client.cipherId)
                }

            val (thirteen, version13, suite13) = posture(BoringSsl.TLS1_3_VERSION)
            assertEquals(0x0304, version13)
            assertEquals("${0x0304}|$suite13", thirteen)

            val (twelve, version12, suite12) = posture(BoringSsl.TLS1_2_VERSION)
            assertEquals(0x0303, version12)
            assertEquals("${0x0303}|$suite12", twelve)

            // Different, so neither assertion is met by a constant that happens to match one of them.
            assertTrue(thirteen != twelve, "both handshakes reported the same posture")
        }
    }
}

/** The versions and ciphersuites a deployment states rather than discovers, and refusals that keep their reasons. */
class PostureTest {
    private val cert = TestCertificate.certificate
    private val key = TestCertificate.key

    @Test
    fun `minProtocolVersion TLS13 refuses a TLS 1_2 client`() {
        val options = TlsOptions(certificatePath = cert, keyPath = key, minProtocolVersion = TlsProtocolVersion.TLS13)

        withTlsServer(options) { port, _ ->
            assertFalse(
                handshakes(port, BoringSsl.TLS1_2_VERSION, BoringSsl.TLS1_2_VERSION),
                "a TLS 1.2 client should have been refused by a 1.3-only server",
            )
            assertTrue(
                handshakes(port, BoringSsl.TLS1_3_VERSION, BoringSsl.TLS1_3_VERSION),
                "a TLS 1.3 client should still be served",
            )
        }
    }

    @Test
    fun `a ciphersuite list the client cannot meet fails the handshake`() {
        // The server offers only AES-256 and each client one suite; both are TLS 1.3's, so only the list decides.
        val options = TlsOptions(
            certificatePath = cert,
            keyPath = key,
            minProtocolVersion = TlsProtocolVersion.TLS13,
            cipherSuites = "TLS_AES_256_GCM_SHA384",
        )

        withTlsServer(options) { port, _ ->
            val tls13 = BoringSsl.TLS1_3_VERSION
            assertTrue(
                handshakes(port, tls13, tls13, shortArrayOf(AES_256_GCM)),
                "a client offering the one suite the server allows should be served",
            )
            assertFalse(
                handshakes(port, tls13, tls13, shortArrayOf(CHACHA20_POLY1305)),
                "a client offering only a suite the server excluded should have been refused",
            )
        }
    }

    @Test
    fun `two handshakes refused in one reactor turn each report their own reason`() {
        val reasons = MpscQueue<String>()
        val started = AtomicInt(0)

        val handler: suspend (TlsService, TcpConnection) -> Unit = { service, conn ->
            started.addAndFetch(1)
            try {
                service.accept(conn).close()
            } catch (e: IOException) {
                val message = e.message ?: ""
                if (message.startsWith("TLS handshake failed")) {
                    reasons.offer(message)
                }
            }
        }

        val options = TlsOptions(
            certificatePath = cert,
            keyPath = key,
            minProtocolVersion = TlsProtocolVersion.TLS13,
            cipherSuites = "TLS_AES_256_GCM_SHA384",
        )

        withTlsServer(options, handler) { port, reactor ->
            // Two different refusals from one server, so a reason that moved between connections shows.
            val tls13 = BoringSsl.TLS1_3_VERSION
            val tls12 = clientHello(BoringSsl.TLS1_2_VERSION, BoringSsl.TLS1_2_VERSION)
            val chacha = clientHello(tls13, tls13, shortArrayOf(CHACHA20_POLY1305))

            // Control: one at a time, each names its own reason.
            val alone12 = refusedAlone(port, tls12, reasons)
            val aloneChacha = refusedAlone(port, chacha, reasons)
            assertTrue(
                alone12.contains("UNSUPPORTED_PROTOCOL"),
                "control: a TLS 1.2 ClientHello alone was refused with: $alone12",
            )
            assertTrue(
                aloneChacha.contains("NO_SHARED_CIPHER"),
                "control: a ChaCha20-only ClientHello alone was refused with: $aloneChacha",
            )

            val before = started.load()
            TestSocket().use { first ->
                TestSocket().use { second ->
                    first.setReceiveTimeout(5_000)
                    second.setReceiveTimeout(5_000)
                    first.connect(port)
                    second.connect(port)
                    assertTrue(
                        waitUntil(10_000) { started.load() == before + 2 },
                        "the server never started both handshakes",
                    )

                    // The reactor is held while both land, so one turn reads them and the second runs during the
                    // first's alert flush. Bounded, so a failing test cannot keep the reactor.
                    val held = Latch(1)
                    val release = Latch(1)
                    reactor.post {
                        held.countDown()
                        release.await(10_000)
                    }

                    try {
                        assertTrue(held.await(10_000), "the reactor never ran the hold")
                        first.write(tls12)
                        second.write(chacha)

                        // In the server's kernel before the reactor goes on, so the next enter has both.
                        assertTrue(
                            first.awaitAcknowledged(5_000) && second.awaitAcknowledged(5_000),
                            "the ClientHellos were never acknowledged",
                        )
                    } finally {
                        release.countDown()
                    }

                    val pair = listOf(nextReason(reasons), nextReason(reasons))
                    assertTrue(
                        pair.any { "UNSUPPORTED_PROTOCOL" in it } && pair.any { "NO_SHARED_CIPHER" in it },
                        "each refusal should report its own reason, got: ${pair.joinToString(" | ")}",
                    )
                }
            }
        }
    }

    @Test
    fun `a ciphersuite name BoringSSL does not know fails at start`() {
        // Not at the first handshake, which is where an unvalidated list would surface.
        val refused = refusal(TlsOptions(certificatePath = cert, keyPath = key, cipherSuites = "TLS_NOT_A_REAL_SUITE"))
        assertTrue(
            refused?.message?.contains("cipherSuites") == true,
            "an unknown suite should be refused at start: ${refused?.message}",
        )
    }

    @Test
    fun `kernel TX accepts a cipherSuites list that states the suite it pins`() {
        // The mode derives its kernel keys from exactly this suite, so naming it agrees with the mode - only a list
        // asking for another suite contradicts it, which TlsTest's refusals cover.
        val options = TlsOptions(
            certificatePath = cert,
            keyPath = key,
            kernelTx = true,
            cipherSuites = "TLS_AES_128_GCM_SHA256",
        )
        assertNull(refusal(options), "kernel TX with a list naming the suite it pins should start, not be refused")

        if (!kernelTlsAvailable()) {
            println("skipped the round trip: the tls kernel module is not loaded (sudo modprobe tls)")
            return
        }

        withTlsServer(options) { port, _ ->
            assertServed(port)
        }
    }

    @Test
    fun `a ciphersuite list that is merely mostly right fails at start`() {
        // OpenSSL ignored the names it did not know as long as one was valid, so a typo silently dropped the suite
        // meant to be pinned: starting was never evidence the list was applied.
        val refused = refusal(
            TlsOptions(
                certificatePath = cert,
                keyPath = key,
                cipherSuites = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA25",   // one digit short
            ),
        )

        assertTrue(
            refused?.message?.contains("TLS_CHACHA20_POLY1305_SHA25") == true,
            "a typo among valid suites should be refused, and named: ${refused?.message}",
        )
    }

    @Test
    fun `an empty ciphersuite list is refused rather than disabling TLS 1_3`() {
        // What a config binder makes of a blank field: on a 1.3 floor, a server that starts and completes no handshake.
        val refused = refusal(TlsOptions(certificatePath = cert, keyPath = key, cipherSuites = ""))
        assertTrue(
            refused?.message?.contains("empty") == true,
            "an empty list should be refused at start: ${refused?.message}",
        )
    }

    // Writes a ClientHello on a connection of its own and returns the refusal the handler reported for it.
    private fun refusedAlone(port: Int, hello: ByteArray, reasons: MpscQueue<String>): String =
        TestSocket().use { socket ->
            socket.setReceiveTimeout(5_000)
            socket.connect(port)
            socket.write(hello)
            nextReason(reasons)
        }

    private fun nextReason(reasons: MpscQueue<String>): String {
        var reason: String? = null
        assertTrue(
            waitUntil(10_000) { reasons.poll()?.also { reason = it } != null },
            "the server never reported a refused handshake",
        )
        return reason!!
    }
}

/** How long an unfinished handshake is tolerated, and what the sweep that enforces it holds on to. */
class HandshakeTimeoutTest {
    private val cert = TestCertificate.certificate
    private val key = TestCertificate.key

    @Test
    fun `a handshake that completes is not swept afterwards`() {
        // An established connection idling past the timeout is an ordinary keep-alive, not a stalled handshake.
        withTlsServer(TlsOptions(certificatePath = cert, keyPath = key, handshakeTimeoutMs = 1_000)) { port, _ ->
            BioClient(port).use { client ->
                assertTrue(client.handshake(), "the handshake was refused: ${client.refusal}")
                sleepMs(2_500)   // well past the handshake deadline

                val status = try {
                    client.write(REQUEST)
                    client.readResponse().first
                } catch (e: IOException) {
                    fail("an established connection was swept as if it were still handshaking: ${e.message}")
                }
                assertEquals(200, status)
            }
        }
    }

    @Test
    fun `0 disables the sweep`() {
        withTlsServer(TlsOptions(certificatePath = cert, keyPath = key, handshakeTimeoutMs = 0)) { port, _ ->
            TestSocket().use { silent ->
                silent.setReceiveTimeout(2_000)
                silent.connect(port)

                // The client gives up first, which is the point: the server did not.
                assertFalse(
                    endedByServer(silent, wait = true),
                    "with the sweep off the server should have held the connection",
                )
            }
        }
    }

    @OptIn(NativeRuntimeApi::class)
    @Test
    fun `a finished handshake does not keep its connection reachable behind a stalled one`() {
        // A small pool: most of each burst is disposed at recycle, so only the sweep's queue could keep it reachable.
        val poolMax = 4
        val burst = 32
        val finished = MpscQueue<WeakReference<TcpConnection>>()
        val started = AtomicInt(0)

        val handler: suspend (TlsService, TcpConnection) -> Unit = { service, conn ->
            started.addAndFetch(1)
            var session: TlsSession? = null
            try {
                session = service.accept(conn)
                conn.read()   // until the client hangs up
            } catch (e: IOException) {
                // A client gone mid-handshake.
            } finally {
                session?.close()
                if (session != null) {
                    finished.offer(WeakReference(conn))
                }
            }
        }

        val config = testConfig(
            tcp = TcpOptions(port = 0, writeSlabSize = 16 * 1024, poolMax = poolMax, recvQueueEntries = 64),
            recvSlots = 256,
        )
        val options = TlsOptions(certificatePath = cert, keyPath = key, handshakeTimeoutMs = 120_000)

        withTlsServer(options, handler, config) { port, _ ->
            // Control: with nothing stalled, all but the pool's worth are released.
            val controlAlive = aliveAfterCollect(burst(port, burst, finished), poolMax)
            assertTrue(
                controlAlive <= poolMax,
                "control: $controlAlive of $burst closed connections stayed reachable with no handshake stalled",
            )

            // Never sends a ClientHello, so its entry heads the sweep's queue for the rest of the test.
            TestSocket().use { stalled ->
                val before = started.load()
                stalled.setReceiveTimeout(5_000)
                stalled.connect(port)
                assertTrue(waitUntil(10_000) { started.load() == before + 1 }, "the stalled handshake never started")

                val behindAlive = aliveAfterCollect(burst(port, burst, finished), poolMax)

                assertFalse(endedByServer(stalled, wait = false), "the stalled handshake ended during the test")
                assertTrue(
                    behindAlive <= poolMax,
                    "$behindAlive of $burst closed connections stayed reachable behind a stalled handshake",
                )
            }
        }
    }

    // Holds count TLS connections open at once, closes them, and returns the server's connection for each.
    private fun burst(
        port: Int,
        count: Int,
        finished: MpscQueue<WeakReference<TcpConnection>>,
    ): List<WeakReference<TcpConnection>> {
        val clients = ArrayList<BioClient>()
        try {
            repeat(count) {
                val client = BioClient(port)
                clients.add(client)
                assertTrue(client.handshake(), "handshake ${it + 1} of $count was refused: ${client.refusal}")
            }
        } finally {
            clients.forEach { it.close() }
        }

        val connections = ArrayList<WeakReference<TcpConnection>>()
        waitUntil(10_000) {
            while (true) {
                connections.add(finished.poll() ?: break)
            }
            connections.size == count
        }

        assertEquals(count, connections.size, "only ${connections.size} of $count handlers finished")
        return connections
    }

    // Collects until at most the pool's worth is left, for a few seconds: each is recycled once its peer's close lands.
    @OptIn(NativeRuntimeApi::class)
    private fun aliveAfterCollect(connections: List<WeakReference<TcpConnection>>, poolMax: Int): Int {
        var alive = connections.size
        var attempt = 0
        while (attempt < 30 && alive > poolMax) {
            sleepMs(100)
            GC.collect()
            alive = connections.count { it.get() != null }
            attempt++
        }

        return alive
    }
}

/** Records that arrive torn or corrupt: one cut into bytes still makes one request, a bad one fails the decrypt. */
class TlsFaultTest {
    private val cert = TestCertificate.certificate
    private val key = TestCertificate.key

    // How many responses one request draws when every TLS byte, the handshake's included, is its own TCP send.
    private fun responsesToTornRequest(options: TlsOptions): Int = withTlsServer(options) { port, _ ->
        val tls13 = BoringSsl.TLS1_3_VERSION
        BioClient(port, tls13, tls13, chunk = 1).use { client ->
            assertTrue(client.handshake(), "the handshake was refused: ${client.refusal}")
            client.write(REQUEST)

            // Every response the server means to send has arrived once it has been quiet for a while.
            client.readUntilQuiet(600).split("HTTP/1.1 200").size - 1
        }
    }

    @Test
    fun `a request whose records arrive a byte at a time gets exactly one response`() {
        // BoringSSL yields nothing until a record is whole, and the read BIO holds the pieces across recvs: one
        // request, one response, however finely its records are cut.
        assertEquals(1, responsesToTornRequest(TlsOptions(certificatePath = cert, keyPath = key)))
    }

    @Test
    fun `under kernel TX a request whose records arrive a byte at a time gets exactly one response`() {
        if (!kernelTlsAvailable()) {
            println("skipped: the tls kernel module is not loaded (sudo modprobe tls)")
            return
        }

        assertEquals(1, responsesToTornRequest(TlsOptions(certificatePath = cert, keyPath = key, kernelTx = true)))
    }

    @Test
    fun `a corrupt record faults the decrypt instead of looking incomplete`() {
        // Read as "the record is incomplete, wait for more", a bad MAC would leave the reader waiting on a stream that
        // is never going to recover.
        val reported = AtomicReference<String?>(null)
        val ready = "ready".encodeToByteArray()

        val handler: suspend (TlsService, TcpConnection) -> Unit = { service, conn ->
            var session: TlsSession? = null
            try {
                val tls = service.accept(conn)
                session = tls

                // Sent once the handshake is done, so the bad record reaches this decrypt rather than riding in with
                // the client's Finished.
                tls.write(conn, ready)
                conn.flush()

                val item = RecvItem()
                while (true) {
                    val snapshot = conn.read()
                    while (conn.tryGetItem(snapshot, item)) {
                        if (item.hasBuffer) {
                            try {
                                tls.decrypt(item.address, item.length)
                            } finally {
                                conn.returnBuffer(item)
                            }
                        }
                    }

                    if (tls.closed || snapshot.isClosed) {
                        reported.compareAndSet(null, "clean end of stream")   // the wrong answer here
                        break
                    }
                }
            } catch (e: Exception) {
                reported.compareAndSet(null, e.message ?: e.toString())
            } finally {
                session?.close()
            }
        }

        withTlsServer(TlsOptions(certificatePath = cert, keyPath = key), handler) { port, _ ->
            val tls13 = BoringSsl.TLS1_3_VERSION
            BioClient(port, tls13, tls13).use { client ->
                assertTrue(client.handshake(), "the handshake was refused: ${client.refusal}")
                assertEquals("ready", client.readText(ready.size))

                // Under the session, straight onto the socket: an application-data header claiming 16 bytes, and noise.
                client.socket.write(byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x10) + ByteArray(16) { 0xAB.toByte() })

                // Open until the handler reports, so what it reports is the record and not the disconnect.
                waitUntil(10_000) { reported.load() != null }
            }
        }

        val outcome = reported.load()
        assertNotNull(outcome, "the handler observed nothing within 10 s")
        assertTrue(outcome.contains("TLS decrypt failed"), "expected the decrypt to fault, got: $outcome")
    }
}

// ---- Shared by the classes above -----------------------------------------------------------------------------------

private const val REQUEST = "GET / HTTP/1.1\r\nHost: test\r\n\r\n"

// TLS 1.3 suites by IANA id.
private const val AES_256_GCM: Short = 0x1302
private const val CHACHA20_POLY1305: Short = 0x1303

/** `int (*)(SSL *, const uint8_t **out, uint8_t *out_len, const uint8_t *in, unsigned in_len, void *arg)`. */
private typealias AlpnSelectFunction =
    CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, UInt, COpaquePointer?) -> Int>

/** Why [options] are refused at start, or null when they start: a reactor that never runs is enough to build on. */
private fun refusal(options: TlsOptions): Exception? = try {
    TlsService.start(Reactor(0, ServerConfig()), options)
    null
} catch (e: Exception) {
    e
}

/**
 * One reactor serving TLS with [options] on a port the kernel picks, [serve] running per connection with the reactor's
 * [TlsService]. A start that throws fails the test at once, rather than as a reactor that never started.
 */
private fun <T> withTlsServer(
    options: TlsOptions,
    serve: suspend (TlsService, TcpConnection) -> Unit = { service, conn -> answerRequests(service, conn) },
    config: ServerConfig = testConfig(),
    body: (port: Int, reactor: Reactor) -> T,
): T {
    val services = arrayOfNulls<TlsService>(1)
    val failure = AtomicReference<Throwable?>(null)
    val ready = Latch(1)

    val server = KronServer(
        config,
        onStart = { reactor ->
            try {
                services[0] = TlsService.start(reactor, options)
            } catch (e: Throwable) {
                failure.store(e)
            }
            ready.countDown()
        },
        handler = { conn ->
            serve(services[0]!!, conn)
        },
    ).start()

    try {
        assertTrue(ready.await(10_000), "the reactor never started")
        failure.load()?.let { throw it }
        return body(server.reactors[0].listenPorts[0], server.reactors[0])
    } finally {
        server.close()
    }
}

/**
 * Answers every request - a head ending in "\r\n\r\n" - with 200 and [body]: per request, not per decrypt, so a
 * request torn across recvs or records draws exactly one response. Runs until the peer closes; a refused handshake
 * ends it quietly, as the client reports that.
 */
private suspend fun answerRequests(
    service: TlsService,
    conn: TcpConnection,
    body: (TlsSession) -> String = { "tls-ok" },
) {
    var session: TlsSession? = null
    try {
        val tls = service.accept(conn)
        session = tls

        val content = body(tls)
        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${content.length}\r\n\r\n$content".encodeToByteArray()
        val text = StringBuilder()
        val item = RecvItem()

        // The first request may have ridden in with the client's Finished.
        val early = tls.drainPlaintext()
        appendText(text, tls.plaintext, early)

        var ended = false
        while (true) {
            val requests = takeRequests(text)
            repeat(requests) {
                tls.write(conn, response)
            }
            if (requests > 0) {
                conn.flush()
            }

            if (ended || tls.closed) {
                return
            }

            val snapshot = conn.read()
            while (conn.tryGetItem(snapshot, item)) {
                if (!item.hasBuffer) {
                    continue
                }

                try {
                    val n = tls.decrypt(item.address, item.length)
                    appendText(text, tls.plaintext, n)
                } finally {
                    conn.returnBuffer(item)
                }
            }

            ended = snapshot.isClosed
        }
    } catch (e: IOException) {
        // A refused handshake, or a peer gone mid-way.
    } finally {
        session?.close()
    }
}

private fun appendText(text: StringBuilder, address: Long, length: Int) {
    for (i in 0 until length) {
        text.append((getByte(address + i).toInt() and 0xFF).toChar())
    }
}

// Drops every whole request head from text, returning how many there were.
private fun takeRequests(text: StringBuilder): Int {
    var count = 0
    while (true) {
        val at = text.indexOf("\r\n\r\n")
        if (at < 0) {
            return count
        }

        text.deleteRange(0, at + 4)
        count++
    }
}

// One TLS 1.3 request over a fresh client, answered 200 "tls-ok".
private fun assertServed(port: Int) {
    val tls13 = BoringSsl.TLS1_3_VERSION
    BioClient(port, tls13, tls13).use { client ->
        assertTrue(client.handshake(), "the handshake was refused: ${client.refusal}")
        client.write(REQUEST)
        assertEquals(200 to "tls-ok", client.readResponse())
    }
}

// Whether a client offering minVersion..maxVersion, and only suites of TLS 1.3's when given, completes the handshake.
private fun handshakes(port: Int, minVersion: Short, maxVersion: Short, suites: ShortArray? = null): Boolean =
    BioClient(port, minVersion, maxVersion, suites).use { client ->
        client.handshake()
    }

private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = Clock.monotonicMs() + timeoutMs
    while (!condition()) {
        if (Clock.monotonicMs() >= deadline) {
            return false
        }
        sleepMs(1)
    }

    return true
}

/**
 * Runs [block] on a thread of its own and returns its result, failing the test when it takes longer than [timeoutMs]:
 * a call that never returns ends this test rather than the binary, its thread left behind.
 */
private fun <T : Any> within(timeoutMs: Long, what: String, block: () -> T): T {
    val result = AtomicReference<Any?>(null)
    val done = Latch(1)
    val threads = TestThreads()

    threads.start {
        try {
            result.store(block())
        } catch (e: Throwable) {
            result.store(e)
        } finally {
            done.countDown()
        }
    }

    if (!done.await(timeoutMs)) {
        fail("$what took over $timeoutMs ms")
    }
    threads.joinAll()

    val value = result.load()
    if (value is Throwable) {
        throw value
    }

    @Suppress("UNCHECKED_CAST")
    return value as T
}

// On a socket that sent nothing: whether the server ended it (a FIN or a reset), or, waiting, the receive timeout ran
// out first. Without waiting it only peeks.
private fun endedByServer(socket: TestSocket, wait: Boolean): Boolean {
    val one = ByteArray(1)
    val flags = if (wait) 0 else MSG_PEEK or MSG_DONTWAIT
    while (true) {
        val n = one.usePinned { recv(socket.fd, it.addressOf(0), 1.convert(), flags) }
        if (n >= 0) {
            return true   // a TLS server that heard nothing has nothing to say, so this is its FIN
        }

        when (errno) {
            EINTR -> continue
            EAGAIN -> return false
            else -> return true
        }
    }
}

// What a memory BIO holds, taken out.
private fun drain(bio: CPointer<BIO>): ByteArray {
    val pending = BIO_ctrl_pending(bio).toInt()
    val bytes = ByteArray(pending)
    if (pending > 0) {
        bytes.usePinned {
            BIO_read(bio, it.addressOf(0), pending)
        }
    }

    return bytes
}

// What write puts into a memory BIO, as text.
private fun pem(write: (CPointer<BIO>) -> Int): String {
    val bio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
    try {
        check(write(bio) == 1) { "PEM write failed: ${BoringSsl.lastError()}" }
        return drain(bio).decodeToString()
    } finally {
        BIO_free(bio)
    }
}

// Only these TLS 1.3 suites, by IANA id.
private fun offerSuites(ssl: CPointer<SSL>, suites: ShortArray) = memScoped {
    val ids = allocArray<UShortVar>(suites.size)
    for (i in suites.indices) {
        ids[i] = suites[i].toUShort()
    }

    check(SSL_set1_tls13_ciphers(ssl, ids, null, suites.size.convert()) == 1) { "SSL_set1_tls13_ciphers failed" }
}

// The wire list: each protocol as a length byte and its bytes. An empty list leaves ALPN off.
private fun offerAlpn(ssl: CPointer<SSL>, protocols: List<String>) {
    val names = protocols.map { it.encodeToByteArray() }
    val wire = ByteArray(names.sumOf { 1 + it.size })
    if (wire.isEmpty()) {
        return
    }

    var at = 0
    for (name in names) {
        wire[at] = name.size.toByte()
        name.copyInto(wire, at + 1)
        at += 1 + name.size
    }

    wire.usePinned {
        check(SSL_set_alpn_protos(ssl, it.addressOf(0).reinterpret<UByteVar>(), wire.size.convert()) == 0) {
            "SSL_set_alpn_protos failed"
        }
    }
}

/**
 * What a client offering [minVersion]..[maxVersion], and only [suites] of TLS 1.3's when given, sends first: its
 * ClientHello, made without a server.
 */
private fun clientHello(minVersion: Short, maxVersion: Short, suites: ShortArray? = null): ByteArray {
    val ctx = SSL_CTX_new(TLS_client_method()) ?: error("SSL_CTX_new failed")
    try {
        SSL_CTX_set_min_proto_version(ctx, minVersion.toUShort())
        SSL_CTX_set_max_proto_version(ctx, maxVersion.toUShort())

        val ssl = SSL_new(ctx) ?: error("SSL_new failed")
        try {
            val wbio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
            SSL_set_bio(ssl, BIO_new(BIO_s_mem()), wbio)
            SSL_set_connect_state(ssl)
            if (suites != null) {
                offerSuites(ssl, suites)
            }

            SSL_do_handshake(ssl)   // writes the hello, then wants the server's answer
            BoringSsl.clearError()

            val hello = drain(wbio)
            check(hello.size > 5 && hello[0] == 0x16.toByte()) { "the client never wrote a handshake record" }
            return hello
        } finally {
            SSL_free(ssl)
        }
    } finally {
        SSL_CTX_free(ctx)
    }
}

/** A read that waited out the receive timeout with nothing from the server. */
private class ServerSilent : Exception("nothing arrived from the server within the receive timeout")

/**
 * A BoringSSL client whose records reach a [TestSocket] through memory BIOs, so a test decides what goes on the wire:
 * the versions offered, only [suites] of TLS 1.3's when given, the ALPN list (null sends no extension), and how finely
 * the bytes are cut - [chunk] per TCP send, a millisecond apart, so one record reaches the server across many recvs.
 * Any certificate is accepted: which one the server holds is not what these tests ask. Construct, then [handshake].
 */
private class BioClient(
    port: Int,
    minVersion: Short = BoringSsl.TLS1_2_VERSION,
    maxVersion: Short = BoringSsl.TLS1_3_VERSION,
    suites: ShortArray? = null,
    alpn: List<String>? = null,
    private val chunk: Int = 0,
) : AutoCloseable {
    val socket = TestSocket()
    private val ctx: CPointer<SSL_CTX>
    private val ssl: CPointer<SSL>
    private val rbio: CPointer<BIO>
    private val wbio: CPointer<BIO>
    private val scratch = ByteArray(16 * 1024)

    /** Why [handshake] did not complete: BoringSSL's reason, or the server hanging up. */
    var refusal: String? = null
        private set

    init {
        try {
            // Every send its own segment: a request after the Finished does not wait out the server's delayed ACK,
            // and a chunk is a segment.
            socket.setReceiveTimeout(TIMEOUT_MS)
            Sockets.setInt(socket.fd, Sockets.IPPROTO_TCP, Sockets.TCP_NODELAY, 1)
            socket.connect(port)
        } catch (e: Throwable) {
            socket.close()
            throw e
        }

        ctx = SSL_CTX_new(TLS_client_method()) ?: error("SSL_CTX_new failed")
        SSL_CTX_set_min_proto_version(ctx, minVersion.toUShort())
        SSL_CTX_set_max_proto_version(ctx, maxVersion.toUShort())

        ssl = SSL_new(ctx) ?: error("SSL_new failed")
        rbio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
        wbio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
        SSL_set_bio(ssl, rbio, wbio)   // the SSL owns both from here
        SSL_set_connect_state(ssl)

        if (suites != null) {
            offerSuites(ssl, suites)
        }
        if (alpn != null) {
            offerAlpn(ssl, alpn)
        }
    }

    /** BoringSSL's wire value for the version settled on: 0x0303 is TLS 1.2, 0x0304 TLS 1.3. */
    val version: Int get() = SSL_version(ssl)

    /** The IANA id of the ciphersuite settled on. */
    val cipherId: Int get() = SSL_get_current_cipher(ssl)?.let { SSL_CIPHER_get_protocol_id(it).toInt() } ?: 0

    /** What ALPN settled on, or null. */
    val alpn: String? get() = BoringSsl.alpnSelected(ssl.rawValue.toLong())

    /**
     * Runs the handshake: true once it completes, false when the server refused it - with an alert, or by hanging up.
     * A server that does neither within the receive timeout fails the test: a hang is not a refusal, and an assertion
     * that a client was turned away must not be satisfied by a server that answers nobody.
     */
    fun handshake(): Boolean {
        while (true) {
            BoringSsl.clearError()
            val rc = SSL_do_handshake(ssl)
            val error = if (rc == 1) 0 else SSL_get_error(ssl, rc)
            if (error != 0 && error != BoringSsl.SSL_ERROR_WANT_READ) {
                refusal = BoringSsl.lastError()
            }

            if (!sendPending()) {
                refusal = refusal ?: "the server closed the connection"
                return false
            }
            if (rc == 1) {
                return true
            }
            if (refusal != null) {
                return false
            }

            val received = try {
                receive()
            } catch (e: ServerSilent) {
                fail("no answer to the handshake within $TIMEOUT_MS ms: a hang is not a refusal")
            }

            if (received == 0) {
                refusal = "the server closed the connection"
                return false
            }
        }
    }

    fun write(text: String) {
        val bytes = text.encodeToByteArray()
        BoringSsl.clearError()
        val n = bytes.usePinned { SSL_write(ssl, it.addressOf(0), bytes.size) }
        if (n != bytes.size) {
            throw IOException("TLS write failed: ${BoringSsl.lastError()}")
        }

        if (!sendPending()) {
            throw IOException("the server closed the connection")
        }
    }

    /** Up to [length] bytes of plaintext, or -1 once the server has ended the session. Throws [ServerSilent]. */
    fun read(into: ByteArray, offset: Int = 0, length: Int = into.size - offset): Int {
        while (true) {
            BoringSsl.clearError()
            val n = into.usePinned { SSL_read(ssl, it.addressOf(offset), length) }
            if (n > 0) {
                return n
            }

            when (SSL_get_error(ssl, n)) {
                BoringSsl.SSL_ERROR_WANT_READ -> if (receive() == 0) {
                    return -1
                }
                BoringSsl.SSL_ERROR_ZERO_RETURN -> return -1
                else -> throw IOException("TLS read failed: ${BoringSsl.lastError()}")
            }
        }
    }

    /** Exactly [count] bytes of plaintext, as text. */
    fun readText(count: Int): String {
        val bytes = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = read(bytes, got, count - got)
            if (n < 0) {
                throw IOException("the session ended after $got of $count bytes")
            }

            got += n
        }

        return bytes.decodeToString()
    }

    /** One response: its status, and its Content-Length body. */
    fun readResponse(): Pair<Int, String> {
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val headEnd = text.indexOf("\r\n\r\n")
            if (headEnd >= 0) {
                val head = text.substring(0, headEnd)
                val length = head.lines()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toInt() ?: 0

                if (text.length >= headEnd + 4 + length) {
                    return head.substring(9, 12).toInt() to text.substring(headEnd + 4, headEnd + 4 + length)
                }
            }

            val n = read(buffer)
            if (n < 0) {
                throw IOException("the session ended before a whole response arrived: '$text'")
            }

            for (i in 0 until n) {
                text.append((buffer[i].toInt() and 0xFF).toChar())
            }
        }
    }

    /** Everything the server sends until it is quiet for [quietMs], waiting the full timeout for the first byte. */
    fun readUntilQuiet(quietMs: Int): String {
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val n = read(buffer)
                if (n < 0) {
                    break
                }

                for (i in 0 until n) {
                    text.append((buffer[i].toInt() and 0xFF).toChar())
                }
                socket.setReceiveTimeout(quietMs)
            }
        } catch (e: ServerSilent) {
            // Quiet: everything the server meant to send is here.
        } finally {
            socket.setReceiveTimeout(TIMEOUT_MS)
        }

        return text.toString()
    }

    override fun close() {
        if (SSL_in_init(ssl) == 0) {
            SSL_shutdown(ssl)
            sendPending()
        }

        BoringSsl.clearError()
        SSL_free(ssl)
        SSL_CTX_free(ctx)
        socket.close()
    }

    // What BoringSSL left in the write BIO, onto the socket: false when the server is no longer taking it.
    private fun sendPending(): Boolean {
        val bytes = drain(wbio)
        try {
            if (chunk <= 0) {
                socket.write(bytes)
                return true
            }

            var at = 0
            while (at < bytes.size) {
                val end = minOf(at + chunk, bytes.size)
                socket.write(bytes.copyOfRange(at, end))
                at = end
                if (at < bytes.size) {
                    usleep(1000u)   // so the server's recv lands mid-record rather than after all of it
                }
            }

            return true
        } catch (e: IOException) {
            return false
        }
    }

    // The next bytes the socket has, into the read BIO: their count, or 0 once the server has closed or reset the
    // connection. Throws ServerSilent when the receive timeout runs out first.
    private fun receive(): Int {
        while (true) {
            val n = scratch.usePinned { recv(socket.fd, it.addressOf(0), scratch.size.convert(), 0) }
            if (n > 0) {
                scratch.usePinned {
                    BIO_write(rbio, it.addressOf(0), n.toInt())
                }
                return n.toInt()
            }
            if (n == 0L) {
                return 0
            }

            when (errno) {
                EINTR -> continue
                EAGAIN -> throw ServerSilent()
                else -> return 0
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}

/** RSA material, made once per run: the fixture every other test uses is EC. */
private object RsaFixture {
    private val key: CPointer<EVP_PKEY> = EVP_RSA_gen(2048u) ?: error("RSA key generation failed")

    val certificatePem: String = selfSigned(key)
    val pkcs8KeyPem: String = pem { PEM_write_bio_PrivateKey(it, key, null, null, 0, null, null) }
    val pkcs1KeyPem: String = pem { PEM_write_bio_RSAPrivateKey(it, EVP_PKEY_get0_RSA(key), null, null, 0, null, null) }

    /** A key the certificate was not issued for. */
    val otherKeyPem: String = (EVP_RSA_gen(2048u) ?: error("RSA key generation failed")).let { other ->
        try {
            pem { PEM_write_bio_PrivateKey(it, other, null, null, 0, null, null) }
        } finally {
            EVP_PKEY_free(other)
        }
    }

    // A localhost certificate for key, signed by itself.
    private fun selfSigned(key: CPointer<EVP_PKEY>): String {
        val certificate = X509_new() ?: error("X509_new failed")
        try {
            X509_set_version(certificate, X509_VERSION_3.toLong())
            ASN1_INTEGER_set(X509_get_serialNumber(certificate), 1)
            X509_gmtime_adj(X509_getm_notBefore(certificate), 0)
            X509_gmtime_adj(X509_getm_notAfter(certificate), 24L * 60 * 60)
            X509_set_pubkey(certificate, key)

            val name = X509_get_subject_name(certificate)
            memScoped {
                X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, "localhost".cstr.ptr.reinterpret(), -1, -1, 0)
            }
            X509_set_issuer_name(certificate, name)
            check(X509_sign(certificate, key, EVP_sha256()) > 0) { "X509_sign failed" }

            return pem { PEM_write_bio_X509(it, certificate) }
        } finally {
            X509_free(certificate)
        }
    }
}

// The fixture's own key, encrypted under a passphrase nobody can supply.
private fun encryptedKeyPem(): String {
    val text = TestCertificate.keyPem.encodeToByteArray()
    val key = text.usePinned { pinned ->
        val bio = BIO_new_mem_buf(pinned.addressOf(0), text.size.toLong())
        try {
            PEM_read_bio_PrivateKey(bio, null, null, null)
        } finally {
            BIO_free(bio)
        }
    } ?: error("the fixture's key does not parse")

    try {
        val passphrase = "correct horse battery staple".encodeToByteArray()
        return passphrase.usePinned { pinned ->
            pem {
                val pass = pinned.addressOf(0).reinterpret<UByteVar>()
                PEM_write_bio_PrivateKey(it, key, EVP_aes_256_cbc(), pass, passphrase.size, null, null)
            }
        }
    } finally {
        EVP_PKEY_free(key)
    }
}
