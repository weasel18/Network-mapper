@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.KindSwatch
import com.networkmapper.app.ui.components.TypeBadge
import com.networkmapper.app.util.Geo

@Composable
fun AssetListScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenAsset: (Long) -> Unit,
    onOpenPath: (Long) -> Unit,
    onShowOnMap: () -> Unit,
) {
    val assets by vm.assets.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sortByDistance by rememberSaveable { mutableStateOf(true) }

    val me = location?.let { GeoPt(it.latitude, it.longitude) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network inventory") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pins (${assets.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Paths (${paths.size})") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search name, notes, details…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = sortByDistance,
                    onClick = { sortByDistance = !sortByDistance },
                    label = { Text(if (sortByDistance) "Nearest first" else "Recently edited") },
                )
                if (tab == 0) {
                    AssetType.entries.forEach { t ->
                        FilterChip(
                            selected = typeFilter == t.name,
                            onClick = { typeFilter = if (typeFilter == t.name) null else t.name },
                            label = { Text(t.label.substringBefore(" /")) },
                        )
                    }
                }
            }

            val q = query.trim().lowercase()
            if (tab == 0) {
                val rows = assets
                    .filter { typeFilter == null || it.type.name == typeFilter }
                    .filter { a ->
                        q.isEmpty() || a.name.lowercase().contains(q) || a.notes.lowercase().contains(q) ||
                            a.type.label.lowercase().contains(q) ||
                            a.attributes.values.any { it.lowercase().contains(q) }
                    }
                    .map { a -> a to me?.let { Geo.distanceMeters(it, GeoPt(a.latitude, a.longitude)) } }
                    .let { list -> if (sortByDistance && me != null) list.sortedBy { it.second } else list }
                if (rows.isEmpty()) EmptyHint("No pins yet. On the map, tap + to drop a pin at your GPS position or long-press anywhere.")
                LazyColumn {
                    items(rows, key = { it.first.id }) { (a, dist) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenAsset(a.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TypeBadge(a.type)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(a.type.label, a.status, dist?.let { Geo.formatDistance(it) }).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                vm.focusOnAsset(a)
                                onShowOnMap()
                            }) { Icon(Icons.Default.Map, "Show on map") }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                val rows = paths
                    .filter { p ->
                        q.isEmpty() || p.name.lowercase().contains(q) || p.notes.lowercase().contains(q) ||
                            p.cableType.lowercase().contains(q) || p.kind.label.lowercase().contains(q) ||
                            p.attributes.values.any { it.lowercase().contains(q) }
                    }
                    .map { p ->
                        p to me?.let { m -> p.points.minOfOrNull { Geo.distanceMeters(m, it) } }
                    }
                    .let { list -> if (sortByDistance && me != null) list.sortedBy { it.second ?: Double.MAX_VALUE } else list }
                val total = rows.sumOf { Geo.pathLengthMeters(it.first.points) }
                Text(
                    "Total length: ${Geo.formatDistance(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (rows.isEmpty()) EmptyHint("No paths yet. On the map, tap the path tool to draw a fiber route or wireless link.")
                LazyColumn {
                    items(rows, key = { it.first.id }) { (p, dist) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPath(p.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KindSwatch(p.kind, p.colorArgb)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        p.kind.label,
                                        p.fiberCount?.takeIf { p.kind.isFiber }?.let { "${it}F" },
                                        Geo.formatDistance(Geo.pathLengthMeters(p.points)),
                                        dist?.let { "${Geo.formatDistance(it)} away" },
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                vm.focusOnPath(p)
                                onShowOnMap()
                            }) { Icon(Icons.Default.Map, "Show on map") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
