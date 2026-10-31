@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.asset

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.Photo
import com.networkmapper.app.ui.ApCandidate
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.AttributesEditor
import com.networkmapper.app.ui.components.ConfirmDialog
import com.networkmapper.app.ui.components.DropdownField
import com.networkmapper.app.ui.components.PhotoSection
import com.networkmapper.app.ui.components.TypeBadge
import com.networkmapper.app.ui.components.rememberGalleryImport
import com.networkmapper.app.ui.components.rememberPhotoCapture
import com.networkmapper.app.util.Geo
import com.networkmapper.app.util.Wifi
import com.networkmapper.app.wifi.WifiScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class AssetForm(
    val type: AssetType,
    val name: String,
    val status: String,
    val notes: String,
    val lat: String,
    val lon: String,
    val accuracy: Float?,
    val altitude: Double?,
    val attributes: Map<String, String>,
) {
    fun cleanedAttributes(): Map<String, String> =
        attributes.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    companion object {
        fun from(a: Asset) = AssetForm(
            type = a.type,
            name = a.name,
            status = a.status,
            notes = a.notes,
            lat = String.format(Locale.US, "%.7f", a.latitude),
            lon = String.format(Locale.US, "%.7f", a.longitude),
            accuracy = a.accuracy,
            altitude = a.altitude,
            attributes = a.attributes,
        )
    }
}

/**
 * Fills an AP-type pin's form from a chosen radio and its sibling radios: MAC / BSSID list,
 * SSID(s), channel(s) or frequency, and your saved AP name (only replacing an automatic pin name).
 */
internal fun fillFromAp(
    form: AssetForm,
    bssidField: String,
    chosen: ApCandidate,
    radios: List<ApCandidate>,
    isDefaultName: (AssetType, String) -> Boolean,
): AssetForm {
    val attrs = LinkedHashMap(form.attributes)
    attrs[bssidField] = radios.map { it.bssid }.distinct().sorted().joinToString(", ")
    val fields = form.type.suggestedFields
    val ssids = radios.map { it.ssid }.filter { it.isNotBlank() }.distinct()
    if ("SSID" in fields && ssids.isNotEmpty()) attrs["SSID"] = ssids.joinToString(", ")
    val tuned = radios.filter { it.frequency > 0 }.distinctBy { Wifi.channel(it.frequency) to Wifi.band(it.frequency) }
    if ("Channel" in fields && tuned.isNotEmpty()) {
        attrs["Channel"] = tuned.sortedBy { it.frequency }.joinToString(", ") { "${Wifi.channel(it.frequency)} (${Wifi.band(it.frequency)})" }
    }
    if ("Frequency" in fields && chosen.frequency > 0) attrs["Frequency"] = "${chosen.frequency} MHz"
    val saved = chosen.savedName ?: radios.firstNotNullOfOrNull { it.savedName }
    val name = if (saved != null && isDefaultName(form.type, form.name)) saved else form.name
    return form.copy(attributes = attrs, name = name)
}

class AssetEditViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = (app as NetworkMapperApp).repository
    val assetId: Long = handle.get<Long>("id") ?: 0L

    private var original: Asset? = null
    private var originalForm: AssetForm? = null
    val form = MutableStateFlow<AssetForm?>(null)
    val missing = MutableStateFlow(false)

    val photos: StateFlow<List<Photo>> =
        repo.photosForAsset(assetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val a = repo.getAsset(assetId)
            if (a == null) {
                missing.value = true
            } else {
                original = a
                originalForm = AssetForm.from(a)
                form.value = originalForm
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

    fun update(block: (AssetForm) -> AssetForm) {
        form.value = form.value?.let(block)
    }

    /** Returns an error message, or null on success. */
    suspend fun save(): String? {
        val f = form.value ?: return "Nothing to save"
        val base = original ?: return "Pin not found"
        val lat = f.lat.trim().replace(',', '.').toDoubleOrNull()
        val lon = f.lon.trim().replace(',', '.').toDoubleOrNull()
        if (lat == null || lat !in -90.0..90.0) return "Latitude must be between -90 and 90"
        if (lon == null || lon !in -180.0..180.0) return "Longitude must be between -180 and 180"
        val moved = lat != base.latitude || lon != base.longitude
        val updated = base.copy(
            type = f.type,
            name = f.name.trim().ifEmpty { f.type.label },
            status = f.status,
            notes = f.notes.trim(),
            latitude = lat,
            longitude = lon,
            accuracy = if (moved && f.accuracy == base.accuracy) null else f.accuracy,
            altitude = f.altitude,
            attributes = f.cleanedAttributes(),
        )
        repo.updateAsset(updated)
        original = updated
        originalForm = AssetForm.from(updated)
        form.value = originalForm
        return null
    }

    suspend fun delete() {
        original?.let { repo.deleteAsset(it) }
    }

    suspend fun deletePhoto(p: Photo) = repo.deletePhoto(p)
}

@Composable
fun AssetEditScreen(
    mainVm: MainViewModel,
    onBack: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onShowOnMap: () -> Unit,
    vm: AssetEditViewModel = viewModel(),
) {
    val context = LocalContext.current
    val form by vm.form.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val location by mainVm.location.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val takePhoto = rememberPhotoCapture { file -> mainVm.attachPhoto(file, assetId = vm.assetId) }
    val pickPhoto = rememberGalleryImport { file -> mainVm.attachPhoto(file, assetId = vm.assetId, fromGallery = true) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(form?.name?.ifBlank { null } ?: "Pin") },
                navigationIcon = {
                    IconButton(onClick = tryBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(onClick = { saveThen(onBack) }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        val f = form
        if (f == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (missing) Text("This pin no longer exists.") else CircularProgressIndicator()
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
                TypeBadge(f.type, 40.dp)
                Spacer(Modifier.width(12.dp))
                DropdownField(
                    label = "Type",
                    value = f.type,
                    options = AssetType.entries,
                    optionLabel = { it.label },
                    onSelect = { t -> vm.update { it.copy(type = t) } },
                    modifier = Modifier.weight(1f),
                )
            }
            val bssidField = f.type.bssidField
            if (bssidField != null) {
                // Pre-fill AP / bridge / router details from saved names and recorded or live Wi-Fi.
                var candidates by remember { mutableStateOf<List<ApCandidate>?>(null) }
                var scanRequested by remember { mutableStateOf(false) }
                val latD = f.lat.trim().replace(',', '.').toDoubleOrNull()
                val lonD = f.lon.trim().replace(',', '.').toDoubleOrNull()
                LaunchedEffect(f.type, latD, lonD) {
                    if (latD == null || lonD == null) {
                        candidates = emptyList()
                        return@LaunchedEffect
                    }
                    candidates = mainVm.apCandidates(latD, lonD, vm.assetId)
                    if (!scanRequested) {
                        scanRequested = true
                        if (WifiScanner(context).requestScan()) {
                            delay(5_000)
                            candidates = mainVm.apCandidates(latD, lonD, vm.assetId)
                        }
                    }
                }
                ApFillSection(
                    candidates = candidates,
                    linkedCount = Wifi.parseBssids(f.attributes[bssidField] ?: "").size,
                    onPick = { chosen ->
                        val all = candidates.orEmpty()
                        val radios = listOf(chosen) + all.filter { Wifi.likelySameAp(chosen.bssid, it.bssid) }
                        vm.update { fillFromAp(it, bssidField, chosen, radios, mainVm::isDefaultPinName) }
                        val label = chosen.savedName ?: chosen.ssid.ifEmpty { chosen.bssid }
                        val extra = radios.size - 1
                        Toast.makeText(
                            context,
                            "Filled from $label" + if (extra > 0) " (+$extra other radio${if (extra == 1) "" else "s"})" else "",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
            OutlinedTextField(
                value = f.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Name / ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                label = "Status",
                value = f.status,
                options = (ASSET_STATUSES + f.status).distinct(),
                optionLabel = { it },
                onSelect = { s -> vm.update { it.copy(status = s) } },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text("Location", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = f.lat,
                    onValueChange = { v -> vm.update { it.copy(lat = v) } },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = f.lon,
                    onValueChange = { v -> vm.update { it.copy(lon = v) } },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            val accText = buildString {
                append(f.accuracy?.let { "Accuracy ±${it.toInt()} m" } ?: "Accuracy unknown (placed manually)")
                f.altitude?.let { append(String.format(Locale.US, " • Altitude %.1f m", it)) }
            }
            Text(accText, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val loc = location
                OutlinedButton(
                    onClick = {
                        if (loc == null) {
                            Toast.makeText(context, "No GPS fix yet", Toast.LENGTH_SHORT).show()
                        } else {
                            vm.update {
                                it.copy(
                                    lat = String.format(Locale.US, "%.7f", loc.latitude),
                                    lon = String.format(Locale.US, "%.7f", loc.longitude),
                                    accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                                    altitude = if (loc.hasAltitude()) loc.altitude else null,
                                )
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.GpsFixed, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (loc != null && loc.hasAccuracy()) "Use GPS (±${loc.accuracy.toInt()} m)" else "Use GPS")
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
                suggested = f.type.suggestedFields,
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
            Button(onClick = { saveThen(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            location?.let { loc ->
                val lat = f.lat.toDoubleOrNull()
                val lon = f.lon.toDoubleOrNull()
                if (lat != null && lon != null) {
                    val d = Geo.distanceMeters(
                        com.networkmapper.app.data.GeoPt(loc.latitude, loc.longitude),
                        com.networkmapper.app.data.GeoPt(lat, lon),
                    )
                    Text("${Geo.formatDistance(d)} from your position", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete pin?",
            text = "This deletes the pin and its ${photos.size} photo(s). Paths connected to it are kept.",
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
            text = "You have unsaved changes to this pin.",
            confirm = "Discard",
            onConfirm = onBack,
            onDismiss = { confirmDiscard = false },
        )
    }
}
