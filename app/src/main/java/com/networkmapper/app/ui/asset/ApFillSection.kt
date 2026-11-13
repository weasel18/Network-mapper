package com.networkmapper.app.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.networkmapper.app.ui.ApCandidate
import com.networkmapper.app.util.Wifi

/**
 * "Fill from Wi-Fi" box on AP / bridge / router pins: suggests saved and nearby radios and fills
 * the pin's MAC, SSID, channel and name from the one you tap.
 */
@Composable
fun ApFillSection(
    candidates: List<ApCandidate>?,
    linkedCount: Int,
    onPick: (ApCandidate) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fill from Wi-Fi", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (linkedCount > 0) {
                            "$linkedCount radio(s) linked. Pick another to add its radios too (edit the MAC / BSSID field below to remove one), then Save."
                        } else {
                            "Tap an AP to fill in its MAC / BSSID, SSID, channel and saved name."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            when {
                candidates == null -> Text("Looking for saved and nearby APs…", style = MaterialTheme.typography.bodySmall)
                candidates.isEmpty() -> Text(
                    "No saved or recorded APs yet. Stand near the AP (it'll show as “in range now”) or run a Wi-Fi survey first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    candidates.take(3).forEach { c ->
                        CandidateRow(c) { onPick(c) }
                    }
                    if (candidates.size > 3) {
                        TextButton(onClick = { showAll = true }) { Text("Choose from all ${candidates.size}…") }
                    }
                }
            }
        }
    }
    if (showAll && candidates != null) {
        ApPickerDialog(candidates, onPick = { showAll = false; onPick(it) }, onDismiss = { showAll = false })
    }
}

@Composable
private fun CandidateRow(c: ApCandidate, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                c.savedName ?: c.ssid.ifEmpty { "(hidden network)" },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (c.savedName != null && c.ssid.isNotEmpty()) c.ssid else null,
                    c.bssid,
                    if (c.frequency > 0) "${Wifi.band(c.frequency)} ch ${Wifi.channel(c.frequency)}" else null,
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                c.source.label + (c.linkedPin?.let { " • already on pin “$it”" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (c.linkedPin != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        c.rssi?.let { Text("$it dBm", color = Color(Wifi.rssiColor(it)), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ApPickerDialog(candidates: List<ApCandidate>, onPick: (ApCandidate) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) {
        candidates
    } else {
        candidates.filter {
            (it.savedName ?: "").lowercase().contains(q) || it.ssid.lowercase().contains(q) ||
                it.bssid.contains(q) || it.bssid.replace(":", "").contains(q.replace(":", "").replace("-", ""))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an AP") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Name, SSID or MAC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.bssid }) { c ->
                        CandidateRow(c) { onPick(c) }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
