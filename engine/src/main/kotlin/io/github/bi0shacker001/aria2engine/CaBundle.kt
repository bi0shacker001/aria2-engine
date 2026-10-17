package io.github.bi0shacker001.aria2engine

import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The certificates the engine trusts: the platform's own CA store, written as one PEM file for libaria2's OpenSSL
 * (EngineConfig.CA_BUNDLE). Android stores them under OpenSSL's old subject-hash names, which OpenSSL 3 does not look
 * up, so the engine cannot read the folder itself. Only system CAs: a certificate a user installed is not trusted for
 * downloads, as Android's own default for apps.
 */
internal object CaBundle {
    /** Writes the bundle to [file]; returns how many certificates it holds. */
    fun write(file: File): Int {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val pem = StringBuilder()
        var count = 0
        for (alias in store.aliases()) {
            if (!alias.startsWith("system:")) continue
            val cert = store.getCertificate(alias) as? X509Certificate ?: continue
            pem.append(encode(cert.encoded))
            count++
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(pem.toString())
        check(temp.renameTo(file)) { "the CA bundle could not be written" }
        return count
    }

    fun encode(der: ByteArray): String =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE-----\n"
}
