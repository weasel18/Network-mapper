package com.networkmapper.app.survey

import android.location.Location
import com.networkmapper.app.wifi.ScanEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide state shared between [SurveyService] and the UI. */
object SurveyState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    private val _readings = MutableStateFlow(0)
    val readings: StateFlow<Int> = _readings.asStateFlow()

    val lastScan = MutableStateFlow<List<ScanEntry>>(emptyList())
    val lastScanAt = MutableStateFlow(0L)
    val lastLocation = MutableStateFlow<Location?>(null)

    /** How often to ask for a Wi-Fi scan while surveying (Android may refuse some; see [refusedScans]). */
    val scanIntervalMs = MutableStateFlow(5_000L)

    /** Average time between scan results actually received, over the last few scans (null until known). */
    val actualScanGapMs = MutableStateFlow<Long?>(null)

    /** Scan requests Android turned down this survey (Wi-Fi scan throttling is on). */
    val refusedScans = MutableStateFlow(0)
    private val scanTimes = ArrayDeque<Long>()

    internal fun scanArrived(at: Long) {
        scanTimes.addLast(at)
        while (scanTimes.size > 6) scanTimes.removeFirst()
        if (scanTimes.size >= 2) actualScanGapMs.value = (scanTimes.last() - scanTimes.first()) / (scanTimes.size - 1)
    }

    /** Short status for the map chip / notification, e.g. "scan every ~31 s (throttled)". */
    fun scanSummary(): String? {
        val gap = actualScanGapMs.value ?: return null
        return "scan every ~${(gap + 500) / 1000} s" + if (refusedScans.value > 0) " (Android is limiting scans)" else ""
    }

    internal fun started(sessionId: Long) {
        _sessionId.value = sessionId
        _readings.value = 0
        _running.value = true
        scanTimes.clear()
        actualScanGapMs.value = null
        refusedScans.value = 0
    }

    internal fun added(count: Int) {
        _readings.value += count
    }

    internal fun stopped() {
        _running.value = false
        _sessionId.value = null
        lastScan.value = emptyList()
    }
}
