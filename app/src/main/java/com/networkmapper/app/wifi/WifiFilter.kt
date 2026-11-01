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

/** Persists the filter (shared by every site) and tracks whether it still needs to be synced. */
class WifiFilterStore(context: Context, name: String = "wifi_filter") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val _filter = MutableStateFlow(
        try {
            WifiFilter.fromJson(prefs.getString("filter", null)?.let { JSONObject(it) })
        } catch (e: Exception) {
            WifiFilter()
        },
    )
    val filter: StateFlow<WifiFilter> = _filter.asStateFlow()

    val updatedAt: Long get() = prefs.getLong("updatedAt", 0)
    val dirty: Boolean get() = prefs.getBoolean("dirty", false)

    private fun save(f: WifiFilter, u: Long, dirty: Boolean) {
        prefs.edit().putString("filter", f.toJson().toString()).putLong("updatedAt", u).putBoolean("dirty", dirty).commit()
        _filter.value = f
    }

    fun update(block: (WifiFilter) -> WifiFilter) = save(block(_filter.value), System.currentTimeMillis(), true)

    /** A newer filter from the sync server. */
    fun applyRemote(f: WifiFilter, u: Long) = save(f, u, false)

    fun markSent(u: Long) {
        if (updatedAt == u) prefs.edit().putBoolean("dirty", false).apply()
    }
}
