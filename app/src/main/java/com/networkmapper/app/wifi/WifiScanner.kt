package com.networkmapper.app.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A single network seen in a scan (or the currently-connected network). */
data class ScanEntry(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val security: String,
    val connected: Boolean = false,
    /** Age of the measurement in ms at the time it was read. */
    val ageMs: Long = 0,
    /** ScanResult.WIFI_STANDARD_* (0 = unknown, before Android 11). */
    val standard: Int = 0,
    /** Channel width in MHz (0 = unknown). */
    val widthMhz: Int = 0,
    /** From the AP's BSS Load element, when it sends one. */
    val stations: Int? = null,
    val utilPct: Int? = null,
)

/** Reads the BSS Load element (id 11): station count (2 bytes, little-endian) and channel utilisation (0–255). */
internal fun parseBssLoad(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 3) return null
    val stations = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    val util = ((bytes[2].toInt() and 0xFF) * 100 + 127) / 255
    return stations to util
}

/** ScanResult.CHANNEL_WIDTH_* to MHz. */
internal fun widthMhz(channelWidth: Int): Int = when (channelWidth) {
    0 -> 20
    1 -> 40
    2 -> 80
    3, 4 -> 160 // 160 MHz and 80+80 MHz
    5 -> 320
    else -> 0
}

class WifiScanner(context: Context) {
    private val appContext = context.applicationContext
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    val isWifiEnabled: Boolean get() = wifi?.isWifiEnabled == true

    /** Emits the latest scan results each time the system finishes a scan (ours or another app's). */
    fun results(): Flow<List<ScanEntry>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(currentResults())
            }
        }
        // Protected system broadcast; EXPORTED is required to receive it on every API level.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        trySend(currentResults())
        awaitClose { appContext.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    fun currentResults(): List<ScanEntry> {
        val list: List<ScanResult> = try {
            wifi?.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        return list.map { r -> r.toEntry(nowUs) }.sortedByDescending { it.rssi }
    }

    /** Asks the system for a new scan. Android throttles this (4 scans / 2 min for foreground apps). */
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = try {
        wifi?.startScan() == true
    } catch (e: SecurityException) {
        false
    }

    /** RSSI of the currently connected AP; this is updated far more often than scans and isn't throttled. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun connected(): ScanEntry? {
        val info = try { wifi?.connectionInfo } catch (e: SecurityException) { null } ?: return null
        val bssid = info.bssid ?: return null
        if (bssid == "02:00:00:00:00:00" || info.networkId == -1 && info.rssi <= -127) return null
        val ssid = info.ssid?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" } ?: ""
        return ScanEntry(
            ssid = ssid,
            bssid = bssid.lowercase(),
            rssi = info.rssi,
            frequency = info.frequency,
            security = "",
            connected = true,
            standard = if (android.os.Build.VERSION.SDK_INT >= 30) info.wifiStandard else 0,
        )
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toEntry(nowUs: Long): ScanEntry {
        var load: Pair<Int, Int>? = null
        var standard = 0
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            standard = wifiStandard
            load = try {
                informationElements.firstOrNull { it.id == 11 }?.bytes?.let { buf ->
                    val b = ByteArray(buf.remaining())
                    buf.duplicate().get(b)
                    parseBssLoad(b)
                }
            } catch (e: Exception) {
                null
            }
        }
        return ScanEntry(
            ssid = SSID ?: "",
            bssid = (BSSID ?: "").lowercase(),
            rssi = level,
            frequency = frequency,
            security = capabilities ?: "",
            ageMs = ((nowUs - timestamp) / 1000L).coerceAtLeast(0L),
            standard = standard,
            widthMhz = widthMhz(channelWidth),
            stations = load?.first,
            utilPct = load?.second,
        )
    }
}
