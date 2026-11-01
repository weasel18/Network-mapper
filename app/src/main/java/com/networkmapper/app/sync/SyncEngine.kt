package com.networkmapper.app.sync

import com.networkmapper.app.data.ApName
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.AttributeCodec
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.data.PointCodec
import com.networkmapper.app.data.Repository
import com.networkmapper.app.data.Site
import com.networkmapper.app.data.SurveySession
import com.networkmapper.app.data.WifiReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val photosUp: Int = 0,
    val photosDown: Int = 0,
    val skipped: Int = 0,
) {
    operator fun plus(o: SyncResult) =
        SyncResult(pushed + o.pushed, pulled + o.pulled, photosUp + o.photosUp, photosDown + o.photosDown, skipped + o.skipped)

    val summary: String
        get() = buildString {
            append("Sent $pushed change(s)")
            if (photosUp > 0) append(" + $photosUp photo(s)")
            append(", received $pulled")
            if (photosDown > 0) append(" + $photosDown photo(s)")
            if (skipped > 0) append(" ($skipped couldn't be placed)")
        }
}

/**
 * Two-way sync of the local database with the sync server.
 *
 * Push: every record changed on this phone (dirty) in the synced sites, plus remembered deletes.
 * Pull: everyone else's changes since [cursor], applied with "latest edit wins": a remote change is
 * ignored if this phone has a newer unsent edit of the same record.
 *
 * @param siteFilter site uids to sync, or null for all sites.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val repo: Repository,
    private val transport: SyncTransport,
    private val siteFilter: Set<String>?,
    private val getCursor: () -> Long,
    private val setCursor: (Long) -> Unit,
) {
    private val sync = db.syncDao()
    private val batch = 300

    suspend fun run(): SyncResult = push() + pull()

    // =================================================================== push

    private suspend fun syncedSites(): List<Site> =
        db.siteDao().getAll().filter { siteFilter == null || it.uid in siteFilter }

    private fun change(c: String, id: String, site: String, parent: String, u: Long, data: JSONObject?): JSONObject =
        JSONObject().put("c", c).put("id", id).put("site", site).put("parent", parent).put("u", u)
            .put("d", data == null).also { if (data != null) it.put("data", data) }

    private fun JSONObject.putOpt2(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    suspend fun push(): SyncResult {
        val sites = syncedSites()
        val siteIds = sites.map { it.id }
        val siteUidById = db.syncDao().siteUids().associate { it.id to it.uid }
        var pushed = 0
        var photosUp = 0

        suspend fun send(changes: List<JSONObject>, onSent: suspend () -> Unit) {
            if (changes.isEmpty()) return
            changes.chunked(batch).forEach { transport.push(it) }
            onSent()
            pushed += changes.size
        }

        // Sites. Every fresh install has an empty "My site"; it's only shared once it has content or a
        // new name, so phones don't collect each other's empty defaults.
        val dirtySites = sync.dirtySites(siteIds).filterNot { s ->
            s.id == com.networkmapper.app.data.DEFAULT_SITE_ID && s.name == AppDatabase.DEFAULT_SITE_NAME &&
                db.assetDao().getForSite(s.id).isEmpty() && db.pathDao().getForSite(s.id).isEmpty() &&
                db.wifiDao().getSessionsForSite(s.id).isEmpty()
        }
        send(dirtySites.map { s ->
            change("sites", s.uid, s.uid, "", s.updatedAt, JSONObject().put("name", s.name).put("notes", s.notes).put("createdAt", s.createdAt))
        }) { dirtySites.forEach { sync.siteClean(it.uid, it.updatedAt) } }

        // Pins
        eachPage(next = { sync.dirtyAssets(siteIds, batch) }) { page ->
            send(page.map { a -> change("assets", a.uid, siteUidById[a.siteId] ?: "", "", a.updatedAt, assetData(a)) }) {
                page.forEach { sync.assetClean(it.uid, it.updatedAt) }
            }
        }

        // Paths (references to pins by uid)
        eachPage(next = { sync.dirtyPaths(siteIds, batch) }) { page ->
            val aUids = sync.assetUids().associate { it.id to it.uid }
            send(page.map { p -> change("paths", p.uid, siteUidById[p.siteId] ?: "", "", p.updatedAt, pathData(p, aUids)) }) {
                page.forEach { sync.pathClean(it.uid, it.updatedAt) }
            }
        }

        // Survey sessions
        eachPage(next = { sync.dirtySessions(siteIds, batch) }) { page ->
            send(page.map { s ->
                change(
                    "sessions", s.uid, siteUidById[s.siteId] ?: "", "", s.updatedAt,
                    JSONObject().put("name", s.name).put("startedAt", s.startedAt).putOpt2("endedAt", s.endedAt),
                )
            }) { page.forEach { sync.sessionClean(it.uid, it.updatedAt) } }
        }

        // Readings (immutable; can be many)
        val sessions = db.syncDao().sessionUids().associate { it.id to it.uid }
        val sessionSite = HashMap<Long, String>()
        eachPage(maxPages = 10_000, next = { sync.dirtyReadings(siteIds, 500) }) { page ->
            val changes = page.map { r ->
                val site = sessionSite.getOrPut(r.sessionId) {
                    sync.sessionByUid(sessions[r.sessionId] ?: "")?.let { siteUidById[it.siteId] } ?: ""
                }
                change("readings", r.uid, site, sessions[r.sessionId] ?: "", r.timestamp, readingData(r))
            }
            send(changes) { sync.readingsClean(page.map { it.uid }) }
        }

        // Photos: upload the file, then the record
        val pathUidById = sync.pathUids().associate { it.id to it.uid }
        val assetUids = sync.assetUids().associate { it.id to it.uid }
        eachPage(next = { sync.dirtyPhotos(siteIds, 50) }) { page ->
            val changes = ArrayList<JSONObject>()
            for (p in page) {
                val file = repo.photoFile(p)
                if (file.exists()) {
                    transport.uploadPhoto(p.uid, withContext(Dispatchers.IO) { file.readBytes() })
                    photosUp++
                }
                val ownerSite = p.assetId?.let { db.assetDao().get(it)?.siteId } ?: p.pathId?.let { db.pathDao().get(it)?.siteId }
                changes += change(
                    "photos", p.uid, ownerSite?.let { siteUidById[it] } ?: "",
                    p.assetId?.let { assetUids[it] } ?: p.pathId?.let { pathUidById[it] } ?: "",
                    p.updatedAt,
                    JSONObject()
                        .put("asset", p.assetId?.let { assetUids[it] } ?: "")
                        .put("path", p.pathId?.let { pathUidById[it] } ?: "")
                        .put("fileName", p.fileName)
                        .putOpt2("lat", p.latitude).putOpt2("lon", p.longitude).putOpt2("acc", p.accuracy)
                        .put("caption", p.caption).put("takenAt", p.takenAt),
                )
            }
            send(changes) { page.forEach { sync.photoClean(it.uid, it.updatedAt) } }
        }

        // AP names (global)
        val names = sync.dirtyApNames()
        send(names.map { n -> change("ap_names", n.bssid, "", "", n.updatedAt, JSONObject().put("name", n.name)) }) {
            names.forEach { sync.apNameClean(it.bssid, it.updatedAt) }
        }

        // Deletes
        val syncedSiteUids = sites.map { it.uid }.toSet()
        eachPage(next = { sync.tombstones(batch) }) { page ->
            val (mine, others) = page.partition { siteFilter == null || it.siteUid.isEmpty() || it.siteUid in syncedSiteUids }
            send(mine.map { t -> change(t.collection, t.uid, t.siteUid, "", t.deletedAt, null) }) {}
            // Deletes in sites this phone doesn't sync are dropped rather than retried forever.
            (mine + others).forEach { sync.clearTombstone(it.collection, it.uid) }
        }

        return SyncResult(pushed = pushed, photosUp = photosUp)
    }

    /**
     * Fetches a page of unsent records, hands it to [handle] (which sends it and marks it clean), and
     * repeats until nothing is left. [maxPages] guards against a page that never gets marked clean.
     */
    private suspend fun <T> eachPage(maxPages: Int = 1000, next: suspend () -> List<T>, handle: suspend (List<T>) -> Unit) {
        var n = 0
        while (n++ < maxPages) {
            val page = next()
            if (page.isEmpty()) return
            handle(page)
        }
    }

    private fun assetData(a: Asset) = JSONObject()
        .put("type", a.type.name).put("name", a.name)
        .put("lat", a.latitude).put("lon", a.longitude)
        .put("alt", a.altitude ?: JSONObject.NULL).put("acc", a.accuracy ?: JSONObject.NULL)
        .put("status", a.status).put("notes", a.notes)
        .put("attributes", JSONObject(AttributeCodec.encode(a.attributes)))
        .put("createdAt", a.createdAt)

    private fun pathData(p: NetPath, assetUids: Map<Long, String>) = JSONObject()
        .put("kind", p.kind.name).put("name", p.name)
        .put("points", PointCodec.encode(p.points))
        .put("fiberCount", p.fiberCount ?: JSONObject.NULL)
        .put("cableType", p.cableType).put("status", p.status).put("notes", p.notes)
        .put("color", p.colorArgb ?: JSONObject.NULL)
        .put("attributes", JSONObject(AttributeCodec.encode(p.attributes)))
        .put("startAsset", p.startAssetId?.let { assetUids[it] } ?: "")
        .put("endAsset", p.endAssetId?.let { assetUids[it] } ?: "")
        .put("createdAt", p.createdAt)

    private fun readingData(r: WifiReading) = JSONObject()
        .put("t", r.timestamp).put("lat", r.latitude).put("lon", r.longitude)
        .put("acc", r.accuracy ?: JSONObject.NULL)
        .put("ssid", r.ssid).put("bssid", r.bssid).put("rssi", r.rssi).put("freq", r.frequency)
        .put("sec", r.security).put("conn", r.connected)

    // =================================================================== pull

    private var pulled = 0
    private var photosDown = 0
    private var skipped = 0

    suspend fun pull(): SyncResult {
        pulled = 0
        photosDown = 0
        skipped = 0
        // 1) All site records first, so everything else has somewhere to go.
        var since = 0L
        while (true) {
            val page = transport.pull(since, siteFilter, listOf("sites"), 1000)
            val arr = page.optJSONArray("changes")
            for (i in 0 until (arr?.length() ?: 0)) applySite(arr!!.getJSONObject(i))
            since = page.optLong("next", since)
            if (!page.optBoolean("more")) break
        }
        // 2) Everything else since the cursor; children whose parent hasn't arrived yet are retried.
        val pending = ArrayList<JSONObject>()
        var cursor = getCursor()
        val rest = listOf("assets", "paths", "sessions", "readings", "photos", "ap_names")
        while (true) {
            val page = transport.pull(cursor, siteFilter, rest, 1000)
            val arr = page.optJSONArray("changes")
            val changes = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
            applyAll(changes, pending, final = false)
            cursor = page.optLong("next", cursor)
            setCursor(cursor)
            if (!page.optBoolean("more")) break
        }
        if (pending.isNotEmpty()) {
            val retry = ArrayList(pending)
            pending.clear()
            applyAll(retry, pending, final = true)
            skipped += pending.size
        }
        return SyncResult(pulled = pulled, photosDown = photosDown, skipped = skipped)
    }

    private val order = mapOf("assets" to 0, "paths" to 1, "sessions" to 2, "readings" to 3, "photos" to 4, "ap_names" to 5)

    private suspend fun applyAll(changes: List<JSONObject>, pending: MutableList<JSONObject>, final: Boolean) {
        val sorted = changes.sortedBy { order[it.optString("c")] ?: 9 }
        val readings = sorted.filter { it.optString("c") == "readings" }
        for (ch in sorted) {
            val ok = when (ch.optString("c")) {
                "assets" -> applyAsset(ch)
                "paths" -> applyPath(ch, final)
                "sessions" -> applySession(ch)
                "photos" -> applyPhoto(ch)
                "ap_names" -> applyApName(ch)
                else -> true
            }
            if (!ok) pending += ch
        }
        if (readings.isNotEmpty()) applyReadings(readings, pending)
    }

    /** Latest edit wins: skip if we already have this version or a newer unsent local edit. */
    private fun take(localExists: Boolean, localUpdated: Long, localDirty: Boolean, remoteU: Long): Boolean = when {
        !localExists -> true
        localDirty && localUpdated >= remoteU -> false
        !localDirty && localUpdated == remoteU -> false
        else -> true
    }

    private suspend fun siteIdFor(siteUid: String): Long? =
        if (siteUid.isEmpty()) null else sync.siteByUid(siteUid)?.id

    private suspend fun applySite(ch: JSONObject) {
        val uid = ch.getString("id")
        val u = ch.optLong("u")
        val local = sync.siteByUid(uid)
        if (ch.optBoolean("d")) {
            if (local != null && take(true, local.updatedAt, local.dirty, u)) {
                deleteSiteLocally(local)
                pulled++
            }
            return
        }
        if (!take(local != null, local?.updatedAt ?: 0, local?.dirty ?: false, u)) return
        val d = ch.optJSONObject("data") ?: JSONObject()
        if (local == null) {
            sync.insertSite(Site(name = d.optString("name"), notes = d.optString("notes"), createdAt = d.optLong("createdAt", u), updatedAt = u, uid = uid, dirty = false))
        } else {
            sync.updateSite(local.copy(name = d.optString("name"), notes = d.optString("notes"), updatedAt = u, dirty = false))
        }
        pulled++
    }

    private suspend fun deleteSiteLocally(site: Site) {
        val photos = db.photoDao().getForSites(listOf(site.id))
        withContext(Dispatchers.IO) { photos.forEach { repo.photoFile(it).delete() } }
        db.assetDao().deleteForSite(site.id)
        db.pathDao().deleteForSite(site.id)
        db.wifiDao().deleteSessionsForSite(site.id)
        db.siteDao().deleteById(site.id)
    }

    private suspend fun applyAsset(ch: JSONObject): Boolean {
        val uid = ch.getString("id")
        val u = ch.optLong("u")
        val local = sync.assetByUid(uid)
        if (ch.optBoolean("d")) {
            if (local != null && take(true, local.updatedAt, local.dirty, u)) {
                val photos = db.photoDao().getForAsset(local.id)
                withContext(Dispatchers.IO) { photos.forEach { repo.photoFile(it).delete() } }
                db.pathDao().clearStartAsset(local.id)
                db.pathDao().clearEndAsset(local.id)
                sync.deleteAsset(local.id)
                pulled++
            }
            return true
        }
        if (!take(local != null, local?.updatedAt ?: 0, local?.dirty ?: false, u)) return true
        val siteId = siteIdFor(ch.optString("site")) ?: return false
        val d = ch.optJSONObject("data") ?: return true
        val asset = Asset(
            id = local?.id ?: 0,
            type = AssetType.entries.firstOrNull { it.name == d.optString("type") } ?: AssetType.OTHER,
            name = d.optString("name"),
            latitude = d.optDouble("lat"),
            longitude = d.optDouble("lon"),
            altitude = d.optNullable("alt"),
            accuracy = d.optNullable("acc")?.toFloat(),
            status = d.optString("status").ifBlank { "Active" },
            notes = d.optString("notes"),
            attributes = AttributeCodec.decode(d.optJSONObject("attributes")?.toString() ?: ""),
            createdAt = d.optLong("createdAt", u),
            updatedAt = u,
            siteId = siteId,
            uid = uid,
            dirty = false,
        )
        if (local == null) sync.insertAsset(asset) else sync.updateAsset(asset)
        pulled++
        return true
    }

    private suspend fun applyPath(ch: JSONObject, final: Boolean): Boolean {
        val uid = ch.getString("id")
        val u = ch.optLong("u")
        val local = sync.pathByUid(uid)
        if (ch.optBoolean("d")) {
            if (local != null && take(true, local.updatedAt, local.dirty, u)) {
                val photos = db.photoDao().getForPath(local.id)
                withContext(Dispatchers.IO) { photos.forEach { repo.photoFile(it).delete() } }
                sync.deletePath(local.id)
                pulled++
            }
            return true
        }
        if (!take(local != null, local?.updatedAt ?: 0, local?.dirty ?: false, u)) return true
        val siteId = siteIdFor(ch.optString("site")) ?: return false
        val d = ch.optJSONObject("data") ?: return true
        suspend fun ref(key: String): Long? {
            val ref = d.optString(key)
            return if (ref.isEmpty()) null else sync.assetByUid(ref)?.id
        }
        val start = ref("startAsset")
        val end = ref("endAsset")
        // Wait for referenced pins that haven't arrived yet (unless this is the last try).
        if (!final && ((d.optString("startAsset").isNotEmpty() && start == null) || (d.optString("endAsset").isNotEmpty() && end == null))) {
            return false
        }
        val path = NetPath(
            id = local?.id ?: 0,
            kind = PathKind.entries.firstOrNull { it.name == d.optString("kind") } ?: PathKind.FIBER_UNDERGROUND,
            name = d.optString("name"),
            points = PointCodec.decode(d.optString("points")),
            fiberCount = d.optNullable("fiberCount")?.toInt(),
            cableType = d.optString("cableType"),
            status = d.optString("status").ifBlank { "Active" },
            notes = d.optString("notes"),
            colorArgb = d.optNullable("color")?.toLong(),
            attributes = AttributeCodec.decode(d.optJSONObject("attributes")?.toString() ?: ""),
            startAssetId = start,
            endAssetId = end,
            createdAt = d.optLong("createdAt", u),
            updatedAt = u,
            siteId = siteId,
            uid = uid,
            dirty = false,
        )
        if (local == null) sync.insertPath(path) else sync.updatePath(path)
        pulled++
        return true
    }

    private suspend fun applySession(ch: JSONObject): Boolean {
        val uid = ch.getString("id")
        val u = ch.optLong("u")
        val local = sync.sessionByUid(uid)
        if (ch.optBoolean("d")) {
            if (local != null && take(true, local.updatedAt, local.dirty, u)) {
                sync.deleteSession(local.id) // readings cascade
                pulled++
            }
            return true
        }
        if (!take(local != null, local?.updatedAt ?: 0, local?.dirty ?: false, u)) return true
        val siteId = siteIdFor(ch.optString("site")) ?: return false
        val d = ch.optJSONObject("data") ?: return true
        val s = SurveySession(
            id = local?.id ?: 0,
            name = d.optString("name"),
            startedAt = d.optLong("startedAt", u),
            endedAt = d.optNullable("endedAt")?.toLong(),
            siteId = siteId,
            updatedAt = u,
            uid = uid,
            dirty = false,
        )
        if (local == null) sync.insertSession(s) else sync.updateSession(s)
        pulled++
        return true
    }

    private suspend fun applyReadings(changes: List<JSONObject>, pending: MutableList<JSONObject>) {
        val live = changes.filter { !it.optBoolean("d") }
        if (live.isEmpty()) return
        val existing = live.map { it.getString("id") }.chunked(500).flatMap { sync.existingReadingUids(it) }.toHashSet()
        val sessionIds = HashMap<String, Long?>()
        val rows = ArrayList<WifiReading>()
        for (ch in live) {
            val uid = ch.getString("id")
            if (uid in existing) continue
            val parent = ch.optString("parent")
            val sessionId = sessionIds.getOrPut(parent) { sync.sessionByUid(parent)?.id }
            if (sessionId == null) {
                pending += ch
                continue
            }
            val d = ch.optJSONObject("data") ?: continue
            rows += WifiReading(
                sessionId = sessionId,
                timestamp = d.optLong("t", ch.optLong("u")),
                latitude = d.optDouble("lat"),
                longitude = d.optDouble("lon"),
                accuracy = d.optNullable("acc")?.toFloat(),
                ssid = d.optString("ssid"),
                bssid = d.optString("bssid"),
                rssi = d.optInt("rssi"),
                frequency = d.optInt("freq"),
                security = d.optString("sec"),
                connected = d.optBoolean("conn"),
                uid = uid,
                dirty = false,
            )
        }
        rows.chunked(500).forEach { sync.insertReadings(it) }
        pulled += rows.size
    }

    private suspend fun applyPhoto(ch: JSONObject): Boolean {
        val uid = ch.getString("id")
        val u = ch.optLong("u")
        val local = sync.photoByUid(uid)
        if (ch.optBoolean("d")) {
            if (local != null && take(true, local.updatedAt, local.dirty, u)) {
                withContext(Dispatchers.IO) { repo.photoFile(local).delete() }
                sync.deletePhoto(local.id)
                pulled++
            }
            return true
        }
        if (!take(local != null, local?.updatedAt ?: 0, local?.dirty ?: false, u)) return true
        val d = ch.optJSONObject("data") ?: return true
        val assetId = d.optString("asset").takeIf { it.isNotEmpty() }?.let { sync.assetByUid(it)?.id }
        val pathId = d.optString("path").takeIf { it.isNotEmpty() }?.let { sync.pathByUid(it)?.id }
        if (assetId == null && pathId == null) return false
        var fileName = local?.fileName ?: d.optString("fileName").ifBlank { "$uid.jpg" }
        val file = File(repo.photosDir, fileName)
        if (local == null && !file.exists()) {
            val bytes = transport.downloadPhoto(uid)
            if (bytes != null) {
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                photosDown++
            }
        } else if (local == null && file.exists()) {
            // A different photo already uses this name on this phone; keep both.
            fileName = "$uid.jpg"
            val bytes = transport.downloadPhoto(uid)
            if (bytes != null) {
                withContext(Dispatchers.IO) { File(repo.photosDir, fileName).writeBytes(bytes) }
                photosDown++
            }
        }
        val photo = Photo(
            id = local?.id ?: 0,
            assetId = assetId,
            pathId = pathId,
            fileName = fileName,
            latitude = d.optNullable("lat"),
            longitude = d.optNullable("lon"),
            accuracy = d.optNullable("acc")?.toFloat(),
            caption = d.optString("caption"),
            takenAt = d.optLong("takenAt", u),
            updatedAt = u,
            uid = uid,
            dirty = false,
        )
        if (local == null) sync.insertPhoto(photo) else sync.updatePhoto(photo)
        pulled++
        return true
    }

    private suspend fun applyApName(ch: JSONObject): Boolean {
        val bssid = ch.getString("id").lowercase()
        val u = ch.optLong("u")
        val local = sync.apName(bssid)
        if (ch.optBoolean("d")) {
            if (local != null && take(true, local.updatedAt, local.dirty, u)) {
                sync.deleteApName(bssid)
                pulled++
            }
            return true
        }
        if (!take(local != null, local?.updatedAt ?: 0, local?.dirty ?: false, u)) return true
        val name = ch.optJSONObject("data")?.optString("name").orEmpty()
        if (name.isBlank()) return true
        sync.upsertApName(ApName(bssid, name, u, dirty = false))
        pulled++
        return true
    }

    private fun JSONObject.optNullable(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
}
