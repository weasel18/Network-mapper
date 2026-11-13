@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.networkmapper.app.ui.map

import android.content.ActivityNotFoundException
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import com.networkmapper.app.ui.theme.Night
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.networkmapper.app.data.Coverage
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.isLocation
import com.networkmapper.app.data.point
import com.networkmapper.app.ui.GroupOffer
import com.networkmapper.app.survey.SurveyState
import com.networkmapper.app.ui.CoverageStats
import com.networkmapper.app.ui.Draft
import com.networkmapper.app.ui.DraftEnd
import com.networkmapper.app.ui.HeatFilter
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.PinMove
import com.networkmapper.app.ui.Selection
import com.networkmapper.app.ui.TileStyle
import com.networkmapper.app.ui.components.AssetTypePickerDialog
import com.networkmapper.app.ui.components.KindSwatch
import com.networkmapper.app.ui.components.PathKindPickerDialog
import com.networkmapper.app.ui.components.PhotoStrip
import com.networkmapper.app.ui.components.TypeBadge
import com.networkmapper.app.ui.components.rememberPhotoCapture
import com.networkmapper.app.util.Geo
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.networkmapper.app.data.NetworkSummary
import com.networkmapper.app.ui.SpotInfo
import com.networkmapper.app.ui.wifi.ApNameDialog
import com.networkmapper.app.util.Fmt
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
    onOpenSites: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenPhoto: (Long) -> Unit = {},
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
    val pinMove by vm.pinMove.collectAsStateWithLifecycle()
    // Photos of the tapped pin, for the strip on its card.
    val selAssetId = (selected as? Selection.AssetSel)?.id
    val selPhotos by remember(selAssetId) {
        selAssetId?.let { vm.repo.photosForAsset(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val currentSite by vm.currentSite.collectAsStateWithLifecycle()
    val unifiLive by vm.unifiLive.collectAsStateWithLifecycle()
    val heatFilter by vm.heatFilter.collectAsStateWithLifecycle()
    val heatBand by vm.heatBand.collectAsStateWithLifecycle()
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val spot by vm.spotInfo.collectAsStateWithLifecycle()
    val apNames by vm.apNames.collectAsStateWithLifecycle()
    var nameAp by remember { mutableStateOf<NetworkSummary?>(null) }
    val surveyRunning by SurveyState.running.collectAsStateWithLifecycle()
    val surveyReadings by SurveyState.readings.collectAsStateWithLifecycle()

    var pendingPin by remember { mutableStateOf<PendingPin?>(null) }
    var showKindPicker by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var linkRadiosFor by remember { mutableStateOf<Asset?>(null) }
    var confirmLeaveDraft by remember { mutableStateOf(false) }
    // Location (closet / rack) offers when placing or moving a pin near another one.
    var newPinOffer by remember { mutableStateOf<Triple<PendingPin, AssetType, GroupOffer>?>(null) }
    var moveOffer by remember { mutableStateOf<Pair<Asset, GroupOffer>?>(null) }
    var addDeviceTo by remember { mutableStateOf<Asset?>(null) }
    var showCombine by remember { mutableStateOf(false) }

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
            val current = vm.draft.value
            if (vm.pinMove.value != null) {
                // Moving a pin: other pins are inert.
            } else if (current != null) {
                // Snap to a pin only while adding points; in Edit-points mode pins are inert.
                if (!current.editPoints) vm.addDraftPoint(GeoPt(asset.latitude, asset.longitude), asset.id)
            } else {
                vm.select(Selection.AssetSel(asset.id))
                // While a single-device signal map is open, tapping another linked radio switches to it.
                if (vm.settings.value.showHeatmap && vm.heatFilter.value is HeatFilter.Device) vm.showDeviceSignal(asset)
            }
        }
        overlays.onPathClick = { path ->
            if (vm.draft.value == null && vm.pinMove.value == null) vm.select(Selection.PathSel(path.id))
        }
        overlays.onMapTap = { pt ->
            val current = vm.draft.value
            if (vm.pinMove.value != null) {
                vm.setPinMovePosition(pt)
                true
            } else if (current != null) {
                // In Edit-points mode a map tap only deselects; points are added in Add mode.
                if (current.editPoints) vm.selectDraftPoint(null) else vm.addDraftPoint(pt)
                true
            } else {
                vm.select(null)
                vm.closeSpot()
                false
            }
        }
        overlays.onLongPress = { pt ->
            if (vm.draft.value == null && vm.pinMove.value == null) pendingPin = PendingPin(pt, null, null, fromGps = false)
            true
        }
        overlays.onAssetDragged = { _, pt -> vm.setPinMovePosition(pt) }
        overlays.onUserPan = { follow = false }
        overlays.onVertexTap = { i -> vm.selectDraftPoint(i) }
        overlays.onHeatTap = { p ->
            if (vm.draft.value == null && vm.pinMove.value == null) {
                vm.inspectSpot(p)
                true
            } else {
                false
            }
        }
        overlays.onVertexDragStart = { i -> vm.beginDraftPointMove(i) }
        overlays.onVertexDrag = { i, pt -> vm.moveDraftPoint(i, pt) }
    }

    LaunchedEffect(settings.tileStyle, settings.darkMap) { overlays.setTileStyle(settings.tileStyle, settings.darkMap) }
    val drafting = draft != null
    val editingPathId = draft?.editingPathId
    LaunchedEffect(assets, paths, settings, drafting, editingPathId, heat, selected, pinMove) {
        overlays.render(assets, paths, settings, drafting, editingPathId, heat, selected, pinMove)
    }
    LaunchedEffect(draft) { overlays.setDraft(draft) }

    fun finishDraft() {
        val editing = vm.draft.value?.editingPathId != null
        scope.launch {
            val id = vm.saveDraft()
            when {
                id == null -> Toast.makeText(context, "A route needs at least 2 points", Toast.LENGTH_SHORT).show()
                editing -> {
                    Toast.makeText(context, "Route saved", Toast.LENGTH_SHORT).show()
                    vm.select(Selection.PathSel(id))
                }
                else -> onOpenPath(id)
            }
        }
    }

    // Back while drawing asks what to do instead of silently leaving.
    BackHandler(enabled = drafting) { confirmLeaveDraft = true }
    BackHandler(enabled = pinMove != null) { vm.cancelPinMove() }
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

    val addPinHere: () -> Unit = {
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
    }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Site switcher and, while a survey runs, its status.
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SitePill(
                            name = currentSite?.name ?: "Network Mapper",
                            subtitle = "${assets.size} pin${if (assets.size == 1) "" else "s"} · ${paths.size} route${if (paths.size == 1) "" else "s"}",
                            onClick = onOpenSites,
                            modifier = Modifier.weight(1f),
                        )
                        if (surveyRunning) {
                            val gap by SurveyState.actualScanGapMs.collectAsStateWithLifecycle()
                            val refused by SurveyState.refusedScans.collectAsStateWithLifecycle()
                            SurveyChip(
                                text = "Surveying · $surveyReadings" + (gap?.let { " · ~${(it + 500) / 1000} s" } ?: "") + if (refused > 0) " ⚠" else "",
                                onClick = onOpenWifi,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .height(64.dp),
                    ) {
                        val tab = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                        TabItem(Icons.Default.Map, "Map", true, {
                            vm.select(null)
                            vm.closeSpot()
                        }, tab)
                        TabItem(Icons.AutoMirrored.Filled.List, "Pins", false, onOpenList, tab)
                        TabItem(Icons.Default.Wifi, "Survey", false, onOpenWifi, tab)
                        Box(tab) {
                            TabItem(Icons.Default.MoreHoriz, "More", false, { showMenu = true }, Modifier.fillMaxSize())
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Sites – open, save & load…") }, onClick = {
                                showMenu = false
                                onOpenSites()
                            })
                            DropdownMenuItem(text = { Text("Cloud sync") }, onClick = {
                                showMenu = false
                                onOpenSync()
                            })
                            DropdownMenuItem(text = { Text("Export & data") }, onClick = {
                                showMenu = false
                                onOpenData()
                            })
                            DropdownMenuItem(text = { Text("Combine stacked pins…") }, onClick = {
                                showMenu = false
                                showCombine = true
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Save this view as the site's start view") }, onClick = {
                                showMenu = false
                                val c = mapView.mapCenter
                                vm.setSiteStartView(GeoPt(c.latitude, c.longitude), mapView.zoomLevelDouble)
                                Toast.makeText(context, "Start view saved – opening this site now goes here", Toast.LENGTH_LONG).show()
                            })
                            if (currentSite?.home != null) {
                                DropdownMenuItem(text = { Text("Go to the site's start view") }, onClick = {
                                    showMenu = false
                                    vm.goToSiteStart()
                                })
                                DropdownMenuItem(text = { Text("Clear the site's start view") }, onClick = {
                                    showMenu = false
                                    vm.clearSiteStartView()
                                    Toast.makeText(context, "Start view cleared – the site opens zoomed to its pins", Toast.LENGTH_SHORT).show()
                                })
                            }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // ---- Top-left: GPS and the heatmap key ----
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 10.dp, end = 72.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GpsChip(location, hasLocationPermission, onRequestPermission)
                if (settings.showHeatmap) {
                    val legendLabel = (heatFilter as? HeatFilter.Bssid)?.let { f ->
                        "AP: ${apNames[f.bssid] ?: f.ssid.ifEmpty { "(hidden)" }}"
                    } ?: heatFilter.label
                    HeatLegend(
                        legendLabel, heat.size, coverage, onClear = vm::clearHeatmap, band = heatBand, onBand = { vm.heatBand.value = it },
                        showDots = settings.showHeatDots,
                        showSurface = settings.showHeatSurface,
                        onDots = { on -> vm.updateSettings { it.copy(showHeatDots = on) } },
                        onSurface = { on -> vm.updateSettings { it.copy(showHeatSurface = on) } },
                    )
                }
            }


            // ---- Right-hand tool rail ----
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RailButton(Icons.Default.Layers, "Map layers") { showLayers = true }
                RailButton(Icons.Default.Wifi, "Wi-Fi heatmap", active = settings.showHeatmap) {
                    if (settings.showHeatmap) vm.clearHeatmap() else vm.updateSettings { it.copy(showHeatmap = true) }
                }
                RailButton(if (follow) Icons.Default.GpsFixed else Icons.Default.MyLocation, "My location", active = follow) {
                    val loc = location
                    if (loc == null) {
                        Toast.makeText(context, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
                    } else {
                        follow = true
                        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), maxOf(mapView.zoomLevelDouble, 18.0), 500L)
                    }
                }
                if (draft == null && pinMove == null) {
                    Box {
                        RailButton(Icons.Default.Add, "Add a pin, photo or route", accent = true) { showAdd = true }
                        DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                            DropdownMenuItem(
                                text = { Text("Pin where I'm standing") },
                                leadingIcon = { Icon(Icons.Default.AddLocationAlt, null) },
                                onClick = {
                                    showAdd = false
                                    addPinHere()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Photo pin") },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                                onClick = {
                                    showAdd = false
                                    takePhotoPin()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Draw a route") },
                                leadingIcon = { Icon(Icons.Default.Timeline, null) },
                                onClick = {
                                    showAdd = false
                                    showKindPicker = true
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Tip: long-press the map to drop a pin there", style = MaterialTheme.typography.bodySmall) },
                                onClick = { showAdd = false },
                            )
                        }
                    }
                }
            }

            // ---- Bottom area: the selected pin's sheet, or the card / bar for what's in progress ----
            val d = draft
            val selAsset = (selected as? Selection.AssetSel)?.let { sel -> assets.firstOrNull { it.id == sel.id } }
            val selPath = (selected as? Selection.PathSel)?.let { sel -> paths.firstOrNull { it.id == sel.id } }
            val move = pinMove
            // A device pin's sheet runs edge to edge; the other cards float with a margin.
            val sheet = move == null && d == null && spot == null && selAsset != null && !selAsset.isLocation
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(if (sheet) 0.dp else 12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    move != null -> PinMoveBar(
                        move = move,
                        location = location,
                        onUseGps = {
                            if (!vm.pinMoveToGps()) Toast.makeText(context, "No GPS fix yet", Toast.LENGTH_SHORT).show()
                        },
                        onCancel = vm::cancelPinMove,
                        onSave = {
                            val moving = assets.firstOrNull { it.id == move.assetId }
                            val offer = moving?.let { vm.groupOfferFor(move.current, it) }
                            if (moving != null && offer != null) {
                                moveOffer = moving to offer
                            } else {
                                vm.savePinMove()
                                Toast.makeText(context, "Pin position saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    d == null && spot != null -> SpotCard(
                        spot = spot!!,
                        names = apNames,
                        onCoverage = { n -> vm.showHeatmapFor(HeatFilter.Bssid(n.bssid, n.ssid)) },
                        onName = { n -> nameAp = n },
                        onClose = vm::closeSpot,
                    )
                    d != null -> DraftBar(
                        draft = d,
                        onUndo = vm::undoDraft,
                        onGpsPoint = {
                            if (!vm.addDraftGpsPoint()) Toast.makeText(context, "No GPS fix yet", Toast.LENGTH_SHORT).show()
                        },
                        onToggleAuto = vm::toggleAutoTrack,
                        onExtendFrom = vm::setDraftExtendFrom,
                        onCancel = { if (d.canUndo) confirmLeaveDraft = true else vm.cancelDraft() },
                        onFinish = ::finishDraft,
                        onDeletePoint = vm::deleteSelectedDraftPoint,
                        onInsertPoint = vm::insertDraftPointNearSelected,
                        onDeselect = { vm.selectDraftPoint(null) },
                        onEditPoints = vm::setDraftEditPoints,
                    )
                    selAsset != null && selAsset.isLocation -> LocationCard(
                        place = selAsset,
                        members = assets.filter { it.groupUid == selAsset.uid && !it.isLocation }
                            .sortedWith(compareBy({ it.type.ordinal }, { it.name })),
                        location = location,
                        onOpenMember = { vm.select(Selection.AssetSel(it.id)) },
                        onAddDevice = { addDeviceTo = selAsset },
                        onDetails = { onOpenAsset(selAsset.id) },
                        onNavigate = { navigateTo(context, selAsset.latitude, selAsset.longitude, selAsset.name) },
                        onMove = { vm.startPinMove(selAsset) },
                        onClose = { vm.select(null) },
                        photos = { PhotoStrip(selPhotos, vm.repo::photoFile, { onOpenPhoto(it.id) }) },
                    )
                    selAsset != null -> AssetCard(
                        asset = selAsset,
                        inLocation = assets.firstOrNull { it.isLocation && selAsset.groupUid.isNotEmpty() && it.uid == selAsset.groupUid },
                        onOpenLocation = { loc -> vm.select(Selection.AssetSel(loc.id)) },
                        onTakeOut = {
                            vm.removeFromLocation(selAsset)
                            Toast.makeText(context, "Taken out – it's a separate pin again", Toast.LENGTH_SHORT).show()
                        },
                        location = location,
                        onEdit = { onOpenAsset(selAsset.id) },
                        onNavigate = { navigateTo(context, selAsset.latitude, selAsset.longitude, selAsset.name) },
                        onClose = { vm.select(null) },
                        linkedRadios = vm.linkedBssids(selAsset).size,
                        signalShowing = settings.showHeatmap &&
                            (heatFilter as? HeatFilter.Device)?.assetId == selAsset.id,
                        onSignal = {
                            if (settings.showHeatmap && (heatFilter as? HeatFilter.Device)?.assetId == selAsset.id) {
                                vm.clearHeatmap()
                            } else if (!vm.showDeviceSignal(selAsset)) {
                                linkRadiosFor = selAsset
                            }
                        },
                        onLinkRadios = { linkRadiosFor = selAsset },
                        onMove = { vm.startPinMove(selAsset) },
                        live = unifiLive,
                        onRefreshLive = vm::refreshLive,
                        photos = { PhotoStrip(selPhotos, vm.repo::photoFile, { onOpenPhoto(it.id) }) },
                        uplink = vm.unifiUplink(
                            selAsset,
                            when (val l = unifiLive) {
                                is MainViewModel.LiveState.Ok -> l.live
                                is MainViewModel.LiveState.Failed -> l.last
                                else -> null
                            },
                        ),
                        downlinks = vm.unifiDownlinks(selAsset),
                        onJump = { vm.focusOnAsset(it) },
                    )
                    selPath != null -> PathCard(
                        path = selPath,
                        onEdit = { onOpenPath(selPath.id) },
                        onEditRoute = { vm.editPathRoute(selPath) },
                        onClose = { vm.select(null) },
                    )
                    else -> Unit
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
                val offer = if (type == AssetType.LOCATION) null else vm.groupOfferFor(pin.pt)
                if (offer != null) {
                    newPinOffer = Triple(pin, type, offer)
                } else {
                    scope.launch {
                        val id = vm.createAsset(type, pin.pt, pin.accuracy, pin.altitude)
                        onOpenAsset(id)
                    }
                }
            },
            onDismiss = { pendingPin = null },
        )
    }

    newPinOffer?.let { (pin, type, offer) ->
        GroupOfferDialog(
            offer = offer,
            deviceName = "the new ${type.label.substringBefore(" /")} pin",
            separateLabel = "Separate pin",
            onJoin = { name ->
                newPinOffer = null
                scope.launch { onOpenAsset(vm.createAssetIn(type, offer, name)) }
            },
            onSeparate = {
                newPinOffer = null
                scope.launch { onOpenAsset(vm.createAsset(type, pin.pt, pin.accuracy, pin.altitude)) }
            },
            onDismiss = { newPinOffer = null },
        )
    }

    moveOffer?.let { (asset, offer) ->
        GroupOfferDialog(
            offer = offer,
            deviceName = "“${asset.name}”",
            separateLabel = "Just move it",
            onJoin = { name ->
                moveOffer = null
                vm.joinLocation(asset, offer, name)
                Toast.makeText(context, "Added to the location", Toast.LENGTH_SHORT).show()
            },
            onSeparate = {
                moveOffer = null
                vm.savePinMove()
                Toast.makeText(context, "Pin position saved", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { moveOffer = null },
        )
    }

    addDeviceTo?.let { place ->
        AssetTypePickerDialog(
            title = "Add device to ${place.name}",
            subtitle = null,
            types = AssetType.entries - AssetType.LOCATION,
            onPick = { type ->
                addDeviceTo = null
                scope.launch {
                    onOpenAsset(vm.createAsset(type, place.point(), place.accuracy, place.altitude, groupUid = place.uid))
                }
            },
            onDismiss = { addDeviceTo = null },
        )
    }

    if (showCombine) {
        CombineStackedDialog(
            clusters = remember { vm.stackedPins() },
            onCombine = { pins, name -> vm.combineIntoLocation(pins, name) },
            onDismiss = { showCombine = false },
        )
    }

    if (confirmLeaveDraft) {
        val d = draft
        AlertDialog(
            onDismissRequest = { confirmLeaveDraft = false },
            title = { Text(if (d?.editingPathId != null) "Leave route editing?" else "Leave route drawing?") },
            text = {
                Text(
                    if ((d?.points?.size ?: 0) >= 2) {
                        "Finish to save the route, or discard your changes."
                    } else {
                        "This route has fewer than 2 points and can't be saved yet."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeaveDraft = false
                        finishDraft()
                    },
                    enabled = (d?.points?.size ?: 0) >= 2,
                ) { Text("Finish & save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmLeaveDraft = false
                        vm.cancelDraft()
                    }) { Text("Discard") }
                    TextButton(onClick = { confirmLeaveDraft = false }) { Text("Keep drawing") }
                }
            },
        )
    }

    nameAp?.let { n ->
        ApNameDialog(
            vm = vm,
            bssid = n.bssid,
            ssid = n.ssid,
            frequency = n.frequency,
            onDismiss = { nameAp = null },
            onPinCreated = { id ->
                vm.closeSpot()
                vm.select(Selection.AssetSel(id))
            },
        )
    }

    linkRadiosFor?.let { asset ->
        LinkRadiosDialog(
            vm = vm,
            asset = asset,
            onDismiss = { linkRadiosFor = null },
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
private fun HeatLegend(
    label: String,
    spots: Int,
    coverage: CoverageStats?,
    onClear: () -> Unit,
    band: com.networkmapper.app.ui.HeatBand,
    onBand: (com.networkmapper.app.ui.HeatBand) -> Unit,
    showDots: Boolean = true,
    showSurface: Boolean = true,
    onDots: (Boolean) -> Unit = {},
    onSurface: (Boolean) -> Unit = {},
) {
    // Tap the title to fold the options away and leave just the colour key, so more of the map shows.
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (expanded) "▾ " else "▸ ") + label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { expanded = !expanded }
                        .padding(vertical = 6.dp),
                )
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Hide heatmap", Modifier.size(16.dp))
                }
            }
            // Band toggle: All / 2.4 / 5 / 6 GHz.
            if (expanded) Row(Modifier.padding(end = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                com.networkmapper.app.ui.HeatBand.entries.forEach { b ->
                    FilterChip(
                        selected = band == b,
                        onClick = { onBand(b) },
                        label = { Text(b.label.removeSuffix(" GHz"), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
                Text("GHz", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
            }
            // What to draw: the dots, the smooth average colour, or both.
            if (expanded) Row(Modifier.padding(end = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = showDots,
                    onClick = { onDots(!showDots) },
                    label = { Text("Dots", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                )
                FilterChip(
                    selected = showSurface,
                    onClick = { onSurface(!showSurface) },
                    label = { Text("Average colour", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                )
            }
            if (!expanded) {
                // Folded: only the colour key below.
            } else if (coverage != null) {
                Text(
                    buildString {
                        append("${coverage.spots} spots")
                        coverage.bestRssi?.let { append(" • best $it dBm") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Reliable (≥ -67): " + (coverage.reliableRangeM?.let { "up to ${Geo.formatDistance(it)}" } ?: "none recorded"),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Usable (≥ -80): " + (coverage.edgeRangeM?.let { "up to ${Geo.formatDistance(it)}" } ?: "none recorded"),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (spots == 0) {
                Text("No survey samples for this filter yet", style = MaterialTheme.typography.labelSmall)
            }
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
private fun SpotCard(
    spot: SpotInfo,
    names: Map<String, String>,
    onCoverage: (NetworkSummary) -> Unit,
    onName: (NetworkSummary) -> Unit,
    onClose: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi heard here", fontWeight = FontWeight.SemiBold)
                    Text(
                        Geo.formatCoord(spot.lat, spot.lon) + (spot.lastSeen?.let { " • ${Fmt.dateTime(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            val t = spot.tapped
            Text(
                "Dot you tapped: ${names[t.bssid] ?: t.ssid.ifEmpty { "(hidden)" }} • ${t.rssi} dBm",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(Wifi.rssiColor(t.rssi)),
                fontWeight = FontWeight.Medium,
            )
            if (spot.networks.isEmpty()) {
                Text("No samples recorded within 5 m.", style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.heightIn(max = 230.dp)) {
                items(spot.networks, key = { it.bssid }) { n ->
                    val named = names[n.bssid]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                named ?: n.ssid.ifEmpty { "(hidden network)" },
                                fontWeight = if (n.bssid == t.bssid) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    if (named != null) n.ssid.ifEmpty { "(hidden)" } else null,
                                    n.bssid,
                                    "${Wifi.band(n.frequency)} ch ${Wifi.channel(n.frequency)}",
                                    Wifi.radioDetails(n.standard, n.widthMhz, n.stations, n.utilPct, n.frequency, peak = true),
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("${n.bestRssi} dBm", color = Color(Wifi.rssiColor(n.bestRssi)), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onName(n) }) { Icon(Icons.Default.Edit, "Name this AP") }
                        IconButton(onClick = { onCoverage(n) }) { Icon(Icons.Default.Wifi, "Show this AP's coverage") }
                    }
                }
            }
            Text(
                "✎ name an AP by its MAC  •  Wi-Fi icon shows that AP's coverage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DraftBar(
    draft: Draft,
    onUndo: () -> Unit,
    onGpsPoint: () -> Unit,
    onToggleAuto: () -> Unit,
    onExtendFrom: (DraftEnd) -> Unit,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    onDeletePoint: () -> Unit,
    onInsertPoint: () -> Unit,
    onDeselect: () -> Unit,
    onEditPoints: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header: title + always-visible Finish button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindSwatch(draft.kind)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (draft.editingPathId != null) "Editing route" else "Drawing: ${draft.kind.label}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${draft.points.size} points • ${Geo.formatDistance(draft.lengthMeters)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = onFinish, enabled = draft.points.size >= 2) {
                    Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Finish")
                }
            }

            // Mode switch: existing points can only be changed in Edit points mode.
            if (draft.points.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !draft.editPoints,
                        onClick = { onEditPoints(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                    ) { Text("Add points") }
                    SegmentedButton(
                        selected = draft.editPoints,
                        onClick = { onEditPoints(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                    ) { Text("Edit points") }
                }
            }

            if (draft.editPoints) {
                val sel = draft.selectedIndex
                if (sel != null && sel in draft.points.indices) {
                    Text(
                        "Point ${sel + 1} of ${draft.points.size} selected – drag it to move, or:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "Tap a point to select it, or press and drag a point to move it. Pins are locked while editing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onUndo, enabled = draft.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    FilledTonalButton(onClick = onDeletePoint, enabled = sel != null) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                    FilledTonalButton(onClick = onInsertPoint, enabled = sel != null && draft.points.size >= 2) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Insert")
                    }
                    if (sel != null) TextButton(onClick = onDeselect) { Text("Deselect") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Text(
                    when {
                        draft.points.isEmpty() -> "Tap the map to place the first point, or use GPS point / Auto-track."
                        draft.points.size == 1 -> "Add at least one more point, then tap Finish."
                        else -> "Tap the map to add at end ${if (draft.extendFrom == DraftEnd.START) "A" else "B"} • " +
                            "tap a pin to snap. Existing points are locked."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Tools wrap onto a second line instead of scrolling off-screen.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onUndo, enabled = draft.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    FilledTonalButton(onClick = onGpsPoint) {
                        Icon(Icons.Default.GpsNotFixed, null)
                        Spacer(Modifier.width(4.dp))
                        Text("GPS point")
                    }
                    FilterChip(selected = draft.autoTrack, onClick = onToggleAuto, label = { Text("Auto-track") })
                    if (draft.points.size >= 2) {
                        FilterChip(
                            selected = draft.extendFrom == DraftEnd.START,
                            onClick = {
                                onExtendFrom(if (draft.extendFrom == DraftEnd.START) DraftEnd.END else DraftEnd.START)
                            },
                            label = { Text(if (draft.extendFrom == DraftEnd.START) "Adding at start (A)" else "Adding at end (B)") },
                        )
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PinMoveBar(
    move: PinMove,
    location: Location?,
    onUseGps: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Moving: ${move.name}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val moved = Geo.distanceMeters(move.original, move.current)
                    Text(
                        if (moved < 0.05) "Not moved yet" else "Moved ${Geo.formatDistance(moved)}" +
                            (move.accuracy?.let { " • GPS ±${it.toInt()} m" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = onSave, enabled = move.current != move.original) {
                    Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Save position")
                }
            }
            Text(
                "Tap the map where the pin belongs, or long-press the pin and drag it. Nothing changes until you save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onUseGps) {
                    Icon(Icons.Default.GpsFixed, null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (location != null && location.hasAccuracy()) {
                            "Use my GPS (±${location.accuracy.toInt()} m)"
                        } else {
                            "Use my GPS"
                        },
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/**
 * The selected pin's sheet. Half open it shows the essentials; drag its top up (or tap it) for every detail,
 * drag down to shrink it again, and further down to close it.
 */
@Composable
private fun AssetCard(
    asset: Asset,
    inLocation: Asset?,
    onOpenLocation: (Asset) -> Unit,
    onTakeOut: () -> Unit,
    location: Location?,
    onEdit: () -> Unit,
    onNavigate: () -> Unit,
    onClose: () -> Unit,
    linkedRadios: Int,
    signalShowing: Boolean,
    onSignal: () -> Unit,
    onLinkRadios: () -> Unit,
    onMove: () -> Unit,
    live: MainViewModel.LiveState?,
    onRefreshLive: () -> Unit,
    photos: @Composable () -> Unit = {},
    uplink: MainViewModel.UnifiLink? = null,
    downlinks: List<MainViewModel.UnifiLink> = emptyList(),
    onJump: (Asset) -> Unit = {},
) {
    var expanded by rememberSaveable(asset.id) { mutableStateOf(false) }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val bodyMax by animateDpAsState(if (expanded) (screenH * 0.68f).dp else (screenH * 0.32f).dp, label = "sheet")
    val scheme = MaterialTheme.colorScheme
    val liveData = when (live) {
        is MainViewModel.LiveState.Ok -> live.live
        is MainViewModel.LiveState.Failed -> live.last
        else -> null
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 12.dp,
    ) {
        Column {
            // Handle + title: this strip is what you drag or tap.
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(expanded) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                when {
                                    total < -30.dp.toPx() -> expanded = true
                                    total > 30.dp.toPx() && expanded -> expanded = false
                                    total > 90.dp.toPx() -> onClose()
                                }
                            },
                        ) { _, dy -> total += dy }
                    }
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .background(scheme.outline, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(asset.type, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            asset.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val where = location?.let {
                            val me = GeoPt(it.latitude, it.longitude)
                            val there = GeoPt(asset.latitude, asset.longitude)
                            "${Geo.formatDistance(Geo.distanceMeters(me, there))} ${Geo.compassPoint(Geo.bearingDegrees(me, there))} of you"
                        }
                        Text(
                            listOfNotNull(asset.type.label.substringBefore(" /"), where).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                }
            }
            Column(
                Modifier
                    .heightIn(max = bodyMax)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Status", asset.status, Modifier.weight(1f))
                    if (liveData != null) {
                        StatTile(
                            "UniFi",
                            if (liveData.online) "Online" else "Offline",
                            Modifier.weight(1f),
                            if (liveData.online) Night.Green else Night.Red,
                        )
                        liveData.clients?.let { StatTile("Clients", it.toString(), Modifier.weight(1f)) }
                    } else {
                        StatTile("GPS fix", asset.accuracy?.let { "±${it.toInt()} m" } ?: "placed by hand", Modifier.weight(1f))
                    }
                }
                photos()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onEdit,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp),
                    ) { Text("Details", fontWeight = FontWeight.Bold) }
                    if (asset.type.bssidField != null) {
                        OutlinedButton(
                            onClick = onSignal,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            border = BorderStroke(1.5.dp, scheme.primary),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) { Text(if (signalShowing) "Hide signal" else "Signal map", maxLines = 1) }
                    }
                    FilledTonalIconButton(onClick = onNavigate, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Directions, "Navigate there")
                    }
                    if (inLocation == null) {
                        FilledTonalIconButton(onClick = onMove, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.OpenWith, "Move pin")
                        }
                    }
                }
                if (inLocation != null) {
                    // Devices move with their location; taking one out makes it a separate pin again.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onOpenLocation(inLocation) }) {
                            Text("${AssetType.LOCATION.glyph} In ${inLocation.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = onTakeOut) { Text("Take out") }
                    }
                }
                if (!expanded) {
                    Text(
                        "Pull up for all details",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                } else {
                    HorizontalDivider(color = scheme.outlineVariant)
                    DetailRow("Type", asset.type.label)
                    DetailRow(
                        "Position",
                        Geo.formatCoord(asset.latitude, asset.longitude) + (asset.accuracy?.let { "  ±${it.toInt()} m" } ?: ""),
                    )
                    Coverage.read(asset.attributes)?.let { p -> DetailRow("Coverage", p.summary()) }
                    asset.attributes.filterKeys { it !in Coverage.keys }.forEach { (k, v) -> if (v.isNotBlank()) DetailRow(k, v) }
                    if (asset.notes.isNotBlank()) DetailRow("Notes", asset.notes)
                    if (asset.type.bssidField != null) {
                        TextButton(onClick = onLinkRadios, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(if (linkedRadios == 0) "Link a Wi-Fi radio…" else "$linkedRadios radio${if (linkedRadios == 1) "" else "s"} linked – change")
                        }
                    }
                    // Live status from UniFi when this pin matches an imported device.
                    if (live != null) {
                        HorizontalDivider(color = scheme.outlineVariant)
                        UnifiLiveSection(live, onRefreshLive, uplink, downlinks, onJump)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One "label: value" line in the opened sheet. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.62f))
    }
}

/** The site switcher in the top strip. */
@Composable
private fun SitePill(name: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(23.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(Night.Green, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Default.ArrowDropDown, "Switch site", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SurveyChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, Night.Red),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Night.Red, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 1)
        }
    }
}

/** A square button in the map's right-hand tool rail. */
@Composable
private fun RailButton(icon: ImageVector, label: String, active: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (accent) scheme.primary else scheme.surfaceContainer,
        border = BorderStroke(1.dp, if (active || accent) scheme.primary else scheme.outlineVariant),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (accent) scheme.onPrimary else if (active) scheme.primary else scheme.onSurface)
        }
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** A closet / rack holding several devices, shown as one pin. */
@Composable
private fun LocationCard(
    place: Asset,
    members: List<Asset>,
    location: Location?,
    onOpenMember: (Asset) -> Unit,
    onAddDevice: () -> Unit,
    onDetails: () -> Unit,
    onNavigate: () -> Unit,
    onMove: () -> Unit,
    onClose: () -> Unit,
    photos: @Composable () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(place.type, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(place.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Location • ${members.size} device${if (members.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            if (location != null) {
                val me = GeoPt(location.latitude, location.longitude)
                val dist = Geo.distanceMeters(me, place.point())
                val bearing = Geo.bearingDegrees(me, place.point())
                Text(
                    "${Geo.formatDistance(dist)} ${Geo.compassPoint(bearing)} (${bearing.toInt()}°) from you",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (place.notes.isNotBlank()) {
                Text(place.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            photos()
            Column(
                Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (members.isEmpty()) {
                    Text("No devices here yet – add the router, switches and APs.", style = MaterialTheme.typography.bodySmall)
                }
                members.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMember(m) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeBadge(m.type, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${m.type.label} • ${m.status}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAddDevice) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add device")
                }
                FilledTonalButton(onClick = onDetails) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
                FilledTonalButton(onClick = onNavigate) {
                    Icon(Icons.Default.Directions, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Navigate")
                }
                OutlinedButton(onClick = onMove) {
                    Icon(Icons.Default.OpenWith, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Move")
                }
            }
        }
    }
}

/** Asks whether a pin being placed / moved should share a nearby location (or start one with a lone pin). */
@Composable
private fun GroupOfferDialog(
    offer: GroupOffer,
    deviceName: String,
    separateLabel: String,
    onJoin: (String) -> Unit,
    onSeparate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val dist = Geo.formatDistance(offer.distanceM)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (offer.joinsExisting) "Add to ${offer.target.name}?" else "Same location?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offer.joinsExisting) {
                    Text("“${offer.target.name}” is $dist away. Put $deviceName in it? It will show on that location's pin instead of its own.")
                } else {
                    Text(
                        "“${offer.target.name}” (${offer.target.type.label}) is $dist away. " +
                            "Put $deviceName and it together in one location, shown as a single pin?",
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Location name") },
                        placeholder = { Text("e.g. IDF-2 closet, Rack A") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(name) }) { Text(if (offer.joinsExisting) "Add to location" else "Create location") }
        },
        dismissButton = { TextButton(onClick = onSeparate) { Text(separateLabel) } },
    )
}

/** Lists pins sitting on top of each other and puts each stack into a named location. */
@Composable
private fun CombineStackedDialog(
    clusters: List<List<Asset>>,
    onCombine: (List<Asset>, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var remaining by remember { mutableStateOf(clusters) }
    val names = remember { mutableStateOf(mapOf<Int, String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Combine stacked pins") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (remaining.isEmpty()) {
                    Text("No pins within a few metres of each other in this site.")
                } else {
                    Text(
                        "These pins are within a few metres of each other. Combine each group into one location pin.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                remaining.forEachIndexed { i, pins ->
                    val key = pins.first().id.toInt()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            pins.forEach { p ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TypeBadge(p.type, 22.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            OutlinedTextField(
                                value = names.value[key] ?: "",
                                onValueChange = { v -> names.value = names.value + (key to v) },
                                label = { Text("Location name") },
                                placeholder = { Text("e.g. IDF-2 closet") },
                                singleLine = true,
                            )
                            FilledTonalButton(onClick = {
                                onCombine(pins, names.value[key] ?: "")
                                remaining = remaining.filterIndexed { j, _ -> j != i }
                            }) { Text("Combine ${pins.size} pins") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PathCard(path: NetPath, onEdit: () -> Unit, onEditRoute: () -> Unit, onClose: () -> Unit) {
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
                            path.fiberCount?.takeIf { path.kind.isFiber }?.let { "${it}F" },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
                FilledTonalButton(onClick = onEditRoute) {
                    Icon(Icons.Default.Timeline, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit / extend route")
                }
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
                Text("Show on the map", style = MaterialTheme.typography.titleSmall)
                CheckRow("Pins", settings.showAssets) { onChange(settings.copy(showAssets = it)) }
                CheckRow("   Pin names (when zoomed in)", settings.showPinNames) { onChange(settings.copy(showPinNames = it)) }
                CheckRow("Fiber / link paths", settings.showPaths) { onChange(settings.copy(showPaths = it)) }
                CheckRow("Wi-Fi signal heatmap", settings.showHeatmap) { onChange(settings.copy(showHeatmap = it)) }
                CheckRow("   Signal dots", settings.showHeatDots) { onChange(settings.copy(showHeatDots = it)) }
                CheckRow("   Smooth average colour", settings.showHeatSurface) { onChange(settings.copy(showHeatSurface = it)) }
                CheckRow("AP coverage patterns", settings.showPatterns) { onChange(settings.copy(showPatterns = it)) }
                CheckRow("Dark street map", settings.darkMap) { onChange(settings.copy(darkMap = it)) }
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
