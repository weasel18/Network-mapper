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
            arr.put(JSONObject().put("mac", d.mac).put("name", d.name).put("model", d.model).put("kind", d.kind).put("ip", d.ip).put("radios", radios))
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
