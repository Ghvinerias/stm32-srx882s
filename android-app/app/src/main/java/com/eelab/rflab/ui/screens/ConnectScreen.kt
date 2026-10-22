package com.eelab.rflab.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.ble.BleConnectController
import com.eelab.rflab.ble.BleEndpoint
import com.eelab.rflab.ble.BleLink
import com.eelab.rflab.bluetooth.BluetoothConnectController
import com.eelab.rflab.bluetooth.BluetoothEndpoint
import com.eelab.rflab.bluetooth.BluetoothLink
import com.eelab.rflab.ui.connect.C3_BOARD_NAME_PREFIX
import com.eelab.rflab.ui.connect.ConnectTransport
import com.eelab.rflab.ui.connect.ConnectUiState
import com.eelab.rflab.ui.connect.ConnectViewModel
import com.eelab.rflab.ui.connect.ConnectionPhase
import com.eelab.rflab.ui.connect.ESP32_BOARD_NAME
import com.eelab.rflab.ui.connect.LinkLiveness
import com.eelab.rflab.ui.connect.StatusDiagnostics
import com.eelab.rflab.ui.connect.connectFooterHint
import com.eelab.rflab.ui.connect.connectMessage
import com.eelab.rflab.ui.connect.connectPhaseLabel
import com.eelab.rflab.ui.connect.connectStatusDetail
import com.eelab.rflab.ui.connect.preselectedBluetoothAddress
import com.eelab.rflab.usb.ConnectPort
import java.util.Locale

/**
 * Connect screen with a transport chooser (USB default, Bluetooth opt-in).
 *
 * The app can reach the board over USB CDC-ACM (STM32) or Bluetooth Classic
 * SPP / RFCOMM (the ESP32 build). Both are
 * [ConnectPort][com.eelab.rflab.usb.ConnectPort]s that attach to the same
 * [com.eelab.rflab.usb.RfLabSession], and everything above the port — the
 * phase machine, the ~1/s STATUS heartbeat, the diagnostics card, the stream
 * Receive/Send observe — is transport-agnostic, so it is reused unchanged.
 * Only the port and the copy that names a transport are swapped.
 *
 * This stateful wrapper owns just the per-visit UI state:
 *
 * - the selected [ConnectTransport], kept in [rememberSaveable] so a tab
 *   switch does not lose it, and defaulting to [ConnectTransport.USB] so the
 *   existing USB flow is still one tap;
 * - the `BLUETOOTH_CONNECT` grant. On API 31+ it is requested at runtime via
 *   [rememberLauncherForActivityResult]; below 31 the legacy `BLUETOOTH`
 *   permissions are install-time grants and
 *   [BluetoothLink.isConnectPermissionGranted] reports true, so no launcher
 *   fires. All `Build.VERSION_CODES.S` handling is behind an SDK check.
 * - the bonded-device list and the selected MAC. The list is re-read when the
 *   grant changes, when the Bluetooth transport is (re-)entered, and on the
 *   picker's Refresh button — pairing happens in Android Settings, so the
 *   screen cannot be notified from here.
 *
 * One [ConnectViewModel] is kept per transport: a single USB one, plus one
 * Bluetooth one per selected address, keyed by the MAC because
 * [BluetoothConnectController] fixes its target at construction time. The key
 * only changes while disconnected (the selector is disabled during
 * CONNECTING/CONNECTED), so a picker tap can never orphan a live socket.
 *
 * @param usbPort USB connection seam built by the app composition root
 *   ([com.eelab.rflab.usb.UsbConnectController]).
 * @param bluetoothLink Bluetooth seam built by the app composition root
 *   ([com.eelab.rflab.bluetooth.AndroidBluetoothLink]). The screen reads the
 *   bonded list from it and builds a per-address controller; it never opens a
 *   socket itself.
 * @param bleLink BLE seam built by the app composition root
 *   ([com.eelab.rflab.ble.AndroidBleLink]). The screen scans with it (the C3's
 *   Nordic UART peripheral is not bonded) and builds a per-address controller;
 *   it never opens a GATT link itself.
 */
@Composable
fun ConnectScreen(
    usbPort: ConnectPort,
    bluetoothLink: BluetoothLink,
    bleLink: BleLink,
    modifier: Modifier = Modifier,
) {
    var transport by rememberSaveable { mutableStateOf(ConnectTransport.USB) }

    // USB has one fixed controller, so one ViewModel is enough. The explicit
    // key keeps it distinct from the per-device Bluetooth ViewModels below.
    val usbViewModel: ConnectViewModel = viewModel(
        key = USB_VIEW_MODEL_KEY,
        factory = ConnectViewModel.factory(usbPort),
    )
    val usbState by usbViewModel.uiState.collectAsStateWithLifecycle()

    // API 31+ asks for BLUETOOTH_CONNECT at runtime; below that the grant is
    // install-time and the link reports it as present. The launcher is cheap
    // to remember on every API level, we simply never fire it below 31.
    val runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var bluetoothPermissionGranted by remember {
        mutableStateOf(bluetoothLink.isConnectPermissionGranted())
    }
    var bondedDevices by remember { mutableStateOf<List<BluetoothEndpoint>?>(null) }
    var bondedRevision by remember { mutableIntStateOf(0) }
    var selectedBluetoothAddress by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The answer is authoritative: re-read the list either way (it is
        // empty before the grant) so the picker reflects reality.
        bluetoothPermissionGranted = granted
        bondedRevision++
    }

    LaunchedEffect(transport, bluetoothPermissionGranted, bondedRevision) {
        if (transport != ConnectTransport.BLUETOOTH) return@LaunchedEffect
        if (!bluetoothPermissionGranted) {
            bondedDevices = emptyList()
            return@LaunchedEffect
        }

        val devices = try {
            bluetoothLink.bondedDevices()
        } catch (revoked: SecurityException) {
            // The grant was revoked in Settings while we were paused; go back
            // to the rationale + grant button instead of crashing.
            bluetoothPermissionGranted = false
            emptyList()
        }

        bondedDevices = devices
        // Keep a still-paired manual choice, otherwise fall back to the ESP32
        // board's name, otherwise leave it unselected for the user to tap.
        selectedBluetoothAddress =
            preselectedBluetoothAddress(devices, selectedBluetoothAddress)
    }

    val targetAddress = selectedBluetoothAddress
    val bluetoothViewModel: ConnectViewModel? =
        if (transport == ConnectTransport.BLUETOOTH && targetAddress != null) {
            // One controller/ViewModel per target: BluetoothConnectController
            // takes the MAC in its constructor. viewModel(key = …) returns the
            // same instance while the address is unchanged and builds a fresh
            // one when the user picks another device (only possible while
            // disconnected).
            viewModel(
                key = BLUETOOTH_VIEW_MODEL_KEY_PREFIX + targetAddress,
                factory = ConnectViewModel.factory(
                    remember(targetAddress) {
                        BluetoothConnectController(bluetoothLink, targetAddress)
                    },
                ),
            )
        } else {
            null
        }

    val bluetoothState: ConnectUiState = if (bluetoothViewModel != null) {
        val state by bluetoothViewModel.uiState.collectAsStateWithLifecycle()
        state
    } else {
        // No device picked yet: show the neutral disconnected card. Connect is
        // disabled, so this never implies an active link.
        ConnectUiState()
    }

    // ---- BLE transport state --------------------------------------------
    // Unlike SPP (bonded devices, no scan), the C3's Nordic UART peripheral is
    // not bonded, so BLE must scan for it. That needs two grants on API 31+
    // (Scan + Connect); below 31 a BLE scan needs ACCESS_FINE_LOCATION and the
    // connect grant is install-time.
    var bleScanGranted by remember { mutableStateOf(bleLink.isScanPermissionGranted()) }
    var bleConnectGranted by remember { mutableStateOf(bleLink.isConnectPermissionGranted()) }
    val bleReadyToScan = bleScanGranted && bleConnectGranted
    var bleDevices by remember { mutableStateOf<List<BleEndpoint>?>(null) }
    var selectedBleAddress by rememberSaveable { mutableStateOf<String?>(null) }

    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-read from the link rather than trusting the result map: it is the
        // single source of truth and already encodes the per-version rules.
        bleScanGranted = bleLink.isScanPermissionGranted()
        bleConnectGranted = bleLink.isConnectPermissionGranted()
    }

    val bleTarget = selectedBleAddress
    val bleViewModel: ConnectViewModel? =
        if (transport == ConnectTransport.BLE && bleTarget != null) {
            // One controller/ViewModel per target, keyed by address, exactly
            // like the Bluetooth path: BleConnectController fixes its target at
            // construction and the key only changes while disconnected.
            viewModel(
                key = BLE_VIEW_MODEL_KEY_PREFIX + bleTarget,
                factory = ConnectViewModel.factory(
                    remember(bleTarget) { BleConnectController(bleLink, bleTarget) },
                ),
            )
        } else {
            null
        }
    val bleState: ConnectUiState = if (bleViewModel != null) {
        val state by bleViewModel.uiState.collectAsStateWithLifecycle()
        state
    } else {
        ConnectUiState()
    }

    // Scan while BLE is the active transport, both grants are held, and no
    // link is opening/live (canDisconnect). Collecting starts the scan;
    // leaving the transport or connecting cancels this effect, stopping it.
    LaunchedEffect(transport, bleReadyToScan, bleState.canDisconnect) {
        if (transport != ConnectTransport.BLE) return@LaunchedEffect
        if (!bleReadyToScan) {
            bleDevices = emptyList()
            return@LaunchedEffect
        }
        if (bleState.canDisconnect) return@LaunchedEffect
        bleDevices = emptyList()
        runCatching { bleLink.scan().collect { bleDevices = it } }
    }

    val activeState = when (transport) {
        ConnectTransport.USB -> usbState
        ConnectTransport.BLUETOOTH -> bluetoothState
        ConnectTransport.BLE -> bleState
    }
    val activeViewModel = when (transport) {
        ConnectTransport.USB -> usbViewModel
        ConnectTransport.BLUETOOTH -> bluetoothViewModel
        ConnectTransport.BLE -> bleViewModel
    }

    // Only switch while idle: canDisconnect is exactly CONNECTING/CONNECTED,
    // i.e. a link is opening or live and must be closed by the user first.
    val transportSwitchEnabled = !activeState.canDisconnect

    val bluetoothReady = bluetoothPermissionGranted && targetAddress != null
    val bleReady = bleReadyToScan && bleTarget != null
    val connectEnabled = when (transport) {
        ConnectTransport.USB -> true
        ConnectTransport.BLUETOOTH -> bluetoothReady
        ConnectTransport.BLE -> bleReady
    }

    ConnectScreen(
        transport = transport,
        state = activeState,
        onTransportChange = { chosen ->
            // Guard the state too, not just the disabled buttons, so a stray
            // event cannot swap the port out from under an open connection.
            if (transportSwitchEnabled) transport = chosen
        },
        onConnect = { activeViewModel?.connect() },
        onDisconnect = { activeViewModel?.disconnect() },
        modifier = modifier,
        transportSwitchEnabled = transportSwitchEnabled,
        connectEnabled = connectEnabled,
        transportContent = when (transport) {
            ConnectTransport.BLUETOOTH -> {
                {
                    BluetoothDevicePicker(
                        runtimePermissionRequired = runtimePermissionRequired,
                        permissionGranted = bluetoothPermissionGranted,
                        devices = bondedDevices,
                        selectedAddress = targetAddress,
                        onGrantPermission = {
                            if (runtimePermissionRequired) {
                                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        },
                        onSelect = { device -> selectedBluetoothAddress = device.address },
                        onRefresh = {
                            // Also re-check the grant: the user may have come
                            // back from Settings after enabling Nearby devices.
                            bluetoothPermissionGranted =
                                bluetoothLink.isConnectPermissionGranted()
                            bondedRevision++
                        },
                    )
                }
            }

            ConnectTransport.BLE -> {
                {
                    BleDevicePicker(
                        permissionGranted = bleReadyToScan,
                        devices = bleDevices,
                        selectedAddress = bleTarget,
                        onGrantPermission = { blePermissionLauncher.launch(blePermissions) },
                        onSelect = { device -> selectedBleAddress = device.address },
                        onRescan = {
                            // Re-read the grants (the user may be back from
                            // Settings) and restart from an empty list; the
                            // scan effect re-collects on the next recomposition.
                            bleScanGranted = bleLink.isScanPermissionGranted()
                            bleConnectGranted = bleLink.isConnectPermissionGranted()
                            bleDevices = if (bleScanGranted && bleConnectGranted) {
                                emptyList()
                            } else {
                                null
                            }
                        },
                    )
                }
            }

            ConnectTransport.USB -> null
        },
    )
}

/**
 * Stateless body, kept separate so the status/diagnostic UI can be previewed
 * or exercised without a ViewModel, a USB device or a Bluetooth adapter.
 *
 * Transport-specific extras (the Bluetooth picker) arrive as
 * [transportContent] and are only rendered between the selector and the
 * status card, so the shared card/controls/error/footer layout is identical on
 * both transports.
 *
 * @param transport active transport; only drives wording and the selector.
 * @param state phase/heartbeat/diagnostics of the active transport's port.
 * @param transportSwitchEnabled false while a link is CONNECTING/CONNECTED.
 * @param connectEnabled false when the active transport is not ready to
 *   connect yet (Bluetooth without a grant or without a selected device).
 * @param transportContent extra body for the active transport, if any.
 */
@Composable
internal fun ConnectScreen(
    transport: ConnectTransport,
    state: ConnectUiState,
    onTransportChange: (ConnectTransport) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    transportSwitchEnabled: Boolean = true,
    connectEnabled: Boolean = true,
    transportContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Device connection",
            style = MaterialTheme.typography.titleLarge,
        )

        TransportSelector(
            transport = transport,
            onTransportChange = onTransportChange,
            enabled = transportSwitchEnabled,
        )

        if (!transportSwitchEnabled) {
            Text(
                text = "Disconnect the ${transport.displayName} link before " +
                    "switching transports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        transportContent?.invoke()

        ConnectionStatusCard(state = state, transport = transport)

        if (state.isConnected) {
            DiagnosticsCard(state.diagnostics)
        }

        ConnectionControls(
            state = state,
            connectEnabled = connectEnabled,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        connectMessage(state, transport)?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Text(
            text = connectFooterHint(transport),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Two-option USB / Bluetooth selector.
 *
 * Disabled as a whole while a link is CONNECTING/CONNECTED: the app has one
 * shared session, so switching transports means tearing the live link down,
 * and that is the user's explicit choice (Disconnect), never a side effect of
 * a tab tap.
 */
@Composable
private fun TransportSelector(
    transport: ConnectTransport,
    onTransportChange: (ConnectTransport) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Transport",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            // Plain for-loop: minSdk is 23 and the app avoids API 24+ stdlib
            // extensions (see the same note in RfLabApp.kt).
            for (option in ConnectTransport.entries) {
                SegmentedButton(
                    selected = option == transport,
                    onClick = { onTransportChange(option) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(
                        index = option.ordinal,
                        count = ConnectTransport.entries.size,
                    ),
                ) {
                    Text(option.displayName)
                }
            }
        }
    }
}

/**
 * Bluetooth-only body: permission rationale, bonded-device list, selection.
 *
 * Stateless on purpose — the wrapper owns the grant/list/selection state, so
 * this card can be previewed in every combination (permission missing, no
 * bonded devices, a few devices, one selected).
 *
 * Only *bonded* devices are listed: the app never scans, which is also why it
 * needs no location permission. The expected board advertises as
 * [ESP32_BOARD_NAME]; when several devices are paired the user's tap decides,
 * and the MAC shown under each name is the identity actually connected to.
 *
 * @param runtimePermissionRequired true on API 31+, where
 *   `BLUETOOTH_CONNECT` is a runtime grant.
 * @param permissionGranted result of the last grant check/launcher answer.
 * @param devices bonded devices, or null while the first read is in flight.
 * @param selectedAddress currently targeted MAC, if any.
 * @param onGrantPermission launches the runtime permission request.
 * @param onSelect sets the target device.
 * @param onRefresh re-checks the grant and re-reads the bonded list (pairing
 *   happens in Android Settings, so this is the only way back).
 */
@Composable
internal fun BluetoothDevicePicker(
    runtimePermissionRequired: Boolean,
    permissionGranted: Boolean,
    devices: List<BluetoothEndpoint>?,
    selectedAddress: String?,
    onGrantPermission: () -> Unit,
    onSelect: (BluetoothEndpoint) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Bluetooth device",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            if (!permissionGranted) {
                Text(
                    text = if (runtimePermissionRequired) {
                        "Bluetooth permission is needed to list paired devices " +
                            "and connect. Android 12 and newer shows this as " +
                            "\"Nearby devices\"."
                    } else {
                        "Bluetooth permission is missing even though it is an " +
                            "install-time grant; reinstall the app to restore it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (runtimePermissionRequired) {
                    Button(
                        onClick = onGrantPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Allow Bluetooth access")
                    }
                    Text(
                        text = "If no dialog appears, enable Nearby devices for " +
                            "this app in Android Settings, then tap Refresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            when {
                devices == null -> Text(
                    text = "Reading paired devices…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                devices.isEmpty() -> Text(
                    text = "No paired devices found. Pair the board in Android " +
                        "Settings → Bluetooth; it advertises as " +
                        "$ESP32_BOARD_NAME, then tap Refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    Text(
                        text = "Paired devices",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (device in devices) {
                        BluetoothDeviceRow(
                            device = device,
                            selected = device.address == selectedAddress,
                            onSelect = { onSelect(device) },
                        )
                    }
                    if (selectedAddress == null) {
                        Text(
                            text = "Select a device to enable Connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One selectable bonded device: radio dot, name (or address when the device
 * has none) and the MAC in monospace.
 *
 * The whole row is the tap target so the MAC is easy to read while picking;
 * the radio's own click is nulled to avoid a double toggle.
 */
@Composable
private fun BluetoothDeviceRow(
    device: BluetoothEndpoint,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = device.name ?: "Unnamed device",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * BLE-only body: permission rationale, a live scan list, and selection.
 *
 * Stateless on purpose — the wrapper owns the grant/scan/selection state, so
 * this card can be previewed in every combination (permission missing,
 * scanning with nothing seen, a few boards, one selected).
 *
 * Unlike the SPP picker this *scans*: the C3's Nordic UART peripheral is not
 * bonded, so [devices] fills in as advertisements arrive while BLE is the
 * active transport. The expected boards advertise as `KeeLoqLab-C3-A` / `-B`;
 * when several appear the user's tap decides, and the MAC under each name is
 * the identity actually connected to.
 *
 * @param permissionGranted true when both Scan and Connect grants are held.
 * @param devices scan results so far, or null before the first scan / while
 *   permission is missing.
 * @param selectedAddress currently targeted MAC, if any.
 * @param onGrantPermission launches the (multi-)permission request.
 * @param onSelect sets the target device.
 * @param onRescan re-checks the grants and restarts the scan list.
 */
@Composable
internal fun BleDevicePicker(
    permissionGranted: Boolean,
    devices: List<BleEndpoint>?,
    selectedAddress: String?,
    onGrantPermission: () -> Unit,
    onSelect: (BleEndpoint) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BLE device",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (permissionGranted) {
                    TextButton(onClick = onRescan) {
                        Text("Rescan")
                    }
                }
            }

            if (!permissionGranted) {
                Text(
                    text = "Bluetooth permission is needed to scan for and " +
                        "connect to the board. Android 12 and newer shows this " +
                        "as \"Nearby devices\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onGrantPermission,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow Bluetooth access")
                }
                Text(
                    text = "If no dialog appears, enable Nearby devices for this " +
                        "app in Android Settings, then tap Rescan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            when {
                devices == null -> Text(
                    text = "Tap Rescan to look for the board…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                devices.isEmpty() -> Text(
                    text = "Scanning… power the board; it advertises as " +
                        "${C3_BOARD_NAME_PREFIX}A / ${C3_BOARD_NAME_PREFIX}B.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    Text(
                        text = "Found devices (scanning…)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (device in devices) {
                        BleDeviceRow(
                            device = device,
                            selected = device.address == selectedAddress,
                            onSelect = { onSelect(device) },
                        )
                    }
                    if (selectedAddress == null) {
                        Text(
                            text = "Select a device to enable Connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One selectable scanned device: radio dot, advertised name (or address when
 * the advertisement carried none) and the MAC in monospace. Mirrors
 * [BluetoothDeviceRow] so both pickers read identically.
 */
@Composable
private fun BleDeviceRow(
    device: BleEndpoint,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = device.name ?: "Unnamed device",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    state: ConnectUiState,
    transport: ConnectTransport,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(statusColor(state), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = connectPhaseLabel(state.phase, transport),
                    style = MaterialTheme.typography.titleMedium,
                )
                connectStatusDetail(state, transport)?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: StatusDiagnostics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "STATUS diagnostics",
                style = MaterialTheme.typography.titleMedium,
            )

            if (diagnostics == null) {
                Text(
                    text = "Waiting for the first STATUS packet…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DiagnosticRow("Clock", "${formatCount(diagnostics.clockHz)} Hz")
                DiagnosticRow("Total edges", formatCount(diagnostics.totalEdges))
                DiagnosticRow("Dropped packets", formatCount(diagnostics.droppedPackets.toLong()))
                DiagnosticRow("STATUS sequence", "#${formatCount(diagnostics.sequence.toLong())}")
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ConnectionControls(
    state: ConnectUiState,
    connectEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            ConnectionPhase.CONNECTING -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connecting…")
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }

            ConnectionPhase.CONNECTED -> {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disconnect")
                }
            }

            else -> {
                Button(
                    onClick = onConnect,
                    // Bluetooth needs its grant and a picked device first; USB
                    // is always ready. The picker above says what is missing.
                    enabled = connectEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.phase == ConnectionPhase.DISCONNECTED) {
                            "Connect"
                        } else {
                            "Try again"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun statusColor(state: ConnectUiState): Color = when {
    state.phase == ConnectionPhase.CONNECTED && state.liveness == LinkLiveness.LIVE ->
        MaterialTheme.colorScheme.primary

    state.phase == ConnectionPhase.CONNECTED && state.liveness == LinkLiveness.STALE ->
        MaterialTheme.colorScheme.error

    state.phase == ConnectionPhase.CONNECTED ->
        MaterialTheme.colorScheme.tertiary

    state.phase == ConnectionPhase.CONNECTING ->
        MaterialTheme.colorScheme.tertiary

    state.phase == ConnectionPhase.PERMISSION_DENIED ||
        state.phase == ConnectionPhase.ERROR ->
        MaterialTheme.colorScheme.error

    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

/** Stable ViewModel-store key for the single USB ViewModel. */
private const val USB_VIEW_MODEL_KEY: String = "connect-usb"

/** Prefix for the per-address Bluetooth ViewModels (MAC appended). */
private const val BLUETOOTH_VIEW_MODEL_KEY_PREFIX: String = "connect-bluetooth:"

/** Prefix for the per-address BLE ViewModels (MAC appended). */
private const val BLE_VIEW_MODEL_KEY_PREFIX: String = "connect-ble:"
