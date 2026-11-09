package com.networkmapper.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {
    @Query(
        "SELECT s.*, " +
            "(SELECT COUNT(*) FROM assets a WHERE a.siteId = s.id) AS pins, " +
            "(SELECT COUNT(*) FROM paths p WHERE p.siteId = s.id) AS paths, " +
            "(SELECT COUNT(*) FROM survey_sessions ss WHERE ss.siteId = s.id) AS surveys " +
            "FROM sites s ORDER BY s.name COLLATE NOCASE",
    )
    fun observeWithCounts(): Flow<List<SiteWithCounts>>

    @Query("SELECT * FROM sites WHERE id = :id")
    fun observe(id: Long): Flow<Site?>

    @Query("SELECT * FROM sites WHERE id = :id")
    suspend fun get(id: Long): Site?

    @Query("SELECT * FROM sites ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Site>

    @Insert
    suspend fun insert(site: Site): Long

    @Update
    suspend fun update(site: Site)

    @Query("DELETE FROM sites WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE siteId = :siteId ORDER BY updatedAt DESC")
    fun observeForSite(siteId: Long): Flow<List<Asset>>

    @Query("SELECT * FROM assets WHERE id = :id")
    fun observe(id: Long): Flow<Asset?>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun get(id: Long): Asset?

    @Query("SELECT * FROM assets WHERE siteId = :siteId")
    suspend fun getForSite(siteId: Long): List<Asset>

    @Query("SELECT * FROM assets WHERE siteId IN (:siteIds) ORDER BY siteId, type, name")
    suspend fun getForSites(siteIds: List<Long>): List<Asset>

    @Insert
    suspend fun insert(asset: Asset): Long

    @Update
    suspend fun update(asset: Asset)

    @Delete
    suspend fun delete(asset: Asset)

    @Query("SELECT * FROM assets WHERE uid = :uid")
    suspend fun getByUid(uid: String): Asset?

    /** Devices inside a location pin. */
    @Query("SELECT * FROM assets WHERE groupUid = :groupUid AND groupUid != '' ORDER BY type, name")
    suspend fun getMembers(groupUid: String): List<Asset>

    @Query("DELETE FROM assets WHERE siteId = :siteId")
    suspend fun deleteForSite(siteId: Long)
}

@Dao
interface PathDao {
    @Query("SELECT * FROM paths WHERE siteId = :siteId ORDER BY updatedAt DESC")
    fun observeForSite(siteId: Long): Flow<List<NetPath>>

    @Query("SELECT * FROM paths WHERE id = :id")
    fun observe(id: Long): Flow<NetPath?>

    @Query("SELECT * FROM paths WHERE id = :id")
    suspend fun get(id: Long): NetPath?

    @Query("SELECT * FROM paths WHERE siteId = :siteId")
    suspend fun getForSite(siteId: Long): List<NetPath>

    @Query("SELECT * FROM paths WHERE siteId IN (:siteIds) ORDER BY siteId, kind, name")
    suspend fun getForSites(siteIds: List<Long>): List<NetPath>

    @Insert
    suspend fun insert(path: NetPath): Long

    @Update
    suspend fun update(path: NetPath)

    @Delete
    suspend fun delete(path: NetPath)

    @Query("DELETE FROM paths WHERE siteId = :siteId")
    suspend fun deleteForSite(siteId: Long)

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

    @Query(
        "SELECT p.* FROM photos p LEFT JOIN assets a ON p.assetId = a.id LEFT JOIN paths pa ON p.pathId = pa.id " +
            "WHERE a.siteId IN (:siteIds) OR pa.siteId IN (:siteIds) ORDER BY p.takenAt",
    )
    suspend fun getForSites(siteIds: List<Long>): List<Photo>

    @Query(
        "SELECT COUNT(*) FROM photos p LEFT JOIN assets a ON p.assetId = a.id LEFT JOIN paths pa ON p.pathId = pa.id " +
            "WHERE a.siteId = :siteId OR pa.siteId = :siteId",
    )
    fun observeCountForSite(siteId: Long): Flow<Int>

    @Insert
    suspend fun insert(photo: Photo): Long

    @Update
    suspend fun update(photo: Photo)

    @Delete
    suspend fun delete(photo: Photo)
}

@Dao
interface ApNameDao {
    @Query("SELECT * FROM ap_names ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ApName>>

    @Query("SELECT * FROM ap_names")
    suspend fun getAll(): List<ApName>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(names: List<ApName>)

    @Query("DELETE FROM ap_names WHERE bssid IN (:bssids)")
    suspend fun delete(bssids: List<String>)
}

@Dao
interface WifiDao {
    @Insert
    suspend fun insertSession(session: SurveySession): Long

    @Query("UPDATE survey_sessions SET endedAt = :endedAt, updatedAt = :endedAt, dirty = 1 WHERE id = :id")
    suspend fun endSession(id: Long, endedAt: Long)

    @Query("DELETE FROM survey_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM survey_sessions WHERE siteId = :siteId")
    suspend fun deleteSessionsForSite(siteId: Long)

    @Query(
        "SELECT s.*, (SELECT COUNT(*) FROM wifi_readings r WHERE r.sessionId = s.id) AS readingCount " +
            "FROM survey_sessions s WHERE s.siteId = :siteId ORDER BY s.startedAt DESC",
    )
    fun observeSessions(siteId: Long): Flow<List<SessionWithCount>>

    @Query("SELECT * FROM survey_sessions WHERE siteId = :siteId ORDER BY startedAt")
    suspend fun getSessionsForSite(siteId: Long): List<SurveySession>

    @Query("SELECT * FROM survey_sessions WHERE siteId IN (:siteIds) ORDER BY startedAt")
    suspend fun getSessionsForSites(siteIds: List<Long>): List<SurveySession>

    @Insert
    suspend fun insertReadings(readings: List<WifiReading>)

    @Query(
        "SELECT r.* FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id " +
            "WHERE s.siteId = :siteId ORDER BY r.timestamp",
    )
    fun observeReadingsForSite(siteId: Long): Flow<List<WifiReading>>

    @Query("SELECT * FROM wifi_readings WHERE sessionId = :sessionId ORDER BY timestamp")
    fun observeReadingsForSession(sessionId: Long): Flow<List<WifiReading>>

    @Query(
        "SELECT r.* FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id " +
            "WHERE s.siteId IN (:siteIds) ORDER BY r.timestamp",
    )
    suspend fun getReadingsForSites(siteIds: List<Long>): List<WifiReading>

    @Query(
        "SELECT COUNT(*) FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id WHERE s.siteId = :siteId",
    )
    fun observeReadingCountForSite(siteId: Long): Flow<Int>

    @Query(
        "SELECT r.bssid AS bssid, r.ssid AS ssid, MAX(r.rssi) AS bestRssi, COUNT(*) AS samples, MAX(r.frequency) AS frequency, " +
            "MAX(r.standard) AS standard, MAX(r.widthMhz) AS widthMhz, MAX(r.stations) AS stations, MAX(r.utilPct) AS utilPct " +
            "FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id WHERE s.siteId = :siteId " +
            "GROUP BY r.bssid ORDER BY bestRssi DESC",
    )
    fun observeNetworks(siteId: Long): Flow<List<NetworkSummary>>

    @Query(
        "SELECT r.bssid AS bssid, r.ssid AS ssid, MAX(r.rssi) AS bestRssi, COUNT(*) AS samples, MAX(r.frequency) AS frequency, " +
            "MAX(r.standard) AS standard, MAX(r.widthMhz) AS widthMhz, MAX(r.stations) AS stations, MAX(r.utilPct) AS utilPct " +
            "FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id WHERE s.siteId = :siteId " +
            "GROUP BY r.bssid ORDER BY bestRssi DESC",
    )
    suspend fun getNetworks(siteId: Long): List<NetworkSummary>

    @Query(
        "SELECT r.bssid AS bssid, r.ssid AS ssid, MAX(r.rssi) AS bestRssi, COUNT(*) AS samples, MAX(r.frequency) AS frequency, " +
            "MAX(r.standard) AS standard, MAX(r.widthMhz) AS widthMhz, MAX(r.stations) AS stations, MAX(r.utilPct) AS utilPct " +
            "FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id WHERE s.siteId = :siteId " +
            "AND r.latitude BETWEEN :south AND :north AND r.longitude BETWEEN :west AND :east " +
            "GROUP BY r.bssid ORDER BY bestRssi DESC",
    )
    suspend fun getNetworksIn(siteId: Long, south: Double, north: Double, west: Double, east: Double): List<NetworkSummary>

    @Query(
        "SELECT r.* FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id " +
            "WHERE s.siteId = :siteId AND r.bssid IN (:bssids) ORDER BY r.rssi DESC, r.timestamp DESC LIMIT 1",
    )
    suspend fun strongestReading(siteId: Long, bssids: List<String>): WifiReading?

    @Query(
        "SELECT r.* FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id " +
            "WHERE s.siteId = :siteId AND r.bssid IN (:bssids)",
    )
    suspend fun readingsForBssids(siteId: Long, bssids: List<String>): List<WifiReading>

    @Query("DELETE FROM wifi_readings WHERE bssid IN (:bssids) AND sessionId IN (SELECT id FROM survey_sessions WHERE siteId = :siteId)")
    suspend fun deleteReadingsFor(siteId: Long, bssids: List<String>)

    @Query(
        "SELECT MAX(r.timestamp) FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id " +
            "WHERE s.siteId = :siteId AND r.latitude BETWEEN :south AND :north AND r.longitude BETWEEN :west AND :east",
    )
    suspend fun lastSeenIn(siteId: Long, south: Double, north: Double, west: Double, east: Double): Long?
}

/** id <-> uid pairs for building / resolving sync references. */
data class IdUid(val id: Long, val uid: String)

/** Queries used by cloud sync. */
@Dao
interface SyncDao {
    // ---- local deletes waiting to be pushed ----
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun addTombstones(tombstones: List<SyncTombstone>)

    @Query("SELECT * FROM sync_tombstones ORDER BY deletedAt LIMIT :limit")
    suspend fun tombstones(limit: Int): List<SyncTombstone>

    @Query("DELETE FROM sync_tombstones WHERE collection = :collection AND uid = :uid")
    suspend fun clearTombstone(collection: String, uid: String)

    // ---- changed records (not yet pushed), limited to the synced sites ----
    @Query("SELECT * FROM sites WHERE dirty = 1 AND id IN (:siteIds)")
    suspend fun dirtySites(siteIds: List<Long>): List<Site>

    @Query("SELECT * FROM assets WHERE dirty = 1 AND siteId IN (:siteIds) LIMIT :limit")
    suspend fun dirtyAssets(siteIds: List<Long>, limit: Int): List<Asset>

    @Query("SELECT * FROM paths WHERE dirty = 1 AND siteId IN (:siteIds) LIMIT :limit")
    suspend fun dirtyPaths(siteIds: List<Long>, limit: Int): List<NetPath>

    @Query(
        "SELECT p.* FROM photos p LEFT JOIN assets a ON p.assetId = a.id LEFT JOIN paths pa ON p.pathId = pa.id " +
            "WHERE p.dirty = 1 AND (a.siteId IN (:siteIds) OR pa.siteId IN (:siteIds)) LIMIT :limit",
    )
    suspend fun dirtyPhotos(siteIds: List<Long>, limit: Int): List<Photo>

    @Query("SELECT * FROM survey_sessions WHERE dirty = 1 AND siteId IN (:siteIds) LIMIT :limit")
    suspend fun dirtySessions(siteIds: List<Long>, limit: Int): List<SurveySession>

    @Query(
        "SELECT r.* FROM wifi_readings r INNER JOIN survey_sessions s ON r.sessionId = s.id " +
            "WHERE r.dirty = 1 AND s.siteId IN (:siteIds) LIMIT :limit",
    )
    suspend fun dirtyReadings(siteIds: List<Long>, limit: Int): List<WifiReading>

    @Query("SELECT * FROM ap_names WHERE dirty = 1")
    suspend fun dirtyApNames(): List<ApName>

    // ---- mark pushed (only if not edited again meanwhile) ----
    @Query("UPDATE sites SET dirty = 0 WHERE uid = :uid AND updatedAt = :updatedAt")
    suspend fun siteClean(uid: String, updatedAt: Long)

    @Query("UPDATE assets SET dirty = 0 WHERE uid = :uid AND updatedAt = :updatedAt")
    suspend fun assetClean(uid: String, updatedAt: Long)

    @Query("UPDATE paths SET dirty = 0 WHERE uid = :uid AND updatedAt = :updatedAt")
    suspend fun pathClean(uid: String, updatedAt: Long)

    @Query("UPDATE photos SET dirty = 0 WHERE uid = :uid AND updatedAt = :updatedAt")
    suspend fun photoClean(uid: String, updatedAt: Long)

    @Query("UPDATE survey_sessions SET dirty = 0 WHERE uid = :uid AND updatedAt = :updatedAt")
    suspend fun sessionClean(uid: String, updatedAt: Long)

    @Query("UPDATE wifi_readings SET dirty = 0 WHERE uid IN (:uids)")
    suspend fun readingsClean(uids: List<String>)

    @Query("UPDATE ap_names SET dirty = 0 WHERE bssid = :bssid AND updatedAt = :updatedAt")
    suspend fun apNameClean(bssid: String, updatedAt: Long)

    // ---- status ----
    @Query(
        "SELECT (SELECT COUNT(*) FROM sites WHERE dirty = 1) + (SELECT COUNT(*) FROM assets WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM paths WHERE dirty = 1) + (SELECT COUNT(*) FROM photos WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM survey_sessions WHERE dirty = 1) + (SELECT COUNT(*) FROM ap_names WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM sync_tombstones)",
    )
    fun observePendingEdits(): Flow<Int>

    @Query("SELECT COUNT(*) FROM wifi_readings WHERE dirty = 1")
    fun observePendingReadings(): Flow<Int>

    // ---- lookups ----
    @Query("SELECT * FROM sites WHERE uid = :uid")
    suspend fun siteByUid(uid: String): Site?

    @Query("SELECT * FROM assets WHERE uid = :uid")
    suspend fun assetByUid(uid: String): Asset?

    @Query("SELECT * FROM paths WHERE uid = :uid")
    suspend fun pathByUid(uid: String): NetPath?

    @Query("SELECT * FROM photos WHERE uid = :uid")
    suspend fun photoByUid(uid: String): Photo?

    @Query("SELECT * FROM survey_sessions WHERE uid = :uid")
    suspend fun sessionByUid(uid: String): SurveySession?

    @Query("SELECT uid FROM wifi_readings WHERE uid IN (:uids)")
    suspend fun existingReadingUids(uids: List<String>): List<String>

    @Query("SELECT * FROM ap_names WHERE bssid = :bssid")
    suspend fun apName(bssid: String): ApName?

    @Query("SELECT id, uid FROM sites")
    suspend fun siteUids(): List<IdUid>

    @Query("SELECT id, uid FROM assets")
    suspend fun assetUids(): List<IdUid>

    @Query("SELECT id, uid FROM paths")
    suspend fun pathUids(): List<IdUid>

    @Query("SELECT id, uid FROM survey_sessions")
    suspend fun sessionUids(): List<IdUid>

    @Query("SELECT uid FROM assets WHERE siteId = :siteId")
    suspend fun assetUidsForSite(siteId: Long): List<String>

    @Query("SELECT uid FROM paths WHERE siteId = :siteId")
    suspend fun pathUidsForSite(siteId: Long): List<String>

    @Query("SELECT uid FROM survey_sessions WHERE siteId = :siteId")
    suspend fun sessionUidsForSite(siteId: Long): List<String>

    // ---- writes from the server (stay clean) ----
    @Insert
    suspend fun insertSite(site: Site): Long

    @Update
    suspend fun updateSite(site: Site)

    @Insert
    suspend fun insertAsset(asset: Asset): Long

    @Update
    suspend fun updateAsset(asset: Asset)

    @Insert
    suspend fun insertPath(path: NetPath): Long

    @Update
    suspend fun updatePath(path: NetPath)

    @Insert
    suspend fun insertPhoto(photo: Photo): Long

    @Update
    suspend fun updatePhoto(photo: Photo)

    @Insert
    suspend fun insertSession(session: SurveySession): Long

    @Update
    suspend fun updateSession(session: SurveySession)

    @Insert
    suspend fun insertReadings(readings: List<WifiReading>)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertApName(name: ApName)

    @Query("DELETE FROM ap_names WHERE bssid = :bssid")
    suspend fun deleteApName(bssid: String)

    @Query("DELETE FROM assets WHERE id = :id")
    suspend fun deleteAsset(id: Long)

    @Query("DELETE FROM paths WHERE id = :id")
    suspend fun deletePath(id: Long)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deletePhoto(id: Long)

    @Query("DELETE FROM survey_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
