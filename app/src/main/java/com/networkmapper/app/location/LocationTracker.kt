package com.networkmapper.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Location updates from the platform LocationManager (GPS + network), so the app works
 * on devices without Google Play services.
 */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = try {
        lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    } catch (e: Exception) {
        false
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission() || lm == null) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    fun updates(minTimeMs: Long = 1000L, minDistanceM: Float = 0f): Flow<Location> = callbackFlow {
        val manager = lm
        if (manager == null || !hasPermission()) {
            close()
            return@callbackFlow
        }
        var best: Location? = null

        // All four methods are overridden explicitly: on API < 30 they are abstract and the
        // framework would throw AbstractMethodError for a SAM lambda.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (isBetter(location, best)) {
                    best = location
                    trySend(location)
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        lastKnown()?.let { loc ->
            if (System.currentTimeMillis() - loc.time < 2 * 60_000L) {
                best = loc
                trySend(loc)
            }
        }

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.allProviders.contains(provider)) {
                    manager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                // Provider missing or permission revoked; keep going with what we have.
            }
        }

        awaitClose { manager.removeUpdates(listener) }
    }

    companion object {
        /** Prefer a fresh fix, but don't let a coarse network fix replace a recent accurate GPS fix. */
        fun isBetter(candidate: Location, current: Location?): Boolean {
            if (current == null) return true
            val ageDelta = candidate.time - current.time
            if (ageDelta > 10_000L) return true
            if (ageDelta < -10_000L) return false
            val accDelta = candidate.accuracy - current.accuracy
            return when {
                accDelta <= 0f -> true
                ageDelta > 0 && candidate.provider == current.provider -> true
                ageDelta > 0 && accDelta < 15f -> true
                else -> false
            }
        }
    }
}
