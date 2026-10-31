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
)

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
        )
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toEntry(nowUs: Long) = ScanEntry(
        ssid = SSID ?: "",
        bssid = (BSSID ?: "").lowercase(),
        rssi = level,
        frequency = frequency,
        security = capabilities ?: "",
        ageMs = ((nowUs - timestamp) / 1000L).coerceAtLeast(0L),
    )
}
