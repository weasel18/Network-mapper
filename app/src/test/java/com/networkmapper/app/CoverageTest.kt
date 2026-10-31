package com.networkmapper.app

import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.Coverage
import com.networkmapper.app.data.CoveragePattern
import com.networkmapper.app.data.PatternType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverageTest {
    @Test
    fun sectorPattern_roundTripsThroughAttributes() {
        val p = CoveragePattern(PatternType.SECTOR, azimuth = 405.0, beamwidth = 90.0, rangeM = 120.0)
        val attrs = Coverage.write(mapOf("Model" to "LAP-120"), p)
        assertEquals("Sector", attrs[Coverage.KEY_PATTERN])
        assertEquals("45", attrs[Coverage.KEY_AZIMUTH])
        assertEquals("LAP-120", attrs["Model"])
        assertEquals(CoveragePattern(PatternType.SECTOR, 45.0, 90.0, 120.0), Coverage.read(attrs))
    }

    @Test
    fun readsHandTypedValues_andRemovesCleanly() {
        val attrs = mapOf(
            Coverage.KEY_PATTERN to "directional",
            Coverage.KEY_AZIMUTH to "270° (W)",
            Coverage.KEY_BEAMWIDTH to "8,5",
            Coverage.KEY_RANGE to "2500 m",
        )
        assertEquals(CoveragePattern(PatternType.DIRECTIONAL, 270.0, 8.5, 2500.0), Coverage.read(attrs))
        val cleared = Coverage.write(attrs, null)
        assertNull(Coverage.read(cleared))
        assertEquals("270° (W)", cleared[Coverage.KEY_AZIMUTH]) // bridges keep their azimuth detail
    }

    @Test
    fun omniIgnoresBeamwidth_andDefaultsByType() {
        assertEquals(360.0, Coverage.read(mapOf(Coverage.KEY_PATTERN to "Omni", Coverage.KEY_BEAMWIDTH to "60"))!!.beamwidth, 0.0)
        assertEquals(PatternType.DIRECTIONAL, Coverage.defaults(AssetType.WIRELESS_BRIDGE).type)
        assertEquals(PatternType.OMNI, Coverage.defaults(AssetType.ACCESS_POINT).type)
    }
}
