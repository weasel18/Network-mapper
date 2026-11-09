package com.networkmapper.app.ui.wifi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.networkmapper.app.wifi.ScanThrottle
import com.networkmapper.app.wifi.ShizukuGrant
import kotlinx.coroutines.launch

/** Lets surveys switch off Android's Wi-Fi scan limit, after a one-time grant via Shizuku or adb. */
@Composable
fun FasterScansSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ScanThrottle.canControl(context)) }
    var auto by remember { mutableStateOf(ScanThrottle.autoEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Faster scans", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (granted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        auto = !auto
                        ScanThrottle.setAuto(context, auto)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Turn off Android's Wi-Fi scan limit while surveying (back on afterwards)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = auto, onCheckedChange = {
                    auto = it
                    ScanThrottle.setAuto(context, it)
                })
            }
            Text(
                "Android's scan limit is currently " + if (ScanThrottle.isThrottled(context)) "on." else "off.",
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text(
                "The app can switch Android's scan limit off during surveys (and back on after), so every network is " +
                    "sampled every few seconds. It needs a one-time permission:",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(enabled = !busy, onClick = {
                busy = true
                message = null
                scope.launch {
                    message = ShizukuGrant.grant(context)
                    granted = ScanThrottle.canControl(context)
                    busy = false
                }
            }) { Text(if (busy) "Waiting for Shizuku…" else "Grant with Shizuku") }
            Text("No Shizuku? From a computer with USB debugging on, run once:", style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    "adb shell pm grant ${context.packageName} ${ScanThrottle.PERMISSION}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            TextButton(onClick = { granted = ScanThrottle.canControl(context) }) { Text("Done that – check again") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
