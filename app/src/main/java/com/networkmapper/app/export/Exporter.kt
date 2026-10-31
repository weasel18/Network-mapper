package com.networkmapper.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.networkmapper.app.BuildConfig
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.data.Repository
import com.networkmapper.app.data.Site
import com.networkmapper.app.data.SurveySession
import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.photo.PhotoStorage
import com.networkmapper.app.util.Fmt
import com.networkmapper.app.util.Geo
import com.networkmapper.app.util.Wifi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Backup / export format (version 2):
 *  - manifest.json      sites + survey sessions + format version
 *  - network.geojson    pins (Point) and paths (LineString), every property incl. siteId and colour
 *  - network.kml        same data for Google Earth / GIS, one folder per site
 *  - wifi_survey.csv    one row per Wi-Fi sample (with site_id / session_id)
 *  - wifi_survey.geojson
 *  - photos.json        photo metadata (owner, where/when taken, caption)
 *  - photos/…           the image files
 */
object Exporter {

    const val FORMAT = "network-mapper"
    const val FORMAT_VERSION = 2

    private data class Snapshot(
        val sites: List<Site>,
        val assets: List<Asset>,
        val paths: List<NetPath>,
        val photos: List<Photo>,
        val sessions: List<SurveySession>,
        val readings: List<WifiReading>,
    )

    private suspend fun snapshot(repo: Repository, siteIds: Set<Long>, includeReadings: Boolean) = Snapshot(
        sites = repo.getSites().filter { it.id in siteIds },
        assets = repo.getAssetsForSites(siteIds),
        paths = repo.getPathsForSites(siteIds),
        photos = repo.getPhotosForSites(siteIds),
        sessions = repo.getSessionsForSites(siteIds),
        readings = if (includeReadings) repo.getReadingsForSites(siteIds) else emptyList(),
    )

    private fun exportDir(context: Context) = File(context.cacheDir, "exports").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() } // keep only the latest export
    }

    /** Suggested file name: the site's name for a single site, otherwise a generic backup name. */
    suspend fun suggestedName(repo: Repository, siteIds: Set<Long>, extension: String): String {
        val sites = repo.getSites().filter { it.id in siteIds }
        val base = if (sites.size == 1) {
            sites.first().name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "site" }
        } else {
            "network_mapper_backup"
        }
        return "${base}_${Fmt.fileStamp()}.$extension"
    }

    /** KMZ for Google Earth / most GIS tools: pins, paths and embedded photos. */
    suspend fun exportKmz(context: Context, repo: Repository, siteIds: Set<Long>): File = withContext(Dispatchers.IO) {
        val snap = snapshot(repo, siteIds, includeReadings = false)
        val out = File(exportDir(context), suggestedName(repo, siteIds, "kmz"))
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml"))
            zip.write(buildKml(snap, photoPrefix = "files/").toByteArray())
            zip.closeEntry()
            addPhotos(zip, repo, snap.photos, "files/")
        }
        out
    }

    /** Full backup of the chosen sites: everything needed to restore them, plus GIS-friendly files. */
    suspend fun exportZip(context: Context, repo: Repository, siteIds: Set<Long>): File = withContext(Dispatchers.IO) {
        val snap = snapshot(repo, siteIds, includeReadings = true)
        val out = File(exportDir(context), suggestedName(repo, siteIds, "zip"))
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun text(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            text("manifest.json", buildManifest(snap).toString(2))
            text("network.geojson", buildNetworkGeoJson(snap).toString(2))
            text("network.kml", buildKml(snap, photoPrefix = "photos/"))
            text("wifi_survey.csv", buildWifiCsv(snap))
            text("wifi_survey.geojson", buildWifiGeoJson(snap).toString())
            text("photos.json", buildPhotoIndex(snap).toString(2))
            addPhotos(zip, repo, snap.photos, "photos/")
        }
        out
    }

    /** Copies a finished export to a user-chosen document (Storage Access Framework). */
    suspend fun saveTo(context: Context, file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Couldn't open the destination file")
    }

    fun share(context: Context, file: File) {
        val uri = PhotoStorage.uriFor(context, file)
        val mime = if (file.name.endsWith(".kmz")) "application/vnd.google-earth.kmz" else "application/zip"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun addPhotos(zip: ZipOutputStream, repo: Repository, photos: List<Photo>, prefix: String) {
        photos.distinctBy { it.fileName }.forEach { p ->
            val f = repo.photoFile(p)
            if (!f.exists()) return@forEach
            zip.putNextEntry(ZipEntry(prefix + p.fileName))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    // ---------------- Manifest ----------------

    private fun buildManifest(s: Snapshot): JSONObject {
        val sites = JSONArray()
        s.sites.forEach { site ->
            sites.put(
                JSONObject()
                    .put("id", site.id)
                    .put("name", site.name)
                    .put("notes", site.notes)
                    .put("created", Fmt.iso(site.createdAt)),
            )
        }
        val sessions = JSONArray()
        s.sessions.forEach { ss ->
            sessions.put(
                JSONObject()
                    .put("id", ss.id)
                    .put("siteId", ss.siteId)
                    .put("name", ss.name)
                    .put("startedAt", Fmt.iso(ss.startedAt))
                    .put("startedAtMs", ss.startedAt)
                    .put("endedAt", ss.endedAt?.let { Fmt.iso(it) } ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("exportedAt", Fmt.iso(System.currentTimeMillis()))
            .put("sites", sites)
            .put("sessions", sessions)
    }

    // ---------------- GeoJSON ----------------

    private fun buildNetworkGeoJson(s: Snapshot): JSONObject {
        val photosByAsset = s.photos.filter { it.assetId != null }.groupBy { it.assetId }
        val photosByPath = s.photos.filter { it.pathId != null }.groupBy { it.pathId }
        val siteNames = s.sites.associate { it.id to it.name }
        val features = JSONArray()
        s.assets.forEach { a ->
            val props = JSONObject()
                .put("id", a.id)
                .put("featureType", "asset")
                .put("siteId", a.siteId)
                .put("siteName", siteNames[a.siteId] ?: "")
                .put("type", a.type.name)
                .put("typeLabel", a.type.label)
                .put("name", a.name)
                .put("status", a.status)
                .put("notes", a.notes)
                .put("accuracyM", a.accuracy ?: JSONObject.NULL)
                .put("altitudeM", a.altitude ?: JSONObject.NULL)
                .put("created", Fmt.iso(a.createdAt))
                .put("updated", Fmt.iso(a.updatedAt))
                .put("attributes", JSONObject(a.attributes))
                .put("photos", JSONArray(photosByAsset[a.id].orEmpty().map { "photos/" + it.fileName }))
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(a.longitude).put(a.latitude)))
                    .put("properties", props),
            )
        }
        s.paths.filter { it.points.size >= 2 }.forEach { p ->
            val coords = JSONArray()
            p.points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
            val props = JSONObject()
                .put("id", p.id)
                .put("featureType", "path")
                .put("siteId", p.siteId)
                .put("siteName", siteNames[p.siteId] ?: "")
                .put("kind", p.kind.name)
                .put("kindLabel", p.kind.label)
                .put("name", p.name)
                .put("fiberCount", p.fiberCount ?: JSONObject.NULL)
                .put("cableType", p.cableType)
                .put("status", p.status)
                .put("notes", p.notes)
                .put("lengthM", Geo.pathLengthMeters(p.points))
                .put("startAssetId", p.startAssetId ?: JSONObject.NULL)
                .put("endAssetId", p.endAssetId ?: JSONObject.NULL)
                .put("color", p.colorArgb?.let { String.format(Locale.US, "#%08X", it) } ?: JSONObject.NULL)
                .put("created", Fmt.iso(p.createdAt))
                .put("updated", Fmt.iso(p.updatedAt))
                .put("attributes", JSONObject(p.attributes))
                .put("photos", JSONArray(photosByPath[p.id].orEmpty().map { "photos/" + it.fileName }))
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                    .put("properties", props),
            )
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features)
    }

    private fun buildWifiGeoJson(s: Snapshot): JSONObject {
        val siteOfSession = s.sessions.associate { it.id to it.siteId }
        val features = JSONArray()
        s.readings.forEach { r ->
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(r.longitude).put(r.latitude)))
                    .put(
                        "properties",
                        JSONObject()
                            .put("siteId", siteOfSession[r.sessionId] ?: JSONObject.NULL)
                            .put("session", r.sessionId)
                            .put("time", Fmt.iso(r.timestamp))
                            .put("ssid", r.ssid)
                            .put("bssid", r.bssid)
                            .put("rssi", r.rssi)
                            .put("frequency", r.frequency)
                            .put("channel", Wifi.channel(r.frequency))
                            .put("connected", r.connected),
                    ),
            )
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features)
    }

    private fun buildPhotoIndex(s: Snapshot): JSONArray {
        val arr = JSONArray()
        s.photos.forEach { p ->
            arr.put(
                JSONObject()
                    .put("file", p.fileName)
                    .put("assetId", p.assetId ?: JSONObject.NULL)
                    .put("pathId", p.pathId ?: JSONObject.NULL)
                    .put("latitude", p.latitude ?: JSONObject.NULL)
                    .put("longitude", p.longitude ?: JSONObject.NULL)
                    .put("accuracyM", p.accuracy ?: JSONObject.NULL)
                    .put("caption", p.caption)
                    .put("takenAt", Fmt.iso(p.takenAt))
                    .put("takenAtMs", p.takenAt),
            )
        }
        return arr
    }

    // ---------------- CSV ----------------

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun buildWifiCsv(s: Snapshot): String {
        val sessions = s.sessions.associateBy { it.id }
        val siteNames = s.sites.associate { it.id to it.name }
        val sb = StringBuilder(
            "session,time,latitude,longitude,accuracy_m,ssid,bssid,rssi_dbm,frequency_mhz,channel,band,security,connected," +
                "site,site_id,session_id,time_ms\n",
        )
        s.readings.forEach { r ->
            val session = sessions[r.sessionId]
            sb.append(csv(session?.name ?: r.sessionId.toString())).append(',')
                .append(Fmt.iso(r.timestamp)).append(',')
                .append(String.format(Locale.US, "%.7f,%.7f", r.latitude, r.longitude)).append(',')
                .append(r.accuracy?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
                .append(csv(r.ssid)).append(',')
                .append(r.bssid).append(',')
                .append(r.rssi).append(',')
                .append(r.frequency).append(',')
                .append(Wifi.channel(r.frequency)).append(',')
                .append(Wifi.band(r.frequency)).append(',')
                .append(csv(r.security)).append(',')
                .append(r.connected).append(',')
                .append(csv(session?.let { siteNames[it.siteId] } ?: "")).append(',')
                .append(session?.siteId ?: "").append(',')
                .append(r.sessionId).append(',')
                .append(r.timestamp).append('\n')
        }
        return sb.toString()
    }

    // ---------------- KML ----------------

    private fun xml(v: String): String = v
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** ARGB -> KML aabbggrr. */
    private fun kmlColor(argb: Long): String {
        val a = (argb shr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return String.format(Locale.US, "%02x%02x%02x%02x", a, b, g, r)
    }

    private fun describe(rows: List<Pair<String, String>>, photos: List<Photo>, photoPrefix: String): String {
        val sb = StringBuilder("<table>")
        rows.filter { it.second.isNotBlank() }.forEach { (k, v) ->
            sb.append("<tr><td><b>").append(xml(k)).append("</b></td><td>").append(xml(v).replace("\n", "<br/>")).append("</td></tr>")
        }
        sb.append("</table>")
        photos.forEach { sb.append("<p><img src=\"").append(photoPrefix).append(it.fileName).append("\" width=\"400\"/></p>") }
        return "<![CDATA[$sb]]>"
    }

    private fun buildKml(s: Snapshot, photoPrefix: String): String {
        val photosByAsset = s.photos.filter { it.assetId != null }.groupBy { it.assetId }
        val photosByPath = s.photos.filter { it.pathId != null }.groupBy { it.pathId }
        val sb = StringBuilder()
        val title = if (s.sites.size == 1) s.sites.first().name else "Network map"
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>")
            .append(xml(title)).append(" ").append(Fmt.dateTime(System.currentTimeMillis())).append("</name>\n")

        AssetType.entries.forEach { t ->
            sb.append("<Style id=\"a_${t.name}\"><IconStyle><color>${kmlColor(t.colorArgb)}</color><scale>1.1</scale>")
                .append("<Icon><href>http://maps.google.com/mapfiles/kml/paddle/wht-blank.png</href></Icon></IconStyle></Style>\n")
        }
        PathKind.entries.forEach { k ->
            sb.append("<Style id=\"p_${k.name}\"><LineStyle><color>${kmlColor(k.colorArgb)}</color><width>4</width></LineStyle></Style>\n")
        }

        val multiSite = s.sites.size > 1
        for (site in s.sites) {
            if (multiSite) sb.append("<Folder><name>").append(xml(site.name)).append("</name>\n")
            s.assets.filter { it.siteId == site.id }.groupBy { it.type }.forEach { (type, assets) ->
                sb.append("<Folder><name>").append(xml(type.label)).append("</name>\n")
                assets.forEach { a ->
                    val rows = listOf(
                        "Site" to site.name,
                        "Type" to a.type.label,
                        "Status" to a.status,
                        "Coordinates" to Geo.formatCoord(a.latitude, a.longitude),
                        "Accuracy" to (a.accuracy?.let { "±${it.toInt()} m" } ?: ""),
                        "Notes" to a.notes,
                    ) + a.attributes.toList()
                    sb.append("<Placemark><name>").append(xml(a.name)).append("</name>")
                        .append("<styleUrl>#a_${a.type.name}</styleUrl>")
                        .append("<description>").append(describe(rows, photosByAsset[a.id].orEmpty(), photoPrefix)).append("</description>")
                        .append(String.format(Locale.US, "<Point><coordinates>%.7f,%.7f,0</coordinates></Point>", a.longitude, a.latitude))
                        .append("</Placemark>\n")
                }
                sb.append("</Folder>\n")
            }
            s.paths.filter { it.siteId == site.id && it.points.size >= 2 }.groupBy { it.kind }.forEach { (kind, paths) ->
                sb.append("<Folder><name>").append(xml(kind.label)).append("</name>\n")
                paths.forEach { p ->
                    val rows = listOf(
                        "Site" to site.name,
                        "Kind" to p.kind.label,
                        "Fiber count" to (p.fiberCount?.toString() ?: ""),
                        "Cable type" to p.cableType,
                        "Status" to p.status,
                        "Length" to Geo.formatDistance(Geo.pathLengthMeters(p.points)),
                        "Notes" to p.notes,
                    ) + p.attributes.toList()
                    sb.append("<Placemark><name>").append(xml(p.name)).append("</name>")
                    val color = p.colorArgb
                    if (color != null) {
                        sb.append("<Style><LineStyle><color>${kmlColor(color)}</color><width>4</width></LineStyle></Style>")
                    } else {
                        sb.append("<styleUrl>#p_${p.kind.name}</styleUrl>")
                    }
                    sb.append("<description>").append(describe(rows, photosByPath[p.id].orEmpty(), photoPrefix)).append("</description>")
                        .append("<LineString><tessellate>1</tessellate><coordinates>")
                    p.points.forEach { sb.append(String.format(Locale.US, "%.7f,%.7f,0 ", it.lon, it.lat)) }
                    sb.append("</coordinates></LineString></Placemark>\n")
                }
                sb.append("</Folder>\n")
            }
            if (multiSite) sb.append("</Folder>\n")
        }
        sb.append("</Document></kml>\n")
        return sb.toString()
    }
}
