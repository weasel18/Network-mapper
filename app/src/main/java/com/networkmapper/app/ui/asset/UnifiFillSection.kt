package com.networkmapper.app.ui.asset

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.unifi.UnifiDevice
import com.networkmapper.app.util.Wifi

/** Pin types that correspond to UniFi devices, and the controller "kind" that fits each best. */
val UNIFI_PIN_KINDS: Map<AssetType, String> = mapOf(
    AssetType.SWITCH to "switch",
    AssetType.ROUTER to "gateway",
    AssetType.ACCESS_POINT to "ap",
    AssetType.WIRELESS_BRIDGE to "ap",
)

/**
 * Fills a pin's form from a UniFi device the user picked: name (only replacing an automatic pin
 * name), hostname, model, IP, MAC, firmware, serial and port count; for radio pins also the radio
 * BSSIDs, SSIDs and channels. Writing the MAC links the pin to the device (live status, imports).
 */
internal fun fillFromUnifi(form: AssetForm, d: UnifiDevice, isDefaultName: (AssetType, String) -> Boolean): AssetForm {
    val attrs = LinkedHashMap(form.attributes)
    val fields = form.type.suggestedFields
    fun set(key: String, value: String?) {
        if (!value.isNullOrBlank()) attrs[key] = value
    }
    set("Model", d.model)
    set("IP address", d.ip)
    if ("Hostname" in fields) set("Hostname", d.name)
    set("Firmware", d.firmware)
    if ("Serial" in fields) set("Serial", d.serial)
    if ("Port count" in fields) set("Port count", d.portCount?.toString())

    val bssidField = form.type.bssidField
    if (bssidField != null && bssidField != "MAC" && d.radios.isNotEmpty()) {
        // Radio pins: list the device MAC plus every radio so survey dots and Signal map match it.
        attrs[bssidField] = (listOf(d.mac) + d.radios.map { it.bssid }).distinct().sorted().joinToString(", ")
        val ssids = d.radios.map { it.ssid }.filter { it.isNotBlank() }.distinct()
        if ("SSID" in fields && ssids.isNotEmpty()) attrs["SSID"] = ssids.joinToString(", ")
        val channels = d.radios.filter { it.channel != null }
            .map { approxFrequency(it.channel, it.band) to "${it.channel} (${it.band ?: Wifi.band(approxFrequency(it.channel, it.band))})" }
            .distinctBy { it.second }.sortedBy { it.first }.map { it.second }
        if ("Channel" in fields && channels.isNotEmpty()) attrs["Channel"] = channels.joinToString(", ")
    } else if (bssidField != null && bssidField != "MAC") {
        attrs[bssidField] = d.mac
    } else {
        attrs["MAC"] = d.mac
    }
    val name = if (d.name.isNotBlank() && isDefaultName(form.type, form.name)) d.name else form.name
    return form.copy(attributes = attrs, name = name)
}

@Composable
fun UnifiFillSection(
    pinType: AssetType,
    devices: List<UnifiDevice>,
    /** Device MAC -> name of another pin it's already linked to. */
    linkedTo: Map<String, String>,
    configured: Boolean,
    refreshing: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onPick: (UnifiDevice) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val kind = UNIFI_PIN_KINDS[pinType]
    val ordered = devices.sortedWith(
        compareBy<UnifiDevice>({ it.kind != kind }, { it.mac in linkedTo }, { it.displayName.lowercase() }),
    )
    val matching = ordered.filter { it.kind == kind }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fill from UniFi", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pick the device to fill in its name, model, IP, MAC and more, and link the pin for live status.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (configured) {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh from controller") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            when {
                devices.isEmpty() && !configured -> Text(
                    "No UniFi devices yet. Connect on Wi-Fi → UniFi (tick “Remember”) and load devices.",
                    style = MaterialTheme.typography.bodySmall,
                )
                devices.isEmpty() -> Text("No devices loaded yet – tap refresh.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    (if (matching.isNotEmpty()) matching else ordered).take(3).forEach { d ->
                        UnifiDeviceRow(d, linkedTo[d.mac]) { onPick(d) }
                    }
                    TextButton(onClick = { showAll = true }) { Text("Choose from all ${devices.size}…") }
                }
            }
        }
    }
    if (showAll) {
        UnifiPickerDialog(ordered, linkedTo, onPick = { showAll = false; onPick(it) }, onDismiss = { showAll = false })
    }
}

@Composable
private fun UnifiDeviceRow(d: UnifiDevice, linkedPin: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    d.model.takeIf { it.isNotBlank() },
                    d.ip.takeIf { it.isNotBlank() },
                    d.portCount?.let { "$it ports" },
                    if (d.radios.isNotEmpty()) "${d.radios.size} radios" else null,
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(d.mac, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            if (linkedPin != null) {
                Text("already on pin “$linkedPin”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun UnifiPickerDialog(devices: List<UnifiDevice>, linkedTo: Map<String, String>, onPick: (UnifiDevice) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) {
        devices
    } else {
        devices.filter {
            it.displayName.lowercase().contains(q) || it.model.lowercase().contains(q) || it.ip.contains(q) ||
                it.mac.replace(":", "").contains(q.replace(":", "").replace("-", ""))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a UniFi device") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Name, model, IP or MAC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.mac }) { d ->
                        UnifiDeviceRow(d, linkedTo[d.mac]) { onPick(d) }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
