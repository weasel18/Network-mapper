@file:OptIn(ExperimentalLayoutApi::class)

package com.networkmapper.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.unifi.LivePort
import com.networkmapper.app.unifi.UnifiLive
import com.networkmapper.app.unifi.UnifiLiveParser
import com.networkmapper.app.util.Fmt
import java.util.Locale

/** Live controller status shown on a UniFi-matched pin's card. */
@Composable
fun UnifiLiveSection(state: MainViewModel.LiveState, onRefresh: () -> Unit) {
    val live: UnifiLive? = when (state) {
        is MainViewModel.LiveState.Ok -> state.live
        is MainViewModel.LiveState.Failed -> state.last
        is MainViewModel.LiveState.Loading -> null
    }
    Column(Modifier.padding(top = 6.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when {
                live == null -> Color.Gray
                live.online -> Color(0xFF2E7D32)
                else -> Color(0xFFC62828)
            }
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                buildString {
                    append("UniFi")
                    if (live != null) {
                        append(" • ").append(live.stateLabel)
                        live.uptimeSec?.let { append(" • up ").append(UnifiLiveParser.formatUptime(it)) }
                    }
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state is MainViewModel.LiveState.Loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Refresh, "Refresh", Modifier.size(18.dp)) }
            }
        }
        if (state is MainViewModel.LiveState.Failed) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (live == null) return@Column

        Column(
            Modifier
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val small = MaterialTheme.typography.bodySmall
            Text(
                listOfNotNull(
                    live.clients?.let { "$it client${if (it == 1) "" else "s"}" },
                    live.cpuPct?.let { String.format(Locale.US, "CPU %.0f%%", it) },
                    live.memPct?.let { String.format(Locale.US, "Mem %.0f%%", it) },
                    live.satisfaction?.let { "Satisfaction $it%" },
                ).joinToString(" • ").ifEmpty { "No load figures reported" },
                style = small,
            )
            val traffic = listOfNotNull(
                live.uplink?.let { "Uplink $it" },
                live.txBps?.let { "↑ ${UnifiLiveParser.formatRate(it)}" },
                live.rxBps?.let { "↓ ${UnifiLiveParser.formatRate(it)}" },
            )
            if (traffic.isNotEmpty()) Text(traffic.joinToString(" • "), style = small)
            Text(
                listOfNotNull(
                    live.ip.takeIf { it.isNotBlank() },
                    live.firmware.takeIf { it.isNotBlank() }?.let { "fw $it" },
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
            )
            live.radios.forEach { r ->
                Text(
                    listOfNotNull(
                        r.band + (r.channel?.let { " ch $it" } ?: ""),
                        r.txPowerDbm?.let { "$it dBm" },
                        r.clients?.let { "$it clients" },
                        r.utilization?.let { "util $it%" },
                    ).joinToString(" • "),
                    style = small,
                )
            }
            if (live.ports.isNotEmpty()) {
                val up = live.ports.count { it.up }
                val poe = live.ports.mapNotNull { it.poeWatts }.sum()
                Text(
                    "Ports $up/${live.ports.size} up" + if (poe > 0) String.format(Locale.US, " • PoE %.1f W", poe) else "",
                    style = small,
                    fontWeight = FontWeight.Medium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    live.ports.forEach { PortChip(it) }
                }
            }
            Text("Updated ${Fmt.dateTime(live.fetchedAt).substringAfter(' ')} • refreshes every 10 s", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PortChip(p: LivePort) {
    val bg = if (p.up) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
    Column(
        Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .then(if (p.isUplink) Modifier.border(2.dp, Color(0xFF1565C0), RoundedCornerShape(4.dp)) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${p.index}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val speed = p.speedMbps?.takeIf { p.up }?.let { if (it >= 1000) "${it / 1000}G" else "${it}M" } ?: "–"
        Text(speed, color = Color.White, fontSize = 9.sp)
        p.poeWatts?.let { Text(String.format(Locale.US, "%.0fW", it), color = Color.White, fontSize = 9.sp) }
    }
}
