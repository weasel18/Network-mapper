package com.networkmapper.app.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.photo.PhotoStorage
import kotlinx.coroutines.launch
import java.io.File

/** Coloured round badge with the asset type's glyph – matches the map markers. */
@Composable
fun TypeBadge(type: AssetType, size: Dp = 32.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(type.colorArgb))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (type == AssetType.ACCESS_POINT) {
            Icon(Icons.Default.Wifi, contentDescription = type.label, tint = Color.White, modifier = Modifier.size(size * 0.62f))
        } else {
            Text(type.glyph, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
        }
    }
}

@Composable
fun KindSwatch(kind: PathKind, colorArgb: Long? = null) {
    Box(
        Modifier
            .width(28.dp)
            .heightIn(min = 6.dp)
            .padding(vertical = 13.dp)
            .background(Color(colorArgb ?: kind.colorArgb), RoundedCornerShape(3.dp)),
    )
}

@Composable
fun AssetTypePickerDialog(
    title: String,
    subtitle: String?,
    onPick: (AssetType) -> Unit,
    onDismiss: () -> Unit,
    types: List<AssetType> = AssetType.entries,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(types) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(t) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TypeBadge(t, 28.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(t.label)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PathKindPickerDialog(onPick: (PathKind) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw a path") },
        text = {
            Column {
                Text(
                    "Tap the map to add points, tap a pin to snap to it, or let GPS auto-track as you walk the route.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PathKind.entries.forEach { k ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(k) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindSwatch(k)
                        Spacer(Modifier.width(12.dp))
                        Text(k.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Read-only text field that opens a dropdown of [options]. */
@Composable
fun <T> DropdownField(
    label: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = optionLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = {
                    onSelect(o)
                    expanded = false
                })
            }
        }
    }
}

/**
 * Returns a function that launches the camera and calls [onCaptured] with the saved file.
 * The pending file path is saveable so it survives the activity being recreated.
 */
@Composable
fun rememberPhotoCapture(onCaptured: (File) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val callback by rememberUpdatedState(onCaptured)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        pendingPath = null
        if (path != null) {
            val f = File(path)
            if (ok && f.exists() && f.length() > 0) callback(f) else f.delete()
        }
    }
    return {
        val f = PhotoStorage.newPhotoFile(context)
        pendingPath = f.absolutePath
        try {
            launcher.launch(PhotoStorage.uriFor(context, f))
        } catch (e: ActivityNotFoundException) {
            pendingPath = null
            Toast.makeText(context, "No camera app available", Toast.LENGTH_LONG).show()
        }
    }
}

/** Returns a function that opens the photo picker and calls [onImported] with a private copy. */
@Composable
fun rememberGalleryImport(onImported: (File) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onImported)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val f = PhotoStorage.importFromUri(context, uri)
                if (f != null) callback(f) else Toast.makeText(context, "Couldn't import image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** A pin's photos as a horizontally scrolling row of thumbnails (nothing when there are none). */
@Composable
fun PhotoStrip(photos: List<Photo>, fileFor: (Photo) -> File, onOpenPhoto: (Photo) -> Unit, size: Dp = 76.dp) {
    if (photos.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(photos, key = { it.id }) { p ->
            AsyncImage(
                model = fileFor(p),
                contentDescription = p.caption.ifEmpty { "Photo" },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenPhoto(p) },
            )
        }
    }
}

@Composable
fun PhotoSection(
    photos: List<Photo>,
    fileFor: (Photo) -> File,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onOpenPhoto: (Photo) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Photos (${photos.size})", style = MaterialTheme.typography.titleMedium)
        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.id }) { p ->
                    AsyncImage(
                        model = fileFor(p),
                        contentDescription = p.caption.ifEmpty { "Photo" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenPhoto(p) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTakePhoto) {
                Icon(Icons.Default.PhotoCamera, null)
                Spacer(Modifier.width(6.dp))
                Text("Take photo")
            }
            OutlinedButton(onClick = onPickPhoto) {
                Icon(Icons.Default.PhotoLibrary, null)
                Spacer(Modifier.width(6.dp))
                Text("Gallery")
            }
        }
    }
}

/** Key/value editor for free-form attributes, pre-populated with the type's suggested fields. */
@Composable
fun AttributesEditor(
    suggested: List<String>,
    values: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
    /** Keys edited elsewhere on the screen (e.g. the coverage pattern) and not shown here. */
    hiddenKeys: Set<String> = emptySet(),
) {
    var showAdd by remember { mutableStateOf(false) }
    val keys = (suggested + values.keys).distinct().filter { it !in hiddenKeys }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Details", style = MaterialTheme.typography.titleMedium)
        keys.forEach { k ->
            OutlinedTextField(
                value = values[k] ?: "",
                onValueChange = { v -> onChange(LinkedHashMap(values).apply { put(k, v) }) },
                label = { Text(k) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = { showAdd = true }) { Text("+ Add custom field") }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Custom field") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Field name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val k = name.trim()
                        if (k.isNotEmpty() && k !in values) onChange(LinkedHashMap(values).apply { put(k, "") })
                        showAdd = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Colour of a Save button once the changes are saved (until something is edited again). */
val SavedGreen = Color(0xFF2E7D32)

/** Save state shown on Save buttons: grey "Saving…" while saving, green "Saved ✓" afterwards. */
enum class SaveState { IDLE, SAVING, SAVED }

/** Full-width Save button that shows [state]. */
@Composable
fun SaveButton(state: SaveState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = state != SaveState.SAVING,
        colors = if (state == SaveState.SAVED) {
            ButtonDefaults.buttonColors(containerColor = SavedGreen, contentColor = Color.White)
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = modifier,
    ) {
        Text(
            when (state) {
                SaveState.IDLE -> "Save"
                SaveState.SAVING -> "Saving…"
                SaveState.SAVED -> "Saved ✓"
            },
        )
    }
}

/** Top-bar Save text button that shows [state]. */
@Composable
fun SaveTextButton(state: SaveState, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = state != SaveState.SAVING) {
        Text(
            when (state) {
                SaveState.IDLE -> "Save"
                SaveState.SAVING -> "Saving…"
                SaveState.SAVED -> "Saved ✓"
            },
            color = if (state == SaveState.SAVED) SavedGreen else Color.Unspecified,
        )
    }
}
