@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.photo

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.photo.PhotoStorage
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.ConfirmDialog
import com.networkmapper.app.util.Fmt
import com.networkmapper.app.util.Geo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun PhotoScreen(vm: MainViewModel, photoId: Long, onBack: () -> Unit, onShowOnMap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val opened by remember(photoId) { vm.repo.observePhoto(photoId) }.collectAsStateWithLifecycle(null)
    // Swipe through all photos of the same pin (or route), starting at the one that was tapped. The owner is
    // remembered so the list survives deleting the photo that was opened.
    var owner by remember { mutableStateOf<Pair<Long?, Long?>?>(null) }
    LaunchedEffect(opened) { opened?.let { if (owner == null) owner = it.assetId to it.pathId } }
    val siblingsFlow = remember(owner) {
        val (assetId, pathId) = owner ?: (null to null)
        when {
            assetId != null -> vm.repo.photosForAsset(assetId)
            pathId != null -> vm.repo.photosForPath(pathId)
            else -> flowOf(emptyList())
        }
    }
    val siblings by siblingsFlow.collectAsStateWithLifecycle(emptyList())
    val photos = siblings.ifEmpty { listOfNotNull(opened) }
    val pager = rememberPagerState { photos.size }
    // Go to the photo that was tapped once it's in the list (the list arrives a moment after the screen opens).
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(photos.map { it.id }) {
        val index = photos.indexOfFirst { it.id == photoId }
        if (!positioned && index >= 0 && siblings.isNotEmpty()) {
            pager.scrollToPage(index)
            positioned = true
        }
    }
    val current = photos.getOrNull(pager.currentPage)
    var caption by remember(current?.id) { mutableStateOf(current?.caption ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (photos.size > 1) "Photo ${(pager.currentPage + 1).coerceAtMost(photos.size)} of ${photos.size}" else "Photo") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    val p = current
                    if (p != null) {
                        if (p.latitude != null && p.longitude != null) {
                            IconButton(onClick = {
                                vm.focusOn(GeoPt(p.latitude, p.longitude))
                                onShowOnMap()
                            }) { Icon(Icons.Default.Map, "Show on map") }
                        }
                        IconButton(onClick = {
                            val uri = PhotoStorage.uriFor(context, vm.repo.photoFile(p))
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("image/jpeg")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Share photo"))
                        }) { Icon(Icons.Default.Share, "Share") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pager,
                // While a photo is zoomed in, dragging pans it instead of changing photo.
                userScrollEnabled = !zoomed,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
            ) { page ->
                val ph = photos.getOrNull(page)
                if (ph != null) {
                    ZoomablePhoto(
                        model = vm.repo.photoFile(ph),
                        description = ph.caption.ifEmpty { "Photo" },
                        onZoomChange = { if (page == pager.currentPage) zoomed = it },
                    )
                }
            }
            val p = current
            if (p != null) {
                Column(Modifier.padding(12.dp)) {
                    Text("Taken ${Fmt.dateTime(p.takenAt)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (p.latitude != null && p.longitude != null) {
                            Geo.formatCoord(p.latitude, p.longitude) + (p.accuracy?.let { "  ±${it.toInt()} m" } ?: "")
                        } else {
                            "No location recorded"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        label = { Text("Caption") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (caption != p.caption) {
                                TextButton(onClick = {
                                    scope.launch { vm.repo.updatePhoto(p.copy(caption = caption.trim())) }
                                }) { Text("Save") }
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete photo?",
            text = "The image file will be removed from this device.",
            confirm = "Delete",
            onConfirm = {
                confirmDelete = false
                val p = current
                if (p != null) {
                    scope.launch {
                        vm.repo.deletePhoto(p)
                        // Stay on the remaining photos; leave when it was the last one.
                        if (photos.size <= 1) onBack()
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Pinch to zoom; once zoomed, one finger pans. At normal size single-finger drags are left alone so the
 * pager can swipe to the next photo.
 */
@Composable
private fun ZoomablePhoto(model: Any, description: String, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            onZoomChange(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
