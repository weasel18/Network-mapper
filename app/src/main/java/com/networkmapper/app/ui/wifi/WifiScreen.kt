@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.wifi

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkmapper.app.data.SessionWithCount
import com.networkmapper.app.survey.SurveyState
import com.networkmapper.app.ui.HeatFilter
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.ConfirmDialog
import com.networkmapper.app.util.Fmt
import com.networkmapper.app.util.Wifi
import com.networkmapper.app.wifi.ScanEntry
import com.networkmapper.app.wifi.WifiScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WifiScreen(vm: MainViewModel, onBack: () -> Unit, onShowMap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by SurveyState.running.collectAsStateWithLifecycle()
    val readings by SurveyState.readings.collectAsStateWithLifecycle()
    val surveyScan by SurveyState.lastScan.collectAsStateWithLifecycle()
    val interval by SurveyState.scanIntervalMs.collectAsStateWithLifecycle()
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle(emptyList())
    val networks by vm.repo.networks.collectAsStateWithLifecycle(emptyList())
    val heatSession by vm.heatSession.collectAsStateWithLifecycle()
    val heatFilter by vm.heatFilter.collectAsStateWithLifecycle()

    val scanner = remember { WifiScanner(context) }
    var liveScan by remember { mutableStateOf(scanner.currentResults()) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var surveyName by rememberSaveable { mutableStateOf("") }
    var deleteSession by remember { mutableStateOf<SessionWithCount?>(null) }

    // When not surveying, keep a live list going for "what's around me".
    LaunchedEffect(running) {
        if (!running) {
            launch { scanner.results().collect { liveScan = it } }
            while (true) {
                scanner.requestScan()
                delay(15_000)
            }
        }
    }
    val connected = remember(liveScan, surveyScan) { scanner.connected() }
    val nearby = (if (running) surveyScan else liveScan).distinctBy { it.bssid }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi survey") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        vm.updateSettings { it.copy(showHeatmap = true) }
                        onShowMap()
                    }) { Icon(Icons.Default.Map, "Show heatmap") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---- Survey control ----
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!scanner.isWifiEnabled) {
                        Text("Wi-Fi is turned off – turn it on to scan.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Open Wi-Fi settings") }
                    }
                    if (running) {
                        Text("Survey running", fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                        Text("$readings readings logged • ${surveyScan.size} networks in last scan")
                        Button(
                            onClick = vm::stopSurvey,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop survey")
                        }
                    } else {
                        OutlinedTextField(
                            value = surveyName,
                            onValueChange = { surveyName = it },
                            label = { Text("Survey name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            vm.startSurvey(surveyName)
                            surveyName = ""
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Start walking survey")
                        }
                    }
                    Text("Scan interval", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5_000L to "5 s", 10_000L to "10 s", 30_000L to "30 s", 60_000L to "60 s").forEach { (ms, label) ->
                            FilterChip(
                                selected = interval == ms,
                                onClick = { SurveyState.scanIntervalMs.value = ms },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "Android limits apps to 4 Wi-Fi scans per 2 minutes. For faster scans, turn off " +
                            "“Wi-Fi scan throttling” in Developer options. The connected network's signal is " +
                            "logged every 2 m regardless.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Nearby") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Recorded") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Sessions") })
            }

            when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize()) {
                    if (connected != null) {
                        item {
                            Text(
                                "Connected",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                            )
                            NetworkRow(connected, null) {
                                vm.showHeatmapFor(HeatFilter.Bssid(connected.bssid, connected.ssid))
                                onShowMap()
                            }
                            HorizontalDivider()
                        }
                    }
                    if (nearby.isEmpty()) {
                        item {
                            Text(
                                "No scan results yet. Make sure Wi-Fi and Location are on.",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(nearby, key = { "n_" + it.bssid }) { e ->
                        NetworkRow(e, null) {
                            vm.showHeatmapFor(HeatFilter.Bssid(e.bssid, e.ssid))
                            onShowMap()
                        }
                        HorizontalDivider()
                    }
                }

                1 -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "Tap a network to map its coverage. Current heatmap: ${heatFilter.label}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.showHeatmapFor(HeatFilter.Strongest)
                                    onShowMap()
                                }
                                .padding(16.dp),
                        ) { Text("Strongest signal at each spot (all networks)", fontWeight = FontWeight.Medium) }
                        HorizontalDivider()
                    }
                    val bySsid = networks.filter { it.ssid.isNotEmpty() }.groupBy { it.ssid }.filter { it.value.size > 1 }
                    if (bySsid.isNotEmpty()) {
                        item {
                            Text("Networks with multiple APs", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp))
                        }
                        items(bySsid.keys.toList(), key = { "s_$it" }) { ssid ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.showHeatmapFor(HeatFilter.Ssid(ssid))
                                        onShowMap()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text("$ssid  (${bySsid[ssid]?.size ?: 0} APs, combined)", fontWeight = FontWeight.Medium)
                            }
                            HorizontalDivider()
                        }
                    }
                    item { Text("Access points", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
                    items(networks, key = { "r_" + it.bssid }) { n ->
                        NetworkRow(
                            ScanEntry(n.ssid, n.bssid, n.bestRssi, n.frequency, ""),
                            "best ${n.bestRssi} dBm • ${n.samples} samples",
                        ) {
                            vm.showHeatmapFor(HeatFilter.Bssid(n.bssid, n.ssid))
                            onShowMap()
                        }
                        HorizontalDivider()
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.heatSession.value = null }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("All sessions", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            if (heatSession == null) Text("Showing", color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                    items(sessions, key = { it.session.id }) { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.heatSession.value = s.session.id }
                                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.session.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "${Fmt.dateTime(s.session.startedAt)} • ${s.readingCount} readings" +
                                        if (s.session.endedAt == null) " • in progress" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (heatSession == s.session.id) Text("Showing", color = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = { deleteSession = s }) { Icon(Icons.Default.Delete, "Delete session") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    deleteSession?.let { s ->
        ConfirmDialog(
            title = "Delete survey?",
            text = "Delete “${s.session.name}” and its ${s.readingCount} readings?",
            confirm = "Delete",
            onConfirm = {
                scope.launch {
                    if (vm.heatSession.value == s.session.id) vm.heatSession.value = null
                    vm.repo.deleteSession(s.session.id)
                }
            },
            onDismiss = { deleteSession = null },
        )
    }
}

@Composable
private fun NetworkRow(e: ScanEntry, extra: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalBars(e.rssi)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.ssid.ifEmpty { "(hidden network)" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${e.bssid} • ch ${Wifi.channel(e.frequency)} (${Wifi.band(e.frequency)})" +
                    (if (e.security.contains("WPA3") || e.security.contains("SAE")) " • WPA3" else if (e.security.contains("WPA")) " • WPA" else ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${e.rssi} dBm", fontWeight = FontWeight.Bold, color = Color(Wifi.rssiColor(e.rssi)))
            Text(Wifi.rssiQuality(e.rssi), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
    val color = Color(Wifi.rssiColor(rssi))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(5.dp)
                    .height((6 + i * 4).dp)
                    .background(if (i <= level) color else Color.LightGray, RoundedCornerShape(1.dp)),
            )
        }
    }
    Spacer(Modifier.size(0.dp))
}
