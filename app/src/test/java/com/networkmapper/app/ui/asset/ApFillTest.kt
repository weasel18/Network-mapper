package com.networkmapper.app.ui.asset

import com.networkmapper.app.data.AssetType
import com.networkmapper.app.ui.ApCandidate
import com.networkmapper.app.ui.ApSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ApFillTest {
    private fun form(type: AssetType, name: String, attrs: Map<String, String> = emptyMap()) =
        AssetForm(type, name, "Active", "", "40.0", "-105.0", null, null, attrs)

    private val isDefault: (AssetType, String) -> Boolean = { _, n -> n.startsWith("Wi-Fi access point") }

    private val radio24 = ApCandidate("aa:bb:cc:dd:ee:01", "Corp", 2412, -50, "AP-Lobby", ApSource.LIVE, null)
    private val radio5 = ApCandidate("aa:bb:cc:dd:ee:02", "Corp", 5180, -55, null, ApSource.NEARBY, null)

    @Test
    fun fillsMacSsidChannelsAndSavedName_keepingOtherDetails() {
        val out = fillFromAp(
            form(AssetType.ACCESS_POINT, "Wi-Fi access point 1", mapOf("Model" to "U6-Pro")),
            "MAC / BSSID", radio24, listOf(radio24, radio5), isDefault,
        )
        assertEquals("aa:bb:cc:dd:ee:01, aa:bb:cc:dd:ee:02", out.attributes["MAC / BSSID"])
        assertEquals("Corp", out.attributes["SSID"])
        assertEquals("1 (2.4 GHz), 36 (5 GHz)", out.attributes["Channel"])
        assertEquals("U6-Pro", out.attributes["Model"])
        assertEquals("AP-Lobby", out.name)
    }

    @Test
    fun keepsAName_youTypedYourself() {
        val out = fillFromAp(form(AssetType.ACCESS_POINT, "Gym AP"), "MAC / BSSID", radio24, listOf(radio24), isDefault)
        assertEquals("Gym AP", out.name)
        assertEquals("aa:bb:cc:dd:ee:01", out.attributes["MAC / BSSID"])
    }

    @Test
    fun bridgeGetsFrequencyInsteadOfChannel() {
        val out = fillFromAp(form(AssetType.WIRELESS_BRIDGE, "Wireless bridge 1"), "MAC", radio5, listOf(radio5), isDefault)
        assertEquals("aa:bb:cc:dd:ee:02", out.attributes["MAC"])
        assertEquals("5180 MHz", out.attributes["Frequency"])
        assertEquals(null, out.attributes["Channel"])
    }
}
