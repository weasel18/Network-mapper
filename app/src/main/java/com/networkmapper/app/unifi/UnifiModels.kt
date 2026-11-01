package com.networkmapper.app.unifi

import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.util.Wifi
import org.json.JSONArray
import org.json.JSONObject

/** One Wi-Fi radio / SSID broadcast by a UniFi AP. */
data class UnifiRadio(val bssid: String, val ssid: String, val channel: Int?, val band: String?)

/** A device adopted by a UniFi Network controller. */
data class UnifiDevice(
    val mac: String,
    val name: String,
    val model: String,
    /** "ap", "switch", "gateway" or "other". */
    val kind: String,
    val ip: String,
    val radios: List<UnifiRadio> = emptyList(),
    /** Integration-API device id (UUID), when known. */
    val id: String = "",
) {
    /** Every MAC this device can show up as: its own MAC plus each radio BSSID. */
    val allMacs: Set<String> get() = (listOf(mac) + radios.map { it.bssid }).filter { it.isNotBlank() }.toSet()
    val displayName: String get() = name.ifBlank { model.ifBlank { mac } }
}

data class UnifiSite(
    /** Integration API site UUID (API key mode) or null for session logins. */
    val id: String?,
    /** Short site name used by the classic API ("default"). */
    val reference: String,
    val name: String,
)

/** Parses the controller's JSON. Field names follow UniFi Network's classic and integration APIs. */
object UnifiParser {

    private fun mac(s: String?): String =
        s?.let { Wifi.parseBssids(it).firstOrNull() } ?: ""

    private fun kindFromType(type: String): String = when (type.lowercase()) {
        "uap" -> "ap"
        "usw" -> "switch"
        "ugw", "udm", "uxg", "ucg", "uck" -> "gateway"
        else -> "other"
    }

    private fun bandFromRadio(radio: String?): String? = when (radio?.lowercase()) {
        "ng" -> "2.4 GHz"
        "na" -> "5 GHz"
        "6e" -> "6 GHz"
        null, "" -> null
        else -> radio
    }

    /** Classic API: GET …/api/s/{site}/stat/device → {"data":[{mac,name,model,type,ip,vap_table:[{bssid,essid,channel,radio}]}]} */
    fun parseClassicDevices(json: String): List<UnifiDevice> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val d = data.optJSONObject(i) ?: return@mapNotNull null
            val m = mac(d.optString("mac"))
            if (m.isEmpty()) return@mapNotNull null
            val vaps = d.optJSONArray("vap_table") ?: JSONArray()
            val radios = (0 until vaps.length()).mapNotNull { j ->
                val v = vaps.optJSONObject(j) ?: return@mapNotNull null
                val b = mac(v.optString("bssid"))
                if (b.isEmpty()) null else UnifiRadio(
                    bssid = b,
                    ssid = v.optString("essid"),
                    channel = if (v.has("channel")) v.optInt("channel") else null,
                    band = bandFromRadio(v.optString("radio")),
                )
            }.distinctBy { it.bssid }
            val type = d.optString("type")
            UnifiDevice(
                mac = m,
                name = d.optString("name"),
                model = d.optString("model"),
                kind = if (radios.isNotEmpty() && type.isBlank()) "ap" else kindFromType(type),
                ip = d.optString("ip"),
                radios = radios,
            )
        }
    }

    /** Integration API: GET /proxy/network/integration/v1/sites/{id}/devices → {"data":[{name,model,macAddress,ipAddress,features}],"totalCount":n} */
    fun parseIntegrationDevices(json: String): Pair<List<UnifiDevice>, Int> {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: JSONArray()
        val devices = (0 until data.length()).mapNotNull { i ->
            val d = data.optJSONObject(i) ?: return@mapNotNull null
            val m = mac(d.optString("macAddress"))
            if (m.isEmpty()) return@mapNotNull null
            val features = d.optJSONArray("features")?.let { f -> (0 until f.length()).map { f.optString(it).lowercase() } }.orEmpty()
            UnifiDevice(
                mac = m,
                name = d.optString("name"),
                model = d.optString("model"),
                kind = when {
                    "accesspoint" in features -> "ap"
                    "switching" in features -> "switch"
                    "gateway" in features || "routing" in features -> "gateway"
                    else -> "other"
                },
                ip = d.optString("ipAddress"),
                id = d.optString("id"),
            )
        }
        return devices to root.optInt("totalCount", devices.size)
    }

    /** Integration API sites: {"data":[{id,internalReference,name}]} */
    fun parseIntegrationSites(json: String): List<UnifiSite> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val s = data.optJSONObject(i) ?: return@mapNotNull null
            UnifiSite(s.optString("id"), s.optString("internalReference").ifBlank { "default" }, s.optString("name").ifBlank { "Default" })
        }
    }

    /** Classic API sites: {"data":[{name:"default",desc:"Default"}]} */
    fun parseClassicSites(json: String): List<UnifiSite> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val s = data.optJSONObject(i) ?: return@mapNotNull null
            val ref = s.optString("name").ifBlank { return@mapNotNull null }
            UnifiSite(null, ref, s.optString("desc").ifBlank { ref })
        }
    }

    /** Adds radio BSSIDs from a classic device list onto integration-API devices with the same MAC. */
    fun mergeRadios(devices: List<UnifiDevice>, classic: List<UnifiDevice>): List<UnifiDevice> {
        val byMac = classic.associateBy { it.mac }
        return devices.map { d -> byMac[d.mac]?.let { c -> d.copy(radios = c.radios, kind = if (c.radios.isNotEmpty()) "ap" else d.kind) } ?: d }
    }

    // ---- cache (so new pins can pre-fill model / IP) ----

    fun toJson(devices: List<UnifiDevice>): String {
        val arr = JSONArray()
        devices.forEach { d ->
            val radios = JSONArray()
            d.radios.forEach { r ->
                radios.put(JSONObject().put("bssid", r.bssid).put("ssid", r.ssid).put("channel", r.channel ?: JSONObject.NULL).put("band", r.band ?: JSONObject.NULL))
            }
            arr.put(
                JSONObject().put("mac", d.mac).put("name", d.name).put("model", d.model).put("kind", d.kind)
                    .put("ip", d.ip).put("id", d.id).put("radios", radios),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<UnifiDevice> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val radios = o.optJSONArray("radios") ?: JSONArray()
            UnifiDevice(
                mac = o.optString("mac"),
                name = o.optString("name"),
                model = o.optString("model"),
                kind = o.optString("kind"),
                ip = o.optString("ip"),
                id = o.optString("id"),
                radios = (0 until radios.length()).mapNotNull { j ->
                    val r = radios.optJSONObject(j) ?: return@mapNotNull null
                    UnifiRadio(
                        r.optString("bssid"),
                        r.optString("ssid"),
                        if (r.isNull("channel")) null else r.optInt("channel"),
                        if (r.isNull("band")) null else r.optString("band"),
                    )
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// ------------------------------------------------------------------ live status

data class LiveRadio(
    val band: String,
    val channel: Int?,
    val txPowerDbm: Int?,
    val clients: Int?,
    /** Channel utilisation, %. */
    val utilization: Int?,
)

data class LivePort(
    val index: Int,
    val name: String,
    val up: Boolean,
    val speedMbps: Int?,
    val poeWatts: Double?,
    val isUplink: Boolean,
)

/** A snapshot of one device's live state from the controller. */
data class UnifiLive(
    val online: Boolean,
    val stateLabel: String,
    val uptimeSec: Long?,
    val clients: Int?,
    val cpuPct: Double?,
    val memPct: Double?,
    val firmware: String,
    val ip: String,
    val uplink: String?,
    /** Current throughput in bits per second (device's uplink). */
    val txBps: Double?,
    val rxBps: Double?,
    val satisfaction: Int?,
    val radios: List<LiveRadio>,
    val ports: List<LivePort>,
    val fetchedAt: Long = System.currentTimeMillis(),
)

object UnifiLiveParser {

    private fun JSONObject.num(vararg keys: String): Double? {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            }
            if (d != null && !d.isNaN()) return d
        }
        return null
    }

    private fun stateLabel(state: Int): String = when (state) {
        0 -> "Offline"
        1 -> "Online"
        2 -> "Pending adoption"
        4 -> "Updating"
        5 -> "Provisioning"
        6 -> "Heartbeat missed"
        7 -> "Adopting"
        9 -> "Adoption error"
        10 -> "Adoption failed"
        11 -> "Isolated"
        else -> "State $state"
    }

    private fun band(radio: String?): String = when (radio?.lowercase()) {
        "ng" -> "2.4 GHz"
        "na" -> "5 GHz"
        "6e" -> "6 GHz"
        else -> radio ?: "?"
    }

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    /** Classic API: GET …/api/s/{site}/stat/device/{mac} (first entry of "data"). */
    fun parseClassic(json: String): UnifiLive? {
        val d = JSONObject(json).optJSONArray("data")?.optJSONObject(0) ?: return null
        val state = d.optInt("state", -1)
        val sys = d.optJSONObject("system-stats")
        val uplink = d.optJSONObject("uplink")
        val uplinkLabel = uplink?.let { u ->
            val speed = u.num("speed")?.toInt()
            val type = u.optString("type")
            listOfNotNull(
                speed?.takeIf { it > 0 }?.let { formatSpeed(it) },
                when (type) {
                    "wire" -> "wired"
                    "wireless" -> "wireless mesh"
                    else -> type.takeIf { it.isNotBlank() }
                },
                u.num("uplink_remote_port")?.toInt()?.let { "to port $it" },
            ).joinToString(" ").ifBlank { null }
        }
        val radios = objects(d.optJSONArray("radio_table_stats")).map { r ->
            LiveRadio(
                band = band(r.optString("radio")),
                channel = r.num("channel")?.toInt(),
                txPowerDbm = r.num("tx_power")?.toInt(),
                clients = r.num("num_sta", "user-num_sta")?.toInt(),
                utilization = r.num("cu_total")?.toInt(),
            )
        }
        val ports = objects(d.optJSONArray("port_table")).map { p ->
            LivePort(
                index = p.optInt("port_idx"),
                name = p.optString("name"),
                up = p.optBoolean("up"),
                speedMbps = p.num("speed")?.toInt()?.takeIf { it > 0 },
                poeWatts = p.num("poe_power")?.takeIf { it > 0.0 },
                isUplink = p.optBoolean("is_uplink"),
            )
        }.sortedBy { it.index }
        return UnifiLive(
            online = state == 1,
            stateLabel = if (state >= 0) stateLabel(state) else "Unknown",
            uptimeSec = d.num("uptime")?.toLong(),
            clients = d.num("num_sta")?.toInt(),
            cpuPct = sys?.num("cpu"),
            memPct = sys?.num("mem"),
            firmware = d.optString("version"),
            ip = d.optString("ip"),
            uplink = uplinkLabel,
            txBps = d.num("tx_bytes-r")?.times(8),
            rxBps = d.num("rx_bytes-r")?.times(8),
            satisfaction = d.num("satisfaction")?.toInt()?.takeIf { it >= 0 },
            radios = radios,
            ports = ports,
        )
    }

    /**
     * Integration API: GET …/devices/{id} and …/devices/{id}/statistics/latest. Field names are read
     * defensively since they vary between Network versions.
     */
    fun parseIntegration(detailJson: String, statsJson: String?): UnifiLive {
        val d = JSONObject(detailJson)
        val st = statsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val state = d.optString("state")
        val ifaces = d.optJSONObject("interfaces")
        val radios = objects(ifaces?.optJSONArray("radios")).map { r ->
            val ghz = r.num("frequencyGHz")
            LiveRadio(
                band = ghz?.let { if (it < 3) "2.4 GHz" else if (it < 5.9) "5 GHz" else "6 GHz" } ?: "?",
                channel = r.num("channel")?.toInt(),
                txPowerDbm = r.num("txPowerDbm", "txPower")?.toInt(),
                clients = r.num("clientCount", "numClients")?.toInt(),
                utilization = r.num("channelUtilizationPct", "utilizationPct")?.toInt(),
            )
        }
        val ports = objects(ifaces?.optJSONArray("ports")).map { p ->
            LivePort(
                index = p.num("idx", "index", "portIdx")?.toInt() ?: 0,
                name = p.optString("name"),
                up = p.optString("state").equals("UP", ignoreCase = true),
                speedMbps = p.num("speedMbps", "speed")?.toInt()?.takeIf { it > 0 },
                poeWatts = p.optJSONObject("poe")?.num("powerW", "power"),
                isUplink = p.optBoolean("isUplink"),
            )
        }.sortedBy { it.index }
        val uplink = st?.optJSONObject("uplink")
        return UnifiLive(
            online = state.equals("ONLINE", ignoreCase = true),
            stateLabel = state.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Unknown" },
            uptimeSec = st?.num("uptimeSec")?.toLong(),
            clients = st?.num("clientCount", "numClients")?.toInt(),
            cpuPct = st?.num("cpuUtilizationPct"),
            memPct = st?.num("memoryUtilizationPct"),
            firmware = d.optString("firmwareVersion"),
            ip = d.optString("ipAddress"),
            uplink = null,
            txBps = uplink?.num("txRateBps"),
            rxBps = uplink?.num("rxRateBps"),
            satisfaction = null,
            radios = radios,
            ports = ports,
        )
    }

    fun formatSpeed(mbps: Int): String = when {
        mbps >= 1000 && mbps % 1000 == 0 -> "${mbps / 1000} Gbps"
        mbps >= 1000 -> String.format(java.util.Locale.US, "%.1f Gbps", mbps / 1000.0)
        else -> "$mbps Mbps"
    }

    fun formatRate(bps: Double): String = when {
        bps >= 1e9 -> String.format(java.util.Locale.US, "%.1f Gbps", bps / 1e9)
        bps >= 1e6 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1e6)
        bps >= 1e3 -> String.format(java.util.Locale.US, "%.0f kbps", bps / 1e3)
        else -> String.format(java.util.Locale.US, "%.0f bps", bps)
    }

    fun formatUptime(sec: Long): String {
        val d = sec / 86_400
        val h = (sec % 86_400) / 3_600
        val m = (sec % 3_600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }
}

/** Matching UniFi devices to pins already on the map. */
object UnifiMatch {

    /** All MAC addresses written anywhere in a pin's MAC-type details. */
    fun pinMacs(pin: Asset): Set<String> =
        pin.attributes.filterKeys { it.contains("MAC", ignoreCase = true) || it.contains("BSSID", ignoreCase = true) }
            .values.flatMap { Wifi.parseBssids(it) }.toSet()

    fun matches(pin: Asset, device: UnifiDevice): Boolean = pinMacs(pin).any { it in device.allMacs }

    /**
     * Returns the pin updated from the controller, or null if nothing changes:
     * the name only if the pin still has its automatic name, model / IP only where blank, and an AP
     * pin's radio list gains the device's BSSIDs so its Signal map works.
     */
    fun updatePin(pin: Asset, device: UnifiDevice, isDefaultName: (AssetType, String) -> Boolean): Asset? {
        val attrs = LinkedHashMap(pin.attributes)
        if (device.model.isNotBlank() && attrs["Model"].isNullOrBlank()) attrs["Model"] = device.model
        if (device.ip.isNotBlank() && attrs["IP address"].isNullOrBlank()) attrs["IP address"] = device.ip
        val field = pin.type.bssidField
        if (field != null && device.radios.isNotEmpty()) {
            val current = Wifi.parseBssids(attrs[field] ?: "")
            val merged = (current + device.radios.map { it.bssid }).toSortedSet()
            if (merged != current.toSortedSet()) attrs[field] = merged.joinToString(", ")
            val ssids = device.radios.map { it.ssid }.filter { it.isNotBlank() }.distinct()
            if (ssids.isNotEmpty() && "SSID" in pin.type.suggestedFields && attrs["SSID"].isNullOrBlank()) {
                attrs["SSID"] = ssids.joinToString(", ")
            }
        }
        val name = if (device.name.isNotBlank() && isDefaultName(pin.type, pin.name)) device.name else pin.name
        val updated = pin.copy(name = name, attributes = attrs)
        return if (updated == pin) null else updated
    }
}
