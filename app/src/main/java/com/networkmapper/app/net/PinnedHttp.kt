package com.networkmapper.app.net

import com.networkmapper.app.unifi.UntrustedCertificateException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Small HTTPS client: system-trusted certificates work normally; a self-signed server is accepted
 * only when its leaf certificate's SHA-256 fingerprint equals [pinnedFingerprint] (confirmed once by the
 * user). An unknown certificate raises [UntrustedCertificateException] with its fingerprint.
 */
class PinnedHttp(baseUrl: String, private val pinnedFingerprint: String?) {

    data class Response(val code: Int, val body: ByteArray) {
        val text: String get() = String(body)
        val ok: Boolean get() = code in 200..299
    }

    val base: String = normalizeBase(baseUrl)
    private val trustManager = PinningTrustManager(pinnedFingerprint)
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    private val hostnameVerifier = HostnameVerifier { host, session ->
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate
        (pinnedFingerprint != null && cert != null && fingerprint(cert) == pinnedFingerprint) ||
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }

    fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
        readTimeoutMs: Int = 60_000,
    ): Response {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = sslContext.socketFactory
                conn.hostnameVerifier = hostnameVerifier
            }
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", contentType)
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                conn.responseCode
            } catch (e: SSLException) {
                val chain = trustManager.lastChain
                if (chain != null && chain.isNotEmpty() && trustManager.rejected) {
                    throw UntrustedCertificateException(fingerprint(chain[0]), chain[0].subjectX500Principal.name)
                }
                throw e
            }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return Response(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    private class PinningTrustManager(private val pin: String?) : X509TrustManager {
        private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()

        @Volatile var lastChain: Array<X509Certificate>? = null
        @Volatile var rejected = false

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            lastChain = chain
            rejected = false
            if (pin != null && chain.isNotEmpty() && fingerprint(chain[0]) == pin) return
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                rejected = true
                throw e
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    companion object {
        fun normalizeBase(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.contains("://")) u = "https://$u"
            return u
        }

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { String.format("%02X", it) }
    }
}

class HttpStatusException(val code: Int, message: String) : IOException(message)
