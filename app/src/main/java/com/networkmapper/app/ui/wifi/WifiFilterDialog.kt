package com.networkmapper.app.ui.wifi

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkmapper.app.ui.MainViewModel
import kotlinx.coroutines.launch

/** What to hide from a network row's 👁 menu. */
enum class HideChoice { AP, SSID, ONLY_SSID }

/** Review / edit which Wi-Fi networks are recorded and shown in the open site (synced). */
@Composable
fun WifiFilterDialog(vm: MainViewModel, knownSsids: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filter by vm.wifiFilter.collectAsStateWithLifecycle()
    val names by vm.apNames.collectAsStateWithLifecycle()
    val site by vm.currentSite.collectAsStateWithLifecycle()
    var allowMode by remember { mutableStateOf(filter.onlySsids.isNotEmpty()) }
    var custom by remember { mutableStateOf("") }
    var filteredCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(filter) { filteredCount = vm.filteredReadingCount() }

    val ssids = (knownSsids + filter.onlySsids + filter.hiddenSsids).filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi networks to map") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Filtered networks aren't recorded in surveys and are left out of the lists, heatmap and suggestions. " +
                        "Applies to this site only (${site?.name ?: "the open site"}) and syncs to your other phones.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoiceRow(!allowMode, "Map all networks except the ones hidden below") {
                    allowMode = false
                    vm.updateWifiFilter { it.copy(onlySsids = emptySet()) }
                }
                ChoiceRow(allowMode, "Only map these SSIDs") { allowMode = true }
                if (allowMode) {
                    ssids.filter { it !in filter.hiddenSsids }.forEach { ssid ->
                        val on = ssid in filter.onlySsids
                        CheckRow(on, ssid, indent = true) {
                            vm.updateWifiFilter { f -> f.copy(onlySsids = if (on) f.onlySsids - ssid else f.onlySsids + ssid) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { custom = it },
                            label = { Text("Add SSID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = custom.isNotBlank(), onClick = {
                            val s = custom.trim()
                            vm.onlyMapSsid(s)
                            custom = ""
                        }) { Text("Add") }
                    }
                    if (filter.onlySsids.isEmpty()) {
                        Text(
                            "Tick at least one SSID – until then every network is mapped.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                }
                CheckRow(filter.hideNoName, "Hide networks with no name (hidden SSIDs)") {
                    vm.updateWifiFilter { it.copy(hideNoName = !it.hideNoName) }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Hidden SSIDs (${filter.hiddenSsids.size})", fontWeight = FontWeight.SemiBold)
                if (filter.hiddenSsids.isEmpty()) Text("None – use 👁 on a network to hide it.", style = MaterialTheme.typography.labelSmall)
                filter.hiddenSsids.sorted().forEach { ssid ->
                    RemovableRow(ssid, null) { vm.updateWifiFilter { it.copy(hiddenSsids = it.hiddenSsids - ssid) } }
                }
                Text("Hidden APs / BSSIDs (${filter.hiddenBssids.size})", fontWeight = FontWeight.SemiBold)
                if (filter.hiddenBssids.isEmpty()) Text("None", style = MaterialTheme.typography.labelSmall)
                filter.hiddenBssids.sorted().forEach { b ->
                    RemovableRow(names[b] ?: b, if (names[b] != null) b else null) {
                        vm.updateWifiFilter { it.copy(hiddenBssids = it.hiddenBssids - b) }
                    }
                }

                if (filteredCount > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val n = vm.purgeFilteredReadings()
                            Toast.makeText(context, "Removed $n reading(s) from this site", Toast.LENGTH_SHORT).show()
                            filteredCount = vm.filteredReadingCount()
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove $filteredCount already-recorded reading(s) of filtered networks from this site")
                    }
                    Text(
                        "Optional: they're already hidden. Removing frees space on this phone.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(checked: Boolean, text: String, indent: Boolean = false, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 24.dp else 0.dp)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemovableRow(title: String, subtitle: String?, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Unhide") }
    }
}
