package com.networkmapper.app.ui.components

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live compass heading in degrees from TRUE north (magnetic declination applied when a position is
 * known), or null if the phone has no rotation sensor. Hold the phone flat, top edge pointing.
 */
@Composable
fun rememberCompassHeading(lat: Double?, lon: Double?): State<Float?> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(lat, lon) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose {}
        val declination = if (lat != null && lon != null) {
            GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination
        } else {
            0f
        }
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        var sx = 0.0
        var sy = 0.0
        var primed = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val rad = orientation[0].toDouble() + Math.toRadians(declination.toDouble())
                // Smooth on the unit circle so 359° -> 1° doesn't swing through 180°.
                if (!primed) {
                    sx = cos(rad)
                    sy = sin(rad)
                    primed = true
                } else {
                    sx += 0.15 * (cos(rad) - sx)
                    sy += 0.15 * (sin(rad) - sy)
                }
                heading.value = ((Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return heading
}
