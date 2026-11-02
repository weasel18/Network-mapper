package com.networkmapper.app.ui

import android.app.Application
import android.content.Context
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.networkmapper.app.NetworkMapperApp
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.DEFAULT_SITE_ID
import com.networkmapper.app.data.GROUP_OFFER_RADIUS_M
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.isLocation
import com.networkmapper.app.data.point
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.NetworkSummary
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.data.SessionWithCount
import com.networkmapper.app.data.Site
import com.networkmapper.app.data.SiteWithCounts
import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.location.LocationTracker
import com.networkmapper.app.photo.PhotoStorage
import com.networkmapper.app.survey.SurveyService
import com.networkmapper.app.util.Fmt
import com.networkmapper.app.util.Geo
import com.networkmapper.app.util.RadioGrouping
import com.networkmapper.app.util.Wifi
import com.networkmapper.app.unifi.UnifiAuth
import com.networkmapper.app.unifi.UnifiClient
import com.networkmapper.app.unifi.UnifiDevice
import com.networkmapper.app.unifi.UnifiLive
import com.networkmapper.app.unifi.UnifiSite
import com.networkmapper.app.unifi.UntrustedCertificateException
import com.networkmapper.app.unifi.UnifiMatch
import com.networkmapper.app.unifi.UnifiStore
import com.networkmapper.app.wifi.WifiFilter
import com.networkmapper.app.wifi.WifiScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.log2

enum class TileStyle(val label: String) { STREET("Street map"), TOPO("Topographic"), SATELLITE("Satellite imagery") }

data class MapSettings(
    val showAssets: Boolean = true,
    val showPaths: Boolean = true,
    val showHeatmap: Boolean = false,
    /** Antenna coverage patterns on AP / bridge / router pins. */
    val showPatterns: Boolean = true,
    /** Smooth average-signal colour surface under the heatmap dots. */
    val showHeatSurface: Boolean = true,
    val tileStyle: TileStyle = TileStyle.STREET,
    val hiddenTypes: Set<AssetType> = emptySet(),
)

sealed interface Selection {
    data class AssetSel(val id: Long) : Selection
    data class PathSel(val id: Long) : Selection
}

data class DraftPoint(val pt: GeoPt, val assetId: Long? = null)

/** A pin being repositioned; nothing is saved until the user confirms. */
data class PinMove(
    val assetId: Long,
    val name: String,
    val original: GeoPt,
    val current: GeoPt,
    val accuracy: Float?,
    val altitude: Double?,
)

/**
 * A pin near where a device is being placed or moved: either a location (closet / rack) it can join,
 * or a lone device it can share a new location with.
 */
data class GroupOffer(val target: Asset, val distanceM: Double) {
    val joinsExisting: Boolean get() = target.isLocation
}

/** Which Wi-Fi band the heatmap shows. */
enum class HeatBand(val label: String) {
    ALL("All"), GHZ_2_4("2.4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz");

    fun includes(frequencyMhz: Int): Boolean = when (this) {
        ALL -> true
        else -> Wifi.band(frequencyMhz) == label
    }
}

/** Which end of the route new points (taps, GPS points, auto-track) are added to. */
enum class DraftEnd { END, START }

data class Draft(
    val kind: PathKind,
    val editingPathId: Long? = null,
    val points: List<DraftPoint> = emptyList(),
    val autoTrack: Boolean = false,
    val extendFrom: DraftEnd = DraftEnd.END,
    /** Edit-points mode: vertices can be selected and dragged. Off = taps only add points. */
    val editPoints: Boolean = false,
    /** Vertex currently selected for move / delete / insert. */
    val selectedIndex: Int? = null,
    /** Previous point lists, newest last, for multi-step undo. */
    val history: List<List<DraftPoint>> = emptyList(),
) {
    val lengthMeters: Double get() = Geo.pathLengthMeters(points.map { it.pt })
    val canUndo: Boolean get() = history.isNotEmpty()

    /** Records the current points in history and replaces them. */
    fun commit(newPoints: List<DraftPoint>, selected: Int? = null): Draft = copy(
        points = newPoints,
        history = (history + listOf(points)).takeLast(200),
        selectedIndex = selected?.takeIf { it in newPoints.indices },
    )
}

sealed interface HeatFilter {
    val label: String

    data object Strongest : HeatFilter {
        override val label = "Strongest signal (all networks)"
    }

    data class Ssid(val ssid: String) : HeatFilter {
        override val label get() = "SSID: ${ssid.ifEmpty { "(hidden)" }}"
    }

    data class Bssid(val bssid: String, val ssid: String) : HeatFilter {
        override val label get() = "AP: ${ssid.ifEmpty { "(hidden)" }} [$bssid]"
    }

    /** Signal of the radio(s) linked to one mapped AP / bridge pin. */
    data class Device(val assetId: Long, val name: String, val bssids: Set<String>) : HeatFilter {
        override val label get() = "$name • ${bssids.size} radio${if (bssids.size == 1) "" else "s"}"
    }
}

data class HeatPoint(
    val lat: Double,
    val lon: Double,
    val rssi: Int,
    val bssid: String = "",
    val ssid: String = "",
    val timestamp: Long = 0,
)

enum class ApSource(val label: String) {
    LIVE("In range now"),
    NEARBY("Recorded near this pin"),
    SITE("Recorded in this site"),
    NAMED("Saved name"),
}

/** A Wi-Fi radio offered to pre-fill an AP / bridge / router pin. */
data class ApCandidate(
    val bssid: String,
    val ssid: String,
    val frequency: Int,
    val rssi: Int?,
    val savedName: String?,
    val source: ApSource,
    /** Name of another pin this radio is already linked to, if any. */
    val linkedPin: String?,
)

/** What was heard at a tapped spot on the signal map. */
data class SpotInfo(
    val lat: Double,
    val lon: Double,
    val tapped: HeatPoint,
    /** Every AP recorded within a few metres of the spot, the tapped one first, then strongest first. */
    val networks: List<NetworkSummary>,
    val lastSeen: Long?,
)

/** Coverage summary for a single-device heatmap, measured from the device's pin. */
data class CoverageStats(
    val spots: Int,
    val bestRssi: Int?,
    /** Farthest sample from the pin with a reliable signal (≥ -67 dBm). */
    val reliableRangeM: Double?,
    /** Farthest sample from the pin still connectable (≥ -80 dBm). */
    val edgeRangeM: Double?,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repo = (app as NetworkMapperApp).repository

    // ---------- Wi-Fi filter (which networks to record and show) ----------
    private val filterStore = (app as NetworkMapperApp).wifiFilter

    /** The open site's Wi-Fi filter (each site has its own). */
    val wifiFilter: StateFlow<WifiFilter> by lazy {
        combine(currentSite, filterStore.all) { site, all -> site?.uid?.let { all[it] } ?: WifiFilter() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, WifiFilter())
    }

    private fun allowed(ssid: String, bssid: String) = wifiFilter.value.allows(ssid, bssid)

    fun updateWifiFilter(block: (WifiFilter) -> WifiFilter) {
        val uid = currentSite.value?.uid ?: return
        filterStore.update(uid, block)
        (getApplication<Application>() as NetworkMapperApp).sync.requestSoon()
    }

    fun hideBssids(bssids: Collection<String>) = updateWifiFilter { it.copy(hiddenBssids = it.hiddenBssids + bssids.map { b -> b.lowercase() }) }
    fun hideSsid(ssid: String) = updateWifiFilter { it.copy(hiddenSsids = it.hiddenSsids + ssid, onlySsids = it.onlySsids - ssid) }
    fun onlyMapSsid(ssid: String) = updateWifiFilter { it.copy(onlySsids = it.onlySsids + ssid, hiddenSsids = it.hiddenSsids - ssid) }

    /** Readings in the open site of networks the filter now excludes. */
    suspend fun filteredReadingCount(): Int {
        val f = wifiFilter.value
        if (f.isEmpty) return 0
        return repo.getNetworks(currentSiteId.value).filter { !f.allows(it.ssid, it.bssid) }.sumOf { it.samples }
    }

    /** Deletes this site's recorded readings of networks the filter excludes (on this phone). Returns the count. */
    suspend fun purgeFilteredReadings(): Int {
        val f = wifiFilter.value
        val site = currentSiteId.value
        val blocked = repo.getNetworks(site).filter { !f.allows(it.ssid, it.bssid) }
        if (blocked.isEmpty()) return 0
        blocked.map { it.bssid }.chunked(500).forEach { repo.deleteReadingsFor(site, it) }
        return blocked.sumOf { it.samples }
    }
    private val tracker = LocationTracker(app)

    // ---------- Location ----------
    private val _location = MutableStateFlow<Location?>(tracker.lastKnown())
    val location: StateFlow<Location?> = _location
    private var locationJob: Job? = null

    fun startLocation() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            tracker.updates(minTimeMs = 1000L).collect { loc ->
                _location.value = loc
                onLocationForDraft(loc)
            }
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    fun isGpsEnabled() = tracker.isGpsEnabled()

    // ---------- Sites ----------
    private val sitePrefs = app.getSharedPreferences("sites", Context.MODE_PRIVATE)

    /** The site whose pins, paths and surveys are shown and edited. */
    val currentSiteId = MutableStateFlow(sitePrefs.getLong("current", DEFAULT_SITE_ID))
    val currentSite: StateFlow<Site?> = currentSiteId.flatMapLatest { repo.observeSite(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sites: StateFlow<List<SiteWithCounts>> = repo.sitesWithCounts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { ensureValidSite() }
        // Filters used to be shared by all sites: copy the old one into every site once.
        viewModelScope.launch { filterStore.migrateLegacy(repo.getSites().map { it.uid }) }
        // The open site can disappear (deleted on another phone and synced): fall back to another.
        viewModelScope.launch {
            sites.collect { list ->
                if (list.isNotEmpty() && list.none { it.site.id == currentSiteId.value }) ensureValidSite()
            }
        }
    }

    /** Makes sure the remembered site still exists (it may have been deleted), creating one if needed. */
    private suspend fun ensureValidSite() {
        if (repo.getSite(currentSiteId.value) != null) return
        val fallback = repo.getSites().firstOrNull()?.id ?: repo.insertSite(Site(name = AppDatabase.DEFAULT_SITE_NAME))
        setCurrentSite(fallback)
    }

    private fun setCurrentSite(id: Long) {
        currentSiteId.value = id
        sitePrefs.edit().putLong("current", id).apply()
    }

    /**
     * Opens another site. Refused (returns false) while a route is being drawn, so the route
     * can't end up in the wrong site.
     */
    fun switchSite(id: Long): Boolean {
        if (draft.value != null) return false
        if (id == currentSiteId.value) return true
        pinMove.value = null
        selected.value = null
        spotInfo.value = null
        heatFilter.value = HeatFilter.Strongest
        heatSession.value = null
        setCurrentSite(id)
        viewModelScope.launch { fitCameraToSite(id) }
        return true
    }

    /** New sites start where you are (GPS), or where the map is, so opening them goes straight there. */
    suspend fun createSite(name: String, notes: String = ""): Long {
        val loc = location.value?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }
        val at = loc?.let { GeoPt(it.latitude, it.longitude) } ?: cameraCenter
        val zoom = if (loc != null) 18.0 else cameraZoom
        return repo.insertSite(
            Site(
                name = name.trim().ifEmpty { "New site" }, notes = notes.trim(),
                homeLat = at?.lat, homeLon = at?.lon, homeZoom = at?.let { zoom },
            ),
        )
    }

    /** Saves where the map is now as the open site's start view. */
    fun setSiteStartView(center: GeoPt, zoom: Double) = viewModelScope.launch {
        val site = repo.getSite(currentSiteId.value) ?: return@launch
        repo.updateSite(site.copy(homeLat = center.lat, homeLon = center.lon, homeZoom = zoom))
    }

    fun clearSiteStartView() = viewModelScope.launch {
        val site = repo.getSite(currentSiteId.value) ?: return@launch
        repo.updateSite(site.copy(homeLat = null, homeLon = null, homeZoom = null))
    }

    /** Jumps to the open site's start view (or fits its pins). */
    fun goToSiteStart() = viewModelScope.launch { fitCameraToSite(currentSiteId.value) }

    fun renameSite(site: Site, name: String, notes: String) = viewModelScope.launch {
        repo.updateSite(site.copy(name = name.trim().ifEmpty { site.name }, notes = notes.trim()))
    }

    /** Deletes a site with all its data; if it was open, another site (or a fresh one) is opened. */
    suspend fun deleteSite(id: Long) {
        val wasCurrent = id == currentSiteId.value
        if (wasCurrent) {
            draft.value = null
            pinMove.value = null
            selected.value = null
            heatFilter.value = HeatFilter.Strongest
            heatSession.value = null
        }
        repo.deleteSite(id)
        if (wasCurrent) {
            val next = repo.getSites().firstOrNull()?.id ?: repo.insertSite(Site(name = AppDatabase.DEFAULT_SITE_NAME))
            setCurrentSite(next)
            fitCameraToSite(next)
        }
    }

    /** Centres / zooms the remembered camera on a site's pins and paths. */
    /** Opens the site's start view if it has one, otherwise fits the map to its pins and routes. */
    private suspend fun fitCameraToSite(id: Long) {
        val site = repo.getSite(id)
        val home = site?.home
        if (home != null) {
            cameraZoom = site.homeZoom ?: 18.0
            cameraCenter = home
            cameraInitialized = true
            focusRequest.value = home
            return
        }
        val pts = repo.getAssetsForSite(id).map { GeoPt(it.latitude, it.longitude) } +
            repo.getPathsForSite(id).flatMap { it.points }
        if (pts.isEmpty()) return
        val south = pts.minOf { it.lat }
        val north = pts.maxOf { it.lat }
        val west = pts.minOf { it.lon }
        val east = pts.maxOf { it.lon }
        val center = GeoPt((south + north) / 2, (west + east) / 2)
        // Rough fit for a phone-sized map (~1.3 tiles wide at DPI-scaled tiles), with margin.
        val lonSpan = (east - west).coerceAtLeast(1e-6)
        val latSpan = ((north - south) / cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)).coerceAtLeast(1e-6)
        val zoom = minOf(log2(360.0 * 1.0 / lonSpan), log2(360.0 * 1.4 / latSpan)).coerceIn(3.0, 19.0)
        cameraZoom = zoom
        cameraCenter = center
        cameraInitialized = true
        focusRequest.value = center
        // The site has no location yet: remember this view as its start view, so it opens here from now on.
        if (site != null) repo.updateSite(site.copy(homeLat = center.lat, homeLon = center.lon, homeZoom = zoom))
    }

    // ---------- Data (always for the current site) ----------
    val assets: StateFlow<List<Asset>> = currentSiteId.flatMapLatest { repo.assets(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val paths: StateFlow<List<NetPath>> = currentSiteId.flatMapLatest { repo.paths(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sessions: StateFlow<List<SessionWithCount>> = currentSiteId.flatMapLatest { repo.sessions(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val networks: StateFlow<List<NetworkSummary>> = currentSiteId.flatMapLatest { repo.networks(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sitePhotoCount: StateFlow<Int> = currentSiteId.flatMapLatest { repo.photoCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val siteReadingCount: StateFlow<Int> = currentSiteId.flatMapLatest { repo.readingCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---------- AP names ----------
    /** Your own AP names by BSSID. */
    val explicitApNames: StateFlow<Map<String, String>> = repo.apNames
        .map { list -> list.associate { it.bssid to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Display names by BSSID: your name first, then the name of a pin linked to that radio. */
    val apNames: StateFlow<Map<String, String>> = combine(explicitApNames, assets) { names, pins ->
        val out = HashMap<String, String>()
        pins.forEach { pin -> linkedBssids(pin).forEach { b -> out.putIfAbsent(b, pin.name) } }
        out.putAll(names)
        out as Map<String, String>
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun apDisplayName(bssid: String, ssid: String): String =
        apNames.value[bssid.lowercase()] ?: ssid.ifEmpty { "(hidden network)" }

    /** Names one or more radios (blank name removes it). */
    fun nameAps(bssids: Collection<String>, name: String) = viewModelScope.launch { repo.setApName(bssids, name) }

    /**
     * Radios to offer when filling in an AP-type pin: in range right now, recorded near the pin,
     * recorded anywhere in this site, then any AP you've named. Live radios come first when you're
     * standing near the pin (within 30 m), otherwise radios recorded near it do.
     */
    suspend fun apCandidates(lat: Double, lon: Double, excludeAssetId: Long? = null): List<ApCandidate> {
        val names = explicitApNames.value
        val site = currentSiteId.value
        val pins = assets.value.filter { it.id != excludeAssetId }
        fun linkedTo(bssid: String) = pins.firstOrNull { bssid in linkedBssids(it) }?.name

        val scanner = WifiScanner(getApplication())
        val live = (listOfNotNull(scanner.connected()) + scanner.currentResults())
            .filter { it.bssid.isNotEmpty() && (it.connected || it.ageMs < 120_000) && allowed(it.ssid, it.bssid) }
            .sortedByDescending { it.rssi }
            .map { ApCandidate(it.bssid, it.ssid, it.frequency, it.rssi, names[it.bssid], ApSource.LIVE, linkedTo(it.bssid)) }

        val dLat = 75.0 / 111_320.0
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val nearby = repo.getNetworksIn(site, lat - dLat, lat + dLat, lon - dLon, lon + dLon)
            .filter { allowed(it.ssid, it.bssid) }
            .map { ApCandidate(it.bssid, it.ssid, it.frequency, it.bestRssi, names[it.bssid], ApSource.NEARBY, linkedTo(it.bssid)) }
        val inSite = repo.getNetworks(site)
            .filter { allowed(it.ssid, it.bssid) }
            .map { ApCandidate(it.bssid, it.ssid, it.frequency, it.bestRssi, names[it.bssid], ApSource.SITE, linkedTo(it.bssid)) }
        // Named radios not heard here: one entry per UniFi device (its own MAC) instead of one per radio.
        val named = names.map { (b, n) -> b to n }
            .distinctBy { (b, _) -> unifiDeviceFor(b)?.mac ?: b }
            .map { (b, n) ->
                val key = unifiDeviceFor(b)?.mac ?: b
                ApCandidate(key, "", 0, null, n, ApSource.NAMED, linkedTo(key))
            }

        val me = location.value
        val standingHere = me != null && Geo.distanceMeters(GeoPt(me.latitude, me.longitude), GeoPt(lat, lon)) <= 30.0
        val ordered = if (standingHere) live + nearby + inSite + named else nearby + live + inSite + named
        val out = LinkedHashMap<String, ApCandidate>()
        ordered.forEach { out.putIfAbsent(it.bssid, it) }
        return out.values.toList()
    }

    /**
     * How far from a pin its radios were recorded: (reliable ≥ -67 dBm, usable ≥ -80 dBm) in metres,
     * either null when there are no such samples.
     */
    suspend fun surveyedRange(bssids: Set<String>, lat: Double, lon: Double): Pair<Double?, Double?> {
        val origin = GeoPt(lat, lon)
        val readings = repo.readingsForBssids(currentSiteId.value, bssids)
        fun reach(min: Int) = readings.filter { it.rssi >= min }
            .maxOfOrNull { Geo.distanceMeters(origin, GeoPt(it.latitude, it.longitude)) }
        return reach(-67) to reach(-80)
    }

    /** True for the automatic names given to new pins ("Wi-Fi access point 3", "Photo pin …"). */
    fun isDefaultPinName(type: AssetType, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.startsWith("Photo pin ")) return true
        return AssetType.entries.any { t ->
            val glyph = t.label.substringBefore(" /").substringBefore(" (")
            Regex("^${Regex.escape(glyph)} \\d+$").matches(n)
        }
    }

    /** Other recorded radios in this site that look like the same physical AP (other band / SSID). */
    suspend fun siblingRadios(bssid: String): List<NetworkSummary> = sameApRadios(bssid)

    /**
     * Every other radio (BSSID) of the same physical AP as [bssid]: recorded in this site or in range
     * now, matched by MAC pattern and confirmed by survey RSSI where there's enough data.
     */
    suspend fun sameApRadios(bssid: String): List<NetworkSummary> {
        val me = bssid.lowercase()
        val live = WifiScanner(getApplication()).currentResults()
            .map { NetworkSummary(it.bssid, it.ssid, it.rssi, 1, it.frequency) }
        val pool = (networks.value + live).distinctBy { it.bssid }.filter { it.bssid == me || allowed(it.ssid, it.bssid) }
        val mine = pool.firstOrNull { it.bssid == me }
        val near = pool.filter { it.bssid != me && Wifi.lowDistance(me, it.bssid) <= RadioGrouping.MAX_SPAN }
        if (near.isEmpty()) return emptyList()
        val readings = repo.readingsForBssids(currentSiteId.value, near.map { it.bssid } + me)
        val grouped = withContext(Dispatchers.Default) {
            RadioGrouping.sameAp(
                RadioGrouping.Radio(me, mine?.frequency ?: 0),
                near.map { RadioGrouping.Radio(it.bssid, it.frequency) },
                readings,
            ).mapTo(HashSet()) { it.bssid }
        }
        return near.filter { it.bssid in grouped }
    }

    /**
     * Drops an access-point pin where these radios were heard strongest, linked to them so
     * "Signal map" works straight away. Returns the new pin id, or null with no survey data.
     */
    suspend fun createApPinFromSurvey(bssids: Set<String>, name: String, ssid: String): Long? {
        val strongest = repo.strongestReading(currentSiteId.value, bssids) ?: return null
        val attrs = LinkedHashMap<String, String>()
        if (ssid.isNotBlank()) attrs["SSID"] = ssid
        attrs["MAC / BSSID"] = bssids.sorted().joinToString(", ")
        return repo.insertAsset(
            Asset(
                type = AssetType.ACCESS_POINT,
                name = name.ifBlank { ssid.ifBlank { "Access point" } },
                latitude = strongest.latitude,
                longitude = strongest.longitude,
                notes = "Placed where the signal was strongest (${strongest.rssi} dBm). Use Move to set the exact spot.",
                attributes = attrs,
                siteId = currentSiteId.value,
            ),
        )
    }

    // ---------- UniFi ----------
    val unifiStore = UnifiStore(app)

    /** Devices last imported from a UniFi controller (used to pre-fill model / IP on new pins). */
    val unifiDevices = MutableStateFlow<List<UnifiDevice>>(emptyList())

    init {
        viewModelScope.launch { unifiDevices.value = withContext(Dispatchers.IO) { unifiStore.loadDevices() } }
    }

    fun unifiDeviceFor(mac: String): UnifiDevice? = mac.lowercase().let { m -> unifiDevices.value.firstOrNull { m in it.allMacs } }

    data class UnifiImportResult(val namesSaved: Int, val namesKept: Int, val pinsUpdated: Int)

    /**
     * Saves controller device names for their MACs and radio BSSIDs (keeping names you set yourself
     * unless [replaceMyNames]) and optionally updates matching pins in the open site.
     */
    suspend fun importUnifi(devices: List<UnifiDevice>, saveNames: Boolean, replaceMyNames: Boolean, updatePins: Boolean): UnifiImportResult {
        var saved = 0
        var kept = 0
        if (saveNames) {
            val mine = explicitApNames.value
            devices.filter { it.name.isNotBlank() }.forEach { d ->
                val (keep, write) = d.allMacs.partition { !replaceMyNames && mine[it] != null && mine[it] != d.name }
                kept += keep.size
                if (write.isNotEmpty()) {
                    repo.setApName(write, d.name)
                    saved += write.size
                }
            }
        }
        var updated = 0
        if (updatePins) {
            for (pin in repo.getAssetsForSite(currentSiteId.value)) {
                val device = devices.firstOrNull { UnifiMatch.matches(pin, it) } ?: continue
                UnifiMatch.updatePin(pin, device, ::isDefaultPinName)?.let {
                    repo.updateAsset(it)
                    updated++
                }
            }
        }
        unifiDevices.value = withContext(Dispatchers.IO) {
            unifiStore.saveDevices(devices)
            unifiStore.loadDevices()
        }
        return UnifiImportResult(saved, kept, updated)
    }

    fun deleteSurveysForCurrentSite() = viewModelScope.launch {
        heatSession.value = null
        repo.deleteSurveysForSite(currentSiteId.value)
    }

    // ---------- Map state ----------
    val settings = MutableStateFlow(MapSettings())
    val selected = MutableStateFlow<Selection?>(null)
    val focusRequest = MutableStateFlow<GeoPt?>(null)

    /** Camera is remembered across navigation because the MapView is recreated. */
    var cameraCenter: GeoPt? = null
    var cameraZoom: Double = 18.0
    var cameraInitialized = false

    fun saveCamera(center: GeoPt, zoom: Double) {
        cameraCenter = center
        cameraZoom = zoom
        cameraInitialized = true
    }

    fun updateSettings(block: (MapSettings) -> MapSettings) = settings.update(block)

    fun select(sel: Selection?) {
        selected.value = sel
        if (sel != null) spotInfo.value = null
    }

    // ---------- Tap a spot on the signal map ----------
    val spotInfo = MutableStateFlow<SpotInfo?>(null)

    /** Looks up every AP heard within ~[radiusM] of a tapped heatmap dot. */
    fun inspectSpot(point: HeatPoint, radiusM: Double = 5.0) = viewModelScope.launch {
        selected.value = null
        val site = currentSiteId.value
        val dLat = radiusM / 111_320.0
        val dLon = dLat / cos(Math.toRadians(point.lat)).coerceAtLeast(0.01)
        val box = listOf(point.lat - dLat, point.lat + dLat, point.lon - dLon, point.lon + dLon)
        val nets = repo.getNetworksIn(site, box[0], box[1], box[2], box[3])
            .filter { it.bssid == point.bssid || allowed(it.ssid, it.bssid) }
            .sortedWith(compareByDescending<NetworkSummary> { it.bssid == point.bssid }.thenByDescending { it.bestRssi })
        spotInfo.value = SpotInfo(point.lat, point.lon, point, nets, repo.lastSeenIn(site, box[0], box[1], box[2], box[3]))
    }

    fun closeSpot() {
        spotInfo.value = null
    }

    // ---------- UniFi live status for the selected pin ----------
    sealed interface LiveState {
        val device: UnifiDevice

        data class Loading(override val device: UnifiDevice) : LiveState
        data class Ok(override val device: UnifiDevice, val live: UnifiLive) : LiveState
        data class Failed(override val device: UnifiDevice, val message: String, val last: UnifiLive? = null) : LiveState
    }

    val unifiLive = MutableStateFlow<LiveState?>(null)
    private var liveJob: Job? = null
    private var livePaused = false
    private var unifiClient: UnifiClient? = null
    private var unifiClientKey: String? = null

    init {
        // Follow the selection: poll the controller while a UniFi-matched pin's card is open.
        viewModelScope.launch {
            combine(selected, unifiDevices) { sel, _ -> sel }.collect { restartLive(it) }
        }
    }

    /** One shared signed-in client per saved controller login. */
    private fun unifiSession(settings: com.networkmapper.app.unifi.UnifiSettings): Triple<UnifiClient, UnifiAuth, UnifiSite> {
        val base = UnifiClient.normalizeBase(settings.url)
        val key = "$base|${settings.useApiKey}|${settings.username}|${settings.pinnedFingerprint}"
        val client = unifiClient?.takeIf { unifiClientKey == key }
            ?: UnifiClient(base, settings.pinnedFingerprint.takeIf { settings.pinnedFor == base }).also {
                unifiClient = it
                unifiClientKey = key
            }
        val auth = if (settings.useApiKey) UnifiAuth.ApiKey(settings.secret) else UnifiAuth.Login(settings.username, settings.secret)
        return Triple(client, auth, UnifiSite(settings.siteId.ifBlank { null }, settings.siteRef, settings.siteName))
    }

    /** True when a UniFi login and site are saved (so devices can be fetched without the UniFi screen). */
    suspend fun unifiConfigured(): Boolean = withContext(Dispatchers.IO) {
        unifiStore.load().ready
    }

    /** Re-reads the device list from the controller into the local cache. Returns an error message, or null. */
    suspend fun refreshUnifiDevices(): String? {
        val settings = withContext(Dispatchers.IO) { unifiStore.load() }
        if (!settings.ready) {
            return "Sign in on the UniFi screen (with “Remember” ticked) and load devices once."
        }
        return try {
            val list = if (settings.cloud) {
                com.networkmapper.app.unifi.UnifiCloudClient(settings.secret).devices().map { it.toUnifiDevice() }
            } else {
                val (client, auth, site) = unifiSession(settings)
                client.devices(auth, site)
            }
            unifiDevices.value = withContext(Dispatchers.IO) {
                unifiStore.saveDevices(list)
                unifiStore.loadDevices()
            }
            null
        } catch (e: UntrustedCertificateException) {
            "The controller's certificate changed. Reconnect on the UniFi screen."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e) {
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
                    "Can't reach the controller (are you on its network / VPN?)"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Stop polling while the app is in the background. */
    fun pauseLive() {
        livePaused = true
        liveJob?.cancel()
    }

    fun resumeLive() {
        livePaused = false
        restartLive(selected.value)
    }

    fun refreshLive() = restartLive(selected.value)

    private fun restartLive(sel: Selection?) {
        liveJob?.cancel()
        val assetId = (sel as? Selection.AssetSel)?.id
        val asset = assetId?.let { id -> assets.value.firstOrNull { it.id == id } }
        val device = asset?.let { a -> unifiDevices.value.firstOrNull { UnifiMatch.matches(a, it) } }
        if (device == null) {
            unifiLive.value = null
            return
        }
        if (livePaused) return
        liveJob = viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { unifiStore.load() }
            if (!settings.ready) {
                unifiLive.value = LiveState.Failed(
                    device,
                    "Sign in on the UniFi screen with “Remember” ticked and load devices to see live status.",
                )
                return@launch
            }
            if (settings.cloud) return@launch pollCloud(device, settings.secret)
            val (client, auth, site) = unifiSession(settings)
            var last = (unifiLive.value as? LiveState.Ok)?.takeIf { it.device.mac == device.mac }?.live
            if (last == null) unifiLive.value = LiveState.Loading(device)
            while (isActive) {
                try {
                    val live = client.liveDevice(auth, site, device.mac, device.id)
                    last = live
                    unifiLive.value = LiveState.Ok(device, live)
                } catch (e: UntrustedCertificateException) {
                    unifiLive.value = LiveState.Failed(device, "The controller's certificate changed. Reconnect on the UniFi screen.", last)
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = when (e) {
                        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
                            "Can't reach the controller (are you on its network / VPN?)"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    unifiLive.value = LiveState.Failed(device, msg, last)
                }
                delay(10_000)
            }
        }
    }

    /** Live status from the UniFi cloud: device-level only (online, firmware, uptime, IP), every 30 s. */
    private suspend fun pollCloud(device: UnifiDevice, key: String) {
        val cloud = com.networkmapper.app.unifi.UnifiCloudClient(key)
        var last = (unifiLive.value as? LiveState.Ok)?.takeIf { it.device.mac == device.mac }?.live
        if (last == null) unifiLive.value = LiveState.Loading(device)
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                val found = cloud.devices().firstOrNull { it.mac == device.mac.lowercase() }
                if (found == null) {
                    unifiLive.value = LiveState.Failed(device, "Not found in the UniFi cloud (is its console linked to your UI.com account?)", last)
                } else {
                    val live = found.toLive()
                    last = live
                    unifiLive.value = LiveState.Ok(device, live)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = when (e) {
                    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
                        "Can't reach the UniFi cloud – is this phone online?"
                    else -> e.message ?: e.javaClass.simpleName
                }
                unifiLive.value = LiveState.Failed(device, msg, last)
            }
            delay(30_000)
        }
    }

    fun focusOn(pt: GeoPt, sel: Selection? = null) {
        cameraCenter = pt
        cameraZoom = maxOf(cameraZoom, 18.5)
        cameraInitialized = true
        focusRequest.value = pt
        if (sel != null) selected.value = sel
    }

    fun focusOnAsset(a: Asset) {
        settings.update { it.copy(showAssets = true, hiddenTypes = it.hiddenTypes - a.type) }
        focusOn(GeoPt(a.latitude, a.longitude), Selection.AssetSel(a.id))
    }

    fun focusOnPath(p: NetPath) {
        if (p.points.isEmpty()) return
        settings.update { it.copy(showPaths = true) }
        val mid = p.points[p.points.size / 2]
        focusOn(mid, Selection.PathSel(p.id))
    }

    // ---------- Assets ----------
    suspend fun createAsset(
        type: AssetType,
        pt: GeoPt,
        accuracy: Float? = null,
        altitude: Double? = null,
        groupUid: String = "",
    ): Long {
        val count = assets.value.count { it.type == type } + 1
        val glyphName = type.label.substringBefore(" /").substringBefore(" (")
        return repo.insertAsset(
            Asset(
                type = type,
                name = "$glyphName $count",
                latitude = pt.lat,
                longitude = pt.lon,
                accuracy = accuracy,
                altitude = altitude,
                siteId = currentSiteId.value,
                groupUid = groupUid,
            ),
        )
    }

    // ---------- Locations: several devices in one closet / rack, shown as one pin ----------

    /** The location pin [a] sits in, if any. */
    fun locationOf(a: Asset): Asset? =
        a.groupUid.takeIf { it.isNotEmpty() }?.let { u -> assets.value.firstOrNull { it.uid == u && it.isLocation } }

    fun membersOf(location: Asset): List<Asset> =
        assets.value.filter { it.groupUid == location.uid && !it.isLocation }.sortedWith(compareBy({ it.type.ordinal }, { it.name }))

    /**
     * Finds a location, or a lone device, close to [pt] that a device being placed / moved could share.
     * Existing locations win over lone pins.
     */
    fun groupOfferFor(pt: GeoPt, moving: Asset? = null): GroupOffer? {
        if (moving?.isLocation == true) return null
        val candidates = assets.value.filter { a ->
            a.id != moving?.id && (a.isLocation || locationOf(a) == null) && a.uid != moving?.groupUid
        }.map { it to Geo.distanceMeters(pt, it.point()) }.filter { it.second <= GROUP_OFFER_RADIUS_M }
        val best = candidates.filter { it.first.isLocation }.minByOrNull { it.second } ?: candidates.minByOrNull { it.second }
        return best?.let { GroupOffer(it.first, it.second) }
    }

    /** Creates a location at [other]'s position and puts [other] in it. */
    suspend fun createLocationWith(other: Asset, name: String): Asset {
        val id = repo.insertAsset(
            Asset(
                type = AssetType.LOCATION,
                name = name.trim().ifEmpty { "Location ${assets.value.count { it.isLocation } + 1}" },
                latitude = other.latitude,
                longitude = other.longitude,
                accuracy = other.accuracy,
                altitude = other.altitude,
                siteId = currentSiteId.value,
            ),
        )
        val location = repo.getAsset(id)!!
        repo.addToLocation(other, location)
        return location
    }

    /** New device pin inside a location (or a new location shared with a lone pin). */
    suspend fun createAssetIn(type: AssetType, offer: GroupOffer, newLocationName: String): Long {
        val location = if (offer.joinsExisting) offer.target else createLocationWith(offer.target, newLocationName)
        return createAsset(type, location.point(), location.accuracy, location.altitude, groupUid = location.uid)
    }

    /** Puts an existing device into the offered location (or a new one shared with a lone pin). */
    fun joinLocation(device: Asset, offer: GroupOffer, newLocationName: String) = viewModelScope.launch {
        val location = if (offer.joinsExisting) offer.target else createLocationWith(offer.target, newLocationName)
        repo.getAsset(device.id)?.let { repo.addToLocation(it, location) }
        pinMove.value = null
        selected.value = Selection.AssetSel(location.id)
    }

    fun removeFromLocation(device: Asset) = viewModelScope.launch {
        repo.getAsset(device.id)?.let { repo.removeFromLocation(it) }
    }

    /** Lone pins (not in a location) that sit within a few metres of each other, biggest groups first. */
    fun stackedPins(): List<List<Asset>> {
        val lone = assets.value.filter { !it.isLocation && locationOf(it) == null }
        val parent = IntArray(lone.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x]
            return x
        }
        for (i in lone.indices) for (j in i + 1 until lone.size) {
            if (Geo.distanceMeters(lone[i].point(), lone[j].point()) <= GROUP_OFFER_RADIUS_M) parent[find(i)] = find(j)
        }
        return lone.indices.groupBy { find(it) }.values.filter { it.size > 1 }
            .map { idx -> idx.map { lone[it] } }.sortedByDescending { it.size }
    }

    /** Puts [pins] into one new location, placed at the most accurately located pin. */
    fun combineIntoLocation(pins: List<Asset>, name: String) = viewModelScope.launch {
        val anchor = pins.minByOrNull { it.accuracy ?: Float.MAX_VALUE } ?: return@launch
        val location = createLocationWith(anchor, name)
        pins.filter { it.id != anchor.id }.forEach { p -> repo.getAsset(p.id)?.let { repo.addToLocation(it, location) } }
    }

    // ---------- Pin move mode ----------
    val pinMove = MutableStateFlow<PinMove?>(null)

    fun startPinMove(asset: Asset) {
        draft.value = null
        spotInfo.value = null
        selected.value = Selection.AssetSel(asset.id)
        val pt = GeoPt(asset.latitude, asset.longitude)
        pinMove.value = PinMove(asset.id, asset.name, pt, pt, asset.accuracy, asset.altitude)
    }

    /** Manual placement (map tap or drag): accuracy becomes unknown. */
    fun setPinMovePosition(pt: GeoPt) {
        pinMove.update { m -> m?.copy(current = pt, accuracy = null, altitude = null) }
    }

    fun pinMoveToGps(): Boolean {
        val loc = location.value ?: return false
        pinMove.update { m ->
            m?.copy(
                current = GeoPt(loc.latitude, loc.longitude),
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
            )
        }
        return true
    }

    fun cancelPinMove() {
        pinMove.value = null
    }

    fun savePinMove() = viewModelScope.launch {
        val m = pinMove.value ?: return@launch
        repo.getAsset(m.assetId)?.let {
            if (it.isLocation) {
                repo.moveLocation(it, m.current, m.accuracy, m.altitude) // its devices move with it
            } else {
                repo.updateAsset(
                    it.copy(latitude = m.current.lat, longitude = m.current.lon, accuracy = m.accuracy, altitude = m.altitude),
                )
            }
        }
        pinMove.value = null
    }

    /** Creates a new pin from a freshly taken photo, located by GPS (or EXIF, or the map centre). */
    suspend fun createPhotoPin(file: File): Long {
        val loc = location.value?.takeIf { System.currentTimeMillis() - it.time < 2 * 60_000L }
        val pt: GeoPt
        var accuracy: Float? = null
        var altitude: Double? = null
        if (loc != null) {
            pt = GeoPt(loc.latitude, loc.longitude)
            accuracy = if (loc.hasAccuracy()) loc.accuracy else null
            altitude = if (loc.hasAltitude()) loc.altitude else null
            PhotoStorage.tagLocation(file, loc)
        } else {
            val exif = PhotoStorage.readLocation(file)
            pt = exif?.let { GeoPt(it.first, it.second) } ?: cameraCenter ?: GeoPt(0.0, 0.0)
        }
        val id = repo.insertAsset(
            Asset(
                type = AssetType.OTHER,
                name = "Photo pin ${Fmt.dateTime(System.currentTimeMillis())}",
                latitude = pt.lat,
                longitude = pt.lon,
                accuracy = accuracy,
                altitude = altitude,
                siteId = currentSiteId.value,
            ),
        )
        repo.insertPhoto(
            Photo(assetId = id, fileName = file.name, latitude = pt.lat, longitude = pt.lon, accuracy = accuracy),
        )
        return id
    }

    /** Attaches a captured or imported photo to an asset or path, recording where it was taken. */
    fun attachPhoto(file: File, assetId: Long? = null, pathId: Long? = null, fromGallery: Boolean = false) =
        viewModelScope.launch {
            val loc = location.value?.takeIf { System.currentTimeMillis() - it.time < 2 * 60_000L }
            var lat: Double? = null
            var lon: Double? = null
            var acc: Float? = null
            val exif = if (fromGallery) PhotoStorage.readLocation(file) else null
            if (exif != null) {
                lat = exif.first
                lon = exif.second
            } else if (loc != null) {
                lat = loc.latitude
                lon = loc.longitude
                acc = if (loc.hasAccuracy()) loc.accuracy else null
                if (!fromGallery) PhotoStorage.tagLocation(file, loc)
            }
            repo.insertPhoto(
                Photo(assetId = assetId, pathId = pathId, fileName = file.name, latitude = lat, longitude = lon, accuracy = acc),
            )
        }

    // ---------- Path drawing ----------
    val draft = MutableStateFlow<Draft?>(null)
    private val draftPrefs = app.getSharedPreferences("draft", Context.MODE_PRIVATE)

    init {
        // An unfinished route survives the app being closed or killed.
        draft.value = restoreDraft()
        viewModelScope.launch { draft.debounce(400).collect { persistDraft(it) } }
    }

    fun startDraft(kind: PathKind) {
        pinMove.value = null
        selected.value = null
        draft.value = Draft(kind = kind)
    }

    /** Re-opens a saved path's route for editing / extending (from the end by default). */
    fun editPathRoute(path: NetPath, extendFrom: DraftEnd = DraftEnd.END) {
        pinMove.value = null
        selected.value = null
        val pts = path.points.mapIndexed { i, p ->
            DraftPoint(
                p,
                when (i) {
                    0 -> path.startAssetId
                    path.points.lastIndex -> path.endAssetId
                    else -> null
                },
            )
        }
        draft.value = Draft(kind = path.kind, editingPathId = path.id, points = pts, extendFrom = extendFrom)
        val focus = if (extendFrom == DraftEnd.END) path.points.lastOrNull() else path.points.firstOrNull()
        focus?.let { focusOn(it) }
    }

    fun addDraftPoint(pt: GeoPt, assetId: Long? = null) {
        draft.update { d ->
            d ?: return@update null
            val p = DraftPoint(pt, assetId)
            d.commit(if (d.extendFrom == DraftEnd.START) listOf(p) + d.points else d.points + p)
        }
    }

    fun addDraftGpsPoint(): Boolean {
        val loc = location.value ?: return false
        addDraftPoint(GeoPt(loc.latitude, loc.longitude))
        return true
    }

    fun undoDraft() {
        draft.update { d ->
            if (d == null || d.history.isEmpty()) return@update d
            d.copy(points = d.history.last(), history = d.history.dropLast(1), selectedIndex = null)
        }
    }

    fun toggleAutoTrack() {
        draft.update { d -> d?.copy(autoTrack = !d.autoTrack) }
    }

    fun setDraftExtendFrom(end: DraftEnd) {
        draft.update { d -> d?.copy(extendFrom = end) }
        val d = draft.value ?: return
        val focus = if (end == DraftEnd.END) d.points.lastOrNull() else d.points.firstOrNull()
        focus?.let { focusOn(it.pt) }
    }

    /** Switches between adding points and editing existing ones. Editing pauses auto-track. */
    fun setDraftEditPoints(on: Boolean) {
        draft.update { d -> d?.copy(editPoints = on, selectedIndex = null, autoTrack = if (on) false else d.autoTrack) }
    }

    fun selectDraftPoint(index: Int?) {
        draft.update { d -> d?.copy(selectedIndex = if (index == d.selectedIndex) null else index) }
    }

    /** Called once when the user starts dragging a vertex, so the whole drag is one undo step. */
    fun beginDraftPointMove(index: Int) {
        draft.update { d -> d?.copy(history = (d.history + listOf(d.points)).takeLast(200), selectedIndex = index) }
    }

    /** Live vertex drag; a moved point is no longer snapped to a pin. */
    fun moveDraftPoint(index: Int, pt: GeoPt) {
        draft.update { d ->
            if (d == null || index !in d.points.indices) return@update d
            d.copy(points = d.points.toMutableList().also { it[index] = DraftPoint(pt, null) })
        }
    }

    fun deleteSelectedDraftPoint() {
        draft.update { d ->
            if (d == null) return@update null
            val i = d.selectedIndex ?: return@update d
            if (i !in d.points.indices) return@update d
            val pts = d.points.toMutableList().also { it.removeAt(i) }
            d.commit(pts, selected = null)
        }
    }

    /** Inserts a new vertex halfway along the segment after the selected point (before it, for the last point). */
    fun insertDraftPointNearSelected() {
        draft.update { d ->
            if (d == null) return@update null
            val i = d.selectedIndex ?: return@update d
            if (d.points.size < 2 || i !in d.points.indices) return@update d
            val (a, b) = if (i < d.points.lastIndex) i to i + 1 else i - 1 to i
            val pa = d.points[a].pt
            val pb = d.points[b].pt
            val mid = DraftPoint(GeoPt((pa.lat + pb.lat) / 2, (pa.lon + pb.lon) / 2))
            val pts = d.points.toMutableList().also { it.add(b, mid) }
            d.commit(pts, selected = b)
        }
    }

    fun cancelDraft() {
        draft.value = null
    }

    private fun onLocationForDraft(loc: Location) {
        val d = draft.value ?: return
        if (!d.autoTrack) return
        if (loc.hasAccuracy() && loc.accuracy > 25f) return
        val pt = GeoPt(loc.latitude, loc.longitude)
        val end = if (d.extendFrom == DraftEnd.START) d.points.firstOrNull() else d.points.lastOrNull()
        if (end == null || Geo.distanceMeters(end.pt, pt) >= 3.0) addDraftPoint(pt)
    }

    private fun persistDraft(d: Draft?) {
        try {
            if (d == null) {
                draftPrefs.edit().remove("draft").apply()
                return
            }
            val pts = JSONArray()
            d.points.forEach { p ->
                pts.put(JSONArray().put(p.pt.lat).put(p.pt.lon).put(p.assetId ?: JSONObject.NULL))
            }
            val o = JSONObject()
                .put("kind", d.kind.name)
                .put("editingPathId", d.editingPathId ?: JSONObject.NULL)
                .put("extendFrom", d.extendFrom.name)
                .put("points", pts)
            draftPrefs.edit().putString("draft", o.toString()).apply()
        } catch (e: Exception) {
            // Best effort only.
        }
    }

    private fun restoreDraft(): Draft? = try {
        draftPrefs.getString("draft", null)?.let { json ->
            val o = JSONObject(json)
            val arr = o.getJSONArray("points")
            val pts = (0 until arr.length()).map { i ->
                val p = arr.getJSONArray(i)
                DraftPoint(GeoPt(p.getDouble(0), p.getDouble(1)), if (p.isNull(2)) null else p.getLong(2))
            }
            Draft(
                kind = PathKind.entries.firstOrNull { it.name == o.optString("kind") } ?: PathKind.FIBER_UNDERGROUND,
                editingPathId = if (o.isNull("editingPathId")) null else o.getLong("editingPathId"),
                points = pts,
                extendFrom = if (o.optString("extendFrom") == DraftEnd.START.name) DraftEnd.START else DraftEnd.END,
            )
        }
    } catch (e: Exception) {
        null
    }

    /** Saves the draft; returns the path id (new or edited) or null when there's nothing to save. */
    suspend fun saveDraft(): Long? {
        val d = draft.value ?: return null
        if (d.points.size < 2) return null
        val pts = d.points.map { it.pt }
        val startId = d.points.first().assetId
        val endId = d.points.last().assetId
        val existing = d.editingPathId?.let { repo.getPath(it) }
        val id = if (existing != null) {
            repo.updatePath(existing.copy(points = pts, startAssetId = startId, endAssetId = endId))
            existing.id
        } else {
            val names = assets.value.associate { it.id to it.name }
            val name = if (startId != null && endId != null) {
                "${names[startId] ?: "?"} → ${names[endId] ?: "?"}"
            } else {
                "${d.kind.label} ${Fmt.dateTime(System.currentTimeMillis())}"
            }
            repo.insertPath(
                NetPath(
                    kind = d.kind, name = name, points = pts, startAssetId = startId, endAssetId = endId,
                    siteId = currentSiteId.value,
                ),
            )
        }
        draft.value = null
        return id
    }

    // ---------- Wi-Fi heatmap ----------
    val heatFilter = MutableStateFlow<HeatFilter>(HeatFilter.Strongest)
    /** 2.4 / 5 / 6 GHz (or all) for the heatmap. */
    val heatBand = MutableStateFlow(HeatBand.ALL)
    val heatSession = MutableStateFlow<Long?>(null)

    val heatPoints: StateFlow<List<HeatPoint>> = settings
        .map { it.showHeatmap }
        .distinctUntilChanged()
        .flatMapLatest { show ->
            if (!show) {
                flowOf(emptyList())
            } else {
                val readings = combine(currentSiteId, heatSession) { site, session -> site to session }
                    .flatMapLatest { (site, session) -> repo.readings(site, session) }
                combine(readings, heatFilter, wifiFilter, heatBand) { readings, filter, wf, band ->
                    toHeatPoints(readings.filter { band.includes(it.frequency) }, filter, wf)
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun toHeatPoints(readings: List<WifiReading>, filter: HeatFilter, wf: WifiFilter): List<HeatPoint> {
        val subset = when (filter) {
            // Filtered-out networks don't count towards "strongest"; picking one AP explicitly still shows it.
            HeatFilter.Strongest -> readings.filter { wf.allows(it.ssid, it.bssid) }
            is HeatFilter.Ssid -> readings.filter { it.ssid == filter.ssid && wf.allows(it.ssid, it.bssid) }
            is HeatFilter.Bssid -> readings.filter { it.bssid == filter.bssid }
            is HeatFilter.Device -> readings.filter { it.bssid in filter.bssids }
        }
        // Collapse samples taken at the same spot (~1 m grid) to the strongest one.
        return subset
            .groupBy { Math.round(it.latitude * 1e5) to Math.round(it.longitude * 1e5) }
            .values
            .map { group -> group.maxBy { it.rssi } }
            .map { HeatPoint(it.latitude, it.longitude, it.rssi, it.bssid, it.ssid, it.timestamp) }
    }

    fun showHeatmapFor(filter: HeatFilter) {
        heatFilter.value = filter
        settings.update { it.copy(showHeatmap = true) }
    }

    fun clearHeatmap() {
        spotInfo.value = null
        heatFilter.value = HeatFilter.Strongest
        settings.update { it.copy(showHeatmap = false) }
    }

    val coverage: StateFlow<CoverageStats?> = combine(heatPoints, heatFilter, assets) { points, filter, all ->
        val device = filter as? HeatFilter.Device ?: return@combine null
        val pin = all.firstOrNull { it.id == device.assetId } ?: return@combine null
        val origin = GeoPt(pin.latitude, pin.longitude)
        fun range(min: Int) = points.filter { it.rssi >= min }
            .maxOfOrNull { Geo.distanceMeters(origin, GeoPt(it.lat, it.lon)) }
        CoverageStats(
            spots = points.size,
            bestRssi = points.maxOfOrNull { it.rssi },
            reliableRangeM = range(-67),
            edgeRangeM = range(-80),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** BSSIDs stored on a pin (AP, bridge or router), or empty if none / not a radio type. */
    fun linkedBssids(asset: Asset): Set<String> =
        asset.type.bssidField?.let { asset.attributes[it] }?.let(Wifi::parseBssids) ?: emptySet()

    /** Shows only this device's signal on the map. Returns false when no radio is linked yet. */
    fun showDeviceSignal(asset: Asset): Boolean {
        val bssids = linkedBssids(asset)
        if (bssids.isEmpty()) return false
        showHeatmapFor(HeatFilter.Device(asset.id, asset.name, bssids))
        return true
    }

    /** Networks recorded within ~[radiusM] of the pin, strongest first (falls back to everything recorded). */
    suspend fun networksNear(asset: Asset, radiusM: Double = 75.0): List<NetworkSummary> {
        val dLat = radiusM / 111_320.0
        val dLon = dLat / cos(Math.toRadians(asset.latitude)).coerceAtLeast(0.01)
        val near = repo.getNetworksIn(
            asset.siteId, asset.latitude - dLat, asset.latitude + dLat, asset.longitude - dLon, asset.longitude + dLon,
        )
        return near.ifEmpty { repo.getNetworks(asset.siteId) }.filter { allowed(it.ssid, it.bssid) }
    }

    /** Saves the chosen radios onto the pin and switches the heatmap to them. */
    fun linkRadios(asset: Asset, bssids: Set<String>) = viewModelScope.launch {
        val field = asset.type.bssidField ?: return@launch
        val fresh = repo.getAsset(asset.id) ?: return@launch
        val attrs = LinkedHashMap(fresh.attributes)
        if (bssids.isEmpty()) attrs.remove(field) else attrs[field] = bssids.sorted().joinToString(", ")
        repo.updateAsset(fresh.copy(attributes = attrs))
        if (bssids.isEmpty()) clearHeatmap() else showHeatmapFor(HeatFilter.Device(fresh.id, fresh.name, bssids))
    }

    // ---------- Survey ----------
    fun startSurvey(name: String) = SurveyService.start(getApplication<Application>(), name, currentSiteId.value)
    fun stopSurvey() = SurveyService.stop(getApplication<Application>())
}
