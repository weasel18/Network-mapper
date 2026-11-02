package com.networkmapper.app

import android.app.Application
import com.networkmapper.app.unifi.UnifiDevice
import com.networkmapper.app.unifi.UnifiLiveParser
import com.networkmapper.app.unifi.UnifiParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which device each device is plugged into, from the console. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnifiUplinkTest {
    private val classic = """
        {"data":[
          {"mac":"74:83:c2:00:00:01","name":"Agg Switch","type":"usw","model":"US24P","ip":"192.168.10.186",
           "uplink":{"uplink_mac":"74:83:c2:00:00:99","uplink_remote_port":1,"type":"wire","speed":1000,"uplink_device_name":"GSR Core"}},
          {"mac":"74:83:c2:00:00:02","name":"SC Demark Pole Switch","type":"usw","model":"USWFLEX",
           "uplink":{"uplink_mac":"74:83:c2:00:00:01","uplink_remote_port":5,"type":"wire","speed":1000}},
          {"mac":"74:83:c2:00:00:99","name":"GSR Core","type":"udm","model":"UDMPRO"}
        ]}
    """.trimIndent()

    @Test
    fun classicDevicesCarryTheirUplink() {
        val list = UnifiParser.parseClassicDevices(classic).associateBy { it.name }
        assertEquals("74:83:c2:00:00:99", list.getValue("Agg Switch").uplinkMac)
        assertEquals(1, list.getValue("Agg Switch").uplinkPort)
        assertEquals("74:83:c2:00:00:01", list.getValue("SC Demark Pole Switch").uplinkMac)
        assertEquals(5, list.getValue("SC Demark Pole Switch").uplinkPort)
        assertEquals("", list.getValue("GSR Core").uplinkMac)

        // Survives the on-phone cache.
        val back = UnifiParser.fromJson(UnifiParser.toJson(list.values.toList())).associateBy { it.name }
        assertEquals(5, back.getValue("SC Demark Pole Switch").uplinkPort)
        assertEquals("74:83:c2:00:00:01", back.getValue("SC Demark Pole Switch").uplinkMac)
    }

    @Test
    fun integrationUplinkIdsResolveToMacs() {
        val devices = listOf(
            UnifiDevice(mac = "aa:00:00:00:00:01", name = "Agg", model = "", kind = "switch", ip = "", id = "id-agg"),
            UnifiDevice(mac = "aa:00:00:00:00:02", name = "Edge", model = "", kind = "switch", ip = "", id = "id-edge", uplinkDeviceId = "id-agg"),
        )
        val resolved = UnifiParser.resolveUplinks(devices).associateBy { it.name }
        assertEquals("aa:00:00:00:00:01", resolved.getValue("Edge").uplinkMac)
        assertEquals("", resolved.getValue("Agg").uplinkMac)
    }

    @Test
    fun liveClassicReportsUplinkDevice() {
        val one = """{"data":[{"mac":"74:83:c2:00:00:02","state":1,"ip":"192.168.10.74",
            "uplink":{"uplink_mac":"74:83:c2:00:00:01","uplink_remote_port":5,"type":"wire","speed":1000,"uplink_device_name":"Agg Switch"}}]}"""
        val live = UnifiLiveParser.parseClassic(one)!!
        assertEquals("74:83:c2:00:00:01", live.uplinkMac)
        assertEquals(5, live.uplinkPort)
        assertEquals("Agg Switch", live.uplinkName)
        assertEquals("console", live.via)
    }
}
