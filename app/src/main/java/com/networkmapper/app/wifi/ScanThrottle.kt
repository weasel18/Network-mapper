package com.networkmapper.app.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Android's Wi-Fi scan throttling (4 scans / 2 min per app) is the global setting behind
 * Developer options → "Wi-Fi scan throttling". With WRITE_SECURE_SETTINGS (granted once via Shizuku or adb)
 * the app turns it off while a survey runs and back on afterwards.
 */
object ScanThrottle {
    private const val KEY = "wifi_scan_throttle_enabled"
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("scan_throttle", Context.MODE_PRIVATE)

    fun canControl(c: Context): Boolean = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The user's choice to switch throttling off during surveys (on by default once the permission is there). */
    fun autoEnabled(c: Context): Boolean = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

    fun isThrottled(c: Context): Boolean = Settings.Global.getInt(c.contentResolver, KEY, 1) != 0

    private fun write(c: Context, throttled: Boolean): Boolean = try {
        Settings.Global.putInt(c.contentResolver, KEY, if (throttled) 1 else 0)
    } catch (e: SecurityException) {
        false
    }

    /** At survey start: switches throttling off if allowed and wanted. Returns true if it did. */
    fun onSurveyStart(c: Context): Boolean {
        if (!canControl(c) || !autoEnabled(c) || !isThrottled(c)) return false
        if (!write(c, false)) return false
        prefs(c).edit().putBoolean("restore", true).commit() // remembered in case the app is killed mid-survey
        return true
    }

    /** At survey end (or app start after a crash): turns throttling back on if a survey switched it off. */
    fun onSurveyStop(c: Context) {
        if (!prefs(c).getBoolean("restore", false)) return
        write(c, true)
        prefs(c).edit().putBoolean("restore", false).apply()
    }
}

/** One-time grant of WRITE_SECURE_SETTINGS through Shizuku's shell access (Shizuku is optional). */
object ShizukuGrant {
    private const val REQUEST_CODE = 4711

    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    private fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (cont.isActive) cont.resume(false)
        }
    }

    /** Runs `pm grant <app> WRITE_SECURE_SETTINGS` as the shell user. Returns null on success, else a message. */
    suspend fun grant(c: Context): String? {
        if (!running()) return "Shizuku isn't running. Open the Shizuku app, start it, then try again."
        if (!hasPermission() && !requestPermission()) return "Shizuku access was denied for Network Mapper."
        val error = withContext(Dispatchers.IO) {
            try {
                // Shizuku's process API is private in its library but still served by Shizuku itself.
                val newProcess = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
                ).apply { isAccessible = true }
                val process = newProcess.invoke(null, arrayOf("pm", "grant", c.packageName, ScanThrottle.PERMISSION), null, null) as Process
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val code = process.waitFor()
                if (code == 0) null else stderr.trim().ifBlank { "pm grant exited with code $code" }
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                cause.message ?: cause.javaClass.simpleName
            }
        }
        return when {
            error != null -> "Couldn't grant the permission: $error"
            !ScanThrottle.canControl(c) -> "Shizuku ran the command but the permission still isn't granted."
            else -> null
        }
    }
}
