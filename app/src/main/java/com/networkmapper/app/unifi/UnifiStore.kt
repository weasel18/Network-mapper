package com.networkmapper.app.unifi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
)

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

    // ---- Keystore encryption ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    } catch (e: Exception) {
        null // key lost (e.g. restored backup) – the user just re-enters it
    }

    companion object {
        private const val ALIAS = "unifi_secret"
    }
}
