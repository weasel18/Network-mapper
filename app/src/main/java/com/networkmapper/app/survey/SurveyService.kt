package com.networkmapper.app.survey

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.networkmapper.app.NetworkMapperApp
import com.networkmapper.app.R
import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.location.LocationTracker
import com.networkmapper.app.ui.MainActivity
import com.networkmapper.app.util.Fmt
import com.networkmapper.app.wifi.ScanEntry
import com.networkmapper.app.wifi.WifiScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that walks along with the user, pairing every Wi-Fi scan result
 * (and the connected AP's RSSI) with the current GPS fix.
 */
class SurveyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSurvey()
            else -> startSurvey(intent?.getStringExtra(EXTRA_NAME))
        }
        return START_NOT_STICKY
    }

    private fun startSurvey(requestedName: String?) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Waiting for GPS fix…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Missing location permission or not allowed to start from background.
            stopSelf()
            return
        }
        if (job != null) return

        val app = application as NetworkMapperApp
        val repo = app.repository
        val tracker = LocationTracker(this)
        val scanner = WifiScanner(this)
        val name = requestedName?.takeIf { it.isNotBlank() } ?: "Survey ${Fmt.dateTime(System.currentTimeMillis())}"

        job = scope.launch {
            val sessionId = repo.startSession(name)
            SurveyState.started(sessionId)
            var lastLoc: Location? = null
            var lastConnectedLoc: Location? = null

            fun reading(e: ScanEntry, loc: Location, connected: Boolean) = WifiReading(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                ssid = e.ssid,
                bssid = e.bssid,
                rssi = e.rssi,
                frequency = e.frequency,
                security = e.security,
                connected = connected,
            )

            // Location stream + connected-AP sampling every ~2 m of movement.
            launch {
                tracker.updates(minTimeMs = 1000L).collect { loc ->
                    lastLoc = loc
                    SurveyState.lastLocation.value = loc
                    val conn = scanner.connected() ?: return@collect
                    val prev = lastConnectedLoc
                    if (prev == null || loc.distanceTo(prev) >= 2f) {
                        lastConnectedLoc = loc
                        repo.insertReadings(listOf(reading(conn, loc, connected = true)))
                        SurveyState.added(1)
                    }
                }
            }

            // Every scan result (ours or triggered by others) is logged at the current position.
            launch {
                scanner.results().collect { results ->
                    SurveyState.lastScan.value = results
                    SurveyState.lastScanAt.value = System.currentTimeMillis()
                    val loc = lastLoc ?: return@collect
                    if (System.currentTimeMillis() - loc.time > 15_000L) return@collect
                    val fresh = results.filter { it.ageMs < 15_000L && it.bssid.isNotEmpty() }
                    if (fresh.isEmpty()) return@collect
                    repo.insertReadings(fresh.map { reading(it, loc, connected = false) })
                    SurveyState.added(fresh.size)
                    updateNotification()
                }
            }

            // Periodic scan requests.
            launch {
                while (isActive) {
                    scanner.requestScan()
                    delay(SurveyState.scanIntervalMs.value.coerceAtLeast(2_000L))
                }
            }
        }
    }

    private fun stopSurvey() {
        job?.cancel()
        job = null
        val sessionId = SurveyState.sessionId.value
        if (sessionId != null) {
            val app = application as NetworkMapperApp
            app.appScope.launch { app.repository.endSession(sessionId) }
        }
        SurveyState.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (job != null) stopSurvey()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        val text = "${SurveyState.readings.value} readings • ${SurveyState.lastScan.value.size} networks in last scan"
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            // Notification permission denied; the service keeps running regardless.
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SurveyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NetworkMapperApp.SURVEY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_survey)
            .setContentTitle("Wi-Fi survey running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.networkmapper.app.survey.START"
        private const val ACTION_STOP = "com.networkmapper.app.survey.STOP"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            val intent = Intent(context, SurveyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SurveyService::class.java).setAction(ACTION_STOP))
        }
    }
}
