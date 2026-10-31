package com.networkmapper.app.data

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
) {
    FIBER_JUNCTION(
        "Fiber junction / Handhole", 0xFF1E88E5, "J",
        listOf("Box ID", "Box size", "Lid type", "Depth", "Conduits in/out"),
    ),
    SPLICE(
        "Splice enclosure", 0xFF8E24AA, "S",
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
    ),
    WIRELESS_BRIDGE(
        "Wireless bridge / PtP radio", 0xFFFB8C00, "B",
        listOf("Link name", "Model", "IP address", "MAC", "Frequency", "Channel width", "Azimuth (°)", "Tilt (°)", "Peer device", "Signal (dBm)"),
    ),
    SWITCH(
        "Switch", 0xFF00897B, "W",
        listOf("Hostname", "Model", "IP address", "MAC", "Port count", "Uplink", "VLANs", "PoE budget"),
    ),
    ROUTER(
        "Router / Gateway / OLT", 0xFF3949AB, "R",
        listOf("Hostname", "Model", "IP address", "WAN / Uplink", "Serial"),
    ),
    POLE(
        "Pole / Tower", 0xFF6D4C41, "P",
        listOf("Pole ID", "Owner", "Height", "Attachment height", "Make-ready notes"),
    ),
    BUILDING_ENTRY(
        "Building entry / Demarc", 0xFFD81B60, "D",
        listOf("Address", "Room / Location", "Contact", "Demarc type"),
    ),
    OTHER("Other / Photo pin", 0xFF757575, "?", emptyList()),
}

val ASSET_STATUSES = listOf("Active", "Planned", "Under construction", "Inactive", "Damaged", "Abandoned")

@Entity(tableName = "assets")
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
)

fun Asset.point(): GeoPt = GeoPt(latitude, longitude)

enum class PathKind(
    val label: String,
    val colorArgb: Long,
    val dashed: Boolean,
    val suggestedFields: List<String>,
) {
    FIBER_UNDERGROUND(
        "Fiber – underground", 0xFF1565C0, false,
        listOf("Cable ID", "Manufacturer", "Buffer tubes", "Conduit size", "Depth", "Install date"),
    ),
    FIBER_AERIAL(
        "Fiber – aerial", 0xFF6A1B9A, false,
        listOf("Cable ID", "Manufacturer", "Buffer tubes", "Strand / Messenger", "Install date"),
    ),
    CONDUIT(
        "Conduit / Duct (empty)", 0xFF757575, true,
        listOf("Conduit size", "Count", "Depth", "Pull tape"),
    ),
    WIRELESS_LINK(
        "Wireless link (PtP / PtMP)", 0xFFEF6C00, true,
        listOf("Frequency", "Channel width", "Capacity", "Signal (dBm)", "SSID"),
    ),
    COPPER("Copper / Ethernet", 0xFF2E7D32, false, listOf("Cable type", "Pairs", "Install date")),
}

@Entity(tableName = "paths")
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
)

@Entity(
    tableName = "photos",
    indices = [Index("assetId"), Index("pathId")],
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
)

@Entity(tableName = "survey_sessions")
data class SurveySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
)

data class SessionWithCount(
    @Embedded val session: SurveySession,
    val readingCount: Int,
)

@Entity(
    tableName = "wifi_readings",
    indices = [Index("sessionId"), Index("bssid")],
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
)

data class NetworkSummary(
    val bssid: String,
    val ssid: String,
    val bestRssi: Int,
    val samples: Int,
    val frequency: Int,
)
