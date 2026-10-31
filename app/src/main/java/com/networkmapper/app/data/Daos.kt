package com.networkmapper.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Asset>>

    @Query("SELECT * FROM assets WHERE id = :id")
    fun observe(id: Long): Flow<Asset?>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun get(id: Long): Asset?

    @Query("SELECT * FROM assets ORDER BY type, name")
    suspend fun getAll(): List<Asset>

    @Insert
    suspend fun insert(asset: Asset): Long

    @Update
    suspend fun update(asset: Asset)

    @Delete
    suspend fun delete(asset: Asset)
}

@Dao
interface PathDao {
    @Query("SELECT * FROM paths ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NetPath>>

    @Query("SELECT * FROM paths WHERE id = :id")
    fun observe(id: Long): Flow<NetPath?>

    @Query("SELECT * FROM paths WHERE id = :id")
    suspend fun get(id: Long): NetPath?

    @Query("SELECT * FROM paths ORDER BY kind, name")
    suspend fun getAll(): List<NetPath>

    @Insert
    suspend fun insert(path: NetPath): Long

    @Update
    suspend fun update(path: NetPath)

    @Delete
    suspend fun delete(path: NetPath)

    @Query("UPDATE paths SET startAssetId = NULL WHERE startAssetId = :assetId")
    suspend fun clearStartAsset(assetId: Long)

    @Query("UPDATE paths SET endAssetId = NULL WHERE endAssetId = :assetId")
    suspend fun clearEndAsset(assetId: Long)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE assetId = :assetId ORDER BY takenAt DESC")
    fun observeForAsset(assetId: Long): Flow<List<Photo>>

    @Query("SELECT * FROM photos WHERE pathId = :pathId ORDER BY takenAt DESC")
    fun observeForPath(pathId: Long): Flow<List<Photo>>

    @Query("SELECT * FROM photos WHERE id = :id")
    fun observe(id: Long): Flow<Photo?>

    @Query("SELECT * FROM photos WHERE assetId = :assetId")
    suspend fun getForAsset(assetId: Long): List<Photo>

    @Query("SELECT * FROM photos WHERE pathId = :pathId")
    suspend fun getForPath(pathId: Long): List<Photo>

    @Query("SELECT * FROM photos ORDER BY takenAt")
    suspend fun getAll(): List<Photo>

    @Query("SELECT COUNT(*) FROM photos")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(photo: Photo): Long

    @Update
    suspend fun update(photo: Photo)

    @Delete
    suspend fun delete(photo: Photo)
}

@Dao
interface WifiDao {
    @Insert
    suspend fun insertSession(session: SurveySession): Long

    @Query("UPDATE survey_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun endSession(id: Long, endedAt: Long)

    @Query("DELETE FROM survey_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM survey_sessions")
    suspend fun deleteAllSessions()

    @Query(
        "SELECT s.*, (SELECT COUNT(*) FROM wifi_readings r WHERE r.sessionId = s.id) AS readingCount " +
            "FROM survey_sessions s ORDER BY s.startedAt DESC",
    )
    fun observeSessions(): Flow<List<SessionWithCount>>

    @Query("SELECT * FROM survey_sessions ORDER BY startedAt")
    suspend fun getSessions(): List<SurveySession>

    @Insert
    suspend fun insertReadings(readings: List<WifiReading>)

    @Query("SELECT * FROM wifi_readings ORDER BY timestamp")
    fun observeAllReadings(): Flow<List<WifiReading>>

    @Query("SELECT * FROM wifi_readings WHERE sessionId = :sessionId ORDER BY timestamp")
    fun observeReadingsForSession(sessionId: Long): Flow<List<WifiReading>>

    @Query("SELECT * FROM wifi_readings ORDER BY timestamp")
    suspend fun getAllReadings(): List<WifiReading>

    @Query("SELECT COUNT(*) FROM wifi_readings")
    fun observeReadingCount(): Flow<Int>

    @Query(
        "SELECT bssid, ssid, MAX(rssi) AS bestRssi, COUNT(*) AS samples, MAX(frequency) AS frequency " +
            "FROM wifi_readings GROUP BY bssid ORDER BY bestRssi DESC",
    )
    fun observeNetworks(): Flow<List<NetworkSummary>>

    @Query(
        "SELECT bssid, ssid, MAX(rssi) AS bestRssi, COUNT(*) AS samples, MAX(frequency) AS frequency " +
            "FROM wifi_readings GROUP BY bssid ORDER BY bestRssi DESC",
    )
    suspend fun getNetworks(): List<NetworkSummary>

    @Query(
        "SELECT bssid, ssid, MAX(rssi) AS bestRssi, COUNT(*) AS samples, MAX(frequency) AS frequency " +
            "FROM wifi_readings WHERE latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east " +
            "GROUP BY bssid ORDER BY bestRssi DESC",
    )
    suspend fun getNetworksIn(south: Double, north: Double, west: Double, east: Double): List<NetworkSummary>
}
