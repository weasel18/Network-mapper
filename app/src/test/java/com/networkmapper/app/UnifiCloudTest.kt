package com.networkmapper.app

import android.app.Application
import com.networkmapper.app.unifi.UnifiCloudClient
import com.networkmapper.app.util.Wifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnifiCloudTest {
    private val page = """
        {"data":[{"hostId":"h1","hostName":"UDM Pro","devices":[
          {"id":"a","mac":"7483C2AABB01","name":"Core SW","model":"USW Pro 24 PoE","shortname":"USWPRO24P","ip":"10.0.0.2",
           "status":"online","version":"7.1.66","updateAvailable":"7.2.1","startupTime":"2026-10-30T08:00:00Z","isConsole":false},
          {"id":"b","mac":"74:83:c2:aa:bb:02","name":"Lobby AP","model":"U6 Pro","shortname":"UAP6MP","ip":"10.0.0.21",
           "status":"offline","version":"6.6.77","updateAvailable":null,"startupTime":null,"isConsole":false},
          {"id":"c","mac":"7483C2AABB03","name":"Gateway","model":"UDM Pro","ip":"203.0.113.5","status":"online","version":"4.1","isConsole":true},
          {"id":"d","mac":"","name":"No MAC"}]}],
         "httpStatusCode":200,"traceId":"x","nextToken":"p2"}
    """.trimIndent()

    @Test
    fun parsesDevicesKindsAndStatus() {
        val list = UnifiCloudClient.parse(page)
        assertEquals(listOf("74:83:c2:aa:bb:01", "74:83:c2:aa:bb:02", "74:83:c2:aa:bb:03"), list.map { it.mac })
        assertEquals(listOf("switch", "ap", "gateway"), list.map { it.kind })
        val sw = list[0]
        assertTrue(sw.online)
        assertEquals("UDM Pro", sw.console)
        val live = sw.toLive()
        assertTrue(live.online)
        assertEquals("online (update 7.2.1 available)", live.stateLabel)
        assertTrue((live.uptimeSec ?: 0) > 0)
        val ap = list[1]
        assertFalse(ap.toLive().online)
        assertNull(ap.toLive().uptimeSec)
        assertEquals("", ap.updateAvailable)
        val dev = ap.toUnifiDevice()
        assertEquals("Lobby AP", dev.name)
        assertEquals("U6 Pro", dev.model)
        assertEquals("ap", dev.kind)
    }

    @Test
    fun smoothRampMatchesStepsAndBlends() {
        assertEquals(0x1B9E3E, Wifi.rssiColorSmooth(-40.0))
        assertEquals(0xFDD835, Wifi.rssiColorSmooth(-67.0))
        assertEquals(0x8E0000, Wifi.rssiColorSmooth(-100.0))
        val mid = Wifi.rssiColorSmooth(-71.0) // halfway between yellow (-67) and orange (-75)
        assertEquals((0xFD + 0xFB) / 2, (mid shr 16) and 0xFF)
        assertEquals((0xD8 + 0x8C) / 2, (mid shr 8) and 0xFF)
    }
}
