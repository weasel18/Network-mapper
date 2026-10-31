@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.photo

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import kotlinx.coroutines.launch

@Composable
fun PhotoScreen(vm: MainViewModel, photoId: Long, onBack: () -> Unit, onShowOnMap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photo by remember(photoId) { vm.repo.observePhoto(photoId) }.collectAsStateWithLifecycle(null)
    var caption by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(photo?.id) {
        if (caption == null) caption = photo?.caption
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    val p = photo
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
        val p = photo
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    },
            ) {
                if (p != null) {
                    AsyncImage(
                        model = vm.repo.photoFile(p),
                        contentDescription = p.caption.ifEmpty { "Photo" },
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
                        value = caption ?: "",
                        onValueChange = { caption = it },
                        label = { Text("Caption") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if ((caption ?: "") != p.caption) {
                                TextButton(onClick = {
                                    scope.launch { vm.repo.updatePhoto(p.copy(caption = caption?.trim() ?: "")) }
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
                val p = photo
                if (p != null) {
                    scope.launch {
                        vm.repo.deletePhoto(p)
                        onBack()
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
