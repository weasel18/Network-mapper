package com.networkmapper.app.sync

import android.content.Context
import android.os.Build
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.networkmapper.app.BuildConfig
import com.networkmapper.app.NetworkMapperApp
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Repository
import com.networkmapper.app.unifi.UntrustedCertificateException
import com.networkmapper.app.util.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SyncConfig(
    val url: String = "",
    val key: String = "",
    val deviceName: String = Build.MODEL ?: "Android",
    val auto: Boolean = false,
    val intervalMin: Int = 15,
    val wifiOnly: Boolean = false,
    /** Site uids to sync, or null for all sites. */
    val sites: Set<String>? = null,
    val pinnedFingerprint: String? = null,
    val pinnedFor: String? = null,
) {
    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank()
}

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastMessage: String = "",
    val lastError: String? = null,
)

/** Persists sync settings; the access key is encrypted with an Android Keystore key. */
class SyncStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val box = SecretBox("sync_secret")

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    /** Built-in defaults (server address in the code, key injected at build time) until the user changes them. */
    private val builtInKey = BuildConfig.DEFAULT_SYNC_KEY

    fun load(): SyncConfig = SyncConfig(
        url = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SYNC_URL,
        key = prefs.getString("key", null)?.let { box.decrypt(it) }?.takeIf { it.isNotBlank() } ?: builtInKey,
        deviceName = prefs.getString("deviceName", null) ?: (Build.MODEL ?: "Android"),
        auto = prefs.getBoolean("auto", builtInKey.isNotBlank()),
        intervalMin = prefs.getInt("interval", 15),
        wifiOnly = prefs.getBoolean("wifiOnly", false),
        sites = prefs.getString("sites", null)?.let { s -> if (s == "*") null else s.split(',').filter { it.isNotBlank() }.toSet() },
        pinnedFingerprint = prefs.getString("pin", null),
        pinnedFor = prefs.getString("pinFor", null),
    )

    fun save(c: SyncConfig) {
        val old = load()
        prefs.edit()
            .putString("url", c.url.trim())
            .putString("key", if (c.key.isNotBlank()) box.encrypt(c.key.trim()) else null)
            .putString("deviceName", c.deviceName.trim().ifBlank { Build.MODEL ?: "Android" })
            .putBoolean("auto", c.auto)
            .putInt("interval", c.intervalMin)
            .putBoolean("wifiOnly", c.wifiOnly)
            .putString("sites", c.sites?.joinToString(",") ?: "*")
            .putString("pin", c.pinnedFingerprint)
            .putString("pinFor", c.pinnedFor)
            .apply()
        // A different server or a different set of sites means re-reading everything.
        if (old.url.trim() != c.url.trim() || old.sites != c.sites) cursor = 0
    }

    var cursor: Long
        get() = prefs.getLong("cursor", 0)
        set(v) {
            prefs.edit().putLong("cursor", v).apply()
        }

    fun saveStatus(s: SyncStatus) {
        prefs.edit().putLong("lastSyncAt", s.lastSyncAt).putString("lastMessage", s.lastMessage).putString("lastError", s.lastError).apply()
    }

    fun loadStatus() = SyncStatus(
        lastSyncAt = prefs.getLong("lastSyncAt", 0),
        lastMessage = prefs.getString("lastMessage", "") ?: "",
        lastError = prefs.getString("lastError", null),
    )
}

/** Runs syncs (one at a time), keeps their status, and schedules automatic sync. */
class SyncManager(
    private val context: Context,
    private val db: AppDatabase,
    private val repo: Repository,
    private val wifiFilter: com.networkmapper.app.wifi.WifiFilterStore? = null,
) {
    val store = SyncStore(context)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(store.loadStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** While remote changes are being written, local-change triggers are ignored. */
    @Volatile private var quietUntil = 0L

    private val observer = object : InvalidationTracker.Observer(
        arrayOf("sites", "assets", "paths", "photos", "survey_sessions", "ap_names", "sync_tombstones"),
    ) {
        override fun onInvalidated(tables: Set<String>) {
            if (System.currentTimeMillis() < quietUntil || mutex.isLocked) return
            val c = store.load()
            if (c.auto && c.configured) scheduleSoon(c)
        }
    }

    fun start() {
        db.invalidationTracker.addObserver(observer)
        applySchedule()
    }

    fun transportFor(c: SyncConfig): HttpSyncTransport {
        val base = com.networkmapper.app.net.PinnedHttp.normalizeBase(c.url)
        return HttpSyncTransport(base, c.pinnedFingerprint.takeIf { c.pinnedFor == base }, c.key, store.deviceId, c.deviceName)
    }

    /** One full push + pull. Throws on failure (status records the error either way). */
    suspend fun syncNow(): SyncResult = mutex.withLock {
        val c = store.load()
        if (!c.configured) throw IOException("Set the sync server address and access key first.")
        _status.value = _status.value.copy(running = true, lastError = null)
        try {
            val engine = SyncEngine(db, repo, transportFor(c), c.sites, { store.cursor }, { store.cursor = it }, wifiFilter)
            val result = withContext(Dispatchers.IO) {
                quietUntil = Long.MAX_VALUE
                try {
                    engine.run()
                } finally {
                    quietUntil = System.currentTimeMillis() + 3_000
                }
            }
            _status.value = SyncStatus(false, System.currentTimeMillis(), result.summary, null)
            store.saveStatus(_status.value)
            result
        } catch (e: Exception) {
            val msg = when (e) {
                is UntrustedCertificateException -> "The server's certificate isn't trusted. Open Cloud sync and tap Test & save."
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
                    "Can't reach the sync server (no internet?)"
                else -> e.message ?: e.javaClass.simpleName
            }
            _status.value = _status.value.copy(running = false, lastError = msg)
            store.saveStatus(_status.value)
            throw e
        }
    }

    /** Asks for a sync shortly (e.g. after a settings change outside the database). */
    fun requestSoon() {
        val c = store.load()
        if (c.auto && c.configured) scheduleSoon(c)
    }

    /** (Re)schedules or cancels the periodic background sync to match the settings. */
    fun applySchedule() {
        val c = store.load()
        val wm = WorkManager.getInstance(context)
        if (!c.auto || !c.configured) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(SOON)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(c.intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints(c))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun scheduleSoon(c: SyncConfig) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(45, TimeUnit.SECONDS)
            .setConstraints(constraints(c))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, req)
    }

    private fun constraints(c: SyncConfig) = Constraints.Builder()
        .setRequiredNetworkType(if (c.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    companion object {
        private const val PERIODIC = "sync-periodic"
        private const val SOON = "sync-soon"
    }
}

/** Background sync run by WorkManager. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as NetworkMapperApp).sync
        return try {
            manager.syncNow()
            Result.success()
        } catch (e: UntrustedCertificateException) {
            Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
