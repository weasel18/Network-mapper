@file:OptIn(ExperimentalMaterial3Api::class)

package com.networkmapper.app.ui.unifi

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.networkmapper.app.ui.MainViewModel
import com.networkmapper.app.ui.components.DropdownField
import com.networkmapper.app.unifi.UnifiAuth
import com.networkmapper.app.unifi.UnifiClient
import com.networkmapper.app.unifi.UnifiDevice
import com.networkmapper.app.unifi.UnifiSite
import com.networkmapper.app.unifi.UnifiStore
import com.networkmapper.app.unifi.UntrustedCertificateException
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Connection state for the UniFi screen (survives rotation). */
class UnifiViewModel(app: Application) : AndroidViewModel(app) {
    private val store = UnifiStore(app)
    private val saved = store.load()

    var url by mutableStateOf(saved.url)
    var useApiKey by mutableStateOf(saved.useApiKey)
    var username by mutableStateOf(saved.username)
    var secret by mutableStateOf(saved.secret)
    var remember by mutableStateOf(saved.remember)
    private var pin: String? = saved.pinnedFingerprint
    private var pinFor: String? = saved.pinnedFor

    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var certPrompt by mutableStateOf<UntrustedCertificateException?>(null)
    var sites by mutableStateOf<List<UnifiSite>>(emptyList())
    var site by mutableStateOf<UnifiSite?>(null)
    var devices by mutableStateOf<List<UnifiDevice>?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())

    private var client: UnifiClient? = null

    private val auth: UnifiAuth
        get() = if (useApiKey) UnifiAuth.ApiKey(secret.trim()) else UnifiAuth.Login(username.trim(), secret)

    val canConnect: Boolean
        get() = url.isNotBlank() && secret.isNotBlank() && (useApiKey || username.isNotBlank())

    private fun persist() = store.save(
        com.networkmapper.app.unifi.UnifiSettings(url.trim(), useApiKey, username.trim(), secret, remember, pin, pinFor),
    )

    fun connect() {
        if (busy) return
        busy = true
        error = null
        devices = null
        viewModelScope.launch {
            try {
                val base = UnifiClient.normalizeBase(url)
                val c = UnifiClient(base, if (pinFor == base) pin else null)
                client = c
                val found = c.sites(auth)
                persist()
                sites = found
                when {
                    found.isEmpty() -> error = "Connected, but no sites were returned for this account."
                    found.size == 1 -> {
                        site = found.first()
                        fetch(c, found.first())
                    }
                    else -> site = site?.takeIf { s -> found.any { it.reference == s.reference } } ?: found.first()
                }
            } catch (e: UntrustedCertificateException) {
                certPrompt = e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    fun loadDevices() {
        val s = site ?: return
        val c = client ?: return connect()
        busy = true
        error = null
        viewModelScope.launch {
            try {
                fetch(c, s)
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun fetch(c: UnifiClient, s: UnifiSite) {
        val list = c.devices(auth, s).sortedWith(compareBy({ kindOrder(it.kind) }, { it.displayName.lowercase() }))
        devices = list
        selected = list.map { it.mac }.toSet()
    }

    fun trustCertificate() {
        val prompt = certPrompt ?: return
        pin = prompt.fingerprint
        pinFor = UnifiClient.normalizeBase(url)
        certPrompt = null
        persist()
        connect()
    }

    fun forget() {
        store.forget()
        secret = ""
        pin = null
        pinFor = null
        sites = emptyList()
        site = null
        devices = null
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "Can't find that address. Check it, and that this phone is on the controller's network or VPN."
        is ConnectException -> "Couldn't connect. Is the controller reachable from this phone (same network / VPN, right port)?"
        is SocketTimeoutException -> "The controller didn't answer in time."
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        fun kindOrder(kind: String) = when (kind) {
            "ap" -> 0
            "switch" -> 1
            "gateway" -> 2
            else -> 3
        }

        fun kindLabel(kind: String) = when (kind) {
            "ap" -> "Access point"
            "switch" -> "Switch"
            "gateway" -> "Gateway / console"
            else -> "Device"
        }
    }
}

@Composable
fun UnifiScreen(mainVm: MainViewModel, onBack: () -> Unit, vm: UnifiViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    var saveNames by remember { mutableStateOf(true) }
    var replaceMine by remember { mutableStateOf(false) }
    var updatePins by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UniFi controller") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connect", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pull device names, models, IPs and AP radio BSSIDs from your UniFi Network controller. " +
                                "This phone must be able to reach it (same network or VPN).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = vm.url,
                            onValueChange = { vm.url = it },
                            label = { Text("Controller address") },
                            placeholder = { Text("192.168.1.1  or  unifi.local:8443") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = vm.useApiKey,
                                onClick = { vm.useApiKey = true },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text("API key") }
                            SegmentedButton(
                                selected = !vm.useApiKey,
                                onClick = { vm.useApiKey = false },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text("Username & password") }
                        }
                        if (vm.useApiKey) {
                            Text(
                                "UniFi OS consoles (UDM, UCG, Cloud Key Gen2+, UniFi OS Server) with Network 9.0+: " +
                                    "Network → Settings → Control Plane → Integrations → Create API key.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            Text(
                                "Works with UniFi OS consoles and classic self-hosted controllers. Use a local admin or " +
                                    "read-only account – Ubiquiti accounts with 2FA can't sign in from apps. This method " +
                                    "always includes AP radio BSSIDs.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            OutlinedTextField(
                                value = vm.username,
                                onValueChange = { vm.username = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            value = vm.secret,
                            onValueChange = { vm.secret = it },
                            label = { Text(if (vm.useApiKey) "API key" else "Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.remember = !vm.remember },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = vm.remember, onCheckedChange = { vm.remember = it })
                            Text("Remember on this phone (encrypted)", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::connect, enabled = vm.canConnect && !vm.busy) { Text("Connect") }
                            TextButton(onClick = vm::forget) { Text("Forget saved login") }
                            if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (vm.sites.size > 1) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropdownField(
                            label = "UniFi site",
                            value = vm.site ?: vm.sites.first(),
                            options = vm.sites,
                            optionLabel = { it.name },
                            onSelect = { vm.site = it },
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = vm::loadDevices, enabled = !vm.busy) { Text("Load") }
                    }
                }
            }

            val list = vm.devices
            if (list != null) {
                item {
                    val aps = list.count { it.kind == "ap" }
                    val radios = list.sumOf { it.radios.size }
                    Text(
                        "${list.size} devices • $aps APs • $radios radio BSSIDs" +
                            if (aps > 0 && radios == 0) "\nNo radio BSSIDs were returned with this API key – sign in with username & password to get them." else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row {
                        TextButton(onClick = { vm.selected = list.map { it.mac }.toSet() }) { Text("Select all") }
                        TextButton(onClick = { vm.selected = emptySet() }) { Text("Select none") }
                    }
                }
                items(list, key = { it.mac }) { d ->
                    val on = d.mac in vm.selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.selected = if (on) vm.selected - d.mac else vm.selected + d.mac },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { vm.selected = if (it) vm.selected + d.mac else vm.selected - d.mac })
                        Column(Modifier.weight(1f)) {
                            Text(d.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(UnifiViewModel.kindLabel(d.kind), d.model, d.ip).filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                d.mac + if (d.radios.isNotEmpty()) "  +${d.radios.size} BSSIDs" else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            OptionRow(saveNames, { saveNames = it }, "Save names for their MACs and radio BSSIDs (survey dots, Wi-Fi lists, pin pre-fill)")
                            if (saveNames) OptionRow(replaceMine, { replaceMine = it }, "Replace names I've set myself", indent = true)
                            OptionRow(
                                updatePins,
                                { updatePins = it },
                                "Update matching pins in this site (by MAC): model, IP, AP radios, and the name if it's still automatic",
                            )
                            Spacer(Modifier.size(6.dp))
                            Button(
                                enabled = vm.selected.isNotEmpty() && !importing && (saveNames || updatePins),
                                onClick = {
                                    importing = true
                                    scope.launch {
                                        result = try {
                                            val r = mainVm.importUnifi(list.filter { it.mac in vm.selected }, saveNames, replaceMine, updatePins)
                                            buildString {
                                                append("Saved names for ${r.namesSaved} MAC/BSSID(s).")
                                                if (r.namesKept > 0) append(" Kept ${r.namesKept} name(s) you'd set yourself.")
                                                if (updatePins) append(" Updated ${r.pinsUpdated} pin(s).")
                                            }
                                        } catch (e: Exception) {
                                            "Import failed: ${e.message}"
                                        } finally {
                                            importing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Import ${vm.selected.size} device(s)") }
                        }
                    }
                }
            }
        }
    }

    vm.certPrompt?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.certPrompt = null },
            title = { Text("Trust this controller?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The controller uses a certificate this phone doesn't recognise (normal for a local UniFi console). " +
                            "Only trust it if you're on your own network. You can compare the fingerprint with " +
                            "the one your browser shows for the console.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(c.subject, style = MaterialTheme.typography.labelSmall)
                    Text("SHA-256:\n${c.fingerprint}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = vm::trustCertificate) { Text("Trust & connect") } },
            dismissButton = { TextButton(onClick = { vm.certPrompt = null }) { Text("Cancel") } },
        )
    }
    result?.let { msg ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("UniFi import") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun OptionRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String, indent: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 32.dp else 0.dp)
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
