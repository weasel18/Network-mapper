package com.networkmapper.app.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.networkmapper.app.NetworkMapperApp
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.location.LocationTracker
import com.networkmapper.app.photo.PhotoStorage
import com.networkmapper.app.survey.SurveyService
import com.networkmapper.app.util.Fmt
import com.networkmapper.app.util.Geo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class TileStyle(val label: String) { STREET("Street map"), TOPO("Topographic"), SATELLITE("Satellite imagery") }

data class MapSettings(
    val showAssets: Boolean = true,
    val showPaths: Boolean = true,
    val showHeatmap: Boolean = false,
    val lockPins: Boolean = true,
    val tileStyle: TileStyle = TileStyle.STREET,
    val hiddenTypes: Set<AssetType> = emptySet(),
)

sealed interface Selection {
    data class AssetSel(val id: Long) : Selection
    data class PathSel(val id: Long) : Selection
}

data class DraftPoint(val pt: GeoPt, val assetId: Long? = null)

data class Draft(
    val kind: PathKind,
    val editingPathId: Long? = null,
    val points: List<DraftPoint> = emptyList(),
    val autoTrack: Boolean = false,
) {
    val lengthMeters: Double get() = Geo.pathLengthMeters(points.map { it.pt })
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
}

data class HeatPoint(val lat: Double, val lon: Double, val rssi: Int)

@OptIn(ExperimentalCoroutinesApi::class)
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

    // ---------- Data ----------
    val assets: StateFlow<List<Asset>> = repo.assets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val paths: StateFlow<List<NetPath>> = repo.paths.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            ),
        )
    }

    fun moveAsset(asset: Asset, pt: GeoPt) = viewModelScope.launch {
        repo.getAsset(asset.id)?.let {
            repo.updateAsset(it.copy(latitude = pt.lat, longitude = pt.lon, accuracy = null))
        }
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

    fun startDraft(kind: PathKind) {
        selected.value = null
        draft.value = Draft(kind = kind)
    }

    fun editPathRoute(path: NetPath) {
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
        draft.value = Draft(kind = path.kind, editingPathId = path.id, points = pts)
        path.points.firstOrNull()?.let { focusOn(it) }
    }

    fun addDraftPoint(pt: GeoPt, assetId: Long? = null) {
        draft.update { d -> d?.copy(points = d.points + DraftPoint(pt, assetId)) }
    }

    fun addDraftGpsPoint(): Boolean {
        val loc = location.value ?: return false
        addDraftPoint(GeoPt(loc.latitude, loc.longitude))
        return true
    }

    fun undoDraftPoint() {
        draft.update { d -> d?.copy(points = d.points.dropLast(1)) }
    }

    fun toggleAutoTrack() {
        draft.update { d -> d?.copy(autoTrack = !d.autoTrack) }
    }

    fun cancelDraft() {
        draft.value = null
    }

    private fun onLocationForDraft(loc: Location) {
        val d = draft.value ?: return
        if (!d.autoTrack) return
        if (loc.hasAccuracy() && loc.accuracy > 25f) return
        val pt = GeoPt(loc.latitude, loc.longitude)
        val last = d.points.lastOrNull()?.pt
        if (last == null || Geo.distanceMeters(last, pt) >= 3.0) addDraftPoint(pt)
    }

    /** Saves the draft; returns the path id (new or edited) or null when there's nothing to save. */
    suspend fun saveDraft(): Long? {
        val d = draft.value ?: return null
        if (d.points.size < 2) return null
        val pts = d.points.map { it.pt }
        val startId = d.points.first().assetId
        val endId = d.points.last().assetId
        val id = if (d.editingPathId != null) {
            val existing = repo.getPath(d.editingPathId) ?: return null
            repo.updatePath(existing.copy(points = pts, startAssetId = startId, endAssetId = endId))
            existing.id
        } else {
            val names = assets.value.associate { it.id to it.name }
            val name = if (startId != null && endId != null) {
                "${names[startId] ?: "?"} → ${names[endId] ?: "?"}"
            } else {
                "${d.kind.label} ${Fmt.dateTime(System.currentTimeMillis())}"
            }
            repo.insertPath(NetPath(kind = d.kind, name = name, points = pts, startAssetId = startId, endAssetId = endId))
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
                combine(heatSession.flatMapLatest { repo.readings(it) }, heatFilter) { readings, filter ->
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

    // ---------- Survey ----------
    fun startSurvey(name: String) = SurveyService.start(getApplication<Application>(), name)
    fun stopSurvey() = SurveyService.stop(getApplication<Application>())
}
