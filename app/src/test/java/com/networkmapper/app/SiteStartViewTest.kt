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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Each site's start view (where the map goes when the site is opened). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SiteStartViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbs = mutableListOf<AppDatabase>()

    private fun db() = AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
        .allowMainThreadQueries().build().also { dbs += it }

    @After
    fun tearDown() = dbs.forEach { it.close() }

    @Test
    fun startViewSyncsAndClears() = runBlocking {
        val server = FakeSyncServer()
        val dbA = db()
        val dbB = db()
        val a = Repository(context, dbA)
        val b = Repository(context, dbB)
        var ca = 0L
        var cb = 0L
        fun syncA() = runBlocking { SyncEngine(dbA, a, FakeTransport(server, "a"), null, { ca }, { ca = it }).run() }
        fun syncB() = runBlocking { SyncEngine(dbB, b, FakeTransport(server, "b"), null, { cb }, { cb = it }).run() }

        val id = a.insertSite(Site(name = "Campus"))
        a.updateSite(a.getSite(id)!!.copy(homeLat = 40.5, homeLon = -105.25, homeZoom = 17.5))
        syncA()
        syncB()
        val onB = b.getSites().first { it.name == "Campus" }
        assertEquals(GeoPt(40.5, -105.25), onB.home)
        assertEquals(17.5, onB.homeZoom!!, 0.0)

        b.updateSite(onB.copy(homeLat = null, homeLon = null, homeZoom = null))
        syncB()
        syncA()
        assertNull(a.getSite(id)!!.home)
    }

    @Test
    fun backupKeepsStartView() = runBlocking {
        val repo1 = Repository(context, db())
        val id = repo1.insertSite(Site(name = "Campus", homeLat = 40.5, homeLon = -105.25, homeZoom = 18.0))
        repo1.insertAsset(Asset(type = AssetType.SWITCH, name = "SW", latitude = 40.5, longitude = -105.25, siteId = id))
        val zip = File(context.filesDir, "home-backup.zip").also { Exporter.exportZip(context, repo1, setOf(id)).copyTo(it, overwrite = true) }
        val uri = Uri.fromFile(zip)
        val key = Importer.inspect(context, uri).sites.single().key

        val repo2 = Repository(context, db())
        Importer.importZip(context, repo2, uri, mapOf(key to ImportTarget.New("Campus")))
        val site = repo2.getSites().first { it.name == "Campus" }
        assertEquals(GeoPt(40.5, -105.25), site.home)
        assertEquals(18.0, site.homeZoom!!, 0.0)
    }
}
