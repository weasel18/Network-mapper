package com.networkmapper.app

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.DEFAULT_SITE_ID
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.data.Repository
import com.networkmapper.app.data.Site
import com.networkmapper.app.data.SurveySession
import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.export.Exporter
import com.networkmapper.app.export.ImportTarget
import com.networkmapper.app.export.Importer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val open = mutableListOf<AppDatabase>()

    private fun memoryDb(): AppDatabase =
        AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    @After
    fun tearDown() {
        open.forEach { it.close() }
    }

    // ---------------------------------------------------------------- migration

    /** Schema exactly as version 1 (before sites) created it. */
    private val v1Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `assets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitude` REAL, `accuracy` REAL, " +
            "`status` TEXT NOT NULL, `notes` TEXT NOT NULL, `attributes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `paths` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `points` TEXT NOT NULL, `fiberCount` INTEGER, `cableType` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, `notes` TEXT NOT NULL, `colorArgb` INTEGER, `attributes` TEXT NOT NULL, " +
            "`startAssetId` INTEGER, `endAssetId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assetId` INTEGER, " +
            "`pathId` INTEGER, `fileName` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `accuracy` REAL, " +
            "`caption` TEXT NOT NULL, `takenAt` INTEGER NOT NULL, " +
            "FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`pathId`) REFERENCES `paths`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_photos_assetId` ON `photos` (`assetId`)",
        "CREATE INDEX IF NOT EXISTS `index_photos_pathId` ON `photos` (`pathId`)",
        "CREATE TABLE IF NOT EXISTS `survey_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER)",
        "CREATE TABLE IF NOT EXISTS `wifi_readings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sessionId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
            "`accuracy` REAL, `ssid` TEXT NOT NULL, `bssid` TEXT NOT NULL, `rssi` INTEGER NOT NULL, `frequency` INTEGER NOT NULL, " +
            "`security` TEXT NOT NULL, `connected` INTEGER NOT NULL, " +
            "FOREIGN KEY(`sessionId`) REFERENCES `survey_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_wifi_readings_sessionId` ON `wifi_readings` (`sessionId`)",
        "CREATE INDEX IF NOT EXISTS `index_wifi_readings_bssid` ON `wifi_readings` (`bssid`)",
    )

    @Test
    fun migration_1_to_2_movesExistingDataIntoDefaultSite() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            v1Schema.forEach { raw.execSQL(it) }
            raw.execSQL(
                "INSERT INTO assets (id, type, name, latitude, longitude, status, notes, attributes, createdAt, updatedAt) " +
                    "VALUES (7, 'SPLICE', 'SP-7', 40.5, -105.5, 'Active', 'in vault', '{\"Tray count\":\"6\"}', 1, 2)",
            )
            raw.execSQL(
                "INSERT INTO paths (id, kind, name, points, fiberCount, cableType, status, notes, attributes, startAssetId, createdAt, updatedAt) " +
                    "VALUES (3, 'FIBER_AERIAL', 'Run A', '40.5,-105.5;40.6,-105.6', 24, 'ADSS', 'Active', '', '{}', 7, 1, 2)",
            )
            raw.execSQL("INSERT INTO photos (assetId, fileName, caption, takenAt) VALUES (7, 'IMG_1.jpg', 'lid', 5)")
            raw.execSQL("INSERT INTO survey_sessions (id, name, startedAt) VALUES (2, 'Walk 1', 100)")
            raw.execSQL(
                "INSERT INTO wifi_readings (sessionId, timestamp, latitude, longitude, ssid, bssid, rssi, frequency, security, connected) " +
                    "VALUES (2, 101, 40.5, -105.5, 'Corp', 'aa:bb:cc:dd:ee:ff', -60, 2437, '', 0)",
            )
            raw.version = 1
        }

        val db = AppDatabase.configure(Room.databaseBuilder(context, AppDatabase::class.java, name))
            .allowMainThreadQueries()
            .build()
            .also { open += it }
        val repo = Repository(context, db)

        assertEquals(listOf(AppDatabase.DEFAULT_SITE_NAME), repo.getSites().map { it.name })
        val asset = repo.getAssetsForSite(DEFAULT_SITE_ID).single()
        assertEquals("SP-7", asset.name)
        assertEquals("6", asset.attributes["Tray count"])
        val path = repo.getPathsForSite(DEFAULT_SITE_ID).single()
        assertEquals(listOf(GeoPt(40.5, -105.5), GeoPt(40.6, -105.6)), path.points)
        assertEquals(7L, path.startAssetId)
        assertEquals("lid", repo.getPhotosForSites(listOf(DEFAULT_SITE_ID)).single().caption)
        assertEquals("Walk 1", repo.getSessionsForSite(DEFAULT_SITE_ID).single().name)
        assertEquals(-60, repo.getReadingsForSites(listOf(DEFAULT_SITE_ID)).single().rssi)

        // New rows still work after migrating.
        val second = repo.insertSite(Site(name = "Second"))
        repo.insertAsset(Asset(type = AssetType.SWITCH, name = "SW", latitude = 1.0, longitude = 2.0, siteId = second))
        assertEquals(1, repo.getAssetsForSite(second).size)
        assertEquals(1, repo.getAssetsForSite(DEFAULT_SITE_ID).size)
    }

    // ---------------------------------------------------------------- export / import

    @Test
    fun exportImport_roundTripsSelectedSitesAndSkipsDuplicates() = runBlocking {
        val repo1 = Repository(context, memoryDb())
        val alpha = repo1.insertSite(Site(name = "Alpha", notes = "Main St"))
        val bravo = repo1.insertSite(Site(name = "Bravo"))
        val sp = repo1.insertAsset(
            Asset(
                type = AssetType.SPLICE, name = "SP-1", latitude = 40.0, longitude = -105.0, accuracy = 3.5f,
                attributes = mapOf("Tray count" to "4", "Notes, with comma" to "x"), siteId = alpha,
            ),
        )
        val hh = repo1.insertAsset(Asset(type = AssetType.FIBER_JUNCTION, name = "HH-2", latitude = 40.001, longitude = -105.001, siteId = alpha))
        repo1.insertPath(
            NetPath(
                kind = PathKind.FIBER_UNDERGROUND, name = "SP-1 → HH-2",
                points = listOf(GeoPt(40.0, -105.0), GeoPt(40.0005, -105.0007), GeoPt(40.001, -105.001)),
                fiberCount = 48, cableType = "Loose tube", colorArgb = 0xFFC62828,
                startAssetId = sp, endAssetId = hh, siteId = alpha,
            ),
        )
        val photo = File(repo1.photosDir, "IMG_20261031_120000_1111.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        repo1.insertPhoto(Photo(assetId = sp, fileName = photo.name, latitude = 40.0, longitude = -105.0, caption = "Tray 1", takenAt = 1_700_000_000_999))
        val session = repo1.insertSession(SurveySession(name = "Walk", startedAt = 1_700_000_000_123, endedAt = 1_700_000_100_000, siteId = alpha))
        repo1.insertReadings(
            listOf(
                WifiReading(
                    sessionId = session, timestamp = 1_700_000_001_456, latitude = 40.0, longitude = -105.0, accuracy = 3f,
                    ssid = "Corp, \"Main\"", bssid = "aa:bb:cc:dd:ee:01", rssi = -55, frequency = 5180, security = "[WPA2]",
                ),
            ),
        )
        repo1.insertAsset(Asset(type = AssetType.ACCESS_POINT, name = "AP-B", latitude = 41.0, longitude = -104.0, siteId = bravo))

        val exported = Exporter.exportZip(context, repo1, setOf(alpha, bravo))
        val backup = File(context.filesDir, "backup.zip").also { exported.copyTo(it, overwrite = true) }
        val uri = Uri.fromFile(backup)

        val preview = Importer.inspect(context, uri)
        assertFalse(preview.legacy)
        assertEquals(setOf("Alpha", "Bravo"), preview.sites.map { it.name }.toSet())
        val alphaInFile = preview.sites.first { it.name == "Alpha" }
        assertEquals(2, alphaInFile.pins)
        assertEquals(1, alphaInFile.paths)
        assertEquals(1, alphaInFile.surveys)
        assertEquals(1, alphaInFile.readings)

        // Import only Alpha, as a new site, into a different (empty) database.
        val repo2 = Repository(context, memoryDb())
        val result = Importer.importZip(context, repo2, uri, mapOf(alphaInFile.key to ImportTarget.New("Alpha")))
        assertEquals(1, result.sitesCreated)
        assertEquals(2, result.assets)
        assertEquals(1, result.paths)
        assertEquals(1, result.photos)
        assertEquals(1, result.sessions)
        assertEquals(1, result.readings)
        assertTrue(repo2.getSites().none { it.name == "Bravo" })

        val site = repo2.getSites().first { it.name == "Alpha" }
        assertEquals("Main St", site.notes)
        val assets = repo2.getAssetsForSite(site.id).associateBy { it.id }
        val path = repo2.getPathsForSite(site.id).single()
        assertEquals(48, path.fiberCount)
        assertEquals("Loose tube", path.cableType)
        assertEquals(0xFFC62828, path.colorArgb)
        assertEquals(3, path.points.size)
        assertEquals("SP-1", assets[path.startAssetId]?.name)
        assertEquals("HH-2", assets[path.endAssetId]?.name)
        val splice = assets.values.first { it.name == "SP-1" }
        assertEquals("4", splice.attributes["Tray count"])
        assertEquals("x", splice.attributes["Notes, with comma"])
        assertEquals(3.5f, splice.accuracy!!, 0.001f)
        val importedPhoto = repo2.getPhotosForSites(listOf(site.id)).single()
        assertEquals(splice.id, importedPhoto.assetId)
        assertEquals("Tray 1", importedPhoto.caption)
        assertEquals(1_700_000_000_999, importedPhoto.takenAt)
        val importedSession = repo2.getSessionsForSite(site.id).single()
        assertEquals(1_700_000_000_123, importedSession.startedAt)
        val reading = repo2.getReadingsForSites(listOf(site.id)).single()
        assertEquals("Corp, \"Main\"", reading.ssid)
        assertEquals(1_700_000_001_456, reading.timestamp)
        assertEquals(-55, reading.rssi)

        // Importing the same site again into it adds nothing.
        val again = Importer.importZip(context, repo2, uri, mapOf(alphaInFile.key to ImportTarget.Existing(site.id)))
        assertEquals(0, again.assets + again.paths + again.sessions + again.readings)
        assertTrue(again.skippedDuplicates >= 4)
        assertEquals(2, repo2.getAssetsForSite(site.id).size)
    }

    @Test
    fun legacyBackup_importsIntoChosenSite() = runBlocking {
        // A "Full data ZIP" as written before sites existed: no manifest, no siteId, old CSV columns.
        val legacy = File(context.filesDir, "legacy.zip")
        ZipOutputStream(legacy.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put(
                "network.geojson",
                """{"type":"FeatureCollection","features":[
                  {"type":"Feature","geometry":{"type":"Point","coordinates":[-105.1,40.1]},
                   "properties":{"id":1,"featureType":"asset","type":"ACCESS_POINT","name":"AP-1","status":"Active","notes":"",
                   "accuracyM":4,"altitudeM":null,"created":"2026-10-31T12:00:00Z","updated":"2026-10-31T12:00:00Z",
                   "attributes":{"SSID":"Corp"},"photos":["photos/IMG_20261031_120500_2222.jpg"]}},
                  {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-105.1,40.1],[-105.2,40.2]]},
                   "properties":{"id":1,"featureType":"path","kind":"WIRELESS_LINK","name":"PtP","fiberCount":null,"cableType":"",
                   "status":"Active","notes":"","startAssetId":1,"endAssetId":null,"attributes":{},"photos":[]}}
                ]}""",
            )
            put(
                "wifi_survey.csv",
                "session,time,latitude,longitude,accuracy_m,ssid,bssid,rssi_dbm,frequency_mhz,channel,band,security,connected\n" +
                    "Walk,2026-10-31T12:01:00Z,40.1000000,-105.1000000,3.0,Corp,AA:BB:CC:DD:EE:02,-48,2412,1,2.4 GHz,,true\n" +
                    "Walk,2026-10-31T12:01:05Z,40.1001000,-105.1001000,3.0,Corp,aa:bb:cc:dd:ee:02,-52,2412,1,2.4 GHz,,true\n",
            )
            zip.putNextEntry(ZipEntry("photos/IMG_20261031_120500_2222.jpg"))
            zip.write(byteArrayOf(9, 8, 7))
            zip.closeEntry()
        }
        val uri = Uri.fromFile(legacy)
        val preview = Importer.inspect(context, uri)
        assertTrue(preview.legacy)
        val only = preview.sites.single()
        assertEquals(1, only.pins)
        assertEquals(1, only.paths)
        assertEquals(2, only.readings)

        val repo = Repository(context, memoryDb())
        val result = Importer.importZip(context, repo, uri, mapOf(only.key to ImportTarget.Existing(DEFAULT_SITE_ID)))
        assertEquals(0, result.sitesCreated)
        assertEquals(1, result.assets)
        assertEquals(1, result.paths)
        assertEquals(1, result.photos)
        assertEquals(2, result.readings)
        val ap = repo.getAssetsForSite(DEFAULT_SITE_ID).single()
        assertEquals("Corp", ap.attributes["SSID"])
        assertEquals(ap.id, repo.getPathsForSite(DEFAULT_SITE_ID).single().startAssetId)
        val photo = repo.getPhotosForSites(listOf(DEFAULT_SITE_ID)).single()
        assertNotNull(photo.latitude)
        assertTrue(repo.getReadingsForSites(listOf(DEFAULT_SITE_ID)).all { it.bssid == "aa:bb:cc:dd:ee:02" })
    }
}
