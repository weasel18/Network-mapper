@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.map

import android.content.ActivityNotFoundException
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.survey.SurveyState
import com.networkmapper.app.ui.Draft
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.Selection
import com.networkmapper.app.ui.TileStyle
import com.networkmapper.app.ui.components.AssetTypePickerDialog
import com.networkmapper.app.ui.components.KindSwatch
import com.networkmapper.app.ui.components.PathKindPickerDialog
import com.networkmapper.app.ui.components.TypeBadge
import com.networkmapper.app.ui.components.rememberPhotoCapture
import com.networkmapper.app.util.Geo
import com.networkmapper.app.util.Wifi
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView

private data class PendingPin(val pt: GeoPt, val accuracy: Float?, val altitude: Double?, val fromGps: Boolean)

@Composable
fun MapScreen(
    vm: MainViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAsset: (Long) -> Unit,
    onOpenPath: (Long) -> Unit,
    onOpenList: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenData: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val assets by vm.assets.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val heat by vm.heatPoints.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val heatFilter by vm.heatFilter.collectAsStateWithLifecycle()
    val surveyRunning by SurveyState.running.collectAsStateWithLifecycle()
    val surveyReadings by SurveyState.readings.collectAsStateWithLifecycle()

    var pendingPin by remember { mutableStateOf<PendingPin?>(null) }
    var showKindPicker by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }

    // ---- MapView ----
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(true)
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            setScrollableAreaLimitLatitude(85.0, -85.0, 0)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(22.0)
            val start = vm.cameraCenter
                ?: vm.location.value?.let { GeoPt(it.latitude, it.longitude) }
            if (start != null) {
                controller.setZoom(if (vm.cameraInitialized) vm.cameraZoom else 18.0)
                controller.setCenter(GeoPoint(start.lat, start.lon))
                if (!vm.cameraInitialized) vm.saveCamera(start, 18.0)
            } else {
                controller.setZoom(4.0)
                controller.setCenter(GeoPoint(39.5, -98.35))
            }
        }
    }
    val overlays = remember { MapOverlayController(context, mapView) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (vm.cameraInitialized || vm.cameraCenter != null) {
                val c = mapView.mapCenter
                vm.saveCamera(GeoPt(c.latitude, c.longitude), mapView.zoomLevelDouble)
            }
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Callbacks always see the latest state.
    SideEffect {
        overlays.onAssetClick = { asset ->
            if (vm.draft.value != null) {
                vm.addDraftPoint(GeoPt(asset.latitude, asset.longitude), asset.id)
            } else {
                vm.select(Selection.AssetSel(asset.id))
            }
        }
        overlays.onPathClick = { path -> if (vm.draft.value == null) vm.select(Selection.PathSel(path.id)) }
        overlays.onMapTap = { pt ->
            if (vm.draft.value != null) {
                vm.addDraftPoint(pt)
                true
            } else {
                vm.select(null)
                false
            }
        }
        overlays.onLongPress = { pt ->
            if (vm.draft.value == null) pendingPin = PendingPin(pt, null, null, fromGps = false)
            true
        }
        overlays.onAssetDragged = { asset, pt -> vm.moveAsset(asset, pt) }
        overlays.onUserPan = { follow = false }
    }

    LaunchedEffect(settings.tileStyle) { overlays.setTileStyle(settings.tileStyle) }
    LaunchedEffect(assets, paths, settings, draft, heat, selected) {
        overlays.render(assets, paths, settings, draft, heat, selected)
    }
    LaunchedEffect(location, follow) {
        overlays.setLocation(location)
        val loc = location ?: return@LaunchedEffect
        if (!vm.cameraInitialized) {
            vm.saveCamera(GeoPt(loc.latitude, loc.longitude), 18.0)
            mapView.controller.setZoom(18.0)
            mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
        } else if (follow) {
            mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        }
    }
    LaunchedEffect(Unit) {
        vm.focusRequest.collect { pt ->
            if (pt != null) {
                follow = false
                mapView.controller.animateTo(GeoPoint(pt.lat, pt.lon), vm.cameraZoom, 600L)
                vm.focusRequest.value = null
            }
        }
    }

    // Keep the screen on while GPS auto-tracking a path.
    val view = LocalView.current
    val autoTrack = draft?.autoTrack == true
    DisposableEffect(autoTrack) {
        view.keepScreenOn = autoTrack
        onDispose { view.keepScreenOn = false }
    }

    val takePhotoPin = rememberPhotoCapture { file ->
        scope.launch {
            val id = vm.createPhotoPin(file)
            onOpenAsset(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Mapper") },
                actions = {
                    IconButton(onClick = { showLayers = true }) { Icon(Icons.Default.Layers, "Layers") }
                    IconButton(onClick = onOpenList) { Icon(Icons.AutoMirrored.Filled.List, "List") }
                    IconButton(onClick = onOpenWifi) { Icon(Icons.Default.Wifi, "Wi-Fi survey") }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Export & data") }, onClick = {
                                showMenu = false
                                onOpenData()
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // ---- Top status chips ----
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GpsChip(location, hasLocationPermission, onRequestPermission)
                if (surveyRunning) {
                    StatusChip(
                        text = "● Surveying – $surveyReadings readings",
                        color = Color(0xFFC62828),
                        onClick = onOpenWifi,
                    )
                }
                if (settings.showHeatmap) HeatLegend(heatFilter.label)
            }

            // ---- Bottom area ----
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val d = draft
                val selAsset = (selected as? Selection.AssetSel)?.let { s -> assets.firstOrNull { it.id == s.id } }
                val selPath = (selected as? Selection.PathSel)?.let { s -> paths.firstOrNull { it.id == s.id } }

                SmallFloatingActionButton(onClick = {
                    val loc = location
                    if (loc == null) {
                        Toast.makeText(context, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
                    } else {
                        follow = true
                        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), maxOf(mapView.zoomLevelDouble, 18.0), 500L)
                    }
                }) {
                    Icon(if (follow) Icons.Default.GpsFixed else Icons.Default.MyLocation, "My location")
                }

                when {
                    d != null -> DraftBar(
                        draft = d,
                        onUndo = vm::undoDraftPoint,
                        onGpsPoint = {
                            if (!vm.addDraftGpsPoint()) Toast.makeText(context, "No GPS fix yet", Toast.LENGTH_SHORT).show()
                        },
                        onToggleAuto = vm::toggleAutoTrack,
                        onCancel = vm::cancelDraft,
                        onSave = {
                            scope.launch {
                                val id = vm.saveDraft()
                                if (id == null) {
                                    Toast.makeText(context, "A path needs at least 2 points", Toast.LENGTH_SHORT).show()
                                } else {
                                    onOpenPath(id)
                                }
                            }
                        },
                    )
                    selAsset != null -> AssetCard(
                        asset = selAsset,
                        location = location,
                        onEdit = { onOpenAsset(selAsset.id) },
                        onNavigate = { navigateTo(context, selAsset.latitude, selAsset.longitude, selAsset.name) },
                        onClose = { vm.select(null) },
                    )
                    selPath != null -> PathCard(
                        path = selPath,
                        onEdit = { onOpenPath(selPath.id) },
                        onClose = { vm.select(null) },
                    )
                    else -> {
                        SmallFloatingActionButton(onClick = { showKindPicker = true }) {
                            Icon(Icons.Default.Timeline, "Draw path")
                        }
                        SmallFloatingActionButton(onClick = takePhotoPin) {
                            Icon(Icons.Default.PhotoCamera, "Photo pin")
                        }
                        FloatingActionButton(onClick = {
                            val loc = location
                            if (loc == null) {
                                Toast.makeText(context, "No GPS fix yet – long-press the map to place a pin manually", Toast.LENGTH_LONG).show()
                            } else {
                                pendingPin = PendingPin(
                                    GeoPt(loc.latitude, loc.longitude),
                                    if (loc.hasAccuracy()) loc.accuracy else null,
                                    if (loc.hasAltitude()) loc.altitude else null,
                                    fromGps = true,
                                )
                            }
                        }) {
                            Icon(Icons.Default.AddLocationAlt, "Add pin at my location")
                        }
                    }
                }
            }
        }
    }

    // ---- Layers sheet ----
    if (showLayers) {
        LayersDialog(
            settings = settings,
            onChange = { newSettings -> vm.updateSettings { newSettings } },
            onDismiss = { showLayers = false },
        )
    }

    pendingPin?.let { pin ->
        val subtitle = buildString {
            append(Geo.formatCoord(pin.pt))
            if (pin.fromGps) append(pin.accuracy?.let { "  (GPS ±${it.toInt()} m)" } ?: "  (GPS)") else append("  (map position)")
        }
        AssetTypePickerDialog(
            title = "Add pin",
            subtitle = subtitle,
            onPick = { type ->
                pendingPin = null
                scope.launch {
                    val id = vm.createAsset(type, pin.pt, pin.accuracy, pin.altitude)
                    onOpenAsset(id)
                }
            },
            onDismiss = { pendingPin = null },
        )
    }

    if (showKindPicker) {
        PathKindPickerDialog(
            onPick = { kind ->
                showKindPicker = false
                vm.startDraft(kind)
            },
            onDismiss = { showKindPicker = false },
        )
    }
}

private fun navigateTo(context: android.content.Context, lat: Double, lon: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No navigation app installed", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun StatusChip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color,
        shadowElevation = 3.dp,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun GpsChip(location: Location?, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    when {
        !hasPermission -> StatusChip("Location permission needed – tap to grant", Color(0xFFC62828), onRequestPermission)
        location == null -> StatusChip("Waiting for GPS fix…", Color(0xFF616161))
        else -> {
            val acc = if (location.hasAccuracy()) location.accuracy else null
            val color = when {
                acc == null -> Color(0xFF616161)
                acc <= 5f -> Color(0xFF2E7D32)
                acc <= 15f -> Color(0xFF9E9D24)
                else -> Color(0xFFEF6C00)
            }
            val src = if (location.provider == "gps") "GPS" else "Network"
            StatusChip("$src ±${acc?.toInt() ?: "?"} m", color)
        }
    }
}

@Composable
private fun HeatLegend(label: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 3.dp) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wifi.legend.forEach { (text, color) ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(Color(color), CircleShape),
                    )
                    Text(" $text  ", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("dBm", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DraftBar(
    draft: Draft,
    onUndo: () -> Unit,
    onGpsPoint: () -> Unit,
    onToggleAuto: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindSwatch(draft.kind)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (draft.editingPathId != null) "Editing route" else "Drawing: ${draft.kind.label}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${draft.points.size} points • ${Geo.formatDistance(draft.lengthMeters)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "Tap map to add a point • tap a pin to snap to it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onUndo, enabled = draft.points.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                }
                FilledTonalButton(onClick = onGpsPoint) {
                    Icon(Icons.Default.GpsNotFixed, null)
                    Spacer(Modifier.width(4.dp))
                    Text("GPS point")
                }
                FilterChip(selected = draft.autoTrack, onClick = onToggleAuto, label = { Text("Auto-track") })
                TextButton(onClick = onCancel) { Text("Cancel") }
                FilledTonalButton(onClick = onSave, enabled = draft.points.size >= 2) {
                    Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun AssetCard(
    asset: Asset,
    location: Location?,
    onEdit: () -> Unit,
    onNavigate: () -> Unit,
    onClose: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(asset.type, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(asset.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${asset.type.label} • ${asset.status}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            Text(
                Geo.formatCoord(asset.latitude, asset.longitude) + (asset.accuracy?.let { "  ±${it.toInt()} m" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (location != null) {
                val me = GeoPt(location.latitude, location.longitude)
                val there = GeoPt(asset.latitude, asset.longitude)
                val dist = Geo.distanceMeters(me, there)
                val bearing = Geo.bearingDegrees(me, there)
                Text(
                    "${Geo.formatDistance(dist)} ${Geo.compassPoint(bearing)} (${bearing.toInt()}°) from you",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (asset.notes.isNotBlank()) {
                Text(asset.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
                FilledTonalButton(onClick = onNavigate) {
                    Icon(Icons.Default.Directions, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Navigate")
                }
            }
        }
    }
}

@Composable
private fun PathCard(path: NetPath, onEdit: () -> Unit, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindSwatch(path.kind, path.colorArgb)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(path.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            path.kind.label,
                            path.fiberCount?.let { "${it}F" },
                            path.cableType.takeIf { it.isNotBlank() },
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            Text(
                "Length ${Geo.formatDistance(Geo.pathLengthMeters(path.points))} • ${path.points.size} points",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, null)
                Spacer(Modifier.width(4.dp))
                Text("Details")
            }
        }
    }
}

@Composable
private fun LayersDialog(
    settings: com.networkmapper.app.ui.MapSettings,
    onChange: (com.networkmapper.app.ui.MapSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map layers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Base map", style = MaterialTheme.typography.titleSmall)
                TileStyle.entries.forEach { style ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChange(settings.copy(tileStyle = style)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.tileStyle == style, onClick = { onChange(settings.copy(tileStyle = style)) })
                        Text(style.label)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                CheckRow("Pins", settings.showAssets) { onChange(settings.copy(showAssets = it)) }
                CheckRow("Fiber / link paths", settings.showPaths) { onChange(settings.copy(showPaths = it)) }
                CheckRow("Wi-Fi signal heatmap", settings.showHeatmap) { onChange(settings.copy(showHeatmap = it)) }
                CheckRow("Lock pins (disable drag-to-move)", settings.lockPins) { onChange(settings.copy(lockPins = it)) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Pin types", style = MaterialTheme.typography.titleSmall)
                AssetType.entries.forEach { t ->
                    CheckRow(t.label, t !in settings.hiddenTypes) { show ->
                        onChange(settings.copy(hiddenTypes = if (show) settings.hiddenTypes - t else settings.hiddenTypes + t))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
