@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.networkmapper.app.ui.sync

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.networkmapper.app.NetworkMapperApp
import com.networkmapper.app.net.PinnedHttp
import com.networkmapper.app.sync.HttpSyncTransport
import com.networkmapper.app.sync.SyncConfig
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.unifi.UntrustedCertificateException
import com.networkmapper.app.util.Fmt
import kotlinx.coroutines.launch

data class RemoteSite(val uid: String, val name: String, val assets: Int, val paths: Int, val sessions: Int)

class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as NetworkMapperApp).sync
    private val db = (app as NetworkMapperApp).repository.db
    private val saved = manager.store.load()

    var url by mutableStateOf(saved.url)
    var key by mutableStateOf(saved.key)
    var deviceName by mutableStateOf(saved.deviceName)
    var auto by mutableStateOf(saved.auto)
    var interval by mutableStateOf(saved.intervalMin)
    var wifiOnly by mutableStateOf(saved.wifiOnly)
    var allSites by mutableStateOf(saved.sites == null)
    var selected by mutableStateOf(saved.sites ?: emptySet())
    private var pin = saved.pinnedFingerprint
    private var pinFor = saved.pinnedFor

    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var certPrompt by mutableStateOf<UntrustedCertificateException?>(null)
    var remoteSites by mutableStateOf<List<RemoteSite>>(emptyList())

    val status = manager.status
    val pendingEdits = db.syncDao().observePendingEdits()
    val pendingReadings = db.syncDao().observePendingReadings()

    private fun config() = SyncConfig(
        url = url.trim(),
        key = key.trim(),
        deviceName = deviceName.trim(),
        auto = auto,
        intervalMin = interval,
        wifiOnly = wifiOnly,
        sites = if (allSites) null else selected,
        pinnedFingerprint = pin,
        pinnedFor = pinFor,
    )

    /** Saves the settings and (re)schedules auto sync; used by switches that don't need a server round trip. */
    fun saveQuietly() {
        manager.store.save(config())
        manager.applySchedule()
    }

    fun testAndSave() {
        if (busy) return
        busy = true
        error = null
        message = null
        viewModelScope.launch {
            try {
                val c = config()
                val t = manager.transportFor(c) as HttpSyncTransport
                val health = t.health()
                val sites = t.sites().optJSONArray("sites")
                remoteSites = (0 until (sites?.length() ?: 0)).map { i ->
                    val s = sites!!.getJSONObject(i)
                    RemoteSite(s.getString("id"), s.optString("name"), s.optInt("assets"), s.optInt("paths"), s.optInt("sessions"))
                }
                manager.store.save(c)
                manager.applySchedule()
                message = "Connected to sync server ${health.optString("version")} • ${remoteSites.size} site(s) on the server"
            } catch (e: UntrustedCertificateException) {
                certPrompt = e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    fun trust() {
        val p = certPrompt ?: return
        pin = p.fingerprint
        pinFor = PinnedHttp.normalizeBase(url)
        certPrompt = null
        testAndSave()
    }

    fun syncNow() {
        if (busy) return
        busy = true
        error = null
        message = null
        viewModelScope.launch {
            try {
                manager.store.save(config())
                message = manager.syncNow().summary
            } catch (e: UntrustedCertificateException) {
                certPrompt = e
            } catch (e: Exception) {
                error = manager.status.value.lastError ?: describe(e)
            } finally {
                busy = false
            }
        }
    }

    fun redownload() {
        manager.store.cursor = 0
        syncNow()
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Can't find that address."
        is java.net.ConnectException -> "Couldn't connect – is the server running and the port open?"
        is java.net.SocketTimeoutException -> "The server didn't answer in time."
        else -> e.message ?: e.javaClass.simpleName
    }
}

@Composable
fun SyncScreen(mainVm: MainViewModel, onBack: () -> Unit, vm: SyncViewModel = viewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val pendingEdits by vm.pendingEdits.collectAsStateWithLifecycle(0)
    val pendingReadings by vm.pendingReadings.collectAsStateWithLifecycle(0)
    val localSites by mainVm.sites.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud sync") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudSync, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (status.running || vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            if (status.lastSyncAt > 0) "Last sync ${Fmt.dateTime(status.lastSyncAt)} – ${status.lastMessage}" else "Not synced yet",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Waiting to send: $pendingEdits change(s), $pendingReadings Wi-Fi reading(s)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                        vm.error?.let { if (it != status.lastError) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::syncNow, enabled = !vm.busy && vm.url.isNotBlank() && vm.key.isNotBlank()) { Text("Sync now") }
                            TextButton(onClick = vm::redownload, enabled = !vm.busy && vm.url.isNotBlank()) { Text("Re-download all") }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Server", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = vm.url,
                            onValueChange = { vm.url = it },
                            label = { Text("Sync server address") },
                            placeholder = { Text("https://map.example.com  or  https://203.0.113.10") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = vm.key,
                            onValueChange = { vm.key = it },
                            label = { Text("Team access key (SYNC_KEY)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = vm.deviceName,
                            onValueChange = { vm.deviceName = it },
                            label = { Text("This phone's name (shown with its changes)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = vm::testAndSave, enabled = !vm.busy && vm.url.isNotBlank() && vm.key.isNotBlank()) {
                            Text("Test & save")
                        }
                        Text(
                            "Set up the server from the server/ folder of the project (Docker on your VPS). The key is stored encrypted on this phone.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Automatic sync", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Syncs in the background on a schedule and about a minute after you change something. Works offline – changes wait until there's a connection.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Switch(checked = vm.auto, onCheckedChange = {
                                vm.auto = it
                                vm.saveQuietly()
                            })
                        }
                        if (vm.auto) {
                            Text("Every", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(15, 30, 60, 180).forEach { m ->
                                    FilterChip(
                                        selected = vm.interval == m,
                                        onClick = {
                                            vm.interval = m
                                            vm.saveQuietly()
                                        },
                                        label = { Text(if (m < 60) "$m min" else "${m / 60} h") },
                                    )
                                }
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.wifiOnly = !vm.wifiOnly
                                        vm.saveQuietly()
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = vm.wifiOnly, onCheckedChange = {
                                    vm.wifiOnly = it
                                    vm.saveQuietly()
                                })
                                Text("Only on Wi-Fi / unmetered networks")
                            }
                        }
                    }
                }
            }

            item {
                Text("Sites to sync", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.allSites = true
                            vm.saveQuietly()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.allSites, onClick = {
                        vm.allSites = true
                        vm.saveQuietly()
                    })
                    Text("All sites (on this phone and on the server)")
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.allSites = false
                            vm.saveQuietly()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !vm.allSites, onClick = {
                        vm.allSites = false
                        vm.saveQuietly()
                    })
                    Text("Only the sites I choose")
                }
            }
            if (!vm.allSites) {
                val rows = (localSites.map { RemoteSite(it.site.uid, it.site.name, it.pins, it.paths, it.surveys) } +
                    vm.remoteSites.filter { r -> localSites.none { it.site.uid == r.uid } })
                    .distinctBy { it.uid }
                items(rows, key = { it.uid }) { s ->
                    val onServerOnly = localSites.none { it.site.uid == s.uid }
                    val on = s.uid in vm.selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selected = if (on) vm.selected - s.uid else vm.selected + s.uid
                                vm.saveQuietly()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = {
                            vm.selected = if (it) vm.selected + s.uid else vm.selected - s.uid
                            vm.saveQuietly()
                        })
                        Column {
                            Text(s.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${s.assets} pins • ${s.paths} paths • ${s.sessions} surveys" + if (onServerOnly) " • on server only" else "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (vm.remoteSites.isEmpty()) {
                    item {
                        Text(
                            "Tap Test & save to also list sites that are only on the server.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    vm.certPrompt?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.certPrompt = null },
            title = { Text("Trust this server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The sync server uses a certificate this phone doesn't recognise (normal for an IP-only setup). " +
                            "Compare this fingerprint with the one make-cert.sh printed on your server.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("SHA-256:\n${c.fingerprint}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = vm::trust) { Text("Trust & connect") } },
            dismissButton = { TextButton(onClick = { vm.certPrompt = null }) { Text("Cancel") } },
        )
    }
}
