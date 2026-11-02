package com.networkmapper.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Repository
import com.networkmapper.app.sync.SyncManager
import com.networkmapper.app.wifi.WifiFilterStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.osmdroid.config.Configuration
import java.io.File

class NetworkMapperApp : Application() {

    lateinit var repository: Repository
        private set

    /** Which Wi-Fi networks to record and show, per site (synced). */
    lateinit var wifiFilter: WifiFilterStore
        private set

    /** Cloud sync with a self-hosted server (multi-phone mapping). */
    lateinit var sync: SyncManager
        private set

    /** Scope for work that must outlive a screen or service (e.g. closing a survey session). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        com.networkmapper.app.util.CrashLog.install(this)
        val db = AppDatabase.build(this)
        repository = Repository(this, db)
        wifiFilter = WifiFilterStore(this)
        sync = SyncManager(this, db, repository, wifiFilter).also { it.start() }

        // osmdroid: keep tile cache in app-private storage so no storage permission is needed.
        val osm = Configuration.getInstance()
        osm.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
        osm.osmdroidBasePath = File(filesDir, "osmdroid")
        osm.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        val channel = NotificationChannel(
            SURVEY_CHANNEL_ID,
            getString(R.string.survey_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val SURVEY_CHANNEL_ID = "survey"
    }
}
