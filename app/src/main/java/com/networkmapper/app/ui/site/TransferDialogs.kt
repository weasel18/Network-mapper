package com.networkmapper.app.ui.site

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.networkmapper.app.data.SiteWithCounts
import com.networkmapper.app.export.Exporter
import com.networkmapper.app.export.ImportPreview
import com.networkmapper.app.export.ImportTarget
import com.networkmapper.app.export.Importer
import com.networkmapper.app.export.SiteInFile
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.DropdownField
import kotlinx.coroutines.launch
import java.io.File

private enum class ExportFormat(val label: String, val detail: String) {
    BACKUP("Full backup (ZIP)", "Everything – pins, paths, photos, Wi-Fi surveys. Can be imported again. Includes GeoJSON/KML/CSV for GIS."),
    KMZ("Google Earth (KMZ)", "Pins, paths and photos for viewing. Can't be imported back."),
}

private fun countsLine(pins: Int, paths: Int, surveys: Int, readings: Int? = null) = buildString {
    append("$pins pins • $paths paths • $surveys surveys")
    if (readings != null && readings > 0) append(" ($readings readings)")
}

@Composable
private fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(text)
            }
        },
    )
}

/** Pick sites and a format, then share or save the export. All sites are selected by default. */
@Composable
fun ExportSitesDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sites by vm.sites.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(vm.sites.value.map { it.site.id }.toSet()) }
    var format by remember { mutableStateOf(ExportFormat.BACKUP) }
    var busy by remember { mutableStateOf(false) }

    suspend fun build(): File =
        if (format == ExportFormat.BACKUP) Exporter.exportZip(context, vm.repo, selected) else Exporter.exportKmz(context, vm.repo, selected)

    fun runSave(uri: Uri?) {
        if (uri == null) return
        busy = true
        scope.launch {
            try {
                Exporter.saveTo(context, build(), uri)
                Toast.makeText(context, "Export saved", Toast.LENGTH_LONG).show()
                onDismiss()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { runSave(it) }
    val saveKmz = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kmz")) { runSave(it) }

    if (busy) {
        BusyDialog("Preparing export…")
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export sites") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} of ${sites.size} sites", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        selected = if (selected.size == sites.size) emptySet() else sites.map { it.site.id }.toSet()
                    }) { Text(if (selected.size == sites.size) "Select none" else "Select all") }
                }
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(sites, key = { it.site.id }) { s ->
                        SiteCheckRow(s, s.site.id in selected) { on ->
                            selected = if (on) selected + s.site.id else selected - s.site.id
                        }
                    }
                }
                HorizontalDivider()
                ExportFormat.entries.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { format = f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Column {
                            Text(f.label, fontWeight = FontWeight.Medium)
                            Text(f.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                Exporter.share(context, build())
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Share…") }
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            val ext = if (format == ExportFormat.BACKUP) "zip" else "kmz"
                            val name = Exporter.suggestedName(vm.repo, selected, ext)
                            if (format == ExportFormat.BACKUP) saveZip.launch(name) else saveKmz.launch(name)
                        }
                    },
                ) { Text("Save to device…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SiteCheckRow(s: SiteWithCounts, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text(s.site.name, fontWeight = FontWeight.Medium)
            Text(countsLine(s.pins, s.paths, s.surveys), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Where one site from the file should go. */
private data class TargetOption(val label: String, val target: ImportTarget)

/**
 * Returns a function that starts an import: pick a backup file, choose which of its sites to
 * import and into which site (new or existing), then import. Shows its own dialogs.
 */
@Composable
fun rememberSiteImport(vm: MainViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        fileUri = uri
        busyText = "Reading backup…"
        scope.launch {
            try {
                preview = Importer.inspect(context, uri)
            } catch (e: Exception) {
                message = "Couldn't read this file: ${e.message}"
            } finally {
                busyText = null
            }
        }
    }

    busyText?.let { BusyDialog(it) }

    val p = preview
    val uri = fileUri
    if (p != null && uri != null && busyText == null) {
        ImportSelectDialog(
            vm = vm,
            preview = p,
            onDismiss = { preview = null },
            onImport = { targets ->
                preview = null
                busyText = "Importing…"
                scope.launch {
                    message = try {
                        Importer.importZip(context, vm.repo, uri, targets).summary
                    } catch (e: Exception) {
                        "Import failed: ${e.message}"
                    } finally {
                        busyText = null
                    }
                }
            },
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Import") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    return { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) }
}

@Composable
private fun ImportSelectDialog(
    vm: MainViewModel,
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onImport: (Map<String, ImportTarget>) -> Unit,
) {
    val existing = vm.sites.value.map { it.site }
    val currentId = vm.currentSiteId.value

    fun optionsFor(s: SiteInFile): List<TargetOption> =
        listOf(TargetOption("New site “${s.name}”", ImportTarget.New(s.name))) +
            existing.map { TargetOption("Into existing “${it.name}”", ImportTarget.Existing(it.id)) }

    fun defaultFor(s: SiteInFile): TargetOption {
        val opts = optionsFor(s)
        val sameName = existing.firstOrNull { it.name.equals(s.name, ignoreCase = true) }
        return when {
            sameName != null -> opts.first { (it.target as? ImportTarget.Existing)?.siteId == sameName.id }
            // Old single-site backups go into the open site by default.
            preview.legacy -> opts.firstOrNull { (it.target as? ImportTarget.Existing)?.siteId == currentId } ?: opts.first()
            else -> opts.first()
        }
    }

    var chosen by remember { mutableStateOf(preview.sites.associate { it.key to defaultFor(it) }) }
    var selected by remember { mutableStateOf(preview.sites.map { it.key }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import sites") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (preview.legacy) {
                        "This backup is from before sites existed. Choose where its data should go."
                    } else {
                        "${preview.sites.size} site(s) in this file. Tick the ones to import and choose where each goes. " +
                            "Items already in the target site are skipped."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (preview.sites.size > 1) {
                    TextButton(onClick = {
                        selected = if (selected.size == preview.sites.size) emptySet() else preview.sites.map { it.key }.toSet()
                    }) { Text(if (selected.size == preview.sites.size) "Select none" else "Select all") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(preview.sites, key = { it.key }) { s ->
                        val on = s.key in selected
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = if (on) selected - s.key else selected + s.key },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = { selected = if (it) selected + s.key else selected - s.key })
                                Column {
                                    Text(s.name, fontWeight = FontWeight.Medium)
                                    Text(countsLine(s.pins, s.paths, s.surveys, s.readings), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (on) {
                                val current = chosen[s.key] ?: defaultFor(s)
                                DropdownField(
                                    label = "Import into",
                                    value = current,
                                    options = optionsFor(s),
                                    optionLabel = { it.label },
                                    onSelect = { opt -> chosen = chosen + (s.key to opt) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp),
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    onImport(selected.associateWith { key -> (chosen[key] ?: defaultFor(preview.sites.first { it.key == key })).target })
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
