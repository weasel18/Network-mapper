package com.networkmapper.app

import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.PointCodec
import com.networkmapper.app.util.Geo
import com.networkmapper.app.util.Wifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun distance_oneDegreeLatitude_isAbout111km() {
        val d = Geo.distanceMeters(GeoPt(0.0, 0.0), GeoPt(1.0, 0.0))
        assertEquals(111_195.0, d, 50.0)
    }

    @Test
    fun pathLength_sumsSegments() {
        val pts = listOf(GeoPt(0.0, 0.0), GeoPt(0.0, 0.001), GeoPt(0.0, 0.002))
        assertEquals(2 * Geo.distanceMeters(pts[0], pts[1]), Geo.pathLengthMeters(pts), 1e-6)
    }

    @Test
    fun bearing_dueEast_is90() {
        assertEquals(90.0, Geo.bearingDegrees(GeoPt(0.0, 0.0), GeoPt(0.0, 1.0)), 1e-6)
        assertEquals("E", Geo.compassPoint(90.0))
        assertEquals("N", Geo.compassPoint(359.0))
    }

    @Test
    fun pointCodec_roundTrips() {
        val pts = listOf(GeoPt(40.1234567, -105.7654321), GeoPt(-33.5, 151.25))
        assertEquals(pts, PointCodec.decode(PointCodec.encode(pts)))
        assertTrue(PointCodec.decode("").isEmpty())
        assertEquals(1, PointCodec.decode("1,2;garbage").size)
    }

    @Test
    fun wifiChannels() {
        assertEquals(1, Wifi.channel(2412))
        assertEquals(6, Wifi.channel(2437))
        assertEquals(36, Wifi.channel(5180))
        assertEquals(149, Wifi.channel(5745))
        assertEquals(1, Wifi.channel(5955))
        assertEquals("6 GHz", Wifi.band(5955))
    }
}
