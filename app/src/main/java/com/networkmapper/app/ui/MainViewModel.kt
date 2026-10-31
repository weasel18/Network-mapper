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
import com.networkmapper.app.data.GeoPt
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
import com.networkmapper.app.util.Wifi
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
import kotlinx.coroutines.launch
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

data class HeatPoint(val lat: Double, val lon: Double, val rssi: Int)

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
        heatFilter.value = HeatFilter.Strongest
        heatSession.value = null
        setCurrentSite(id)
        viewModelScope.launch { fitCameraToSite(id) }
        return true
    }

    suspend fun createSite(name: String, notes: String = ""): Long =
        repo.insertSite(Site(name = name.trim().ifEmpty { "New site" }, notes = notes.trim()))

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
    private suspend fun fitCameraToSite(id: Long) {
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
    suspend fun createAsset(type: AssetType, pt: GeoPt, accuracy: Float? = null, altitude: Double? = null): Long {
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
            ),
        )
    }

    // ---------- Pin move mode ----------
    val pinMove = MutableStateFlow<PinMove?>(null)

    fun startPinMove(asset: Asset) {
        draft.value = null
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
            repo.updateAsset(
                it.copy(latitude = m.current.lat, longitude = m.current.lon, accuracy = m.accuracy, altitude = m.altitude),
            )
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
                combine(readings, heatFilter) { readings, filter ->
                    toHeatPoints(readings, filter)
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun toHeatPoints(readings: List<WifiReading>, filter: HeatFilter): List<HeatPoint> {
        val subset = when (filter) {
            HeatFilter.Strongest -> readings
            is HeatFilter.Ssid -> readings.filter { it.ssid == filter.ssid }
            is HeatFilter.Bssid -> readings.filter { it.bssid == filter.bssid }
            is HeatFilter.Device -> readings.filter { it.bssid in filter.bssids }
        }
        // Collapse samples taken at the same spot (~1 m grid) to the strongest one.
        return subset
            .groupBy { Math.round(it.latitude * 1e5) to Math.round(it.longitude * 1e5) }
            .values
            .map { group -> group.maxBy { it.rssi } }
            .map { HeatPoint(it.latitude, it.longitude, it.rssi) }
    }

    fun showHeatmapFor(filter: HeatFilter) {
        heatFilter.value = filter
        settings.update { it.copy(showHeatmap = true) }
    }

    fun clearHeatmap() {
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
        return near.ifEmpty { repo.getNetworks(asset.siteId) }
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
