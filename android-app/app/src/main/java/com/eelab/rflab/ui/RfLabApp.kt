package com.eelab.rflab.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.R
import com.eelab.rflab.ble.AndroidBleLink
import com.eelab.rflab.bluetooth.AndroidBluetoothLink
import com.eelab.rflab.favorites.FavoritesRepository
import com.eelab.rflab.favorites.SharedPreferencesFavoriteStorage
import com.eelab.rflab.send.SendGraph
import com.eelab.rflab.ui.receive.ReceiveViewModel
import com.eelab.rflab.ui.screens.ConnectScreen
import com.eelab.rflab.ui.screens.ReceiveScreen
import com.eelab.rflab.ui.screens.SendScreen
import com.eelab.rflab.usb.RfLabSession
import com.eelab.rflab.usb.UsbConnectController

private enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Connect("connect", R.string.nav_connect, Icons.Default.Settings),
    Receive("receive", R.string.nav_receive, Icons.Default.List),
    Send("send", R.string.nav_send, Icons.Default.Send),
}

/**
 * Top-level shell: three destinations with bottom navigation.
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
    // Activity-scoped so it outlives tab navigation and decodes continuously.
    val receiveViewModel: ReceiveViewModel = viewModel(
        factory = ReceiveViewModel.factory(RfLabSession.events),
    )

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
                        label = { Text(label) },
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
                )
            }
        }
    }
}
