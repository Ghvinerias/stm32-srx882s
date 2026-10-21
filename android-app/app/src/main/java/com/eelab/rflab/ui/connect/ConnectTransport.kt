package com.eelab.rflab.ui.connect

import com.eelab.rflab.bluetooth.BluetoothEndpoint
import java.util.Locale

/**
 * Which physical link the Connect screen currently drives.
 *
 * The app can reach the lab board two ways and they differ only *below*
 * [com.eelab.rflab.usb.ConnectPort]: USB CDC-ACM to the STM32 board, or
 * Bluetooth Classic SPP (RFCOMM) to the ESP32 build. Everything above the
 * port — [ConnectionPhase] transitions, the STATUS heartbeat, the diagnostics
 * card, and the single `RfLabSession` the Receive/Send tabs observe — is
 * identical, so the screen keeps one body and swaps only the port (and the
 * wording that names a transport).
 *
 * USB is declared first and is the screen's default: it is the primary board
 * and the flow the app has always had, so the existing USB connect stays one
 * tap away. Bluetooth is per-visit opt-in and needs a bonded device.
 *
 * This enum is plain Kotlin (no Android or Compose types) so the wording and
 * default-device rules can be unit-tested on the JVM.
 *
 * @property displayName short user-facing name used in labels and hints,
 *   e.g. "Bluetooth permission denied", so no shared copy hard-codes "USB".
 */
enum class ConnectTransport(val displayName: String) {
    USB("USB"),
    BLUETOOTH("Bluetooth"),
}

/**
 * The name the ESP32 build advertises while pairing (see
 * `esp32-bt-console/README.md` and `../esp32-bt-console/`).
 *
 * Used only to pre-select a device in the picker; the selected *address* is
 * the identity handed to
 * [com.eelab.rflab.bluetooth.BluetoothConnectController] because Bluetooth
 * names are neither unique nor stable.
 */
internal const val ESP32_BOARD_NAME: String = "ESP32-KeeLoq-Lab"

/**
 * Title for the status card, naming the active transport where the phase
 * itself is transport-specific.
 *
 * A denied permission is a different dialog on each transport (USB's
 * per-device grant vs. Android 12+'s "Nearby devices"), so the label says
 * which one was refused instead of always reading "USB permission denied".
 */
internal fun connectPhaseLabel(
    phase: ConnectionPhase,
    transport: ConnectTransport,
): String = when (phase) {
    ConnectionPhase.DISCONNECTED -> "Disconnected"
    ConnectionPhase.CONNECTING -> "Connecting…"
    ConnectionPhase.PERMISSION_DENIED -> "${transport.displayName} permission denied"
    ConnectionPhase.CONNECTED -> "Connected"
    ConnectionPhase.ERROR -> "Connection error"
}

/**
 * Secondary line under the phase label, or null when there is nothing to add.
 *
 * Only the CONNECTING sentence differs per transport (finding a USB device and
 * asking for its grant vs. opening an RFCOMM channel to an already-paired
 * device). Once connected, the heartbeat wording is identical: both boards
 * send the same ~1/s STATUS packet.
 */
internal fun connectStatusDetail(
    state: ConnectUiState,
    transport: ConnectTransport,
): String? = when (state.phase) {
    ConnectionPhase.CONNECTING -> when (transport) {
        ConnectTransport.USB ->
            "Looking for the board and requesting USB permission…"

        ConnectTransport.BLUETOOTH ->
            "Opening the RFCOMM link to the selected paired device…"
    }

    ConnectionPhase.CONNECTED -> when (state.liveness) {
        LinkLiveness.WAITING -> "Waiting for the first STATUS heartbeat…"
        LinkLiveness.LIVE ->
            "Heartbeat OK — last STATUS ${formatAge(state.lastStatusAgeMs)} ago"

        LinkLiveness.STALE ->
            "No STATUS for ${formatAge(state.lastStatusAgeMs)} — link may be down"
    }

    // DISCONNECTED / PERMISSION_DENIED / ERROR: the controls and the error
    // surface already say what happened.
    else -> null
}

/**
 * Explanation shown when the user (or the system prompt timeout) refuses the
 * permission the active transport needs.
 *
 * [ConnectUiState.message] carries the controller's own text for a denial,
 * and that text is transport-neutral by construction (the
 * ViewModel only knows [com.eelab.rflab.usb.ConnectPort]); this function
 * supplies the transport-aware wording at presentation time instead.
 */
internal fun connectPermissionMessage(transport: ConnectTransport): String = when (transport) {
    ConnectTransport.USB ->
        "USB permission was denied or the prompt timed out. Tap Connect to ask again."

    ConnectTransport.BLUETOOTH ->
        "Bluetooth permission was denied. Allow it above — Android 12 and newer " +
            "calls it \"Nearby devices\" — then tap Connect again."
}

/**
 * The error surface text for [state].
 *
 * Permission denials are re-worded for the active transport; every other
 * message ("No board found", "Connection lost: …", the controller's
 * Bluetooth failures) already describes what happened and passes through
 * untouched.
 */
internal fun connectMessage(
    state: ConnectUiState,
    transport: ConnectTransport,
): String? = when (state.phase) {
    ConnectionPhase.PERMISSION_DENIED -> connectPermissionMessage(transport)
    else -> state.message
}

/**
 * Footer hint under the controls: what the user must do *before* Connect can
 * work on the selected transport.
 *
 * Kept transport-aware rather than shared, because plugging in a cable and
 * pairing a device are completely different preparations. The Bluetooth text
 * also repeats that the app never scans (only bonded devices are listed, so
 * no location permission is involved) and that the ESP32 build has no receive
 * path, which is why the Receive tab stays empty on that link.
 */
internal fun connectFooterHint(transport: ConnectTransport): String = when (transport) {
    ConnectTransport.USB ->
        "Plug the board in over USB OTG. Android asks for permission the first " +
            "time; the grant is reused until the device is unplugged."

    ConnectTransport.BLUETOOTH ->
        "Pair the board in Android Settings → Bluetooth; it advertises as " +
            "$ESP32_BOARD_NAME. Only already-paired devices are listed — the app " +
            "never scans. This board build has no receive path, so the Receive " +
            "tab stays empty over Bluetooth."
}

/**
 * The bonded device the picker should start on.
 *
 * A manual choice wins while it is still paired (so tapping Refresh does not
 * silently move the target); otherwise the ESP32 board is preferred by name.
 * With no default the result is null on purpose: the selected MAC is the one
 * identity the controller trusts, so the user must tap the device rather than
 * have the app guess between similarly named neighbours.
 *
 * @param devices bonded devices as reported by the platform, maybe empty.
 * @param current the currently selected address, or null.
 */
internal fun preselectedBluetoothAddress(
    devices: List<BluetoothEndpoint>,
    current: String?,
): String? {
    if (current != null && devices.any { it.address == current }) return current
    // Plain iteration instead of firstOrNull-with-index tricks: this is a
    // handful of devices and the loop reads the same on every API level.
    for (device in devices) {
        if (device.name == ESP32_BOARD_NAME) return device.address
    }
    return null
}

/** "123 ms" / "1.4 s"; the unknown case covers a state refreshed too early. */
private fun formatAge(ageMs: Long?): String = when {
    ageMs == null -> "unknown"
    ageMs < 1_000L -> "$ageMs ms"
    else -> String.format(Locale.US, "%.1f s", ageMs / 1_000.0)
}
