package com.networkmapper.app.util

import com.networkmapper.app.data.WifiReading
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Works out which BSSIDs belong to the same physical access point (one per SSID per band).
 *
 * 1. MAC pattern ([Wifi.likelySameAp]) proposes candidates, walking outward from the chosen radio
 *    so a whole multi-SSID / multi-band AP is found, but never further than [MAX_SPAN] from it.
 * 2. Survey evidence confirms them: radios in one box are heard at almost the same relative
 *    strength everywhere you walk, so when two radios were heard together in at least
 *    [MIN_CO_SAMPLES] scans, the spread of their RSSI difference must stay within [MAX_STDEV_DB].
 *    Two radios on the same band but different channels need that evidence to be grouped.
 */
object RadioGrouping {
    const val MIN_CO_SAMPLES = 4
    const val MAX_STDEV_DB = 5.0
    const val MAX_SPAN = 63

    data class Radio(val bssid: String, val frequency: Int)

    private fun sampleKey(r: WifiReading) =
        "${r.sessionId}:${(r.latitude * 1e6).roundToLong()}:${(r.longitude * 1e6).roundToLong()}:${r.timestamp / 3000}"

    /** Standard deviation of the RSSI difference between two radios over scans where both were heard, or null. */
    fun rssiSpread(a: String, b: String, readings: List<WifiReading>): Pair<Int, Double>? {
        val scans = readings.filter { !it.connected }
        val ofA = scans.filter { it.bssid == a }.associateBy(::sampleKey)
        val deltas = scans.filter { it.bssid == b }.mapNotNull { r -> ofA[sampleKey(r)]?.let { (it.rssi - r.rssi).toDouble() } }
        if (deltas.isEmpty()) return null
        val mean = deltas.average()
        val sd = sqrt(deltas.sumOf { (it - mean) * (it - mean) } / deltas.size)
        return deltas.size to sd
    }

    /** True if survey data and channels allow [a] and [b] to be radios of one AP (MAC test done separately). */
    fun consistent(a: Radio, b: Radio, readings: List<WifiReading>): Boolean {
        val spread = rssiSpread(a.bssid, b.bssid, readings)
        if (spread != null && spread.first >= MIN_CO_SAMPLES) return spread.second <= MAX_STDEV_DB
        val sameBand = a.frequency > 0 && b.frequency > 0 && Wifi.band(a.frequency) == Wifi.band(b.frequency)
        val differentChannel = Wifi.channel(a.frequency) != Wifi.channel(b.frequency)
        // One radio per band carries all its SSIDs on one channel; different channels on the same band
        // without evidence most likely means two different APs.
        return !(sameBand && differentChannel)
    }

    /** All other radios in [pool] that belong to the same physical AP as [chosen]. */
    fun sameAp(chosen: Radio, pool: List<Radio>, readings: List<WifiReading>): List<Radio> {
        val others = pool.filter { it.bssid != chosen.bssid }.distinctBy { it.bssid }
        val accepted = LinkedHashMap<String, Radio>()
        val queue = ArrayDeque<Radio>().apply { add(chosen) }
        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            for (c in others) {
                if (c.bssid in accepted) continue
                if (!Wifi.likelySameAp(from.bssid, c.bssid)) continue
                if (Wifi.lowDistance(chosen.bssid, c.bssid) > MAX_SPAN) continue
                if (!consistent(chosen, c, readings)) continue
                accepted[c.bssid] = c
                queue.add(c)
            }
        }
        return accepted.values.toList()
    }
}
