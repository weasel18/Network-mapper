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

    /** How often to request a Wi-Fi scan while surveying. */
    val scanIntervalMs = MutableStateFlow(30_000L)

    internal fun started(sessionId: Long) {
        _sessionId.value = sessionId
        _readings.value = 0
        _running.value = true
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
