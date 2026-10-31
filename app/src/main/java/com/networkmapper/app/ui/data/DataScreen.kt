@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.data

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.export.Exporter
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.ConfirmDialog
import com.networkmapper.app.util.Geo
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DataScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assets by vm.assets.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val photoCount by vm.repo.photoCount.collectAsStateWithLifecycle(0)
    val readingCount by vm.repo.readingCount.collectAsStateWithLifecycle(0)
    var busy by remember { mutableStateOf(false) }
    var confirmClearWifi by remember { mutableStateOf(false) }

    fun export(block: suspend () -> File) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                Exporter.share(context, block())
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export & data") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Summary", style = MaterialTheme.typography.titleMedium)
                    Text("${assets.size} pins • ${paths.size} paths • $photoCount photos • $readingCount Wi-Fi readings")
                    AssetType.entries.forEach { t ->
                        val n = assets.count { it.type == t }
                        if (n > 0) Text("  ${t.label}: $n", style = MaterialTheme.typography.bodySmall)
                    }
                    PathKind.entries.forEach { k ->
                        val list = paths.filter { it.kind == k }
                        if (list.isNotEmpty()) {
                            val len = list.sumOf { Geo.pathLengthMeters(it.points) }
                            Text("  ${k.label}: ${list.size} (${Geo.formatDistance(len)})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Text("Export", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { export { Exporter.exportKmz(context, vm.repo) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Public, null)
                Spacer(Modifier.width(8.dp))
                Text("KMZ for Google Earth (pins, paths, photos)")
            }
            Button(onClick = { export { Exporter.exportZip(context, vm.repo) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Full data ZIP (GeoJSON, KML, Wi-Fi CSV, photos)")
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Preparing export…")
                }
            }
            Text(
                "GeoJSON opens in QGIS, ArcGIS and most web maps. The Wi-Fi CSV has one row per sample " +
                    "(time, lat/lon, SSID, BSSID, RSSI, channel) for coverage reports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Manage", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { confirmClearWifi = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete all Wi-Fi survey data")
            }

            Text("Tips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "• Long-press the map to drop a pin anywhere; the + button drops one at your GPS position.\n" +
                    "• The camera button creates a photo pin at your location; photos are GPS-tagged in EXIF too.\n" +
                    "• Use the path tool to draw fiber runs: tap pins to snap the ends to junctions or splices, " +
                    "or turn on Auto-track and walk the route. Tap Finish to save.\n" +
                    "• To edit or continue a route later, tap it on the map → Edit / extend route. Existing points are " +
                    "locked until you switch to ‘Edit points’; then tap to select, drag, delete or insert. In ‘Add points’, " +
                    "switch ‘Adding at end/start’ to extend either end.\n" +
                    "• Wireless links: choose ‘Wireless link’ and tap the two bridge pins.\n" +
                    "• Satellite imagery is in the Layers menu. Map tiles you've viewed stay cached for offline use.\n" +
                    "• Pins never move by accident: tap a pin → Move, then tap the new spot, drag it, or use GPS, and Save position.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmClearWifi) {
        ConfirmDialog(
            title = "Delete Wi-Fi data?",
            text = "All $readingCount survey readings will be removed. Pins, paths and photos are not affected.",
            confirm = "Delete",
            onConfirm = { scope.launch { vm.repo.deleteAllSurveys() } },
            onDismiss = { confirmClearWifi = false },
        )
    }
}
