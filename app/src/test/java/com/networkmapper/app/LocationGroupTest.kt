package com.networkmapper.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.Repository
import com.networkmapper.app.data.Site
import com.networkmapper.app.export.Exporter
import com.networkmapper.app.export.ImportTarget
import com.networkmapper.app.export.Importer
import com.networkmapper.app.sync.SyncEngine
import com.networkmapper.app.ui.map.LocationChips
import com.networkmapper.app.util.Geo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Several devices in one closet / rack ("location" pins). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationGroupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbs = mutableListOf<AppDatabase>()

    private fun db() = AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
        .allowMainThreadQueries().build().also { dbs += it }

    @After
    fun tearDown() = dbs.forEach { it.close() }

    private suspend fun closetWithDevices(repo: Repository, siteId: Long): Asset {
        val locId = repo.insertAsset(Asset(type = AssetType.LOCATION, name = "IDF-2", latitude = 40.0, longitude = -105.0, siteId = siteId))
        val loc = repo.getAsset(locId)!!
        for ((type, name) in listOf(AssetType.ROUTER to "Core", AssetType.SWITCH to "SW-1", AssetType.SWITCH to "SW-2")) {
            val id = repo.insertAsset(Asset(type = type, name = name, latitude = 40.00002, longitude = -105.00001, siteId = siteId))
            repo.addToLocation(repo.getAsset(id)!!, loc)
        }
        return repo.getAsset(locId)!!
    }

    @Test
    fun devicesFollowTheirLocation_andAreReleasedWhenItIsDeleted() = runBlocking {
        val repo = Repository(context, db())
        val site = repo.insertSite(Site(name = "HQ"))
        val loc = closetWithDevices(repo, site)

        val members = repo.getGroupMembers(loc)
        assertEquals(listOf("Core", "SW-1", "SW-2"), members.map { it.name }.sorted())
        assertTrue(members.all { it.latitude == 40.0 && it.longitude == -105.0 })

        repo.moveLocation(loc, GeoPt(41.0, -104.0), 3f, null)
        assertTrue(repo.getGroupMembers(loc).all { it.latitude == 41.0 && it.longitude == -104.0 && it.accuracy == 3f })

        repo.deleteAsset(repo.getAsset(loc.id)!!)
        val left = repo.getAssetsForSite(site)
        assertEquals(3, left.size)
        assertTrue(left.all { it.groupUid.isEmpty() })
        // Spread a few metres apart so each can be tapped again.
        left.forEach { a -> assertEquals(4.0, Geo.distanceMeters(GeoPt(41.0, -104.0), GeoPt(a.latitude, a.longitude)), 0.1) }
        assertTrue(Geo.distanceMeters(GeoPt(left[0].latitude, left[0].longitude), GeoPt(left[1].latitude, left[1].longitude)) > 3.0)
    }

    @Test
    fun groupingSyncsToOtherPhones() = runBlocking {
        val server = FakeSyncServer()
        val dbA = db()
        val dbB = db()
        val a = Repository(context, dbA)
        val b = Repository(context, dbB)
        var cursorA = 0L
        var cursorB = 0L
        val site = a.insertSite(Site(name = "HQ"))
        val loc = closetWithDevices(a, site)
        SyncEngine(dbA, a, FakeTransport(server, "a"), null, { cursorA }, { cursorA = it }).run()
        SyncEngine(dbB, b, FakeTransport(server, "b"), null, { cursorB }, { cursorB = it }).run()

        val locB = b.getAssetByUid(loc.uid)!!
        assertEquals(AssetType.LOCATION, locB.type)
        assertEquals(3, b.getGroupMembers(locB).size)

        // Phone B takes one switch out; phone A sees it as a separate pin.
        val sw = b.getGroupMembers(locB).first { it.name == "SW-2" }
        b.removeFromLocation(sw)
        SyncEngine(dbB, b, FakeTransport(server, "b"), null, { cursorB }, { cursorB = it }).run()
        SyncEngine(dbA, a, FakeTransport(server, "a"), null, { cursorA }, { cursorA = it }).run()
        assertEquals(listOf("Core", "SW-1"), a.getGroupMembers(a.getAsset(loc.id)!!).map { it.name }.sorted())
        assertEquals("", a.getAssetByUid(sw.uid)!!.groupUid)
    }

    @Test
    fun backupKeepsDevicesInTheirLocation() = runBlocking {
        val repo1 = Repository(context, db())
        val site = repo1.insertSite(Site(name = "HQ"))
        closetWithDevices(repo1, site)
        val zip = File(context.filesDir, "loc-backup.zip").also { Exporter.exportZip(context, repo1, setOf(site)).copyTo(it, overwrite = true) }
        val uri = Uri.fromFile(zip)
        val key = Importer.inspect(context, uri).sites.single().key

        val repo2 = Repository(context, db())
        Importer.importZip(context, repo2, uri, mapOf(key to ImportTarget.New("HQ")))
        val newSite = repo2.getSites().first { it.name == "HQ" }
        val pins = repo2.getAssetsForSite(newSite.id)
        val loc = pins.single { it.type == AssetType.LOCATION }
        assertEquals(listOf("Core", "SW-1", "SW-2"), repo2.getGroupMembers(loc).map { it.name }.sorted())
    }

    @Test
    fun locationPinChips() {
        fun pin(t: AssetType) = Asset(type = t, name = t.name, latitude = 0.0, longitude = 0.0)
        assertEquals(listOf("▣"), LocationChips.of(emptyList()).map { it.text })
        assertEquals(
            listOf("R", "S×2", "A"),
            LocationChips.of(listOf(pin(AssetType.ACCESS_POINT), pin(AssetType.SWITCH), pin(AssetType.ROUTER), pin(AssetType.SWITCH))).map { it.text },
        )
        val many = listOf(AssetType.ROUTER, AssetType.SWITCH, AssetType.ACCESS_POINT, AssetType.FDH, AssetType.POLE, AssetType.POLE).map(::pin)
        assertEquals(listOf("R", "S", "A", "+3"), LocationChips.of(many).map { it.text })
    }
}
