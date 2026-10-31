package com.networkmapper.app.data

import androidx.room.TypeConverter
import org.json.JSONObject

class Converters {
    @TypeConverter
    fun assetTypeToString(value: AssetType): String = value.name

    @TypeConverter
    fun stringToAssetType(value: String): AssetType =
        AssetType.entries.firstOrNull { it.name == value } ?: AssetType.OTHER

    @TypeConverter
    fun pathKindToString(value: PathKind): String = value.name

    @TypeConverter
    fun stringToPathKind(value: String): PathKind =
        PathKind.entries.firstOrNull { it.name == value } ?: PathKind.FIBER_UNDERGROUND

    @TypeConverter
    fun mapToString(value: Map<String, String>): String = AttributeCodec.encode(value)

    @TypeConverter
    fun stringToMap(value: String): Map<String, String> = AttributeCodec.decode(value)

    @TypeConverter
    fun pointsToString(value: List<GeoPt>): String = PointCodec.encode(value)

    @TypeConverter
    fun stringToPoints(value: String): List<GeoPt> = PointCodec.decode(value)
}

object AttributeCodec {
    fun encode(value: Map<String, String>): String {
        val o = JSONObject()
        value.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun decode(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(value)
            val out = LinkedHashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optString(k)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/** Compact "lat,lon;lat,lon" encoding for path geometry. */
object PointCodec {
    fun encode(points: List<GeoPt>): String =
        points.joinToString(";") { "${it.lat},${it.lon}" }

    fun decode(value: String): List<GeoPt> {
        if (value.isBlank()) return emptyList()
        return value.split(';').mapNotNull { part ->
            val bits = part.split(',')
            if (bits.size != 2) return@mapNotNull null
            val lat = bits[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lon = bits[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            GeoPt(lat, lon)
        }
    }
}
