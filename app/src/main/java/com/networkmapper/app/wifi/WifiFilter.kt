package com.networkmapper.app.wifi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which Wi-Fi networks to record and show.
 *  - [onlySsids] non-empty = allowlist: only these SSIDs are mapped.
 *  - [hiddenSsids] / [hiddenBssids] are always excluded (a hidden BSSID stays hidden even if its SSID is allowed).
 *  - [hideNoName] drops networks that don't broadcast an SSID.
 */
data class WifiFilter(
    val onlySsids: Set<String> = emptySet(),
    val hiddenSsids: Set<String> = emptySet(),
    val hiddenBssids: Set<String> = emptySet(),
    val hideNoName: Boolean = false,
) {
    val isEmpty: Boolean get() = onlySsids.isEmpty() && hiddenSsids.isEmpty() && hiddenBssids.isEmpty() && !hideNoName

    fun allows(ssid: String, bssid: String): Boolean {
        if (bssid.lowercase() in hiddenBssids) return false
        if (ssid.isEmpty()) return !hideNoName && onlySsids.isEmpty()
        if (ssid in hiddenSsids) return false
        return onlySsids.isEmpty() || ssid in onlySsids
    }

    fun toJson(): JSONObject = JSONObject()
        .put("only", JSONArray(onlySsids.sorted()))
        .put("ssids", JSONArray(hiddenSsids.sorted()))
        .put("bssids", JSONArray(hiddenBssids.sorted()))
        .put("noName", hideNoName)

    companion object {
        private fun JSONArray?.strings(): Set<String> =
            if (this == null) emptySet() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }.toSet()

        fun fromJson(o: JSONObject?): WifiFilter = if (o == null) WifiFilter() else WifiFilter(
            onlySsids = o.optJSONArray("only").strings(),
            hiddenSsids = o.optJSONArray("ssids").strings(),
            hiddenBssids = o.optJSONArray("bssids").strings().map { it.lowercase() }.toSet(),
            hideNoName = o.optBoolean("noName"),
        )
    }
}

/**
 * Persists one filter per site (keyed by site uid) and tracks which still need to be synced.
 * Before filters were per site there was one shared filter; it is copied into every site once.
 */
class WifiFilterStore(context: Context, name: String = "wifi_filter") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun parse(json: String?): WifiFilter = try {
        WifiFilter.fromJson(json?.let { JSONObject(it) })
    } catch (e: Exception) {
        WifiFilter()
    }

    private val _all = MutableStateFlow(
        prefs.all.keys.filter { it.startsWith("f:") }.associate { k -> k.removePrefix("f:") to parse(prefs.getString(k, null)) },
    )

    /** Every site's filter, by site uid. A site without an entry maps everything. */
    val all: StateFlow<Map<String, WifiFilter>> = _all.asStateFlow()

    fun filterFor(siteUid: String): WifiFilter = _all.value[siteUid] ?: WifiFilter()
    fun updatedAt(siteUid: String): Long = prefs.getLong("u:$siteUid", 0)
    fun isDirty(siteUid: String): Boolean = prefs.getBoolean("d:$siteUid", false)
    fun dirtySites(): List<String> = _all.value.keys.filter { isDirty(it) }

    @Synchronized
    private fun save(siteUid: String, f: WifiFilter, u: Long, dirty: Boolean) {
        prefs.edit().putString("f:$siteUid", f.toJson().toString()).putLong("u:$siteUid", u).putBoolean("d:$siteUid", dirty).commit()
        _all.value = _all.value + (siteUid to f)
    }

    fun update(siteUid: String, block: (WifiFilter) -> WifiFilter) {
        if (siteUid.isEmpty()) return
        save(siteUid, block(filterFor(siteUid)), System.currentTimeMillis(), true)
    }

    /** A newer filter for a site from the sync server. */
    fun applyRemote(siteUid: String, f: WifiFilter, u: Long) = save(siteUid, f, u, false)

    fun markSent(siteUid: String, u: Long) {
        if (updatedAt(siteUid) == u) prefs.edit().putBoolean("d:$siteUid", false).apply()
    }

    /** The old shared filter on this phone, if it hasn't been copied into the sites yet. */
    val legacy: WifiFilter?
        get() = prefs.getString("filter", null)?.let { parse(it) }?.takeIf { !it.isEmpty }

    /**
     * Copies a shared (pre per-site) filter into each of [siteUids] that has no filter of its own.
     * [dirty] = push the copies (this phone's own old filter); false for one that came from the server.
     */
    @Synchronized
    fun adoptShared(f: WifiFilter, siteUids: Collection<String>, u: Long, dirty: Boolean) {
        if (f.isEmpty) return
        siteUids.filter { it.isNotEmpty() && it !in _all.value }.forEach { save(it, f, u, dirty) }
    }

    /** Copies this phone's old shared filter into every site once, then forgets it. */
    @Synchronized
    fun migrateLegacy(siteUids: Collection<String>) {
        if (siteUids.isEmpty()) return
        // Keep the old filter's own timestamp so a newer per-site filter (set elsewhere) always wins.
        legacy?.let { adoptShared(it, siteUids, prefs.getLong("updatedAt", 1L).coerceAtLeast(1L), dirty = true) }
        prefs.edit().remove("filter").remove("updatedAt").remove("dirty").commit()
    }
}
