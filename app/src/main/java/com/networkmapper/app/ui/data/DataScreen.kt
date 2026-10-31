@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkmapper.app.BuildConfig
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.ConfirmDialog
import com.networkmapper.app.ui.site.ExportSitesDialog
import com.networkmapper.app.ui.site.rememberSiteImport
import com.networkmapper.app.util.Geo

@Composable
fun DataScreen(vm: MainViewModel, onBack: () -> Unit, onOpenSites: () -> Unit) {
    val assets by vm.assets.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val photoCount by vm.sitePhotoCount.collectAsStateWithLifecycle()
    val readingCount by vm.siteReadingCount.collectAsStateWithLifecycle()
    val site by vm.currentSite.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    var confirmClearWifi by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val startImport = rememberSiteImport(vm)

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
                    Text("Site: ${site?.name ?: "…"}", style = MaterialTheme.typography.titleMedium)
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
                    TextButton(onClick = onOpenSites) { Text("${sites.size} site(s) on this phone – manage sites") }
                    Text("App version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Export & import", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { showExport = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Upload, null)
                Spacer(Modifier.width(8.dp))
                Text("Export sites… (backup ZIP or Google Earth KMZ)")
            }
            OutlinedButton(onClick = startImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Import / restore sites…")
            }
            Text(
                "Export: pick which sites to include (all by default). A backup ZIP holds everything – sites, pins, " +
                    "paths, colours, photos and Wi-Fi surveys – plus GeoJSON, KML and CSV for QGIS/ArcGIS/Excel.\n" +
                    "Import: choose which sites from the file to load and whether each becomes a new site or merges " +
                    "into an existing one. Older single-site backups work too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Manage", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onOpenSites, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Map, null)
                Spacer(Modifier.width(8.dp))
                Text("Sites – create, open, rename, delete")
            }
            OutlinedButton(onClick = { confirmClearWifi = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete this site's Wi-Fi survey data")
            }

            Text("Tips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "• Tap the site name at the top of the map to switch sites or create a new one.\n" +
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

    if (showExport) ExportSitesDialog(vm, onDismiss = { showExport = false })

    if (confirmClearWifi) {
        ConfirmDialog(
            title = "Delete Wi-Fi data?",
            text = "All $readingCount survey readings in “${site?.name ?: "this site"}” will be removed. " +
                "Pins, paths, photos and other sites are not affected.",
            confirm = "Delete",
            onConfirm = { vm.deleteSurveysForCurrentSite() },
            onDismiss = { confirmClearWifi = false },
        )
    }
}
