package com.networkmapper.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class Repository(private val context: Context, val db: AppDatabase) {

    private val assetDao = db.assetDao()
    private val pathDao = db.pathDao()
    private val photoDao = db.photoDao()
    private val wifiDao = db.wifiDao()

    val photosDir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun photoFile(photo: Photo): File = File(photosDir, photo.fileName)

    // ---- Assets ----
    val assets: Flow<List<Asset>> = assetDao.observeAll()
    fun observeAsset(id: Long): Flow<Asset?> = assetDao.observe(id)
    suspend fun getAsset(id: Long): Asset? = assetDao.get(id)
    suspend fun getAllAssets(): List<Asset> = assetDao.getAll()
    suspend fun insertAsset(asset: Asset): Long = assetDao.insert(asset)
    suspend fun updateAsset(asset: Asset) = assetDao.update(asset.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteAsset(asset: Asset) {
        deletePhotoFiles(photoDao.getForAsset(asset.id))
        pathDao.clearStartAsset(asset.id)
        pathDao.clearEndAsset(asset.id)
        assetDao.delete(asset)
    }

    // ---- Paths ----
    val paths: Flow<List<NetPath>> = pathDao.observeAll()
    fun observePath(id: Long): Flow<NetPath?> = pathDao.observe(id)
    suspend fun getPath(id: Long): NetPath? = pathDao.get(id)
    suspend fun getAllPaths(): List<NetPath> = pathDao.getAll()
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
    val photoCount: Flow<Int> = photoDao.observeCount()
    suspend fun getAllPhotos(): List<Photo> = photoDao.getAll()
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
    val sessions: Flow<List<SessionWithCount>> = wifiDao.observeSessions()
    val networks: Flow<List<NetworkSummary>> = wifiDao.observeNetworks()
    val readingCount: Flow<Int> = wifiDao.observeReadingCount()

    suspend fun startSession(name: String): Long = wifiDao.insertSession(SurveySession(name = name))
    suspend fun endSession(id: Long) = wifiDao.endSession(id, System.currentTimeMillis())
    suspend fun deleteSession(id: Long) = wifiDao.deleteSession(id)
    suspend fun deleteAllSurveys() = wifiDao.deleteAllSessions()
    suspend fun getSessions(): List<SurveySession> = wifiDao.getSessions()
    suspend fun insertReadings(readings: List<WifiReading>) {
        if (readings.isNotEmpty()) wifiDao.insertReadings(readings)
    }

    fun readings(sessionId: Long?): Flow<List<WifiReading>> =
        if (sessionId == null) wifiDao.observeAllReadings() else wifiDao.observeReadingsForSession(sessionId)

    suspend fun getAllReadings(): List<WifiReading> = wifiDao.getAllReadings()
    suspend fun getNetworks(): List<NetworkSummary> = wifiDao.getNetworks()
    suspend fun getNetworksIn(south: Double, north: Double, west: Double, east: Double): List<NetworkSummary> =
        wifiDao.getNetworksIn(south, north, west, east)
}
