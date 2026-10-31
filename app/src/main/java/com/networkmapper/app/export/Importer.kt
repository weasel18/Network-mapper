package com.networkmapper.app.export

import android.content.Context
import android.net.Uri
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.data.Repository
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

data class ImportResult(
    val assets: Int,
    val paths: Int,
    val photos: Int,
    val sessions: Int,
    val readings: Int,
    val skippedDuplicates: Int,
) {
    val summary: String
        get() = buildString {
            append("Restored $assets pins, $paths paths, $photos photos, $readings Wi-Fi readings")
            if (sessions > 0) append(" in $sessions survey(s)")
            if (skippedDuplicates > 0) append(". Skipped $skippedDuplicates item(s) already on this device")
            append('.')
        }
}

/**
 * Restores the "Full data ZIP" produced by [Exporter.exportZip] (any app version): pins, paths,
 * photos and Wi-Fi survey readings. Items already present are skipped, so importing twice is safe.
 */
object Importer {

    private val imageExt = setOf("jpg", "jpeg", "png", "webp")

    suspend fun importZip(context: Context, repo: Repository, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val texts = HashMap<String, String>()
        val photoNames = HashMap<String, String>() // name in zip -> name on device

        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val path = entry.name.replace('\\', '/')
                    val base = path.substringAfterLast('/')
                    if (base.isBlank() || base.startsWith(".")) continue
                    when {
                        path.startsWith("photos/") && base.substringAfterLast('.', "").lowercase() in imageExt ->
                            photoNames[base] = savePhoto(repo.photosDir, base, zip)
                        base in setOf("network.geojson", "wifi_survey.csv", "photos.json") ->
                            texts[base] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }
        } ?: error("Couldn't open the selected file")

        val network = texts["network.geojson"]
            ?: error("This isn't a Network Mapper data ZIP (network.geojson missing). Use “Full data ZIP”, not KMZ.")

        val photoIndex = texts["photos.json"]?.let { parsePhotoIndex(it) } ?: emptyMap()
        var skipped = 0
        var photoCount = 0

        // ---- Pins ----
        val existingAssets = repo.getAllAssets()
        val assetIdMap = HashMap<Long, Long>()
        val features = JSONObject(network).optJSONArray("features") ?: JSONArray()
        var assetCount = 0
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            if (props.optString("featureType") != "asset") continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) continue
            val type = AssetType.entries.firstOrNull { it.name == props.optString("type") } ?: AssetType.OTHER
            val name = props.optString("name")
            val oldId = props.optLong("id", -1)
            val dup = existingAssets.firstOrNull {
                it.type == type && it.name == name && abs(it.latitude - lat) < 1e-7 && abs(it.longitude - lon) < 1e-7
            }
            if (dup != null) {
                if (oldId >= 0) assetIdMap[oldId] = dup.id
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
            )
            val newId = repo.insertAsset(asset)
            if (oldId >= 0) assetIdMap[oldId] = newId
            assetCount++
            photoCount += attachPhotos(repo, props.optJSONArray("photos"), photoNames, photoIndex, assetId = newId, pathId = null, fallback = GeoPt(lat, lon))
        }

        // ---- Paths ----
        val existingPaths = repo.getAllPaths()
        var pathCount = 0
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            if (props.optString("featureType") != "path") continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val points = (0 until coords.length()).mapNotNull { j ->
                val c = coords.optJSONArray(j) ?: return@mapNotNull null
                val lon = c.optDouble(0)
                val lat = c.optDouble(1)
                if (lat.isNaN() || lon.isNaN()) null else GeoPt(lat, lon)
            }
            if (points.size < 2) continue
            val name = props.optString("name")
            if (existingPaths.any { it.name == name && it.points.size == points.size && samePoints(it.points, points) }) {
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
                startAssetId = props.optNullableLong("startAssetId")?.let { assetIdMap[it] },
                endAssetId = props.optNullableLong("endAssetId")?.let { assetIdMap[it] },
                createdAt = Fmt.parseIso(props.optString("created")) ?: System.currentTimeMillis(),
                updatedAt = Fmt.parseIso(props.optString("updated")) ?: System.currentTimeMillis(),
            )
            val newId = repo.insertPath(path)
            pathCount++
            photoCount += attachPhotos(repo, props.optJSONArray("photos"), photoNames, photoIndex, assetId = null, pathId = newId, fallback = null)
        }

        // ---- Wi-Fi survey ----
        var sessionCount = 0
        var readingCount = 0
        texts["wifi_survey.csv"]?.let { csvText ->
            val rows = parseCsv(csvText)
            if (rows.size > 1) {
                val header = rows.first().map { it.trim() }
                fun col(name: String) = header.indexOf(name)
                val cSession = col("session")
                val cTime = col("time")
                val cLat = col("latitude")
                val cLon = col("longitude")
                val cAcc = col("accuracy_m")
                val cSsid = col("ssid")
                val cBssid = col("bssid")
                val cRssi = col("rssi_dbm")
                val cFreq = col("frequency_mhz")
                val cSec = col("security")
                val cConn = col("connected")
                fun List<String>.at(i: Int) = if (i in indices) this[i] else ""

                val existingSessions = repo.getSessions()
                val bySession = rows.drop(1).filter { it.size >= header.size - 1 }.groupBy { it.at(cSession) }
                for ((sessionName, list) in bySession) {
                    val times = list.mapNotNull { Fmt.parseIso(it.at(cTime)) }
                    val start = times.minOrNull() ?: System.currentTimeMillis()
                    val end = times.maxOrNull()
                    // Exports store times to the second, so compare at that resolution.
                    if (existingSessions.any { it.name == sessionName && abs(it.startedAt - start) < 1000 }) {
                        skipped++
                        continue
                    }
                    val sessionId = repo.insertSession(SurveySession(name = sessionName.ifBlank { "Imported survey" }, startedAt = start, endedAt = end))
                    sessionCount++
                    val readings = list.mapNotNull { r ->
                        val lat = r.at(cLat).toDoubleOrNull() ?: return@mapNotNull null
                        val lon = r.at(cLon).toDoubleOrNull() ?: return@mapNotNull null
                        val rssi = r.at(cRssi).toIntOrNull() ?: return@mapNotNull null
                        WifiReading(
                            sessionId = sessionId,
                            timestamp = Fmt.parseIso(r.at(cTime)) ?: start,
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
                    readings.chunked(500).forEach { repo.insertReadings(it) }
                    readingCount += readings.size
                }
            }
        }

        ImportResult(assetCount, pathCount, photoCount, sessionCount, readingCount, skipped)
    }

    private data class PhotoMeta(val lat: Double?, val lon: Double?, val accuracy: Float?, val caption: String, val takenAt: Long?)

    private fun parsePhotoIndex(text: String): Map<String, PhotoMeta> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("file") to PhotoMeta(
                o.optNullableDouble("latitude"),
                o.optNullableDouble("longitude"),
                o.optNullableDouble("accuracyM")?.toFloat(),
                o.optString("caption"),
                Fmt.parseIso(o.optString("takenAt")),
            )
        }.toMap()
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
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
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
                    else -> field.append(c)
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
