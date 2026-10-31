package com.networkmapper.app.data

import java.util.Locale

enum class PatternType(val short: String, val label: String, val defaultBeamwidth: Double) {
    OMNI("Omni", "Omni (360°)", 360.0),
    SECTOR("Sector", "Sector antenna", 120.0),
    DIRECTIONAL("Directional", "Directional / PtP dish", 15.0),
}

/** An antenna coverage pattern drawn around an AP / bridge / router pin. */
data class CoveragePattern(
    val type: PatternType,
    /** Direction the antenna faces, degrees clockwise from true north (ignored for omni). */
    val azimuth: Double,
    /** Horizontal beamwidth in degrees (360 for omni). */
    val beamwidth: Double,
    /** Usable range in metres (outer edge of the pattern). */
    val rangeM: Double,
) {
    val effectiveBeamwidth: Double get() = if (type == PatternType.OMNI) 360.0 else beamwidth.coerceIn(1.0, 359.0)

    fun summary(): String = buildString {
        append(type.short)
        if (type != PatternType.OMNI) {
            append(String.format(Locale.US, " %.0f°", beamwidth))
            append(String.format(Locale.US, " facing %.0f°", azimuth))
        }
        append(String.format(Locale.US, ", %.0f m", rangeM))
    }
}

/**
 * Coverage patterns are stored in the pin's details (attributes) so they show in the details list,
 * export to GeoJSON/KML and travel in backups without a schema change.
 */
object Coverage {
    const val KEY_PATTERN = "Coverage pattern"
    const val KEY_AZIMUTH = "Azimuth (°)"
    const val KEY_BEAMWIDTH = "Beamwidth (°)"
    const val KEY_RANGE = "Coverage range (m)"

    val keys = setOf(KEY_PATTERN, KEY_AZIMUTH, KEY_BEAMWIDTH, KEY_RANGE)

    private val number = Regex("-?\\d+(?:[.,]\\d+)?")

    fun parseNumber(text: String?): Double? =
        text?.let { number.find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }

    /** Sensible starting pattern for a newly configured device. */
    fun defaults(type: AssetType): CoveragePattern = when (type) {
        AssetType.WIRELESS_BRIDGE -> CoveragePattern(PatternType.DIRECTIONAL, 0.0, 15.0, 1000.0)
        AssetType.ROUTER -> CoveragePattern(PatternType.OMNI, 0.0, 360.0, 25.0)
        else -> CoveragePattern(PatternType.OMNI, 0.0, 360.0, 30.0)
    }

    fun read(attrs: Map<String, String>): CoveragePattern? {
        val raw = attrs[KEY_PATTERN]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val type = PatternType.entries.firstOrNull { raw.startsWith(it.short, ignoreCase = true) } ?: return null
        val beam = parseNumber(attrs[KEY_BEAMWIDTH]) ?: type.defaultBeamwidth
        val az = ((parseNumber(attrs[KEY_AZIMUTH]) ?: 0.0) % 360 + 360) % 360
        val range = parseNumber(attrs[KEY_RANGE])?.takeIf { it > 0 } ?: 30.0
        return CoveragePattern(type, az, if (type == PatternType.OMNI) 360.0 else beam, range)
    }

    /** Returns [attrs] with the pattern written (or removed when [pattern] is null; azimuth is kept for bridges). */
    fun write(attrs: Map<String, String>, pattern: CoveragePattern?): Map<String, String> {
        val out = LinkedHashMap(attrs)
        if (pattern == null) {
            out.remove(KEY_PATTERN)
            out.remove(KEY_BEAMWIDTH)
            out.remove(KEY_RANGE)
            return out
        }
        out[KEY_PATTERN] = pattern.type.short
        if (pattern.type == PatternType.OMNI) {
            out.remove(KEY_BEAMWIDTH)
        } else {
            out[KEY_AZIMUTH] = String.format(Locale.US, "%.0f", ((pattern.azimuth % 360) + 360) % 360)
            out[KEY_BEAMWIDTH] = String.format(Locale.US, "%.0f", pattern.beamwidth)
        }
        out[KEY_RANGE] = String.format(Locale.US, "%.0f", pattern.rangeM)
        return out
    }
}
