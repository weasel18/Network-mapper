package com.networkmapper.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.networkmapper.app.ui.asset.AssetEditScreen
import com.networkmapper.app.ui.data.DataScreen
import com.networkmapper.app.ui.list.AssetListScreen
import com.networkmapper.app.ui.map.MapScreen
import com.networkmapper.app.ui.path.PathEditScreen
import com.networkmapper.app.ui.photo.PhotoScreen
import com.networkmapper.app.ui.site.SitesScreen
import com.networkmapper.app.ui.sync.SyncScreen
import com.networkmapper.app.ui.unifi.UnifiScreen
import com.networkmapper.app.ui.theme.NetworkMapperTheme
import com.networkmapper.app.ui.wifi.WifiScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the system bars always get light icons.
        val bars = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            NetworkMapperTheme {
                AppRoot()
                com.networkmapper.app.ui.components.CrashReportDialog()
            }
        }
    }
}

object Permissions {
    fun wanted(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(context: Context) =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
}

private object Routes {
    const val MAP = "map"
    const val LIST = "list"
    const val WIFI = "wifi"
    const val DATA = "data"
    const val SITES = "sites"
    const val UNIFI = "unifi"
    const val SYNC = "sync"
    const val ASSET = "asset/{id}"
    const val PATH = "path/{id}"
    const val PHOTO = "photo/{id}"
    fun asset(id: Long) = "asset/$id"
    fun path(id: Long) = "path/$id"
    fun photo(id: Long) = "photo/$id"
}

/** Pops back to the map (it's always at the bottom of the stack). */
private fun NavHostController.backToMap() {
    if (!popBackStack(Routes.MAP, inclusive = false)) navigate(Routes.MAP)
}

@Composable
fun AppRoot() {
    val vm: MainViewModel = viewModel()
    val context = LocalContext.current
    val nav = rememberNavController()

    var hasLocation by remember { mutableStateOf(Permissions.hasLocation(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = Permissions.hasLocation(context)
        if (hasLocation) vm.startLocation()
    }
    val requestPermissions = {
        permissionLauncher.launch(Permissions.wanted().toTypedArray())
    }

    LaunchedEffect(Unit) {
        if (Permissions.wanted().any { !Permissions.granted(context, it) }) requestPermissions()
    }

    // GPS runs while the app is visible; the survey service keeps its own updates in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    hasLocation = Permissions.hasLocation(context)
                    if (hasLocation) vm.startLocation()
                    vm.resumeLive()
                }
                Lifecycle.Event.ON_STOP -> {
                    vm.stopLocation()
                    vm.pauseLive()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = nav, startDestination = Routes.MAP) {
        composable(Routes.MAP) {
            MapScreen(
                vm = vm,
                hasLocationPermission = hasLocation,
                onRequestPermission = requestPermissions,
                onOpenAsset = { nav.navigate(Routes.asset(it)) },
                onOpenPath = { nav.navigate(Routes.path(it)) },
                onOpenList = { nav.navigate(Routes.LIST) },
                onOpenWifi = { nav.navigate(Routes.WIFI) },
                onOpenData = { nav.navigate(Routes.DATA) },
                onOpenSites = { nav.navigate(Routes.SITES) },
                onOpenSync = { nav.navigate(Routes.SYNC) },
                onOpenPhoto = { nav.navigate(Routes.photo(it)) },
            )
        }
        composable(Routes.LIST) {
            AssetListScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenAsset = { nav.navigate(Routes.asset(it)) },
                onOpenPath = { nav.navigate(Routes.path(it)) },
                onShowOnMap = { nav.backToMap() },
            )
        }
        composable(Routes.WIFI) {
            WifiScreen(vm = vm, onBack = { nav.popBackStack() }, onShowMap = { nav.backToMap() }, onOpenUnifi = { nav.navigate(Routes.UNIFI) })
        }
        composable(Routes.DATA) {
            DataScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenSites = { nav.navigate(Routes.SITES) },
                onOpenUnifi = { nav.navigate(Routes.UNIFI) },
                onOpenSync = { nav.navigate(Routes.SYNC) },
            )
        }
        composable(Routes.SYNC) {
            SyncScreen(mainVm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.UNIFI) {
            UnifiScreen(mainVm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.SITES) {
            SitesScreen(vm = vm, onBack = { nav.popBackStack() }, onOpened = { nav.backToMap() }, onOpenSync = { nav.navigate(Routes.SYNC) })
        }
        composable(Routes.ASSET, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            AssetEditScreen(
                mainVm = vm,
                onBack = { nav.popBackStack() },
                onOpenPhoto = { nav.navigate(Routes.photo(it)) },
                onShowOnMap = {
                    val a = vm.assets.value.firstOrNull { it.id == id }
                    if (a != null) vm.focusOnAsset(a)
                    nav.backToMap()
                },
            )
        }
        composable(Routes.PATH, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            PathEditScreen(
                mainVm = vm,
                onBack = { nav.popBackStack() },
                onOpenPhoto = { nav.navigate(Routes.photo(it)) },
                onShowOnMap = {
                    val p = vm.paths.value.firstOrNull { it.id == id }
                    if (p != null) vm.focusOnPath(p)
                    nav.backToMap()
                },
                onEditRoute = { nav.backToMap() },
            )
        }
        composable(Routes.PHOTO, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            PhotoScreen(vm = vm, photoId = id, onBack = { nav.popBackStack() }, onShowOnMap = { nav.backToMap() })
        }
    }
}
