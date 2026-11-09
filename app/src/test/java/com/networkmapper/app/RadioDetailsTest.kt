package com.networkmapper.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Repository
import com.networkmapper.app.data.Site
import com.networkmapper.app.data.SurveySession
import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.sync.SyncEngine
import com.networkmapper.app.util.Wifi
import com.networkmapper.app.wifi.parseBssLoad
import com.networkmapper.app.wifi.widthMhz
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RadioDetailsTest {
    @Test
    fun bssLoadAndWidths() {
        // 12 stations (0x000C little-endian), utilisation 89/255 ≈ 35 %, admission capacity ignored.
        assertEquals(12 to 35, parseBssLoad(byteArrayOf(0x0C, 0x00, 89, 0x00, 0x00)))
        assertEquals(300 to 100, parseBssLoad(byteArrayOf(0x2C, 0x01, 0xFF.toByte())))
        assertNull(parseBssLoad(byteArrayOf(1, 2)))
        assertEquals(listOf(20, 40, 80, 160, 160, 320, 0), listOf(0, 1, 2, 3, 4, 5, 9).map { widthMhz(it) })
    }

    @Test
    fun detailsText() {
        assertEquals("Wi-Fi 6 • 80 MHz • 12 clients • 35% busy", Wifi.radioDetails(6, 80, 12, 35, 5180))
        assertEquals("Wi-Fi 6E • 160 MHz", Wifi.radioDetails(6, 160, null, null, 6115))
        assertEquals("Wi-Fi 4 • up to 1 client • up to 5% busy", Wifi.radioDetails(4, 0, 1, 5, 2437, peak = true))
        assertNull(Wifi.radioDetails(0, 0, null, null))
    }

    @Test
    fun extraFieldsSyncAndSummarise() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val server = FakeSyncServer()
        fun db() = AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)).allowMainThreadQueries().build()
        val dbA = db()
        val dbB = db()
        val a = Repository(context, dbA)
        val b = Repository(context, dbB)
        var ca = 0L
        var cb = 0L
        val site = a.insertSite(Site(name = "Resort"))
        val session = a.insertSession(SurveySession(name = "Walk", siteId = site))
        a.insertReadings(
            listOf(
                WifiReading(sessionId = session, timestamp = 1, latitude = 37.0, longitude = -112.0, accuracy = 3f, ssid = "Guest",
                    bssid = "aa:bb:cc:00:00:01", rssi = -60, frequency = 5180, standard = 6, widthMhz = 80, stations = 7, utilPct = 20),
                WifiReading(sessionId = session, timestamp = 2, latitude = 37.0001, longitude = -112.0, accuracy = 3f, ssid = "Guest",
                    bssid = "aa:bb:cc:00:00:01", rssi = -55, frequency = 5180, standard = 6, widthMhz = 80, stations = 12, utilPct = 35),
            ),
        )
        SyncEngine(dbA, a, FakeTransport(server, "a"), null, { ca }, { ca = it }).run()
        SyncEngine(dbB, b, FakeTransport(server, "b"), null, { cb }, { cb = it }).run()
        val siteB = b.getSites().first { it.name == "Resort" }
        val r = b.getReadingsForSites(listOf(siteB.id)).maxBy { it.rssi }
        assertEquals(listOf(6, 80, 12, 35), listOf(r.standard, r.widthMhz, r.stations, r.utilPct))
        val summary = b.getNetworks(siteB.id).single()
        assertEquals(listOf(6, 80, 12, 35), listOf(summary.standard, summary.widthMhz, summary.stations, summary.utilPct))
        dbA.close()
        dbB.close()
    }
}
