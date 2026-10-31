package com.networkmapper.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class Repository(private val context: Context, val db: AppDatabase) {

    private val siteDao = db.siteDao()
    private val assetDao = db.assetDao()
    private val pathDao = db.pathDao()
    private val photoDao = db.photoDao()
    private val wifiDao = db.wifiDao()
    private val apNameDao = db.apNameDao()

    val photosDir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun photoFile(photo: Photo): File = File(photosDir, photo.fileName)

    // ---- Sites ----
    val sitesWithCounts: Flow<List<SiteWithCounts>> = siteDao.observeWithCounts()
    fun observeSite(id: Long): Flow<Site?> = siteDao.observe(id)
    suspend fun getSite(id: Long): Site? = siteDao.get(id)
    suspend fun getSites(): List<Site> = siteDao.getAll()
    suspend fun insertSite(site: Site): Long = siteDao.insert(site)
    suspend fun updateSite(site: Site) = siteDao.update(site)

    /** Deletes a site and everything in it, including photo files. */
    suspend fun deleteSite(siteId: Long) {
        deletePhotoFiles(photoDao.getForSites(listOf(siteId)))
        assetDao.deleteForSite(siteId) // photos cascade
        pathDao.deleteForSite(siteId)
        wifiDao.deleteSessionsForSite(siteId) // readings cascade
        siteDao.deleteById(siteId)
    }

    // ---- Assets ----
    fun assets(siteId: Long): Flow<List<Asset>> = assetDao.observeForSite(siteId)
    fun observeAsset(id: Long): Flow<Asset?> = assetDao.observe(id)
    suspend fun getAsset(id: Long): Asset? = assetDao.get(id)
    suspend fun getAssetsForSite(siteId: Long): List<Asset> = assetDao.getForSite(siteId)
    suspend fun getAssetsForSites(siteIds: Collection<Long>): List<Asset> = assetDao.getForSites(siteIds.toList())
    suspend fun insertAsset(asset: Asset): Long = assetDao.insert(asset)
    suspend fun updateAsset(asset: Asset) = assetDao.update(asset.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteAsset(asset: Asset) {
        deletePhotoFiles(photoDao.getForAsset(asset.id))
        pathDao.clearStartAsset(asset.id)
        pathDao.clearEndAsset(asset.id)
        assetDao.delete(asset)
    }

    // ---- Paths ----
    fun paths(siteId: Long): Flow<List<NetPath>> = pathDao.observeForSite(siteId)
    fun observePath(id: Long): Flow<NetPath?> = pathDao.observe(id)
    suspend fun getPath(id: Long): NetPath? = pathDao.get(id)
    suspend fun getPathsForSite(siteId: Long): List<NetPath> = pathDao.getForSite(siteId)
    suspend fun getPathsForSites(siteIds: Collection<Long>): List<NetPath> = pathDao.getForSites(siteIds.toList())
    suspend fun insertPath(path: NetPath): Long = pathDao.insert(path)
    suspend fun updatePath(path: NetPath) = pathDao.update(path.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deletePath(path: NetPath) {
        deletePhotoFiles(photoDao.getForPath(path.id))
        pathDao.delete(path)
    }

    // ---- Photos ----
    fun photosForAsset(assetId: Long): Flow<List<Photo>> = photoDao.observeForAsset(assetId)
    fun photosForPath(pathId: Long): Flow<List<Photo>> = photoDao.observeForPath(pathId)
    fun observePhoto(id: Long): Flow<Photo?> = photoDao.observe(id)
    fun photoCount(siteId: Long): Flow<Int> = photoDao.observeCountForSite(siteId)
    suspend fun getPhotosForSites(siteIds: Collection<Long>): List<Photo> = photoDao.getForSites(siteIds.toList())
    suspend fun insertPhoto(photo: Photo): Long = photoDao.insert(photo)
    suspend fun updatePhoto(photo: Photo) = photoDao.update(photo)

    suspend fun deletePhoto(photo: Photo) {
        deletePhotoFiles(listOf(photo))
        photoDao.delete(photo)
    }

    private suspend fun deletePhotoFiles(photos: List<Photo>) = withContext(Dispatchers.IO) {
        photos.forEach { photoFile(it).delete() }
    }

    // ---- Wi-Fi survey ----
    fun sessions(siteId: Long): Flow<List<SessionWithCount>> = wifiDao.observeSessions(siteId)
    fun networks(siteId: Long): Flow<List<NetworkSummary>> = wifiDao.observeNetworks(siteId)
    fun readingCount(siteId: Long): Flow<Int> = wifiDao.observeReadingCountForSite(siteId)

    suspend fun startSession(name: String, siteId: Long): Long =
        wifiDao.insertSession(SurveySession(name = name, siteId = siteId))
    suspend fun insertSession(session: SurveySession): Long = wifiDao.insertSession(session)
    suspend fun endSession(id: Long) = wifiDao.endSession(id, System.currentTimeMillis())
    suspend fun deleteSession(id: Long) = wifiDao.deleteSession(id)
    suspend fun deleteSurveysForSite(siteId: Long) = wifiDao.deleteSessionsForSite(siteId)
    suspend fun getSessionsForSite(siteId: Long): List<SurveySession> = wifiDao.getSessionsForSite(siteId)
    suspend fun getSessionsForSites(siteIds: Collection<Long>): List<SurveySession> = wifiDao.getSessionsForSites(siteIds.toList())
    suspend fun insertReadings(readings: List<WifiReading>) {
        if (readings.isNotEmpty()) wifiDao.insertReadings(readings)
    }

    /** All readings of a site, or of one session when [sessionId] is set. */
    fun readings(siteId: Long, sessionId: Long?): Flow<List<WifiReading>> =
        if (sessionId == null) wifiDao.observeReadingsForSite(siteId) else wifiDao.observeReadingsForSession(sessionId)

    suspend fun getReadingsForSites(siteIds: Collection<Long>): List<WifiReading> = wifiDao.getReadingsForSites(siteIds.toList())
    suspend fun strongestReading(siteId: Long, bssids: Collection<String>): WifiReading? =
        if (bssids.isEmpty()) null else wifiDao.strongestReading(siteId, bssids.toList())
    suspend fun lastSeenIn(siteId: Long, south: Double, north: Double, west: Double, east: Double): Long? =
        wifiDao.lastSeenIn(siteId, south, north, west, east)

    // ---- AP names ----
    val apNames: Flow<List<ApName>> = apNameDao.observeAll()
    suspend fun getApNames(): List<ApName> = apNameDao.getAll()

    /** Names (or, with a blank name, un-names) one or more BSSIDs. */
    suspend fun setApName(bssids: Collection<String>, name: String) {
        val keys = bssids.map { it.lowercase() }.distinct()
        if (keys.isEmpty()) return
        if (name.isBlank()) apNameDao.delete(keys) else apNameDao.upsert(keys.map { ApName(it, name.trim()) })
    }

    suspend fun upsertApNames(names: List<ApName>) {
        if (names.isNotEmpty()) apNameDao.upsert(names)
    }
    suspend fun getNetworks(siteId: Long): List<NetworkSummary> = wifiDao.getNetworks(siteId)
    suspend fun getNetworksIn(siteId: Long, south: Double, north: Double, west: Double, east: Double): List<NetworkSummary> =
        wifiDao.getNetworksIn(siteId, south, north, west, east)
}
