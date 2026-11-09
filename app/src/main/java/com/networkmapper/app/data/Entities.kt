package com.networkmapper.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A simple lat/lon pair used for path geometry. */
data class GeoPt(val lat: Double, val lon: Double)

enum class AssetType(
    val label: String,
    val colorArgb: Long,
    val glyph: String,
    val suggestedFields: List<String>,
    /** Attribute that holds this device's Wi-Fi radio MAC(s) / BSSID(s), for per-device signal maps. */
    val bssidField: String? = null,
) {
    FIBER_JUNCTION(
        "Fiber junction / Handhole", 0xFF1E88E5, "J",
        listOf("Box ID", "Box size", "Lid type", "Depth", "Conduits in/out"),
    ),
    SPLICE(
        "Splice enclosure", 0xFF8E24AA, "X",
        listOf("Enclosure ID", "Enclosure model", "Tray count", "Splice count", "Fiber count", "Cables in", "Splice diagram ref"),
    ),
    FDH(
        "Fiber cabinet / FDH / Patch panel", 0xFF5E35B1, "F",
        listOf("Cabinet ID", "Model", "Port count", "Splitter ratio", "Feeder cable"),
    ),
    SLACK_LOOP(
        "Slack loop / Coil", 0xFF039BE5, "L",
        listOf("Cable ID", "Slack length", "Fiber count"),
    ),
    ACCESS_POINT(
        "Wi-Fi access point", 0xFF43A047, "A",
        listOf("SSID", "MAC / BSSID", "IP address", "Model", "Channel", "Mount height", "Switch port"),
        bssidField = "MAC / BSSID",
    ),
    WIRELESS_BRIDGE(
        "Wireless bridge / PtP radio", 0xFFFB8C00, "B",
        listOf("Link name", "Model", "IP address", "MAC", "Frequency", "Channel width", "Azimuth (°)", "Tilt (°)", "Peer device", "Signal (dBm)"),
        bssidField = "MAC",
    ),
    SWITCH(
        "Switch", 0xFF00897B, "S",
        listOf("Hostname", "Model", "IP address", "MAC", "Port count", "Uplink", "VLANs", "PoE budget"),
    ),
    ROUTER(
        "Router / Gateway / OLT", 0xFF3949AB, "R",
        listOf("Hostname", "Model", "IP address", "WAN / Uplink", "Serial", "Wi-Fi MAC / BSSID"),
        bssidField = "Wi-Fi MAC / BSSID",
    ),
    POLE(
        "Pole / Tower", 0xFF6D4C41, "P",
        listOf("Pole ID", "Owner", "Height", "Attachment height", "Make-ready notes"),
    ),
    BUILDING_ENTRY(
        "Building entry / Demarc", 0xFFD81B60, "D",
        listOf("Address", "Room / Location", "Contact", "Demarc type"),
    ),
    /** A place that holds several devices (closet, rack, cabinet). Its devices are drawn as one pin. */
    LOCATION(
        "Location / Closet / Rack", 0xFF37474F, "▣",
        listOf("Building / Floor", "Room", "Rack / Cabinet", "Access / Keys", "Power / UPS", "Contact"),
    ),
    OTHER("Other / Photo pin", 0xFF757575, "?", emptyList()),
}

/** Devices dropped or moved within this distance of another pin are offered to share its location. */
const val GROUP_OFFER_RADIUS_M = 6.0

/** A job / location. Every pin, path and Wi-Fi survey belongs to exactly one site. */
fun newUid(): String = java.util.UUID.randomUUID().toString()

@Entity(tableName = "sites", indices = [Index(value = ["uid"], unique = true)])
data class Site(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Permanent id shared by every phone and the sync server. */
    val uid: String = newUid(),
    /** True when changed on this phone and not yet pushed to the sync server. */
    val dirty: Boolean = true,
    /** Start view: where the map goes when this site is opened (null = fit to the site's pins). */
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    val homeZoom: Double? = null,
) {
    val home: GeoPt? get() = if (homeLat != null && homeLon != null) GeoPt(homeLat, homeLon) else null
}

data class SiteWithCounts(
    @Embedded val site: Site,
    val pins: Int,
    val paths: Int,
    val surveys: Int,
)

/** Site that existing (pre-sites) data is moved into, and that a fresh install starts with. */
const val DEFAULT_SITE_ID = 1L

val ASSET_STATUSES = listOf("Active", "Planned", "Under construction", "Inactive", "Damaged", "Abandoned")

@Entity(tableName = "assets", indices = [Index("siteId"), Index(value = ["uid"], unique = true)])
data class Asset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: AssetType,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val status: String = "Active",
    val notes: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1") val siteId: Long = DEFAULT_SITE_ID,
    /** Permanent id shared by every phone and the sync server. */
    val uid: String = newUid(),
    /** True when changed on this phone and not yet pushed to the sync server. */
    val dirty: Boolean = true,
    /** uid of the LOCATION pin this device sits in ("" = its own pin). Its position follows the location. */
    @ColumnInfo(defaultValue = "") val groupUid: String = "",
)

fun Asset.point(): GeoPt = GeoPt(latitude, longitude)
val Asset.isLocation: Boolean get() = type == AssetType.LOCATION

enum class PathKind(
    val label: String,
    val colorArgb: Long,
    val dashed: Boolean,
    val suggestedFields: List<String>,
    /** Only fiber routes have a fiber (strand) count. */
    val isFiber: Boolean = false,
    /** Label for the main cable / duct description field, or null when the route has no cable (wireless). */
    val cableLabel: String? = null,
    val cablePlaceholder: String = "",
) {
    FIBER_UNDERGROUND(
        "Fiber – underground", 0xFF1565C0, false,
        listOf("Cable ID", "Manufacturer", "Buffer tubes", "Conduit size", "Depth", "Install date"),
        isFiber = true, cableLabel = "Cable type", cablePlaceholder = "e.g. loose tube, armored, microduct",
    ),
    FIBER_AERIAL(
        "Fiber – aerial", 0xFF6A1B9A, false,
        listOf("Cable ID", "Manufacturer", "Buffer tubes", "Strand / Messenger", "Install date"),
        isFiber = true, cableLabel = "Cable type", cablePlaceholder = "e.g. ADSS, lashed, figure-8",
    ),
    CONDUIT(
        "Conduit / Duct (empty)", 0xFF757575, true,
        listOf("Conduit size", "Count", "Depth", "Pull tape"),
        cableLabel = "Duct material", cablePlaceholder = "e.g. HDPE, PVC, innerduct",
    ),
    WIRELESS_LINK(
        "Wireless link (PtP / PtMP)", 0xFFEF6C00, true,
        listOf("Frequency", "Channel width", "Capacity", "Signal (dBm)", "SSID"),
    ),
    COPPER(
        "Copper / Ethernet", 0xFF2E7D32, false,
        listOf("Pairs", "Shielded / UTP", "PoE", "Termination", "Install date"),
        cableLabel = "Cable category", cablePlaceholder = "e.g. Cat6, Cat6A, Cat5e",
    ),
}

@Entity(tableName = "paths", indices = [Index("siteId"), Index(value = ["uid"], unique = true)])
data class NetPath(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: PathKind,
    val name: String,
    val points: List<GeoPt>,
    val fiberCount: Int? = null,
    val cableType: String = "",
    val status: String = "Active",
    val notes: String = "",
    /** Optional colour override (ARGB); null = colour of [kind]. */
    val colorArgb: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
    val startAssetId: Long? = null,
    val endAssetId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1") val siteId: Long = DEFAULT_SITE_ID,
    /** Permanent id shared by every phone and the sync server. */
    val uid: String = newUid(),
    /** True when changed on this phone and not yet pushed to the sync server. */
    val dirty: Boolean = true,
)

@Entity(
    tableName = "photos",
    indices = [Index("assetId"), Index("pathId"), Index(value = ["uid"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = Asset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = NetPath::class, parentColumns = ["id"], childColumns = ["pathId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long? = null,
    val pathId: Long? = null,
    val fileName: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val caption: String = "",
    val takenAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Permanent id shared by every phone and the sync server. */
    val uid: String = newUid(),
    /** True when changed on this phone and not yet pushed to the sync server. */
    val dirty: Boolean = true,
)

@Entity(tableName = "survey_sessions", indices = [Index("siteId"), Index(value = ["uid"], unique = true)])
data class SurveySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    @ColumnInfo(defaultValue = "1") val siteId: Long = DEFAULT_SITE_ID,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Permanent id shared by every phone and the sync server. */
    val uid: String = newUid(),
    /** True when changed on this phone and not yet pushed to the sync server. */
    val dirty: Boolean = true,
)

data class SessionWithCount(
    @Embedded val session: SurveySession,
    val readingCount: Int,
)

@Entity(
    tableName = "wifi_readings",
    indices = [Index("sessionId"), Index("bssid"), Index(value = ["uid"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = SurveySession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class WifiReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val security: String = "",
    /** True when this sample came from the currently-connected network (sampled more often than scans). */
    val connected: Boolean = false,
    /** Permanent id shared by every phone and the sync server. */
    val uid: String = newUid(),
    /** True when changed on this phone and not yet pushed to the sync server. */
    val dirty: Boolean = true,
    /** Wi-Fi generation as Android reports it (ScanResult.WIFI_STANDARD_*; 0 = unknown). See [Wifi.generation]. */
    @ColumnInfo(defaultValue = "0") val standard: Int = 0,
    /** Channel width in MHz (20/40/80/160/320; 0 = unknown). */
    @ColumnInfo(defaultValue = "0") val widthMhz: Int = 0,
    /** Clients connected to the AP, from its BSS Load broadcast (null if it doesn't send one). */
    val stations: Int? = null,
    /** How busy the channel is, 0–100 %, from the AP's BSS Load broadcast. */
    val utilPct: Int? = null,
)

/** Your own name for a Wi-Fi radio, keyed by BSSID (lower-case aa:bb:cc:dd:ee:ff). Shared by all sites. */
@Entity(tableName = "ap_names")
data class ApName(
    @PrimaryKey val bssid: String,
    val name: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

/** Remembers a local delete so it can be pushed to the sync server. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["collection", "uid"])
data class SyncTombstone(
    val collection: String,
    val uid: String,
    val siteUid: String,
    val deletedAt: Long = System.currentTimeMillis(),
)

data class NetworkSummary(
    val bssid: String,
    val ssid: String,
    val bestRssi: Int,
    val samples: Int,
    val frequency: Int,
    /** Highest Wi-Fi generation / channel width seen, and the AP's peak client count and channel load (if it reports them). */
    val standard: Int = 0,
    val widthMhz: Int = 0,
    val stations: Int? = null,
    val utilPct: Int? = null,
)
