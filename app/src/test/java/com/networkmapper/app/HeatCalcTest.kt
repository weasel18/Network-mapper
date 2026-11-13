package com.networkmapper.app

import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.ui.HeatCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatCalcTest {
    private val lat0 = 40.0
    private val lon0 = -105.0
    private fun r(northM: Double, bssid: String, rssi: Int, connected: Boolean) = WifiReading(
        sessionId = 1, timestamp = 0, latitude = lat0 + northM / 111_320.0, longitude = lon0, accuracy = 3f,
        ssid = "Net", bssid = bssid, rssi = rssi, frequency = 5180, connected = connected,
    )

    @Test
    fun spotWithOnlyTheConnectedApTakesTheNearbyScan() {
        val readings = listOf(
            r(0.0, "far-ap", -82, connected = false), r(0.0, "near-ap", -48, connected = false), // a full scan
            r(6.0, "far-ap", -84, connected = true), // 6 m on: still stuck on the far AP, no scan here
        )
        val out = HeatCalc.strongestPerSpot(readings).sortedBy { it.latitude }
        assertEquals(2, out.size)
        assertEquals(-48, out[0].rssi)
        assertEquals(-48, out[1].rssi)
        assertEquals("near-ap", out[1].bssid)
        assertEquals(readings[2].latitude, out[1].latitude, 0.0) // still drawn where it was measured
    }

    @Test
    fun connectedOnlySpotFarFromAnyScanIsLeftOut() {
        val out = HeatCalc.strongestPerSpot(
            listOf(r(0.0, "near-ap", -50, connected = false), r(60.0, "far-ap", -85, connected = true)),
        )
        assertEquals(listOf(-50), out.map { it.rssi })
    }

    @Test
    fun strongerConnectedReadingIsKept_andOldSurveysWithoutScansStillShow() {
        val kept = HeatCalc.strongestPerSpot(
            listOf(r(0.0, "a", -70, connected = false), r(4.0, "b", -55, connected = true)),
        ).sortedBy { it.latitude }
        assertEquals(listOf(-70, -55), kept.map { it.rssi })

        val old = HeatCalc.strongestPerSpot(listOf(r(0.0, "a", -60, connected = true), r(30.0, "a", -75, connected = true)))
        assertEquals(2, old.size)
        assertTrue(old.all { it.connected })
    }
}
