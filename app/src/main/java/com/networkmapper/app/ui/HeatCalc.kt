package com.networkmapper.app.ui

import com.networkmapper.app.data.WifiReading
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Turns survey readings into one value per spot for the "strongest signal" heatmap.
 *
 * A survey records two kinds of reading: full scans (every AP in range, every few seconds) and, in between, the AP the
 * phone happens to be connected to (every couple of metres). A phone clings to an AP long after a nearer one is
 * stronger, so a spot that only has the connected AP's reading would show that weak signal instead of the best one
 * there. Such a spot takes the best signal of the nearest full scan within [NEARBY_M]; with no scan that close it is
 * left out rather than shown as weak.
 */
object HeatCalc {
    const val NEARBY_M = 15.0

    fun strongestPerSpot(readings: List<WifiReading>): List<WifiReading> {
        if (readings.isEmpty()) return emptyList()
        // ~1 m grid: the strongest scan reading and the strongest reading of any kind in each cell.
        class Spot(var best: WifiReading, var bestScan: WifiReading?)
        val spots = HashMap<Pair<Long, Long>, Spot>()
        for (r in readings) {
            val key = Math.round(r.latitude * 1e5) to Math.round(r.longitude * 1e5)
            val s = spots[key]
            if (s == null) {
                spots[key] = Spot(r, if (r.connected) null else r)
            } else {
                if (r.rssi > s.best.rssi) s.best = r
                if (!r.connected && r.rssi > (s.bestScan?.rssi ?: Int.MIN_VALUE)) s.bestScan = r
            }
        }
        val scanned = spots.values.filter { it.bestScan != null }
        // Surveys from before scans were recorded everywhere have nothing to borrow from: show them as they are.
        if (scanned.isEmpty()) return spots.values.map { it.best }

        val mPerLat = 111_320.0
        val mPerLon = mPerLat * cos(Math.toRadians(readings[0].latitude)).coerceAtLeast(0.01)
        fun cell(r: WifiReading) = floor(r.latitude * mPerLat / NEARBY_M).toLong() to floor(r.longitude * mPerLon / NEARBY_M).toLong()
        val byCell = scanned.groupBy { cell(it.best) }

        val out = ArrayList<WifiReading>(spots.size)
        for (s in spots.values) {
            if (s.bestScan != null) {
                out += s.best
                continue
            }
            // Connected-AP reading only: use the nearest full scan's best signal, if it is stronger.
            val (cx, cy) = cell(s.best)
            var nearest: Spot? = null
            var nearestM = NEARBY_M
            for (dx in -1L..1L) for (dy in -1L..1L) {
                for (o in byCell[(cx + dx) to (cy + dy)].orEmpty()) {
                    val m = hypot((o.best.latitude - s.best.latitude) * mPerLat, (o.best.longitude - s.best.longitude) * mPerLon)
                    if (m <= nearestM) {
                        nearestM = m
                        nearest = o
                    }
                }
            }
            val near = nearest ?: continue
            val scan = near.bestScan!!
            out += if (scan.rssi > s.best.rssi) {
                s.best.copy(rssi = scan.rssi, bssid = scan.bssid, ssid = scan.ssid, frequency = scan.frequency)
            } else {
                s.best
            }
        }
        return out
    }
}
