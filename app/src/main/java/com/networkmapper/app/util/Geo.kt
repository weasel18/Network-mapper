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

    /** The point [distM] metres from [from] along [bearingDeg] (clockwise from north). */
    fun destination(from: GeoPt, bearingDeg: Double, distM: Double): GeoPt {
        val d = distM / EARTH_RADIUS_M
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = kotlin.math.asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPt(Math.toDegrees(lat2), Math.toDegrees(lon2))
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

    private val rampStops = listOf(
        -50.0 to 0x1B9E3E, -60.0 to 0x7CB342, -67.0 to 0xFDD835, -75.0 to 0xFB8C00, -85.0 to 0xE53935, -95.0 to 0x8E0000,
    )

    /** Same ramp as [rssiColor] but blended smoothly between its steps (RGB, no alpha) – for the average heat surface. */
    fun rssiColorSmooth(rssi: Double): Int {
        if (rssi >= rampStops.first().first) return rampStops.first().second
        for (i in 1 until rampStops.size) {
            val (hi, cHi) = rampStops[i - 1]
            val (lo, cLo) = rampStops[i]
            if (rssi >= lo) {
                val t = (hi - rssi) / (hi - lo)
                fun mix(shift: Int) = (((cHi shr shift) and 0xFF) * (1 - t) + ((cLo shr shift) and 0xFF) * t).toInt()
                return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
        return rampStops.last().second
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

    private fun macBytes(mac: String): IntArray? {
        val parts = mac.split(':')
        if (parts.size != 6) return null
        return parts.map { it.toIntOrNull(16) ?: return null }.toIntArray()
    }

    /**
     * MAC-pattern test for "another radio / SSID of the same physical AP". Covers the common
     * vendor schemes for per-SSID / per-band BSSIDs:
     *  - sequential ("next-up") addresses, including carries into the 5th/4th octet (within 32);
     *  - the locally-administered bit and VAP index bits set in the first octet (only its high
     *    nibble must match);
     *  - UniFi-style radios that also step the 4th octet (last two octets equal, 4th within 16).
     * Octets 2 and 3 must always match.
     */
    fun likelySameAp(a: String, b: String): Boolean {
        val x = macBytes(a.lowercase()) ?: return false
        val y = macBytes(b.lowercase()) ?: return false
        if (x.contentEquals(y)) return false
        if (x[1] != y[1] || x[2] != y[2]) return false
        if ((x[0] and 0xF0) != (y[0] and 0xF0)) return false
        val lowX = (x[3] shl 16) or (x[4] shl 8) or x[5]
        val lowY = (y[3] shl 16) or (y[4] shl 8) or y[5]
        if (kotlin.math.abs(lowX - lowY) <= 32) return true
        return x[4] == y[4] && x[5] == y[5] && kotlin.math.abs(x[3] - y[3]) <= 16
    }

    /** Distance between the low 24 bits of two MACs (used to stop groups from chaining across APs). */
    fun lowDistance(a: String, b: String): Int {
        val x = macBytes(a.lowercase()) ?: return Int.MAX_VALUE
        val y = macBytes(b.lowercase()) ?: return Int.MAX_VALUE
        val lowX = (x[3] shl 16) or (x[4] shl 8) or x[5]
        val lowY = (y[3] shl 16) or (y[4] shl 8) or y[5]
        val low16Same = x[4] == y[4] && x[5] == y[5]
        return if (low16Same) kotlin.math.abs(x[3] - y[3]) else kotlin.math.abs(lowX - lowY)
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
    fun parseIso(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            synchronized(iso) { iso.parse(text)?.time }
        } catch (e: Exception) {
            null
        }
    }

    /** Recovers the capture time from our photo file names (IMG_yyyyMMdd_HHmmss_nnnn.jpg). */
    fun parseFileStamp(name: String): Long? {
        val m = Regex("(\\d{8}_\\d{6})").find(name) ?: return null
        return try {
            synchronized(fileStamp) { fileStamp.parse(m.value)?.time }
        } catch (e: Exception) {
            null
        }
    }
}
