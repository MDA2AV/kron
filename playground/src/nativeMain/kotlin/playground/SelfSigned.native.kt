package playground

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kronos.boringssl.ASN1_INTEGER_set
import kronos.boringssl.BIO
import kronos.boringssl.BIO_ctrl_pending
import kronos.boringssl.BIO_free
import kronos.boringssl.BIO_new
import kronos.boringssl.BIO_read
import kronos.boringssl.BIO_s_mem
import kronos.boringssl.EC_KEY_generate_key
import kronos.boringssl.EC_KEY_new_by_curve_name
import kronos.boringssl.EVP_PKEY_assign_EC_KEY
import kronos.boringssl.EVP_PKEY_free
import kronos.boringssl.EVP_PKEY_new
import kronos.boringssl.EVP_sha256
import kronos.boringssl.MBSTRING_ASC
import kronos.boringssl.NID_X9_62_prime256v1
import kronos.boringssl.PEM_write_bio_PrivateKey
import kronos.boringssl.PEM_write_bio_X509
import kronos.boringssl.X509_NAME_add_entry_by_txt
import kronos.boringssl.X509_VERSION_3
import kronos.boringssl.X509_free
import kronos.boringssl.X509_get_serialNumber
import kronos.boringssl.X509_get_subject_name
import kronos.boringssl.X509_getm_notAfter
import kronos.boringssl.X509_getm_notBefore
import kronos.boringssl.X509_gmtime_adj
import kronos.boringssl.X509_new
import kronos.boringssl.X509_set_issuer_name
import kronos.boringssl.X509_set_pubkey
import kronos.boringssl.X509_set_version
import kronos.boringssl.X509_sign

/**
 * A throwaway certificate for the TLS sample when none is configured: an EC P-256 key and a self-signed certificate
 * for localhost, valid for a year, made with the BoringSSL kronos links. Clients have to skip verification
 * (`curl -k`).
 */
actual object SelfSigned {
    /** The certificate and its private key, PEM-encoded. */
    actual fun generate(): Pair<String, String> {
        val ec = EC_KEY_new_by_curve_name(NID_X9_62_prime256v1)
        check(ec != null && EC_KEY_generate_key(ec) == 1) { "EC P-256 key generation failed" }

        val key = EVP_PKEY_new()
        check(key != null && EVP_PKEY_assign_EC_KEY(key, ec) == 1) { "EVP_PKEY_assign_EC_KEY failed" }

        val certificate = X509_new()
        try {
            check(certificate != null) { "X509_new failed" }

            X509_set_version(certificate, X509_VERSION_3.toLong())
            ASN1_INTEGER_set(X509_get_serialNumber(certificate), 1)
            X509_gmtime_adj(X509_getm_notBefore(certificate), 0)
            X509_gmtime_adj(X509_getm_notAfter(certificate), 365L * 24 * 60 * 60)
            X509_set_pubkey(certificate, key)

            val name = X509_get_subject_name(certificate)
            memScoped {
                X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, "localhost".cstr.ptr.reinterpret(), -1, -1, 0)
            }
            X509_set_issuer_name(certificate, name)
            check(X509_sign(certificate, key, EVP_sha256()) > 0) { "X509_sign failed" }

            val certificatePem = pem { PEM_write_bio_X509(it, certificate) }
            val keyPem = pem { PEM_write_bio_PrivateKey(it, key, null, null, 0, null, null) }
            return certificatePem to keyPem
        } finally {
            X509_free(certificate)
            EVP_PKEY_free(key)
        }
    }

    private fun pem(write: (CPointer<BIO>) -> Int): String {
        val bio = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
        try {
            check(write(bio) == 1) { "PEM write failed" }

            val length = BIO_ctrl_pending(bio).toInt()
            val bytes = ByteArray(length)
            bytes.usePinned {
                BIO_read(bio, it.addressOf(0), length)
            }

            return bytes.decodeToString()
        } finally {
            BIO_free(bio)
        }
    }
}
