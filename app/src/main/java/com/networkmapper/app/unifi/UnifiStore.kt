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
    /** Which tab the UniFi screen shows (console or cloud); both can be set up at once. */
    val cloud: Boolean = false,
    /** UniFi cloud (api.ui.com) Site Manager API key, decrypted; empty if not set. */
    val cloudKey: String = "",
) {
    /** A direct console login is saved (used first: it has the most detail). */
    val consoleReady: Boolean get() = url.isNotBlank() && secret.isNotBlank() && siteRef.isNotBlank()

    /** A UniFi cloud key is saved (used when the console can't be reached). */
    val cloudReady: Boolean get() = cloudKey.isNotBlank()

    /** Enough is saved to fetch devices and live status without opening the UniFi screen. */
    val ready: Boolean get() = consoleReady || cloudReady
}

/** Controller settings; the API key / password is encrypted with an Android Keystore key. */
class UnifiStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("unifi", Context.MODE_PRIVATE)
    private val cacheFile = File(appContext.filesDir, "unifi_devices.json")

    fun load(): UnifiSettings = UnifiSettings(
        cloudKey = prefs.getString("cloudKey", null)?.let { decrypt(it) } ?: "",
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
    ).let { s ->
        // The previous version kept the cloud key in the console's secret slot.
        // It also saved a placeholder site "cloud", which isn't a console site.
        val migrated = prefs.getBoolean("keysSplit", false)
        val fixed = if (s.siteRef == "cloud") s.copy(siteRef = "", siteId = "", siteName = "") else s
        if (!migrated && fixed.cloud && fixed.cloudKey.isBlank() && fixed.secret.isNotBlank()) {
            fixed.copy(cloudKey = fixed.secret, secret = "")
        } else {
            fixed
        }
    }

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
            .putString("cloudKey", if (s.remember && s.cloudKey.isNotEmpty()) encrypt(s.cloudKey) else null)
            .putBoolean("keysSplit", true)
            .apply()
    }

    fun forget() {
        prefs.edit().clear().apply()
    }

    fun loadDevices(): List<UnifiDevice> =
        if (cacheFile.exists()) UnifiParser.fromJson(cacheFile.readText()) else emptyList()

    /**
     * Merges newly imported devices into the cache (by MAC). Details a source doesn't provide (the cloud has
     * no radios, uplinks or ids) are kept from what the console reported earlier.
     */
    fun saveDevices(devices: List<UnifiDevice>) {
        val old = loadDevices().associateBy { it.mac }
        val fresh = devices.associateBy { it.mac }.mapValues { (mac, d) ->
            val o = old[mac] ?: return@mapValues d
            d.copy(
                radios = d.radios.ifEmpty { o.radios },
                id = d.id.ifBlank { o.id },
                portCount = d.portCount ?: o.portCount,
                serial = d.serial.ifBlank { o.serial },
                uplinkMac = d.uplinkMac.ifBlank { o.uplinkMac },
                uplinkPort = d.uplinkPort ?: o.uplinkPort,
            )
        }
        val merged = (old + fresh).values.toList()
        cacheFile.writeText(UnifiParser.toJson(merged))
    }

    private val box = com.networkmapper.app.util.SecretBox(ALIAS)

    private fun encrypt(plain: String): String? = box.encrypt(plain)

    private fun decrypt(stored: String): String? = box.decrypt(stored)

    companion object {
        private const val ALIAS = "unifi_secret"
    }
}
