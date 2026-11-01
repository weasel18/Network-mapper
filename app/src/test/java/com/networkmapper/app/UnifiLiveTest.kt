package com.networkmapper.app

import android.app.Application
import com.networkmapper.app.unifi.UnifiLiveParser
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
class UnifiLiveTest {

    @Test
    fun parsesClassicApStats() {
        val json = """
            {"meta":{"rc":"ok"},"data":[{"mac":"74:83:c2:aa:bb:cc","state":1,"uptime":273600,"num_sta":12,
              "version":"6.6.77","ip":"10.0.0.21","satisfaction":97,
              "system-stats":{"cpu":"7.1","mem":"41.0"},
              "uplink":{"type":"wire","speed":1000,"uplink_remote_port":5},
              "tx_bytes-r":250000,"rx_bytes-r":1800000,
              "radio_table_stats":[
                {"radio":"ng","channel":6,"tx_power":17,"num_sta":4,"cu_total":38},
                {"radio":"na","channel":36,"tx_power":23,"num_sta":8,"cu_total":12}]}]}
        """.trimIndent()
        val live = UnifiLiveParser.parseClassic(json)!!
        assertTrue(live.online)
        assertEquals("Online", live.stateLabel)
        assertEquals(273600L, live.uptimeSec)
        assertEquals(12, live.clients)
        assertEquals(7.1, live.cpuPct!!, 0.01)
        assertEquals("1 Gbps wired to port 5", live.uplink)
        assertEquals(2_000_000.0, live.txBps!!, 0.1)
        assertEquals(2, live.radios.size)
        assertEquals("5 GHz", live.radios[1].band)
        assertEquals(12, live.radios[1].utilization)
        assertEquals("3d 4h", UnifiLiveParser.formatUptime(live.uptimeSec!!))
    }

    @Test
    fun parsesClassicSwitchPorts() {
        val json = """
            {"data":[{"mac":"f4:92:bf:11:22:33","state":0,"port_table":[
              {"port_idx":2,"name":"Port 2","up":false,"speed":0},
              {"port_idx":1,"name":"AP-Lobby","up":true,"speed":1000,"poe_power":"6.42","is_uplink":false},
              {"port_idx":25,"name":"SFP+ 1","up":true,"speed":10000,"is_uplink":true}]}]}
        """.trimIndent()
        val live = UnifiLiveParser.parseClassic(json)!!
        assertFalse(live.online)
        assertEquals("Offline", live.stateLabel)
        assertEquals(listOf(1, 2, 25), live.ports.map { it.index })
        assertEquals(6.42, live.ports[0].poeWatts!!, 0.001)
        assertNull(live.ports[1].speedMbps)
        assertTrue(live.ports[2].isUplink)
        assertEquals("10 Gbps", UnifiLiveParser.formatSpeed(10000))
        assertNull(UnifiLiveParser.parseClassic("""{"data":[]}"""))
    }

    @Test
    fun parsesIntegrationDetailAndStats() {
        val detail = """
            {"id":"a","name":"AP-Lobby","state":"ONLINE","firmwareVersion":"6.6.77","ipAddress":"10.0.0.21",
             "interfaces":{"radios":[{"frequencyGHz":5,"channel":36},{"frequencyGHz":2.4,"channel":6}],
                           "ports":[{"idx":1,"state":"UP","speedMbps":2500}]}}
        """.trimIndent()
        val stats = """{"uptimeSec":3600,"cpuUtilizationPct":12.5,"memoryUtilizationPct":40,"uplink":{"txRateBps":1000000,"rxRateBps":5000000}}"""
        val live = UnifiLiveParser.parseIntegration(detail, stats)
        assertTrue(live.online)
        assertEquals("Online", live.stateLabel)
        assertEquals(3600L, live.uptimeSec)
        assertEquals(12.5, live.cpuPct!!, 0.01)
        assertEquals(listOf("5 GHz", "2.4 GHz"), live.radios.map { it.band })
        assertEquals(2500, live.ports.single().speedMbps)
        assertEquals("5.0 Mbps", UnifiLiveParser.formatRate(live.rxBps!!))
    }
}
