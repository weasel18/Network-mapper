package com.networkmapper.app.util

import com.networkmapper.app.data.GeoPt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(a: GeoPt, b: GeoPt): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    fun pathLengthMeters(points: List<GeoPt>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    /** Initial bearing from [from] to [to] in degrees, 0..360 clockwise from north. */
    fun bearingDegrees(from: GeoPt, to: GeoPt): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun compassPoint(bearing: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]
    }

    fun formatDistance(meters: Double): String {
        val feet = meters * 3.28084
        return if (meters < 1000) {
            "${meters.roundToInt()} m (${feet.roundToInt()} ft)"
        } else {
            String.format(Locale.US, "%.2f km (%.2f mi)", meters / 1000.0, meters / 1609.344)
        }
    }

    fun formatCoord(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.6f, %.6f", lat, lon)

    fun formatCoord(p: GeoPt): String = formatCoord(p.lat, p.lon)
}

object Wifi {
    fun channel(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5160..5885 -> (frequencyMhz - 5000) / 5
        frequencyMhz == 5935 -> 2
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5
        frequencyMhz in 58320..70200 -> (frequencyMhz - 56160) / 2160
        else -> 0
    }

    fun band(frequencyMhz: Int): String = when {
        frequencyMhz < 3000 -> "2.4 GHz"
        frequencyMhz < 5925 -> "5 GHz"
        frequencyMhz < 7200 -> "6 GHz"
        else -> "60 GHz"
    }

    /** Colour ramp used everywhere for signal strength (ARGB). */
    fun rssiColor(rssi: Int): Long = when {
        rssi >= -50 -> 0xFF1B9E3E
        rssi >= -60 -> 0xFF7CB342
        rssi >= -67 -> 0xFFFDD835
        rssi >= -75 -> 0xFFFB8C00
        rssi >= -85 -> 0xFFE53935
        else -> 0xFF8E0000
    }

    fun rssiQuality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Good"
        rssi >= -67 -> "Reliable"
        rssi >= -75 -> "Weak"
        rssi >= -85 -> "Poor"
        else -> "Unusable"
    }

    private val macRegex = Regex(
        "(?i)(?<![0-9a-f])([0-9a-f]{2})[:-]?([0-9a-f]{2})[:-]?([0-9a-f]{2})[:-]?" +
            "([0-9a-f]{2})[:-]?([0-9a-f]{2})[:-]?([0-9a-f]{2})(?![0-9a-f])",
    )

    /** Pulls every MAC address out of free text ("AA-BB-..", "aabb..", comma lists) as lower-case aa:bb:cc:dd:ee:ff. */
    fun parseBssids(text: String): Set<String> =
        macRegex.findAll(text).map { m -> (1..6).joinToString(":") { m.groupValues[it].lowercase() } }.toSet()

    /**
     * Heuristic for "another radio / SSID of the same physical AP": vendors usually vary only the
     * last octet, or only the first (locally-administered) octet, between virtual BSSIDs.
     */
    fun likelySameAp(a: String, b: String): Boolean {
        if (a.length != 17 || b.length != 17 || a == b) return false
        return a.substring(0, 14) == b.substring(0, 14) || a.substring(3) == b.substring(3)
    }

    /** Legend entries: label to colour. */
    val legend: List<Pair<String, Long>> = listOf(
        "≥ -50" to rssiColor(-50),
        "-60" to rssiColor(-60),
        "-67" to rssiColor(-67),
        "-75" to rssiColor(-75),
        "-85" to rssiColor(-85),
        "< -85" to rssiColor(-90),
    )
}

object Fmt {
    private val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    fun dateTime(ms: Long): String = synchronized(dateTime) { dateTime.format(Date(ms)) }
    fun fileStamp(ms: Long = System.currentTimeMillis()): String = synchronized(fileStamp) { fileStamp.format(Date(ms)) }
    fun iso(ms: Long): String = synchronized(iso) { iso.format(Date(ms)) }
}
