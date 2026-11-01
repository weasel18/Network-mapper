@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.site

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkmapper.app.data.Site
import com.networkmapper.app.data.SiteWithCounts
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.ConfirmDialog
import com.networkmapper.app.util.Fmt
import kotlinx.coroutines.launch

@Composable
fun SitesScreen(vm: MainViewModel, onBack: () -> Unit, onOpened: () -> Unit, onOpenSync: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val currentId by vm.currentSiteId.collectAsStateWithLifecycle()

    var showNew by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Site?>(null) }
    var deleting by remember { mutableStateOf<SiteWithCounts?>(null) }
    var showExport by remember { mutableStateOf(false) }
    val startImport = rememberSiteImport(vm)

    fun open(id: Long) {
        if (vm.switchSite(id)) {
            onOpened()
        } else {
            Toast.makeText(context, "Finish or cancel the route you're drawing first", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sites") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New site") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Save & load sites", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "While a site is open, everything you map is saved on this phone automatically. " +
                                "To keep a copy or move sites to another phone, save them to a file; load a file to bring them back.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { showExport = true }, enabled = sites.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Upload, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save sites to file…")
                        }
                        OutlinedButton(onClick = startImport, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Load sites from file…")
                        }
                        OutlinedButton(onClick = onOpenSync, modifier = Modifier.fillMaxWidth()) {
                            Text("Cloud sync – share sites with other phones…")
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Sites on this phone", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap a site to open it on the map. Tap + New site to start another job.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sites, key = { it.site.id }) { s ->
                val isCurrent = s.site.id == currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open(s.site.id) }
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isCurrent) Icons.Default.CheckCircle else Icons.Default.Place,
                        contentDescription = if (isCurrent) "Open site" else null,
                        tint = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.site.name,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${s.pins} pins • ${s.paths} paths • ${s.surveys} surveys" + if (isCurrent) " • open" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (s.site.notes.isNotBlank()) {
                            Text(s.site.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { editing = s.site }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = { deleting = s }) { Icon(Icons.Default.Delete, "Delete") }
                }
                HorizontalDivider()
            }
        }
    }

    if (showNew) {
        SiteDialog(
            title = "New site",
            initialName = "",
            initialNotes = "",
            confirm = "Create & open",
            onDismiss = { showNew = false },
            onConfirm = { name, notes ->
                showNew = false
                scope.launch {
                    val id = vm.createSite(name, notes)
                    open(id)
                }
            },
        )
    }
    editing?.let { site ->
        SiteDialog(
            title = "Edit site",
            initialName = site.name,
            initialNotes = site.notes,
            confirm = "Save",
            onDismiss = { editing = null },
            onConfirm = { name, notes ->
                editing = null
                vm.renameSite(site, name, notes)
            },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete “${s.site.name}”?",
            text = "This permanently deletes ${s.pins} pins, ${s.paths} paths, ${s.surveys} Wi-Fi surveys and their photos. " +
                "Export the site first if you might need it.",
            confirm = "Delete",
            onConfirm = { scope.launch { vm.deleteSite(s.site.id) } },
            onDismiss = { deleting = null },
        )
    }
    if (showExport) ExportSitesDialog(vm, onDismiss = { showExport = false })
}

@Composable
private fun SiteDialog(
    title: String,
    initialName: String,
    initialNotes: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf(initialNotes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Site name") },
                    placeholder = { Text("e.g. Main St campus ${Fmt.dateTime(System.currentTimeMillis()).take(4)}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (address, customer, job #)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, notes) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
