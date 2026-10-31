package com.networkmapper.app.export

import android.content.Context
import android.net.Uri
import com.networkmapper.app.data.ApName
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.data.Repository
import com.networkmapper.app.data.Site
import com.networkmapper.app.data.SurveySession
import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.photo.PhotoStorage
import com.networkmapper.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** One site found in a backup file. [key] identifies it within that file. */
data class SiteInFile(
    val key: String,
    val name: String,
    val notes: String,
    val pins: Int,
    val paths: Int,
    val surveys: Int,
    val readings: Int,
)

data class ImportPreview(
    val sites: List<SiteInFile>,
    /** True for backups made before sites existed (everything is one pseudo-site). */
    val legacy: Boolean,
)

sealed interface ImportTarget {
    data class Existing(val siteId: Long) : ImportTarget
    data class New(val name: String) : ImportTarget
}

data class ImportResult(
    val sitesCreated: Int,
    val assets: Int,
    val paths: Int,
    val photos: Int,
    val sessions: Int,
    val readings: Int,
    val skippedDuplicates: Int,
) {
    val summary: String
        get() = buildString {
            if (sitesCreated > 0) append("Created $sitesCreated site(s). ")
            append("Imported $assets pins, $paths paths, $photos photos, $readings Wi-Fi readings")
            if (sessions > 0) append(" in $sessions survey(s)")
            if (skippedDuplicates > 0) append(". Skipped $skippedDuplicates item(s) already in the target site")
            append('.')
        }
}

/**
 * Reads backups written by [Exporter.exportZip] — the current site-aware format and the older
 * single-site format — and imports selected sites. Items already present in the target site are
 * skipped, so importing the same file twice is safe.
 */
object Importer {

    const val LEGACY_KEY = "legacy"
    private val imageExt = setOf("jpg", "jpeg", "png", "webp")
    private val textFiles = setOf("manifest.json", "network.geojson", "wifi_survey.csv", "photos.json", "ap_names.json")

    // ------------------------------------------------------------------ parsing

    private class Contents(
        val manifest: JSONObject?,
        val features: List<JSONObject>,
        val csvHeader: List<String>,
        val csvRows: List<List<String>>,
        val photoIndex: Map<String, PhotoMeta>,
        val apNames: Map<String, String>,
    ) {
        val legacy get() = manifest == null
        fun col(name: String) = csvHeader.indexOf(name)
    }

    private data class PhotoMeta(val lat: Double?, val lon: Double?, val accuracy: Float?, val caption: String, val takenAt: Long?)

    private fun readTexts(context: Context, uri: Uri): Map<String, String> {
        val texts = HashMap<String, String>()
        openZip(context, uri) { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.replace('\\', '/').substringAfterLast('/')
                if (base in textFiles && !entry.name.contains("photos/")) {
                    texts[base] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return texts
    }

    private fun parse(texts: Map<String, String>): Contents {
        val network = texts["network.geojson"]
            ?: error("This isn't a Network Mapper backup (network.geojson missing). Use a backup / Full data ZIP, not a KMZ.")
        val manifest = texts["manifest.json"]?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("format") == Exporter.FORMAT }
        val arr = JSONObject(network).optJSONArray("features") ?: JSONArray()
        val features = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val rows = texts["wifi_survey.csv"]?.let { parseCsv(it) }.orEmpty()
        val header = rows.firstOrNull()?.map { it.trim() }.orEmpty()
        val body = rows.drop(1).filter { it.size >= header.size - 1 && it.any { cell -> cell.isNotBlank() } }
        val apNames = texts["ap_names.json"]?.let { t ->
            runCatching { JSONArray(t).objects().associate { it.optString("bssid").lowercase() to it.optString("name") } }.getOrNull()
        }.orEmpty().filter { it.key.isNotBlank() && it.value.isNotBlank() }
        return Contents(manifest, features, header, body, texts["photos.json"]?.let { parsePhotoIndex(it) }.orEmpty(), apNames)
    }

    private fun featureKey(c: Contents, props: JSONObject): String =
        if (c.legacy || !props.has("siteId") || props.isNull("siteId")) LEGACY_KEY else props.optLong("siteId").toString()

    private fun rowKey(c: Contents, row: List<String>): String {
        val i = c.col("site_id")
        return if (c.legacy || i !in row.indices || row[i].isBlank()) LEGACY_KEY else row[i].trim()
    }

    // ------------------------------------------------------------------ inspect

    /** Lists the sites in a backup with their contents, without importing anything. */
    suspend fun inspect(context: Context, uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val c = parse(readTexts(context, uri))
        val pins = HashMap<String, Int>()
        val paths = HashMap<String, Int>()
        c.features.forEach { f ->
            val props = f.optJSONObject("properties") ?: return@forEach
            val key = featureKey(c, props)
            when (props.optString("featureType")) {
                "asset" -> pins[key] = (pins[key] ?: 0) + 1
                "path" -> paths[key] = (paths[key] ?: 0) + 1
            }
        }
        val readings = c.csvRows.groupingBy { rowKey(c, it) }.eachCount()
        val sites = if (c.legacy) {
            val sessionCol = c.col("session")
            listOf(
                SiteInFile(
                    LEGACY_KEY, "Imported data", "",
                    pins[LEGACY_KEY] ?: 0, paths[LEGACY_KEY] ?: 0,
                    c.csvRows.map { if (sessionCol in it.indices) it[sessionCol] else "" }.distinct().size,
                    readings[LEGACY_KEY] ?: 0,
                ),
            )
        } else {
            val sessionsBySite = c.manifest!!.optJSONArray("sessions").objects().groupingBy { it.optLong("siteId").toString() }.eachCount()
            c.manifest.optJSONArray("sites").objects().map { s ->
                val key = s.optLong("id").toString()
                SiteInFile(
                    key, s.optString("name").ifBlank { "Site $key" }, s.optString("notes"),
                    pins[key] ?: 0, paths[key] ?: 0, sessionsBySite[key] ?: 0, readings[key] ?: 0,
                )
            }
        }
        ImportPreview(sites, c.legacy)
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports the sites listed in [targets] (keys from [inspect]); every other site in the file is ignored.
     */
    suspend fun importZip(
        context: Context,
        repo: Repository,
        uri: Uri,
        targets: Map<String, ImportTarget>,
    ): ImportResult = withContext(Dispatchers.IO) {
        val c = parse(readTexts(context, uri))
        val manifestSites = c.manifest?.optJSONArray("sites").objects().associateBy { it.optLong("id").toString() }

        // Resolve target sites (creating new ones as needed).
        var sitesCreated = 0
        val siteFor = HashMap<String, Long>()
        for ((key, target) in targets) {
            siteFor[key] = when (target) {
                is ImportTarget.Existing -> target.siteId
                is ImportTarget.New -> {
                    sitesCreated++
                    repo.insertSite(Site(name = target.name.trim().ifEmpty { "Imported site" }, notes = manifestSites[key]?.optString("notes") ?: ""))
                }
            }
        }

        // Only extract the photos that belong to selected sites.
        val wantedPhotos = HashSet<String>()
        c.features.forEach { f ->
            val props = f.optJSONObject("properties") ?: return@forEach
            if (featureKey(c, props) !in siteFor) return@forEach
            val refs = props.optJSONArray("photos") ?: return@forEach
            for (i in 0 until refs.length()) wantedPhotos += refs.optString(i).substringAfterLast('/')
        }
        val photoNames = HashMap<String, String>()
        if (wantedPhotos.isNotEmpty()) {
            openZip(context, uri) { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val path = entry.name.replace('\\', '/')
                    val base = path.substringAfterLast('/')
                    if (path.startsWith("photos/") && base in wantedPhotos &&
                        base.substringAfterLast('.', "").lowercase() in imageExt
                    ) {
                        photoNames[base] = savePhoto(repo.photosDir, base, zip)
                    }
                }
            }
        }

        var skipped = 0
        var photoCount = 0
        val existingAssets = HashMap<Long, MutableList<Asset>>()
        val existingPaths = HashMap<Long, MutableList<NetPath>>()
        val assetIdMap = HashMap<String, Long>() // "<siteKey>:<oldId>" -> new id

        // ---- Pins ----
        var assetCount = 0
        for (f in c.features) {
            val props = f.optJSONObject("properties") ?: continue
            if (props.optString("featureType") != "asset") continue
            val key = featureKey(c, props)
            val siteId = siteFor[key] ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) continue
            val type = AssetType.entries.firstOrNull { it.name == props.optString("type") } ?: AssetType.OTHER
            val name = props.optString("name")
            val oldId = props.optLong("id", -1)
            val inSite = existingAssets.getOrPut(siteId) { repo.getAssetsForSite(siteId).toMutableList() }
            val dup = inSite.firstOrNull {
                it.type == type && it.name == name && abs(it.latitude - lat) < 1e-7 && abs(it.longitude - lon) < 1e-7
            }
            if (dup != null) {
                if (oldId >= 0) assetIdMap["$key:$oldId"] = dup.id
                skipped++
                continue
            }
            val asset = Asset(
                type = type,
                name = name,
                latitude = lat,
                longitude = lon,
                altitude = props.optNullableDouble("altitudeM"),
                accuracy = props.optNullableDouble("accuracyM")?.toFloat(),
                status = props.optString("status").ifBlank { "Active" },
                notes = props.optString("notes"),
                attributes = props.optJSONObject("attributes").toStringMap(),
                createdAt = Fmt.parseIso(props.optString("created")) ?: System.currentTimeMillis(),
                updatedAt = Fmt.parseIso(props.optString("updated")) ?: System.currentTimeMillis(),
                siteId = siteId,
            )
            val newId = repo.insertAsset(asset)
            inSite += asset.copy(id = newId)
            if (oldId >= 0) assetIdMap["$key:$oldId"] = newId
            assetCount++
            photoCount += attachPhotos(repo, props.optJSONArray("photos"), photoNames, c.photoIndex, assetId = newId, pathId = null, fallback = GeoPt(lat, lon))
        }

        // ---- Paths ----
        var pathCount = 0
        for (f in c.features) {
            val props = f.optJSONObject("properties") ?: continue
            if (props.optString("featureType") != "path") continue
            val key = featureKey(c, props)
            val siteId = siteFor[key] ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val points = (0 until coords.length()).mapNotNull { j ->
                val p = coords.optJSONArray(j) ?: return@mapNotNull null
                val lon = p.optDouble(0)
                val lat = p.optDouble(1)
                if (lat.isNaN() || lon.isNaN()) null else GeoPt(lat, lon)
            }
            if (points.size < 2) continue
            val name = props.optString("name")
            val inSite = existingPaths.getOrPut(siteId) { repo.getPathsForSite(siteId).toMutableList() }
            if (inSite.any { it.name == name && it.points.size == points.size && samePoints(it.points, points) }) {
                skipped++
                continue
            }
            val path = NetPath(
                kind = PathKind.entries.firstOrNull { it.name == props.optString("kind") } ?: PathKind.FIBER_UNDERGROUND,
                name = name,
                points = points,
                fiberCount = props.optNullableLong("fiberCount")?.toInt(),
                cableType = props.optString("cableType"),
                status = props.optString("status").ifBlank { "Active" },
                notes = props.optString("notes"),
                colorArgb = props.optString("color").takeIf { it.startsWith("#") }?.removePrefix("#")?.toLongOrNull(16),
                attributes = props.optJSONObject("attributes").toStringMap(),
                startAssetId = props.optNullableLong("startAssetId")?.let { assetIdMap["$key:$it"] },
                endAssetId = props.optNullableLong("endAssetId")?.let { assetIdMap["$key:$it"] },
                createdAt = Fmt.parseIso(props.optString("created")) ?: System.currentTimeMillis(),
                updatedAt = Fmt.parseIso(props.optString("updated")) ?: System.currentTimeMillis(),
                siteId = siteId,
            )
            val newId = repo.insertPath(path)
            inSite += path.copy(id = newId)
            pathCount++
            photoCount += attachPhotos(repo, props.optJSONArray("photos"), photoNames, c.photoIndex, assetId = null, pathId = newId, fallback = null)
        }

        // ---- Wi-Fi surveys ----
        var sessionCount = 0
        var readingCount = 0
        val existingSessions = HashMap<Long, List<SurveySession>>()
        suspend fun isDuplicateSession(siteId: Long, name: String, start: Long): Boolean =
            existingSessions.getOrPut(siteId) { repo.getSessionsForSite(siteId) }
                .any { it.name == name && abs(it.startedAt - start) < 1000 }

        fun List<String>.at(i: Int) = if (i in indices) this[i] else ""
        val cTime = c.col("time")
        val cTimeMs = c.col("time_ms")
        val cLat = c.col("latitude")
        val cLon = c.col("longitude")
        val cAcc = c.col("accuracy_m")
        val cSsid = c.col("ssid")
        val cBssid = c.col("bssid")
        val cRssi = c.col("rssi_dbm")
        val cFreq = c.col("frequency_mhz")
        val cSec = c.col("security")
        val cConn = c.col("connected")
        val cSession = c.col("session")
        val cSessionId = c.col("session_id")

        fun toReading(r: List<String>, sessionId: Long, fallbackTime: Long): WifiReading? {
            val lat = r.at(cLat).toDoubleOrNull() ?: return null
            val lon = r.at(cLon).toDoubleOrNull() ?: return null
            val rssi = r.at(cRssi).toIntOrNull() ?: return null
            return WifiReading(
                sessionId = sessionId,
                timestamp = r.at(cTimeMs).toLongOrNull() ?: Fmt.parseIso(r.at(cTime)) ?: fallbackTime,
                latitude = lat,
                longitude = lon,
                accuracy = r.at(cAcc).toFloatOrNull(),
                ssid = r.at(cSsid),
                bssid = r.at(cBssid).lowercase(),
                rssi = rssi,
                frequency = r.at(cFreq).toIntOrNull() ?: 0,
                security = r.at(cSec),
                connected = r.at(cConn).equals("true", ignoreCase = true),
            )
        }

        val manifestSessions = c.manifest?.optJSONArray("sessions").objects()
        if (!c.legacy && manifestSessions.isNotEmpty() && cSessionId >= 0) {
            val rowsBySession = c.csvRows.groupBy { it.at(cSessionId).trim() }
            for (s in manifestSessions) {
                val key = s.optLong("siteId").toString()
                val siteId = siteFor[key] ?: continue
                val name = s.optString("name")
                val start = s.optNullableLong("startedAtMs") ?: Fmt.parseIso(s.optString("startedAt")) ?: System.currentTimeMillis()
                if (isDuplicateSession(siteId, name, start)) {
                    skipped++
                    continue
                }
                val end = if (s.isNull("endedAt")) null else Fmt.parseIso(s.optString("endedAt"))
                val newSession = repo.insertSession(SurveySession(name = name, startedAt = start, endedAt = end, siteId = siteId))
                sessionCount++
                val readings = rowsBySession[s.optLong("id").toString()].orEmpty().mapNotNull { toReading(it, newSession, start) }
                readings.chunked(500).forEach { repo.insertReadings(it) }
                readingCount += readings.size
            }
        } else {
            // Older backups: group rows by session name; everything goes to the legacy target.
            val siteId = siteFor[LEGACY_KEY]
            if (siteId != null) {
                val bySession = c.csvRows.groupBy { it.at(cSession) }
                for ((sessionName, list) in bySession) {
                    val times = list.mapNotNull { Fmt.parseIso(it.at(cTime)) }
                    val start = times.minOrNull() ?: System.currentTimeMillis()
                    val name = sessionName.ifBlank { "Imported survey" }
                    if (isDuplicateSession(siteId, name, start)) {
                        skipped++
                        continue
                    }
                    val newSession = repo.insertSession(SurveySession(name = name, startedAt = start, endedAt = times.maxOrNull(), siteId = siteId))
                    sessionCount++
                    val readings = list.mapNotNull { toReading(it, newSession, start) }
                    readings.chunked(500).forEach { repo.insertReadings(it) }
                    readingCount += readings.size
                }
            }
        }

        // AP names are global; add the file's names without overwriting ones already on this phone.
        if (c.apNames.isNotEmpty()) {
            val have = repo.getApNames().mapTo(HashSet()) { it.bssid }
            repo.upsertApNames(c.apNames.filterKeys { it !in have }.map { (b, n) -> ApName(b, n) })
        }

        ImportResult(sitesCreated, assetCount, pathCount, photoCount, sessionCount, readingCount, skipped)
    }

    // ------------------------------------------------------------------ helpers

    private inline fun openZip(context: Context, uri: Uri, block: (ZipInputStream) -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the selected file")
        input.use { raw -> ZipInputStream(raw.buffered()).use(block) }
    }

    private fun parsePhotoIndex(text: String): Map<String, PhotoMeta> = try {
        JSONArray(text).objects().associate { o ->
            o.optString("file") to PhotoMeta(
                o.optNullableDouble("latitude"),
                o.optNullableDouble("longitude"),
                o.optNullableDouble("accuracyM")?.toFloat(),
                o.optString("caption"),
                o.optNullableLong("takenAtMs") ?: Fmt.parseIso(o.optString("takenAt")),
            )
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private suspend fun attachPhotos(
        repo: Repository,
        refs: JSONArray?,
        photoNames: Map<String, String>,
        index: Map<String, PhotoMeta>,
        assetId: Long?,
        pathId: Long?,
        fallback: GeoPt?,
    ): Int {
        if (refs == null) return 0
        var n = 0
        for (i in 0 until refs.length()) {
            val original = refs.optString(i).substringAfterLast('/')
            val local = photoNames[original] ?: continue
            val meta = index[original]
            val file = File(repo.photosDir, local)
            val exif = if (meta?.lat == null) PhotoStorage.readLocation(file) else null
            repo.insertPhoto(
                Photo(
                    assetId = assetId,
                    pathId = pathId,
                    fileName = local,
                    latitude = meta?.lat ?: exif?.first ?: fallback?.lat,
                    longitude = meta?.lon ?: exif?.second ?: fallback?.lon,
                    accuracy = meta?.accuracy,
                    caption = meta?.caption ?: "",
                    takenAt = meta?.takenAt ?: Fmt.parseFileStamp(original) ?: file.lastModified(),
                ),
            )
            n++
        }
        return n
    }

    /** Writes a photo from the zip, reusing an identical existing file or picking a free name. */
    private fun savePhoto(dir: File, name: String, input: ZipInputStream): String {
        val bytes = input.readBytes()
        var target = File(dir, name)
        if (target.exists()) {
            if (target.length() == bytes.size.toLong()) return name
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "jpg")
            var k = 1
            while (target.exists()) target = File(dir, "${stem}_$k.$ext").also { k++ }
        }
        target.writeBytes(bytes)
        return target.name
    }

    private fun samePoints(a: List<GeoPt>, b: List<GeoPt>) =
        a.zip(b).all { (p, q) -> abs(p.lat - q.lat) < 1e-7 && abs(p.lon - q.lon) < 1e-7 }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = optString(k)
        }
        return out
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }

    /** RFC 4180-ish CSV parser (quoted fields, doubled quotes, embedded commas/newlines). */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
