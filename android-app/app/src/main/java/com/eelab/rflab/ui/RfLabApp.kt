package com.eelab.rflab.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.ble.AndroidBleLink
import com.eelab.rflab.bluetooth.AndroidBluetoothLink
import com.eelab.rflab.favorites.FavoritesRepository
import com.eelab.rflab.favorites.SharedPreferencesFavoriteStorage
import com.eelab.rflab.ota.OtaGraph
import com.eelab.rflab.ota.OtaSecretStore
import com.eelab.rflab.protocol.CapabilityAction
import com.eelab.rflab.send.SendGraph
import com.eelab.rflab.ui.receive.ReceiveViewModel
import com.eelab.rflab.ui.screens.ConnectScreen
import com.eelab.rflab.ui.screens.FirmwareScreen
import com.eelab.rflab.ui.screens.ProtocolCatalogScreen
import com.eelab.rflab.ui.screens.ProtocolDetailScreen
import com.eelab.rflab.ui.screens.ReceiveScreen
import com.eelab.rflab.ui.screens.SendScreen
import com.eelab.rflab.ui.screens.StaticCodesScreen
import com.eelab.rflab.usb.RfLabSession
import com.eelab.rflab.usb.UsbConnectController
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Top-level shell: bottom navigation across the destinations in
 * [Destination] (Connect, Receive, Send, Keys, Capabilities, Firmware).
 *
 * This is the app-level composition root for both shared resources:
 *
 * - one [SendGraph.engine] instance, so batch sends and preset scenarios
 *   share the same serialising mutex;
 * - one [UsbConnectController], built from the application context, which
 *   attaches/detaches the single connection in
 *   [com.eelab.rflab.usb.RfLabSession]. Receive and Send observe that same
 *   session, so tapping Connect on the first tab immediately feeds the
 *   others.
 * - one [AndroidBluetoothLink], the Android side of the Bluetooth SPP
 *   transport. The Connect screen reads the bonded-device list through it and
 *   builds its per-device
 *   [com.eelab.rflab.bluetooth.BluetoothConnectController] from it, so both
 *   transports end up attaching the same [RfLabSession]. Nothing here depends
 *   on which transport is live: Receive and Send are transport-agnostic.
 * - one [FavoritesRepository], built over the app-private SharedPreferences
 *   file, so queue favorites are shared by every visit to the Send tab and
 *   survive process death.
 *
 * The receive ViewModel is created here rather than inside the Receive tab
 * so it starts collecting [RfLabSession.events] as soon as the process does:
 * frames that arrive while the user is still on the Connect tab are decoded
 * and queued in the list instead of being missed.
 *
 * Only the application context is retained: USB permission uses a
 * `PendingIntent` broadcast, and the Bluetooth `BLUETOOTH_CONNECT` grant is
 * requested by the Connect screen's own activity-result launcher. Neither
 * needs an Activity reference held here.
 */
@Composable
fun RfLabApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // One sequential sender for the whole app: never two commands in flight.
    val sendEngine = SendGraph.engine
    val appContext = LocalContext.current.applicationContext
    // Survives recomposition and tab switches; owns no Activity reference.
    val usbConnectPort = remember(appContext) { UsbConnectController(appContext) }
    // The Bluetooth seam: same application context, no Activity retained. The
    // Connect screen picks the bonded device and builds a controller per
    // address from this, mirroring how UsbConnectController owns USB.
    val bluetoothLink = remember(appContext) { AndroidBluetoothLink(appContext) }
    // The BLE seam for the ESP32-C3 build: the Connect screen scans with it
    // (the C3's Nordic UART peripheral is not bonded) and builds a per-address
    // controller, mirroring the USB/SPP seams. No Activity retained.
    val bleLink = remember(appContext) { AndroidBleLink(appContext) }
    // One process-wide favorites store; SharedPreferences keeps it across
    // process death, and remember keeps the same instance across tabs.
    val favoritesRepository = remember(appContext) {
        FavoritesRepository(SharedPreferencesFavoriteStorage.create(appContext))
    }
    // The user's KeeLoq keystore and per-serial counter tracker, both
    // app-private. Keys stay on the phone; the counter tracker lets "send next
    // code" default to the last counter seen + 1.
    val keyRepository = remember(appContext) {
        com.eelab.rflab.crypto.KeeLoqKeyRepository(
            com.eelab.rflab.crypto.SharedPreferencesKeyStorage.create(appContext),
        )
    }
    val counterTracker = remember(appContext) {
        com.eelab.rflab.crypto.CounterTracker(
            com.eelab.rflab.crypto.SharedPreferencesCounterStore.create(appContext),
        )
    }
    // One process-wide OTA secret store: a dedicated SharedPreferences file
    // (same storage mechanism as the favorites) holding the validated
    // 64-hex-character secret typed on the Firmware tab.
    val otaSecretStore = remember(appContext) { OtaSecretStore.create(appContext) }
    // The firmware web server's settings, TLS pins and last-flashed-hash
    // records — three dedicated prefs files built like the OTA secret store —
    // plus the network side that uses them. `cacheDir` is the scratch area
    // the verified image download runs through.
    val firmwareSettingsStore = remember(appContext) {
        com.eelab.rflab.ota.update.FirmwareServerSettingsStore.create(appContext)
    }
    val firmwareTofuPinStore = remember(appContext) {
        com.eelab.rflab.ota.update.SharedPreferencesTofuPinStore.create(appContext)
    }
    val lastFlashStore = remember(appContext) {
        com.eelab.rflab.ota.update.SharedPreferencesLastFlashStore.create(appContext)
    }
    val firmwareRemoteRepository = remember(appContext) {
        com.eelab.rflab.ota.update.FirmwareRemoteRepository(
            api = com.eelab.rflab.ota.update.FirmwareServerClient(
                pinStore = firmwareTofuPinStore,
            ),
            cacheDir = appContext.cacheDir,
        )
    }
    // Activity-scoped so it outlives tab navigation and decodes continuously.
    // The selected key as a flow, so received frames are decrypted with
    // whatever key is active in the keystore.
    val selectedKeyFlow = remember(keyRepository) {
        kotlinx.coroutines.flow.combine(
            keyRepository.keys,
            keyRepository.selectedId,
        ) { keys, id -> keys.firstOrNull { it.id == id } }
    }
    val receiveViewModel: ReceiveViewModel = viewModel(
        factory = ReceiveViewModel.factory(
            transportEvents = RfLabSession.events,
            connected = RfLabSession.isConnected,
            modeSetter = sendEngine::setMode,
            selectedKey = selectedKeyFlow,
            onCounterObserved = counterTracker::observe,
        ),
    )
    // Read once here so the catalog/detail screens can show a live
    // "hardware connected?" fact next to any capability that needs one,
    // without each screen subscribing to RfLabSession itself.
    val isConnected by RfLabSession.isConnected.collectAsStateWithLifecycle(initialValue = false)

    Scaffold(
        bottomBar = {
            NavigationBar {
                // Plain loop, not Iterable.forEach: the latter is API 24 and
                // minSdk here is 23 (lint NewApi).
                for (destination in Destination.entries) {
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = label) },
                        label = {
                            // A single bottom-nav item is narrow; without a line
                            // limit a longer label wraps to two lines and shoves
                            // the row taller than its neighbours. One line, clip
                            // with an ellipsis if it still doesn't fit.
                            Text(
                                label,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Connect.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Connect.route) {
                ConnectScreen(
                    usbPort = usbConnectPort,
                    bluetoothLink = bluetoothLink,
                    bleLink = bleLink,
                )
            }
            composable(Destination.Receive.route) { ReceiveScreen(viewModel = receiveViewModel) }
            composable(Destination.Send.route) {
                SendScreen(
                    sendEngine = sendEngine,
                    favoritesRepository = favoritesRepository,
                    selectedKey = selectedKeyFlow,
                    nextCounterFor = counterTracker::next,
                    observeCounter = counterTracker::observe,
                )
            }
            composable(Destination.Keys.route) {
                com.eelab.rflab.ui.screens.KeysScreen(
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                        factory = com.eelab.rflab.ui.keys.KeyManagerViewModel.factory(keyRepository),
                    ),
                )
            }
            composable(Destination.Capabilities.route) {
                ProtocolCatalogScreen(
                    onOpenFamily = { family ->
                        navController.navigate(
                            "capabilities/detail/${URLEncoder.encode(family, "UTF-8")}",
                        )
                    },
                )
            }
            composable(
                route = "capabilities/detail/{family}",
                arguments = listOf(navArgument("family") { type = NavType.StringType }),
            ) { entry ->
                val encoded = entry.arguments?.getString("family").orEmpty()
                ProtocolDetailScreen(
                    familyName = URLDecoder.decode(encoded, "UTF-8"),
                    isConnected = isConnected,
                    onAction = { action -> navigateForAction(navController, action) },
                )
            }
            composable(STATIC_CODES_ROUTE) {
                StaticCodesScreen()
            }
            composable(Destination.Firmware.route) {
                FirmwareScreen(
                    secretStore = otaSecretStore,
                    engine = OtaGraph.engine,
                    connectionStatus = RfLabSession.isConnected,
                    settingsStore = firmwareSettingsStore,
                    pinStore = firmwareTofuPinStore,
                    lastFlashStore = lastFlashStore,
                    repository = firmwareRemoteRepository,
                    transportEvents = RfLabSession.events,
                )
            }
        }
    }
}

/**
 * Follows a [CapabilityAction] from a protocol-detail button to the real
 * bottom-nav tab it names, with the same `popUpTo`/`launchSingleTop`/
 * `restoreState` behavior as tapping that tab directly — a capability
 * action should feel identical to navigating there yourself, not push a
 * second, parallel copy of the destination onto the back stack.
 *
 * Route resolution itself ([CapabilityAction.route]) is a plain function
 * with no `NavHostController` dependency and is unit-tested directly; this
 * function is the thin, untested-by-necessity glue that calls it.
 */
private fun navigateForAction(navController: NavHostController, action: CapabilityAction) {
    val route = action.route() ?: return
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
