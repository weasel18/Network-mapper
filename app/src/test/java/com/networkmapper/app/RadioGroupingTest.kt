package com.networkmapper.app

import com.networkmapper.app.data.WifiReading
import com.networkmapper.app.util.RadioGrouping
import com.networkmapper.app.util.RadioGrouping.Radio
import com.networkmapper.app.util.Wifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioGroupingTest {

    private fun scan(i: Int, bssid: String, rssi: Int) = WifiReading(
        sessionId = 1, timestamp = i * 10_000L, latitude = 40.0 + i * 1e-4, longitude = -105.0,
        accuracy = 3f, ssid = "", bssid = bssid, rssi = rssi, frequency = 0,
    )

    @Test
    fun unifiStyle_multiSsidMultiBand_allGrouped() {
        // Locally-administered first octet varies per SSID; 4th octet steps per band.
        val chosen = Radio("76:83:c2:ab:bb:cc", 2437)
        val pool = listOf(
            Radio("76:83:c2:ac:bb:cc", 5180), // same SSID, 5 GHz
            Radio("7a:83:c2:ab:bb:cc", 2437), // 2nd SSID, 2.4 GHz
            Radio("7e:83:c2:ac:bb:cc", 5180), // 2nd SSID, 5 GHz
            Radio("76:83:c2:ab:bb:cd", 2462), // different AP: same band, other channel, no evidence
            Radio("76:44:c2:ab:bb:cc", 2437), // different vendor block
        )
        val grouped = RadioGrouping.sameAp(chosen, pool, emptyList()).map { it.bssid }.toSet()
        assertEquals(setOf("76:83:c2:ac:bb:cc", "7a:83:c2:ab:bb:cc", "7e:83:c2:ac:bb:cc"), grouped)
    }

    @Test
    fun sequentialNextUpMacs_includingCarry() {
        assertTrue(Wifi.likelySameAp("aa:bb:cc:00:00:3f", "aa:bb:cc:00:00:40"))
        assertTrue(Wifi.likelySameAp("aa:bb:cc:00:ff:fe", "aa:bb:cc:01:00:02"))
        val chosen = Radio("aa:bb:cc:00:00:30", 2412)
        val pool = (1..7).map { Radio("aa:bb:cc:00:00:%02x".format(0x30 + it), if (it < 4) 2412 else 5180) }
        assertEquals(7, RadioGrouping.sameAp(chosen, pool, emptyList()).size)
    }

    @Test
    fun groupingDoesNotChainIntoNeighbouringAps() {
        val chosen = Radio("aa:bb:cc:00:00:00", 2412)
        val pool = listOf(30, 60, 90).map { Radio("aa:bb:cc:00:00:%02x".format(it), 2412) }
        val grouped = RadioGrouping.sameAp(chosen, pool, emptyList()).map { it.bssid }
        assertEquals(listOf("aa:bb:cc:00:00:1e", "aa:bb:cc:00:00:3c"), grouped)
    }

    @Test
    fun surveySignalConfirmsOrRejects() {
        val a = "aa:bb:cc:00:00:10"
        val tri = "aa:bb:cc:00:00:14"   // 2nd 5 GHz radio of a tri-band AP (other channel)
        val other = "aa:bb:cc:00:00:20" // a different AP on the same channel
        val readings = (0 until 8).flatMap { i ->
            listOf(
                scan(i, a, -50 - i * 3),
                scan(i, tri, -53 - i * 3),              // follows a closely
                scan(i, other, -80 + i * 4),            // gets stronger as a gets weaker
            )
        }
        val chosen = Radio(a, 5180)
        val grouped = RadioGrouping.sameAp(chosen, listOf(Radio(tri, 5745), Radio(other, 5180)), readings).map { it.bssid }
        assertEquals(listOf(tri), grouped)
        assertTrue(RadioGrouping.rssiSpread(a, tri, readings)!!.second < 1.0)
        assertFalse(RadioGrouping.consistent(chosen, Radio(other, 5180), readings))
    }
}
