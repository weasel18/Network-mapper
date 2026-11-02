@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.networkmapper.app.ui.path

import com.networkmapper.app.ui.components.SaveState
import com.networkmapper.app.ui.components.SaveTextButton
import com.networkmapper.app.ui.components.SaveButton
import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.networkmapper.app.NetworkMapperApp
import com.networkmapper.app.data.ASSET_STATUSES
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.PathKind
import com.networkmapper.app.data.Photo
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.AttributesEditor
import com.networkmapper.app.ui.components.ConfirmDialog
import com.networkmapper.app.ui.components.DropdownField
import com.networkmapper.app.ui.components.KindSwatch
import com.networkmapper.app.ui.components.PhotoSection
import com.networkmapper.app.ui.components.rememberGalleryImport
import com.networkmapper.app.ui.components.rememberPhotoCapture
import com.networkmapper.app.util.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Palette offered for colour-coding cables (e.g. by fiber count or owner). */
val PATH_COLORS: List<Long> = listOf(
    0xFF1565C0, 0xFF6A1B9A, 0xFFEF6C00, 0xFF2E7D32, 0xFFC62828,
    0xFF00838F, 0xFFF9A825, 0xFF4E342E, 0xFF212121, 0xFFAD1457,
)

data class PathForm(
    val kind: PathKind,
    val name: String,
    val fiberCount: String,
    val cableType: String,
    val status: String,
    val notes: String,
    val colorArgb: Long?,
    val attributes: Map<String, String>,
) {
    fun cleanedAttributes(): Map<String, String> =
        attributes.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    companion object {
        fun from(p: NetPath) = PathForm(
            kind = p.kind,
            name = p.name,
            fiberCount = p.fiberCount?.toString() ?: "",
            cableType = p.cableType,
            status = p.status,
            notes = p.notes,
            colorArgb = p.colorArgb,
            attributes = p.attributes,
        )
    }
}

class PathEditViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = (app as NetworkMapperApp).repository
    val pathId: Long = handle.get<Long>("id") ?: 0L

    val path: StateFlow<NetPath?> = repo.observePath(pathId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val photos: StateFlow<List<Photo>> =
        repo.photosForPath(pathId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val form = MutableStateFlow<PathForm?>(null)
    val missing = MutableStateFlow(false)
    val endpointNames = MutableStateFlow<Pair<String?, String?>>(null to null)
    private var originalForm: PathForm? = null

    init {
        viewModelScope.launch {
            val p = repo.getPath(pathId)
            if (p == null) {
                missing.value = true
            } else {
                originalForm = PathForm.from(p)
                form.value = originalForm
                endpointNames.value = p.startAssetId?.let { repo.getAsset(it)?.name } to p.endAssetId?.let { repo.getAsset(it)?.name }
            }
        }
    }

    fun photoFile(p: Photo) = repo.photoFile(p)

    val isDirty: Boolean
        get() {
            val f = form.value ?: return false
            val o = originalForm ?: return false
            return f.copy(attributes = f.cleanedAttributes()) != o.copy(attributes = o.cleanedAttributes())
        }

    fun update(block: (PathForm) -> PathForm) {
        form.value = form.value?.let(block)
    }

    suspend fun save(): String? {
        val f = form.value ?: return "Nothing to save"
        val base = repo.getPath(pathId) ?: return "Path not found"
        val count = f.fiberCount.trim()
        // A fiber count only makes sense on fiber routes; drop a stale one if the kind was changed.
        val fiberCount = if (!f.kind.isFiber || count.isEmpty()) null else count.toIntOrNull() ?: return "Fiber count must be a number"
        val updated = base.copy(
            kind = f.kind,
            name = f.name.trim().ifEmpty { f.kind.label },
            fiberCount = fiberCount,
            cableType = if (f.kind.cableLabel == null) "" else f.cableType.trim(),
            status = f.status,
            notes = f.notes.trim(),
            colorArgb = f.colorArgb,
            attributes = f.cleanedAttributes(),
        )
        repo.updatePath(updated)
        originalForm = PathForm.from(updated)
        form.value = originalForm
        return null
    }

    suspend fun delete() {
        repo.getPath(pathId)?.let { repo.deletePath(it) }
    }
}

@Composable
fun PathEditScreen(
    mainVm: MainViewModel,
    onBack: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onShowOnMap: () -> Unit,
    onEditRoute: () -> Unit,
    vm: PathEditViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form by vm.form.collectAsStateWithLifecycle()
    val path by vm.path.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val endpoints by vm.endpointNames.collectAsStateWithLifecycle()

    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val takePhoto = rememberPhotoCapture { file -> mainVm.attachPhoto(file, pathId = vm.pathId) }
    val pickPhoto = rememberGalleryImport { file -> mainVm.attachPhoto(file, pathId = vm.pathId, fromGallery = true) }

    val tryBack: () -> Unit = {
        if (vm.isDirty) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }
    BackHandler(onBack = tryBack)

    fun saveThen(after: () -> Unit) {
        scope.launch {
            val err = vm.save()
            if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else after()
        }
    }

    // Save stays on this screen: grey while saving, then green "Saved ✓" until something is edited again.
    var saving by remember { mutableStateOf(false) }
    var savedForm by remember { mutableStateOf<Any?>(null) }
    val saveState = when {
        saving -> SaveState.SAVING
        savedForm != null && savedForm == form -> SaveState.SAVED
        else -> SaveState.IDLE
    }
    fun saveHere() {
        if (saving) return
        saving = true
        scope.launch {
            val err = vm.save()
            saving = false
            if (err != null) {
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            } else {
                savedForm = vm.form.value
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(form?.name?.ifBlank { null } ?: "Path") },
                navigationIcon = { IconButton(onClick = tryBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    SaveTextButton(saveState, ::saveHere)
                },
            )
        },
    ) { padding ->
        val f = form
        val p = path
        if (f == null || p == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (missing) Text("This path no longer exists.") else CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindSwatch(f.kind, f.colorArgb)
                Spacer(Modifier.width(12.dp))
                DropdownField(
                    label = "Kind",
                    value = f.kind,
                    options = PathKind.entries,
                    optionLabel = { it.label },
                    onSelect = { k -> vm.update { it.copy(kind = k) } },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = f.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Name / Cable ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Fields depend on the route kind: only fiber has a strand count; wireless has no cable.
            val cableLabel = f.kind.cableLabel
            if (f.kind.isFiber || cableLabel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f.kind.isFiber) {
                        OutlinedTextField(
                            value = f.fiberCount,
                            onValueChange = { v -> vm.update { it.copy(fiberCount = v.filter(Char::isDigit)) } },
                            label = { Text("Fiber count") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (cableLabel != null) {
                        OutlinedTextField(
                            value = f.cableType,
                            onValueChange = { v -> vm.update { it.copy(cableType = v) } },
                            label = { Text(cableLabel) },
                            placeholder = { Text(f.kind.cablePlaceholder) },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
            }
            DropdownField(
                label = "Status",
                value = f.status,
                options = (ASSET_STATUSES + f.status).distinct(),
                optionLabel = { it },
                onSelect = { s -> vm.update { it.copy(status = s) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Line colour", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorDot(color = f.kind.colorArgb, selected = f.colorArgb == null, label = "Auto") {
                    vm.update { it.copy(colorArgb = null) }
                }
                PATH_COLORS.forEach { c ->
                    ColorDot(color = c, selected = f.colorArgb == c) { vm.update { it.copy(colorArgb = c) } }
                }
            }

            HorizontalDivider()
            Text("Route", style = MaterialTheme.typography.titleMedium)
            Text(
                "Length ${Geo.formatDistance(Geo.pathLengthMeters(p.points))} • ${p.points.size} points",
                style = MaterialTheme.typography.bodyMedium,
            )
            val (startName, endName) = endpoints
            if (startName != null || endName != null) {
                Text("From: ${startName ?: "—"}\nTo: ${endName ?: "—"}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (vm.isDirty) {
                        saveThen { mainVm.editPathRoute(p); onEditRoute() }
                    } else {
                        mainVm.editPathRoute(p)
                        onEditRoute()
                    }
                }) {
                    Icon(Icons.Default.Timeline, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit / extend route")
                }
                OutlinedButton(onClick = {
                    if (vm.isDirty) saveThen(onShowOnMap) else onShowOnMap()
                }) {
                    Icon(Icons.Default.Map, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Map")
                }
            }

            HorizontalDivider()
            PhotoSection(
                photos = photos,
                fileFor = vm::photoFile,
                onTakePhoto = takePhoto,
                onPickPhoto = pickPhoto,
                onOpenPhoto = { onOpenPhoto(it.id) },
            )

            HorizontalDivider()
            AttributesEditor(
                suggested = f.kind.suggestedFields,
                values = f.attributes,
                onChange = { m -> vm.update { it.copy(attributes = m) } },
            )
            OutlinedTextField(
                value = f.notes,
                onValueChange = { v -> vm.update { it.copy(notes = v) } },
                label = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            SaveButton(saveState, ::saveHere, Modifier.fillMaxWidth())
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete path?",
            text = "This deletes the path and its ${photos.size} photo(s). Pins along it are kept.",
            confirm = "Delete",
            onConfirm = {
                scope.launch {
                    vm.delete()
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "You have unsaved changes to this path.",
            confirm = "Discard",
            onConfirm = onBack,
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun ColorDot(color: Long, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .background(Color(color), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.LightGray, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
