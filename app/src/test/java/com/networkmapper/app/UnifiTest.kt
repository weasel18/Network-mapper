package com.networkmapper.app

import android.app.Application
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.ui.asset.approxFrequency
import com.networkmapper.app.unifi.UnifiMatch
import com.networkmapper.app.unifi.UnifiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnifiTest {

    private val classic = """
        {"meta":{"rc":"ok"},"data":[
          {"_id":"1","mac":"74:83:C2:AA:BB:CC","name":"AP-Lobby","model":"U6PRO","type":"uap","ip":"10.0.0.21",
           "vap_table":[
             {"bssid":"76:83:c2:ab:bb:cc","essid":"Corp","channel":6,"radio":"ng"},
             {"bssid":"76:83:c2:ac:bb:cc","essid":"Corp","channel":36,"radio":"na"},
             {"bssid":"76:83:c2:ac:bb:cc","essid":"Corp","channel":36,"radio":"na"}]},
          {"_id":"2","mac":"f4:92:bf:11:22:33","name":"SW-IDF1","model":"USW24P","type":"usw","ip":"10.0.0.2"},
          {"_id":"3","mac":"","name":"broken"}
        ]}
    """.trimIndent()

    private val integration = """
        {"offset":0,"limit":200,"count":2,"totalCount":2,"data":[
          {"id":"a","name":"AP-Lobby","model":"U6 Pro","macAddress":"74:83:c2:aa:bb:cc","ipAddress":"10.0.0.21","state":"ONLINE","features":["accessPoint"]},
          {"id":"b","name":"Gateway","model":"UCG Ultra","macAddress":"0c:ea:14:00:00:01","ipAddress":"10.0.0.1","features":["switching","gateway"]}
        ]}
    """.trimIndent()

    @Test
    fun parsesClassicDevicesWithRadios() {
        val devices = UnifiParser.parseClassicDevices(classic)
        assertEquals(2, devices.size)
        val ap = devices.first { it.name == "AP-Lobby" }
        assertEquals("74:83:c2:aa:bb:cc", ap.mac)
        assertEquals("ap", ap.kind)
        assertEquals(2, ap.radios.size)
        assertEquals("2.4 GHz", ap.radios.first { it.channel == 6 }.band)
        assertEquals(setOf("74:83:c2:aa:bb:cc", "76:83:c2:ab:bb:cc", "76:83:c2:ac:bb:cc"), ap.allMacs)
        assertEquals("switch", devices.first { it.name == "SW-IDF1" }.kind)
    }

    @Test
    fun parsesIntegrationDevices_andMergesRadiosFromClassic() {
        val (devices, total) = UnifiParser.parseIntegrationDevices(integration)
        assertEquals(2, total)
        assertEquals("ap", devices.first { it.name == "AP-Lobby" }.kind)
        assertEquals("switch", devices.first { it.name == "Gateway" }.kind) // switching listed first
        val merged = UnifiParser.mergeRadios(devices, UnifiParser.parseClassicDevices(classic))
        assertEquals(2, merged.first { it.name == "AP-Lobby" }.radios.size)
        assertTrue(merged.first { it.name == "Gateway" }.radios.isEmpty())
    }

    @Test
    fun parsesSites_andCacheRoundTrips() {
        val sites = UnifiParser.parseIntegrationSites("""{"data":[{"id":"uuid-1","internalReference":"default","name":"Default"}]}""")
        assertEquals("uuid-1", sites.single().id)
        assertEquals("default", sites.single().reference)
        val classicSites = UnifiParser.parseClassicSites("""{"data":[{"name":"default","desc":"HQ"},{"name":"b2","desc":""}]}""")
        assertEquals(listOf("HQ", "b2"), classicSites.map { it.name })
        val devices = UnifiParser.parseClassicDevices(classic)
        assertEquals(devices, UnifiParser.fromJson(UnifiParser.toJson(devices)))
    }

    @Test
    fun updatesMatchingPin_keepingWhatYouTyped() {
        val ap = UnifiParser.parseClassicDevices(classic).first { it.name == "AP-Lobby" }
        val isDefault: (AssetType, String) -> Boolean = { _, n -> n.startsWith("Wi-Fi access point") }
        val pin = Asset(
            type = AssetType.ACCESS_POINT, name = "Wi-Fi access point 2", latitude = 0.0, longitude = 0.0,
            attributes = mapOf("MAC / BSSID" to "74-83-C2-AA-BB-CC", "IP address" to "192.168.9.9"),
        )
        assertTrue(UnifiMatch.matches(pin, ap))
        val updated = UnifiMatch.updatePin(pin, ap, isDefault)!!
        assertEquals("AP-Lobby", updated.name)
        assertEquals("U6PRO", updated.attributes["Model"])
        assertEquals("192.168.9.9", updated.attributes["IP address"]) // not overwritten
        assertEquals("74:83:c2:aa:bb:cc, 76:83:c2:ab:bb:cc, 76:83:c2:ac:bb:cc", updated.attributes["MAC / BSSID"])
        assertEquals("Corp", updated.attributes["SSID"])

        val named = pin.copy(name = "Lobby ceiling")
        assertEquals("Lobby ceiling", UnifiMatch.updatePin(named, ap, isDefault)!!.name)
        assertNull(UnifiMatch.updatePin(updated, ap, isDefault)) // nothing left to change
    }

    @Test
    fun channelToFrequency() {
        assertEquals(2437, approxFrequency(6, "2.4 GHz"))
        assertEquals(5180, approxFrequency(36, "5 GHz"))
        assertEquals(5955, approxFrequency(1, "6 GHz"))
        assertEquals(0, approxFrequency(null, "5 GHz"))
    }
}
