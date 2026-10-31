package com.networkmapper.app.ui.wifi

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.util.Wifi
import kotlinx.coroutines.launch

/**
 * Name an access point by its BSSID (radio MAC). Optionally applies the name to the AP's other
 * radios (same MAC prefix) and drops a linked AP pin where it was heard strongest.
 */
@Composable
fun ApNameDialog(
    vm: MainViewModel,
    bssid: String,
    ssid: String,
    frequency: Int,
    onDismiss: () -> Unit,
    onPinCreated: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = bssid.lowercase()
    val existing = vm.explicitApNames.value[key]
    val linkedPin = vm.assets.value.firstOrNull { key in vm.linkedBssids(it) }
    val siblings = remember(key) { vm.siblingRadios(key) }

    var name by remember { mutableStateOf(existing ?: linkedPin?.name ?: "") }
    var includeSiblings by remember { mutableStateOf(siblings.isNotEmpty()) }
    var dropPin by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Name this AP" else "Rename AP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildString {
                        append("SSID: ").append(ssid.ifEmpty { "(hidden)" })
                        if (frequency > 0) append("  •  ").append(Wifi.band(frequency)).append(" ch ").append(Wifi.channel(frequency))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("BSSID / MAC: $key", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (linkedPin != null) {
                    Text("Linked to pin “${linkedPin.name}”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("AP name") },
                    placeholder = { Text("e.g. AP-Lobby-2F") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (siblings.isNotEmpty()) {
                    CheckLine(
                        checked = includeSiblings,
                        onChange = { includeSiblings = it },
                        text = "Also name its other ${siblings.size} radio(s)/SSID(s) with a matching MAC",
                    )
                    Text(
                        siblings.joinToString("\n") { "${it.bssid}  ${it.ssid.ifEmpty { "(hidden)" }}  ${Wifi.band(it.frequency)}" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                }
                if (linkedPin == null) {
                    CheckLine(
                        checked = dropPin,
                        onChange = { dropPin = it },
                        text = "Also drop an AP pin where it was heard strongest",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val radios = buildSet {
                        add(key)
                        if (includeSiblings) siblings.forEach { add(it.bssid) }
                    }
                    vm.nameAps(radios, name)
                    if (dropPin) {
                        scope.launch {
                            val id = vm.createApPinFromSurvey(radios, name, ssid)
                            if (id == null) {
                                Toast.makeText(context, "No recorded samples for this AP in this site yet", Toast.LENGTH_LONG).show()
                            } else {
                                onPinCreated(id)
                            }
                            onDismiss()
                        }
                    } else {
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = {
                        vm.nameAps(setOf(key), "")
                        onDismiss()
                    }) { Text("Remove name") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CheckLine(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
