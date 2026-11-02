package com.networkmapper.app.unifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/** A device as the UniFi cloud (Site Manager API, api.ui.com) reports it. */
data class CloudDevice(
    val mac: String,
    val name: String,
    val model: String,
    val shortname: String,
    val ip: String,
    val status: String,
    val firmware: String,
    val updateAvailable: String,
    val startupTime: String,
    val console: String,
    val isConsole: Boolean,
) {
    val online: Boolean get() = status == "online"

    /** "ap", "switch", "gateway" or "other", guessed from the model (the cloud API has no device type). */
    val kind: String
        get() {
            val m = "$model $shortname".uppercase()
            return when {
                Regex("""\b(UDM|UDR|UCG|UXG|USG|UDW|EFG)""").containsMatchIn(m) -> "gateway"
                Regex("""\b(USW|US-|US\d|USL|USM|USF|USPM|USP)""").containsMatchIn(m) || m.contains("SWITCH") -> "switch"
                Regex("""\b(U6|U7|E7|UAP|UAL|UK|U5|AC |NANOHD|FLEXHD|BEACONHD|IW-|UWB|U6M|U7P)""").containsMatchIn(m) ||
                    m.contains(" AP") -> "ap"
                isConsole -> "gateway"
                else -> "other"
            }
        }

    fun toUnifiDevice() = UnifiDevice(mac = mac, name = name, model = model.ifBlank { shortname }, kind = kind, ip = ip, firmware = firmware)

    /** What the pin card shows. The cloud gives device-level status only (no clients, ports or throughput). */
    fun toLive(): UnifiLive = UnifiLive(
        online = online,
        stateLabel = when {
            status.isBlank() -> "unknown"
            updateAvailable.isNotBlank() -> "$status (update $updateAvailable available)"
            else -> status
        },
        uptimeSec = if (online) startedAgoSec() else null,
        clients = null,
        cpuPct = null,
        memPct = null,
        firmware = firmware,
        ip = ip,
        uplink = null,
        txBps = null,
        rxBps = null,
        satisfaction = null,
        radios = emptyList(),
        ports = emptyList(),
    )

    private fun startedAgoSec(): Long? = try {
        (System.currentTimeMillis() / 1000 - Instant.parse(startupTime).epochSecond).takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }
}

/** Reads devices from the UniFi cloud with a Site Manager API key (unifi.ui.com → API). Works anywhere with internet. */
class UnifiCloudClient(private val apiKey: String, private val baseUrl: String = "https://api.ui.com") {

    suspend fun devices(): List<CloudDevice> = withContext(Dispatchers.IO) {
        val out = ArrayList<CloudDevice>()
        var token: String? = null
        repeat(50) {
            val body = get("/v1/devices" + (token?.let { "?nextToken=" + URLEncoder.encode(it, "UTF-8") } ?: ""))
            out += parse(body)
            token = JSONObject(body).optString("nextToken").takeIf { it.isNotBlank() }
            if (token == null) return@withContext out
        }
        out
    }

    private fun get(path: String): String {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("X-API-KEY", apiKey.trim().trim('"', '\'').trim())
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            when (code) {
                in 200..299 -> return text
                401, 403 -> throw UnifiException(
                    "The UniFi cloud refused this API key (HTTP $code). Create a key at unifi.ui.com → API, " +
                        "signed in as the console owner or a super admin.",
                )
                else -> throw UnifiException("UniFi cloud error (HTTP $code)")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private fun mac(raw: String): String {
            val hex = raw.filter { it.isLetterOrDigit() }.lowercase()
            return if (hex.length == 12) hex.chunked(2).joinToString(":") else ""
        }

        /** Devices in one /v1/devices page. */
        fun parse(json: String): List<CloudDevice> {
            val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<CloudDevice>()
            for (i in 0 until data.length()) {
                val host = data.optJSONObject(i) ?: continue
                val devices = host.optJSONArray("devices") ?: continue
                for (j in 0 until devices.length()) {
                    val d = devices.optJSONObject(j) ?: continue
                    val m = mac(d.optString("mac"))
                    if (m.isEmpty()) continue
                    out += CloudDevice(
                        mac = m,
                        name = d.optString("name"),
                        model = d.optString("model"),
                        shortname = d.optString("shortname"),
                        ip = d.optString("ip"),
                        status = d.optString("status").lowercase(),
                        firmware = d.optString("version"),
                        updateAvailable = d.optString("updateAvailable").takeIf { it != "null" } ?: "",
                        startupTime = d.optString("startupTime").takeIf { it != "null" } ?: "",
                        console = host.optString("hostName"),
                        isConsole = d.optBoolean("isConsole"),
                    )
                }
            }
            return out
        }
    }
}
