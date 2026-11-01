package com.networkmapper.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.networkmapper.app.data.AppDatabase
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
import com.networkmapper.app.sync.SyncEngine
import com.networkmapper.app.sync.SyncTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** In-memory twin of server/server.py: last-writer-wins records, a sequence cursor and filters. */
class FakeSyncServer {
    private data class Rec(val c: String, val id: String, val site: String, val parent: String, val u: Long, val d: Boolean, val data: JSONObject?, val device: String, val seq: Long)

    private val records = LinkedHashMap<String, Rec>()
    private var seq = 0L
    val photos = HashMap<String, ByteArray>()

    fun push(device: String, changes: List<JSONObject>): JSONObject {
        var applied = 0
        for (ch in changes) {
            val key = ch.getString("c") + "/" + ch.getString("id")
            val u = ch.optLong("u")
            val old = records[key]
            if (old != null && (old.u > u || (old.u == u && old.device >= device))) continue
            val deleted = ch.optBoolean("d")
            records[key] = Rec(
                ch.getString("c"), ch.getString("id"),
                ch.optString("site").ifEmpty { old?.site ?: "" }, ch.optString("parent").ifEmpty { old?.parent ?: "" },
                u, deleted, if (deleted) null else ch.optJSONObject("data"), device, ++seq,
            )
            if (deleted && ch.getString("c") == "sessions") records.values.removeAll { it.c == "readings" && it.parent == ch.getString("id") }
            applied++
        }
        return JSONObject().put("applied", applied).put("seq", seq)
    }

    fun pull(device: String, since: Long, sites: Set<String>?, collections: List<String>?, limit: Int): JSONObject {
        val matching = records.values.filter { r ->
            r.seq > since && r.device != device &&
                (collections == null || r.c in collections) &&
                (sites == null || (r.c == "sites" && r.id in sites) || r.c == "ap_names" || (r.c != "sites" && r.site in sites))
        }.sortedBy { it.seq }
        val page = matching.take(limit)
        val more = matching.size > limit
        val arr = JSONArray()
        page.forEach { r ->
            arr.put(
                JSONObject().put("c", r.c).put("id", r.id).put("site", r.site).put("parent", r.parent)
                    .put("u", r.u).put("d", r.d).put("seq", r.seq).also { if (r.data != null) it.put("data", r.data) },
            )
        }
        return JSONObject().put("changes", arr).put("next", if (more) page.last().seq else maxOf(since, seq)).put("more", more)
    }

    fun count(c: String) = records.values.count { it.c == c && !it.d }
}

class FakeTransport(private val server: FakeSyncServer, private val device: String) : SyncTransport {
    override suspend fun push(changes: List<JSONObject>) = server.push(device, changes)
    override suspend fun pull(since: Long, sites: Set<String>?, collections: List<String>?, limit: Int) =
        server.pull(device, since, sites, collections, limit)
    override suspend fun sites() = JSONObject().put("sites", JSONArray())
    override suspend fun uploadPhoto(uid: String, bytes: ByteArray) {
        server.photos[uid] = bytes
    }
    override suspend fun downloadPhoto(uid: String) = server.photos[uid]
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbs = mutableListOf<AppDatabase>()

    private inner class Phone(name: String, server: FakeSyncServer, private val sites: Set<String>? = null) {
        val db = AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
            .allowMainThreadQueries().build().also { dbs += it }
        val repo = Repository(context, db)
        private var cursor = 0L
        private val transport = FakeTransport(server, name)
        fun sync() = runBlocking { SyncEngine(db, repo, transport, sites, { cursor }, { cursor = it }).run() }
    }

    @After
    fun tearDown() = dbs.forEach { it.close() }

    @Test
    fun twoPhonesConverge_editsDeletesAndConflicts() = runBlocking {
        val server = FakeSyncServer()
        val a = Phone("phone-a", server)
        val b = Phone("phone-b", server)

        // Phone A maps a site.
        val hq = a.repo.insertSite(Site(name = "HQ", notes = "Main St"))
        val sp = a.repo.insertAsset(Asset(type = AssetType.SPLICE, name = "SP-1", latitude = 40.0, longitude = -105.0, attributes = mapOf("Tray count" to "4"), siteId = hq))
        val hh = a.repo.insertAsset(Asset(type = AssetType.FIBER_JUNCTION, name = "HH-2", latitude = 40.001, longitude = -105.001, siteId = hq))
        a.repo.insertPath(
            NetPath(
                kind = PathKind.FIBER_UNDERGROUND, name = "SP-1 → HH-2", points = listOf(GeoPt(40.0, -105.0), GeoPt(40.001, -105.001)),
                fiberCount = 48, colorArgb = 0xFFC62828, startAssetId = sp, endAssetId = hh, siteId = hq,
            ),
        )
        val photoFile = File(a.repo.photosDir, "IMG_sync_test_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        a.repo.insertPhoto(Photo(assetId = sp, fileName = photoFile.name, caption = "Tray 1"))
        val session = a.repo.insertSession(SurveySession(name = "Walk", siteId = hq))
        a.repo.insertReadings((1..3).map { i ->
            WifiReading(sessionId = session, timestamp = 1000L * i, latitude = 40.0, longitude = -105.0, accuracy = 3f, ssid = "Corp", bssid = "aa:bb:cc:dd:ee:01", rssi = -50 - i, frequency = 5180)
        })
        a.repo.setApName(listOf("aa:bb:cc:dd:ee:01"), "AP-Lobby")

        val pushed = a.sync()
        assertTrue(pushed.pushed >= 9)
        assertEquals(1, pushed.photosUp)
        assertEquals(1, server.count("sites")) // only HQ: the untouched default "My site" isn't shared

        // Phone B receives everything with the same identities and links.
        b.sync()
        val bHq = b.repo.getSites().single { it.name == "HQ" }
        assertEquals("Main St", bHq.notes)
        val bAssets = b.repo.getAssetsForSite(bHq.id).associateBy { it.name }
        assertEquals("4", bAssets["SP-1"]!!.attributes["Tray count"])
        val bPath = b.repo.getPathsForSite(bHq.id).single()
        assertEquals(48, bPath.fiberCount)
        assertEquals(0xFFC62828, bPath.colorArgb)
        assertEquals(bAssets["SP-1"]!!.id, bPath.startAssetId)
        assertEquals(bAssets["HH-2"]!!.id, bPath.endAssetId)
        assertEquals("Tray 1", b.repo.getPhotosForSites(listOf(bHq.id)).single().caption)
        assertEquals(3, b.repo.getReadingsForSites(listOf(bHq.id)).size)
        assertEquals("AP-Lobby", b.repo.getApNames().single().name)
        assertEquals(a.repo.getAssetsForSite(hq).map { it.uid }.toSet(), bAssets.values.map { it.uid }.toSet())
        // Nothing was echoed back as a new change.
        assertEquals(0, b.sync().pushed)

        // B renames a pin; A gets it.
        b.repo.updateAsset(bAssets["SP-1"]!!.copy(name = "SP-1 (vault)"))
        b.sync()
        a.sync()
        assertEquals("SP-1 (vault)", a.repo.getAsset(sp)!!.name)

        // Conflict: both edit HH-2; the later edit wins on both phones.
        a.repo.updateAsset(a.repo.getAsset(hh)!!.copy(notes = "from A"))
        Thread.sleep(5)
        val bHh = b.repo.getAssetsForSite(bHq.id).single { it.name == "HH-2" }
        b.repo.updateAsset(bHh.copy(notes = "from B"))
        a.sync()
        b.sync()
        a.sync()
        assertEquals("from B", a.repo.getAsset(hh)!!.notes)
        assertEquals("from B", b.repo.getAsset(bHh.id)!!.notes)

        // A deletes the path and the survey; B follows.
        a.repo.deletePath(a.repo.getPathsForSite(hq).single())
        a.repo.deleteSession(session)
        a.sync()
        b.sync()
        assertTrue(b.repo.getPathsForSite(bHq.id).isEmpty())
        assertTrue(b.repo.getSessionsForSite(bHq.id).isEmpty())
        assertTrue(b.repo.getReadingsForSites(listOf(bHq.id)).isEmpty())

        // Un-naming an AP syncs too.
        a.repo.setApName(listOf("aa:bb:cc:dd:ee:01"), "")
        a.sync()
        b.sync()
        assertTrue(b.repo.getApNames().isEmpty())
    }

    @Test
    fun phoneSyncingOneSite_onlyGetsThatSite() = runBlocking {
        val server = FakeSyncServer()
        val a = Phone("phone-a", server)
        val one = a.repo.insertSite(Site(name = "Site One"))
        val two = a.repo.insertSite(Site(name = "Site Two"))
        a.repo.insertAsset(Asset(type = AssetType.SWITCH, name = "SW-1", latitude = 1.0, longitude = 1.0, siteId = one))
        a.repo.insertAsset(Asset(type = AssetType.SWITCH, name = "SW-2", latitude = 2.0, longitude = 2.0, siteId = two))
        a.sync()

        val oneUid = a.repo.getSite(one)!!.uid
        val c = Phone("phone-c", server, sites = setOf(oneUid))
        c.sync()
        val names = c.repo.getSites().map { it.name }.toSet()
        assertTrue("Site One" in names)
        assertTrue("Site Two" !in names)
        val cOne = c.repo.getSites().single { it.uid == oneUid }
        assertEquals(listOf("SW-1"), c.repo.getAssetsForSite(cOne.id).map { it.name })

        // Deleting the whole site on A removes it from C.
        a.repo.deleteSite(one)
        a.sync()
        c.sync()
        assertNull(c.repo.getSites().firstOrNull { it.uid == oneUid })
    }
}
