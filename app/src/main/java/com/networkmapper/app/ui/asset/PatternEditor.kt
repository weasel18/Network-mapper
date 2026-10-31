@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.asset

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.Coverage
import com.networkmapper.app.data.CoveragePattern
import com.networkmapper.app.data.PatternType
import com.networkmapper.app.ui.components.rememberCompassHeading
import com.networkmapper.app.util.Geo
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Edits the antenna coverage pattern stored in a pin's details. */
@Composable
fun PatternEditor(
    type: AssetType,
    attrs: Map<String, String>,
    lat: Double?,
    lon: Double?,
    hasLinkedRadios: Boolean,
    onChange: (Map<String, String>) -> Unit,
    surveyedRange: suspend () -> Pair<Double?, Double?>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pattern = Coverage.read(attrs)
    fun set(p: CoveragePattern?) = onChange(Coverage.write(attrs, p))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Coverage pattern", fontWeight = FontWeight.SemiBold)
            Text(
                "Drawn on the map around this pin (Layers → AP coverage patterns).",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = pattern == null, onClick = { set(null) }, label = { Text("None") })
                PatternType.entries.forEach { t ->
                    FilterChip(
                        selected = pattern?.type == t,
                        onClick = {
                            val base = pattern ?: Coverage.defaults(type).copy(
                                azimuth = Coverage.parseNumber(attrs[Coverage.KEY_AZIMUTH]) ?: 0.0,
                            )
                            set(base.copy(type = t, beamwidth = if (base.type == t) base.beamwidth else t.defaultBeamwidth))
                        },
                        label = { Text(t.short) },
                    )
                }
            }
            if (pattern == null) return@Column

            Row(verticalAlignment = Alignment.CenterVertically) {
                PatternPreview(pattern, Modifier.size(96.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(pattern.summary(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "Shaded green → yellow → orange: strong (½ range), good (⅘), weak (edge).",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (pattern.type != PatternType.OMNI) {
                val heading by rememberCompassHeading(lat, lon)
                Text(
                    String.format(Locale.US, "Facing %.0f° %s (true north)", pattern.azimuth, Geo.compassPoint(pattern.azimuth)),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = pattern.azimuth.toFloat(),
                    onValueChange = { set(pattern.copy(azimuth = it.roundToInt().toDouble())) },
                    valueRange = 0f..359f,
                )
                val h = heading
                OutlinedButton(
                    onClick = { if (h != null) set(pattern.copy(azimuth = h.roundToInt().toDouble() % 360)) },
                    enabled = h != null,
                ) {
                    Icon(Icons.Default.Explore, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (h != null) "Aim with phone (${h.roundToInt()}°)" else "No compass on this phone")
                }
                Text(
                    "Stand at the antenna, hold the phone flat with its top edge pointing the way the antenna faces, then tap.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(String.format(Locale.US, "Beamwidth %.0f°", pattern.beamwidth), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = pattern.beamwidth.toFloat().coerceIn(3f, 180f),
                    onValueChange = { set(pattern.copy(beamwidth = it.roundToInt().toDouble())) },
                    valueRange = 3f..180f,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 30, 60, 90, 120).forEach { bw ->
                        FilterChip(
                            selected = pattern.beamwidth.roundToInt() == bw,
                            onClick = { set(pattern.copy(beamwidth = bw.toDouble())) },
                            label = { Text("$bw°") },
                        )
                    }
                }
            }

            var rangeText by remember(pattern.type) { mutableStateOf(String.format(Locale.US, "%.0f", pattern.rangeM)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rangeText,
                    onValueChange = { v ->
                        rangeText = v
                        Coverage.parseNumber(v)?.takeIf { it > 0 }?.let { set(pattern.copy(rangeM = it)) }
                    },
                    label = { Text("Range (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    enabled = hasLinkedRadios && lat != null && lon != null,
                    onClick = {
                        scope.launch {
                            val (reliable, usable) = surveyedRange()
                            val r = usable ?: reliable
                            if (r == null || r < 1.0) {
                                Toast.makeText(context, "No survey samples for this pin's radios yet", Toast.LENGTH_LONG).show()
                            } else {
                                rangeText = String.format(Locale.US, "%.0f", r)
                                set(pattern.copy(rangeM = r))
                                Toast.makeText(
                                    context,
                                    String.format(Locale.US, "Usable to %.0f m%s", r, reliable?.let { String.format(Locale.US, ", reliable to %.0f m", it) } ?: ""),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.Wifi, null)
                    Spacer(Modifier.width(4.dp))
                    Text("From survey")
                }
            }
            if (!hasLinkedRadios) {
                Text(
                    "Link this pin's radio (Fill from Wi-Fi above) to size the range from your survey.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PatternPreview(p: CoveragePattern, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(ring, radius = r, center = c, style = Stroke(width = 1.dp.toPx()))
        // North tick
        drawLine(text, Offset(c.x, c.y - r), Offset(c.x, c.y - r + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
        listOf(1.0f to Color(0x80FB8C00), 0.8f to Color(0x99FDD835), 0.5f to Color(0xB343A047)).forEach { (f, color) ->
            band(p, c, r * 0.92f * f, color)
        }
        drawCircle(text, radius = 3.dp.toPx(), center = c)
    }
}

private fun DrawScope.band(p: CoveragePattern, c: Offset, r: Float, color: Color) {
    if (p.type == PatternType.OMNI) {
        drawCircle(color, radius = r, center = c)
    } else {
        val beam = p.effectiveBeamwidth.toFloat()
        drawArc(
            color = color,
            startAngle = (p.azimuth - 90.0 - beam / 2.0).toFloat(),
            sweepAngle = beam,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
        )
    }
}
