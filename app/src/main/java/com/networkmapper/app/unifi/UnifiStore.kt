package com.networkmapper.app.unifi

import android.content.Context
import java.io.File

data class UnifiSettings(
    val url: String = "",
    val useApiKey: Boolean = true,
    val username: String = "",
    /** API key or password, decrypted; empty if not remembered. */
    val secret: String = "",
    val remember: Boolean = true,
    /** Pinned SHA-256 fingerprint of the controller certificate the user trusted. */
    val pinnedFingerprint: String? = null,
    val pinnedFor: String? = null,
    /** Last site loaded on the UniFi screen; live data uses it. */
    val siteRef: String = "",
    val siteId: String = "",
    val siteName: String = "",
    /** Use the UniFi cloud (api.ui.com, Site Manager API key) instead of connecting to the console directly. */
    val cloud: Boolean = false,
) {
    /** Enough is saved to fetch devices and live status without opening the UniFi screen. */
    val ready: Boolean get() = secret.isNotBlank() && (cloud || (url.isNotBlank() && siteRef.isNotBlank()))
}

/** Controller settings; the API key / password is encrypted with an Android Keystore key. */
class UnifiStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("unifi", Context.MODE_PRIVATE)
    private val cacheFile = File(appContext.filesDir, "unifi_devices.json")

    fun load(): UnifiSettings = UnifiSettings(
        url = prefs.getString("url", "") ?: "",
        useApiKey = prefs.getBoolean("useApiKey", true),
        username = prefs.getString("username", "") ?: "",
        secret = prefs.getString("secret", null)?.let { decrypt(it) } ?: "",
        remember = prefs.getBoolean("remember", true),
        pinnedFingerprint = prefs.getString("pin", null),
        pinnedFor = prefs.getString("pinFor", null),
        siteRef = prefs.getString("siteRef", "") ?: "",
        siteId = prefs.getString("siteId", "") ?: "",
        siteName = prefs.getString("siteName", "") ?: "",
        cloud = prefs.getBoolean("cloud", false),
    )

    fun save(s: UnifiSettings) {
        prefs.edit()
            .putString("url", s.url)
            .putBoolean("useApiKey", s.useApiKey)
            .putString("username", s.username)
            .putBoolean("remember", s.remember)
            .putString("secret", if (s.remember && s.secret.isNotEmpty()) encrypt(s.secret) else null)
            .putString("pin", s.pinnedFingerprint)
            .putString("pinFor", s.pinnedFor)
            .putString("siteRef", s.siteRef)
            .putString("siteId", s.siteId)
            .putString("siteName", s.siteName)
            .putBoolean("cloud", s.cloud)
            .apply()
    }

    fun forget() {
        prefs.edit().clear().apply()
    }

    fun loadDevices(): List<UnifiDevice> =
        if (cacheFile.exists()) UnifiParser.fromJson(cacheFile.readText()) else emptyList()

    /** Merges newly imported devices into the cache (by MAC). */
    fun saveDevices(devices: List<UnifiDevice>) {
        val merged = (loadDevices().associateBy { it.mac } + devices.associateBy { it.mac }).values.toList()
        cacheFile.writeText(UnifiParser.toJson(merged))
    }

    private val box = com.networkmapper.app.util.SecretBox(ALIAS)

    private fun encrypt(plain: String): String? = box.encrypt(plain)

    private fun decrypt(stored: String): String? = box.decrypt(stored)

    companion object {
        private const val ALIAS = "unifi_secret"
    }
}
