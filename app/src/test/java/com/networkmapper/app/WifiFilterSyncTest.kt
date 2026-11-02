package com.networkmapper.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Repository
import com.networkmapper.app.data.Site
import com.networkmapper.app.sync.SyncEngine
import com.networkmapper.app.wifi.WifiFilter
import com.networkmapper.app.wifi.WifiFilterStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each site has its own Wi-Fi filter, synced between phones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WifiFilterSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbs = mutableListOf<AppDatabase>()

    @After
    fun tearDown() = dbs.forEach { it.close() }

    private inner class Phone(name: String, private val server: FakeSyncServer) {
        val db = AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
            .allowMainThreadQueries().build().also { dbs += it }
        val repo = Repository(context, db)
        val filters = WifiFilterStore(context, "wifi_filter_test_$name").also {
            context.getSharedPreferences("wifi_filter_test_$name", Context.MODE_PRIVATE).edit().clear().commit()
        }
        private var cursor = 0L
        private val transport = FakeTransport(server, name)
        fun sync() = runBlocking { SyncEngine(db, repo, transport, null, { cursor }, { cursor = it }, filters).run() }
    }

    @Test
    fun filtersArePerSiteAndSync() = runBlocking {
        val server = FakeSyncServer()
        val a = Phone("a", server)
        val b = Phone("b", server)
        val campus = a.repo.insertSite(Site(name = "Campus"))
        val depot = a.repo.insertSite(Site(name = "Depot"))
        val campusUid = a.repo.getSite(campus)!!.uid
        val depotUid = a.repo.getSite(depot)!!.uid

        a.filters.update(campusUid) { it.copy(hiddenSsids = setOf("Neighbor-5G")) }
        assertFalse(a.filters.filterFor(campusUid).allows("Neighbor-5G", "11:22:33:44:55:66"))
        assertTrue(a.filters.filterFor(depotUid).allows("Neighbor-5G", "11:22:33:44:55:66")) // other site unaffected

        a.sync()
        b.sync()
        assertEquals(setOf("Neighbor-5G"), b.filters.filterFor(campusUid).hiddenSsids)
        assertTrue(b.filters.filterFor(depotUid).isEmpty)

        // Phone B edits the depot's filter; phone A gets it, and the campus filter stays as it was.
        b.filters.update(depotUid) { it.copy(onlySsids = setOf("Depot-WiFi")) }
        b.sync()
        a.sync()
        assertEquals(setOf("Depot-WiFi"), a.filters.filterFor(depotUid).onlySsids)
        assertEquals(setOf("Neighbor-5G"), a.filters.filterFor(campusUid).hiddenSsids)
    }

    @Test
    fun oldSharedFilterIsCopiedIntoSitesWithoutTheirOwn() = runBlocking {
        val server = FakeSyncServer()
        val a = Phone("c", server)
        val one = a.repo.getSite(a.repo.insertSite(Site(name = "One")))!!.uid
        val two = a.repo.getSite(a.repo.insertSite(Site(name = "Two")))!!.uid
        a.filters.update(two) { it.copy(hiddenSsids = setOf("Own")) }
        // A phone on the old version pushed the shared filter.
        server.push("old-phone", listOf(
            JSONObject().put("c", "settings").put("id", "wifi_filter").put("site", "").put("parent", "").put("u", 5L).put("d", false)
                .put("data", WifiFilter(hiddenBssids = setOf("aa:bb:cc:dd:ee:ff")).toJson()),
        ))
        a.sync()
        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), a.filters.filterFor(one).hiddenBssids)
        assertEquals(setOf("Own"), a.filters.filterFor(two).hiddenSsids) // its own filter wins
        assertTrue(a.filters.filterFor(two).hiddenBssids.isEmpty())
    }
}
