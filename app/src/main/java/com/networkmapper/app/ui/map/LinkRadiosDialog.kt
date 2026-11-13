package com.networkmapper.app.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.NetworkSummary
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.util.Wifi
import kotlinx.coroutines.launch

/**
 * Lets the user pick which surveyed radios (BSSIDs) belong to a mapped AP / bridge pin.
 * Candidates are the networks recorded closest to the pin, strongest first. Ticking one also
 * ticks its likely sibling radios (other band / SSID of the same box).
 */
@Composable
fun LinkRadiosDialog(vm: MainViewModel, asset: Asset, onDismiss: () -> Unit) {
    var candidates by remember { mutableStateOf<List<NetworkSummary>?>(null) }
    var checked by remember { mutableStateOf(vm.linkedBssids(asset)) }
    var manual by remember { mutableStateOf("") }

    LaunchedEffect(asset.id) {
        val found = vm.networksNear(asset)
        // Keep already-linked radios visible even if they weren't heard nearby.
        val missing = checked.filter { b -> found.none { it.bssid == b } }
            .map { NetworkSummary(bssid = it, ssid = "", bestRssi = -127, samples = 0, frequency = 0) }
        candidates = found + missing
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun toggle(n: NetworkSummary) {
        if (n.bssid in checked) {
            checked = checked - n.bssid
            return
        }
        checked = checked + n.bssid
        // Also tick the AP's other SSIDs / bands, adding any that weren't listed yet.
        scope.launch {
            val siblings = vm.sameApRadios(n.bssid)
            val listed = candidates.orEmpty()
            candidates = listed + siblings.filter { s -> listed.none { it.bssid == s.bssid } }
            checked = checked + siblings.map { it.bssid }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link radios to ${asset.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tick every BSSID that belongs to this device, then Save (Cancel or tapping outside keeps the old list). " +
                        "Networks heard closest to the pin are listed first; ticking one also ticks the same AP's other " +
                        "SSIDs and bands (matched by MAC and survey signal).",
                    style = MaterialTheme.typography.bodySmall,
                )
                val list = candidates
                when {
                    list == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    list.isEmpty() -> Text(
                        "No Wi-Fi survey data yet. Run a survey (Wi-Fi screen → Start walking survey) around " +
                            "this AP, or type its MAC below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(list, key = { it.bssid }) { n ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { toggle(n) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = n.bssid in checked, onCheckedChange = { toggle(n) })
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        vm.explicitApNames.value[n.bssid]?.let { "$it (${n.ssid.ifEmpty { "hidden" }})" }
                                            ?: n.ssid.ifEmpty { if (n.samples == 0) "(linked, not heard)" else "(hidden network)" },
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        n.bssid + if (n.frequency > 0) " • ${Wifi.band(n.frequency)} ch ${Wifi.channel(n.frequency)}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (n.samples > 0) {
                                    Text("${n.bestRssi} dBm", color = Color(Wifi.rssiColor(n.bestRssi)), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text("Or enter MAC(s) manually") },
                    placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val all = checked + Wifi.parseBssids(manual)
            TextButton(
                onClick = {
                    vm.linkRadios(asset, all)
                    onDismiss()
                },
                enabled = all.isNotEmpty() || vm.linkedBssids(asset).isNotEmpty(),
            ) { Text(if (all.isEmpty()) "Unlink all" else "Save ${all.size} radio${if (all.size == 1) "" else "s"}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
