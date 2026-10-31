package com.networkmapper.app

import com.networkmapper.app.export.Importer
import com.networkmapper.app.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImporterTest {
    @Test
    fun parseCsv_handlesQuotesCommasAndNewlines() {
        val text = "session,ssid,rssi\r\n\"Walk, north\",\"Cafe \"\"Free\"\" WiFi\",-61\nS2,,-80\n"
        val rows = Importer.parseCsv(text)
        assertEquals(3, rows.size)
        assertEquals(listOf("session", "ssid", "rssi"), rows[0])
        assertEquals(listOf("Walk, north", "Cafe \"Free\" WiFi", "-61"), rows[1])
        assertEquals(listOf("S2", "", "-80"), rows[2])
    }

    @Test
    fun isoTimestamps_roundTripToTheSecond() {
        val now = (System.currentTimeMillis() / 1000) * 1000
        assertEquals(now, Fmt.parseIso(Fmt.iso(now)))
    }

    @Test
    fun photoFileStamp_isRecovered() {
        val t = Fmt.parseFileStamp("IMG_20261031_201500_1234.jpg")
        assertNotNull(t)
        assertEquals("20261031_201500", Fmt.fileStamp(t!!))
    }
}
