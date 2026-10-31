package com.networkmapper.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.networkmapper.app.data.AppDatabase
import com.networkmapper.app.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.osmdroid.config.Configuration
import java.io.File

class NetworkMapperApp : Application() {

    lateinit var repository: Repository
        private set

    /** Scope for work that must outlive a screen or service (e.g. closing a survey session). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this, AppDatabase.build(this))

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
