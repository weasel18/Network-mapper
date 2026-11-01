package com.networkmapper.app

import com.networkmapper.app.wifi.WifiFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiFilterTest {
    @Test
    fun blocklist() {
        val f = WifiFilter(hiddenSsids = setOf("Neighbor-5G"), hiddenBssids = setOf("aa:bb:cc:dd:ee:ff"))
        assertTrue(f.allows("Corp", "11:22:33:44:55:66"))
        assertFalse(f.allows("Neighbor-5G", "11:22:33:44:55:66"))
        assertFalse(f.allows("Corp", "AA:BB:CC:DD:EE:FF"))
        assertTrue(f.allows("", "11:22:33:44:55:66")) // no-name networks allowed unless hidden
        assertFalse(f.copy(hideNoName = true).allows("", "11:22:33:44:55:66"))
    }

    @Test
    fun allowlist_stillHonoursHiddenBssids() {
        val f = WifiFilter(onlySsids = setOf("Corp", "Corp-Guest"), hiddenBssids = setOf("aa:bb:cc:dd:ee:ff"))
        assertTrue(f.allows("Corp", "11:22:33:44:55:66"))
        assertTrue(f.allows("Corp-Guest", "11:22:33:44:55:67"))
        assertFalse(f.allows("Coffee", "11:22:33:44:55:68"))
        assertFalse(f.allows("", "11:22:33:44:55:69"))
        assertFalse(f.allows("Corp", "aa:bb:cc:dd:ee:ff")) // rogue AP using the company SSID
    }
}
