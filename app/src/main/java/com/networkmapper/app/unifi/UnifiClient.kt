package com.networkmapper.app.unifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

/** The controller presented a certificate the phone doesn't trust yet (normal for a local console). */
class UntrustedCertificateException(val fingerprint: String, val subject: String) :
    IOException("Untrusted certificate $subject")

class UnifiException(message: String) : IOException(message)

sealed interface UnifiAuth {
    data class ApiKey(val key: String) : UnifiAuth
    data class Login(val username: String, val password: String) : UnifiAuth
}

/**
 * Minimal UniFi Network client (no third-party HTTP library).
 *
 * - API key: UniFi OS consoles with Network 9.0+ — integration API
 *   (/proxy/network/integration/v1/…), plus the classic stat/device endpoint for radio BSSIDs when the key allows it.
 * - Username/password: UniFi OS consoles (/api/auth/login, then /proxy/network/api/…) or classic
 *   self-hosted controllers (/api/login, then /api/…).
 *
 * TLS: system-trusted certificates work as normal. A self-signed controller certificate is only
 * accepted if its SHA-256 fingerprint equals [pinnedFingerprint], which the user confirms once.
 */
class UnifiClient(baseUrl: String, private val pinnedFingerprint: String?) {

    val base: String = normalizeBase(baseUrl)
    private val cookies = LinkedHashMap<String, String>()
    private var csrf: String? = null
    private var apiPrefix = "/proxy/network" // "" for classic controllers
    private val trustManager = PinningTrustManager(pinnedFingerprint)
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    private val hostnameVerifier = HostnameVerifier { host, session ->
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate
        (pinnedFingerprint != null && cert != null && fingerprint(cert) == pinnedFingerprint) ||
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }

    // ------------------------------------------------------------------ public API

    /** Signs in (for username/password) and lists the controller's sites. */
    suspend fun sites(auth: UnifiAuth): List<UnifiSite> = withContext(Dispatchers.IO) {
        when (auth) {
            is UnifiAuth.ApiKey -> {
                val (code, body) = request("GET", "/proxy/network/integration/v1/sites", key = auth.key)
                check(code, body, "list sites")
                UnifiParser.parseIntegrationSites(body)
            }
            is UnifiAuth.Login -> {
                login(auth)
                val (code, body) = request("GET", "$apiPrefix/api/self/sites")
                check(code, body, "list sites")
                UnifiParser.parseClassicSites(body)
            }
        }
    }

    /** Devices of one site, with radio BSSIDs whenever the controller provides them. */
    suspend fun devices(auth: UnifiAuth, site: UnifiSite): List<UnifiDevice> = withContext(Dispatchers.IO) {
        when (auth) {
            is UnifiAuth.ApiKey -> {
                val siteId = site.id ?: throw UnifiException("Site id missing")
                val all = ArrayList<UnifiDevice>()
                var offset = 0
                while (true) {
                    val (code, body) = request(
                        "GET",
                        "/proxy/network/integration/v1/sites/${enc(siteId)}/devices?offset=$offset&limit=200",
                        key = auth.key,
                    )
                    check(code, body, "list devices")
                    val (page, total) = UnifiParser.parseIntegrationDevices(body)
                    all += page
                    offset += 200
                    if (page.isEmpty() || offset >= total) break
                }
                // Radio BSSIDs live in the classic stats; newer consoles accept the API key there too.
                val classic = try {
                    val (code, body) = request("GET", "/proxy/network/api/s/${enc(site.reference)}/stat/device", key = auth.key)
                    if (code in 200..299) UnifiParser.parseClassicDevices(body) else emptyList()
                } catch (e: IOException) {
                    emptyList()
                }
                UnifiParser.mergeRadios(all, classic)
            }
            is UnifiAuth.Login -> {
                if (cookies.isEmpty()) login(auth)
                val (code, body) = request("GET", "$apiPrefix/api/s/${enc(site.reference)}/stat/device")
                check(code, body, "list devices")
                UnifiParser.parseClassicDevices(body)
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun login(auth: UnifiAuth.Login) {
        val payload = JSONObject().put("username", auth.username).put("password", auth.password).put("rememberMe", false).toString()
        // UniFi OS console first, then a classic controller.
        val (osCode, osBody) = request("POST", "/api/auth/login", payload)
        if (osCode in 200..299) {
            apiPrefix = "/proxy/network"
            return
        }
        if (osCode == 401 || osCode == 403) throw UnifiException(loginError(osBody))
        val (code, body) = request("POST", "/api/login", payload)
        if (code in 200..299) {
            apiPrefix = ""
            return
        }
        throw UnifiException(if (code == 400 || code == 401) loginError(body) else "Sign-in failed (HTTP $code)")
    }

    private fun loginError(body: String): String {
        val msg = try {
            JSONObject(body).let { it.optJSONObject("meta")?.optString("msg") ?: it.optString("message") }
        } catch (e: Exception) {
            null
        }
        return "Sign-in refused" + (msg?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ". Use a local controller account; accounts with 2FA can't sign in from apps."
    }

    private fun check(code: Int, body: String, what: String) {
        if (code in 200..299) return
        val hint = when (code) {
            401, 403 -> "not authorised – check the API key / account permissions"
            404 -> "not found – check the address, or try the other sign-in method"
            else -> "HTTP $code"
        }
        throw UnifiException("Couldn't $what: $hint" + body.take(160).takeIf { code !in listOf(401, 403, 404) && it.isNotBlank() }?.let { "\n$it" }.orEmpty())
    }

    private fun request(method: String, path: String, body: String? = null, key: String? = null): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = sslContext.socketFactory
                conn.hostnameVerifier = hostnameVerifier
            }
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            key?.let { conn.setRequestProperty("X-API-KEY", it) }
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            csrf?.let { conn.setRequestProperty("X-CSRF-Token", it) }
            // The TLS handshake happens on the first write (POST) or read; both are covered here.
            val code = try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                conn.responseCode
            } catch (e: SSLException) {
                val chain = trustManager.lastChain
                if (chain != null && chain.isNotEmpty() && trustManager.rejected) {
                    throw UntrustedCertificateException(fingerprint(chain[0]), chain[0].subjectX500Principal.name)
                }
                throw e
            }
            conn.headerFields.forEach { (name, values) ->
                when {
                    name.equals("Set-Cookie", ignoreCase = true) -> values.forEach { v ->
                        val pair = v.substringBefore(';')
                        val k = pair.substringBefore('=').trim()
                        if (k.isNotEmpty()) cookies[k] = pair.substringAfter('=', "").trim()
                    }
                    name.equals("X-CSRF-Token", ignoreCase = true) || name.equals("X-Updated-CSRF-Token", ignoreCase = true) ->
                        values.firstOrNull()?.let { csrf = it }
                }
            }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Accepts system-trusted chains, or exactly the pinned leaf certificate. Remembers what it saw. */
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

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
