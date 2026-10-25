package com.eelab.rflab.ble

import com.eelab.rflab.usb.RfLabTransport
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * A BLE device the user can pick on the Connect screen.
 *
 * A plain data class on purpose, mirroring
 * [com.eelab.rflab.bluetooth.BluetoothEndpoint]: [BleLink] and
 * [BleConnectController] stay free of Android types so their outcome logic is
 * testable on the JVM with fakes. The production [AndroidBleLink] maps a
 * `BluetoothDevice`/`ScanResult` into this at the edge.
 *
 * @property name the advertised name, or null when the advertisement carried
 *   none (the UI falls back to [address]).
 * @property address the device MAC, e.g. `AA:BB:CC:DD:EE:FF`. This is the
 *   identity the controller connects to; advertised names are not unique.
 */
data class BleEndpoint(
    val name: String?,
    val address: String,
)

/**
 * The Android BLE operations [BleConnectController] and the Connect screen
 * need, isolated behind an injectable seam.
 *
 * This is the BLE twin of [com.eelab.rflab.bluetooth.BluetoothLink], but the
 * discovery model is fundamentally different: the ESP32-C3 build advertises a
 * Nordic UART Service and is **not a bonded device**, so the app must *scan*
 * for it rather than read a bonded list. Scanning needs its own permission
 * ([isScanPermissionGranted]); connecting needs [isConnectPermissionGranted].
 *
 * - [isScanPermissionGranted] answers whether the app may run a BLE scan
 *   (`BLUETOOTH_SCAN` on API 31+, `ACCESS_FINE_LOCATION` below that).
 * - [isConnectPermissionGranted] answers whether it may open a GATT
 *   connection (`BLUETOOTH_CONNECT` on API 31+; install-time below that).
 * - [scan] runs a filtered BLE scan and emits the growing set of matching
 *   devices; collection starts the scan and cancellation stops it.
 * - [openTransport] performs the GATT connect + service discovery +
 *   notification subscription and returns a ready byte stream. It must not
 *   touch [com.eelab.rflab.usb.RfLabSession]; attaching is the controller's
 *   job.
 *
 * Implementations may block/suspend in [openTransport]; the controller calls
 * it on an IO dispatcher.
 */
interface BleLink {

    /** True when the app may run a BLE scan on this device/OS version. */
    fun isScanPermissionGranted(): Boolean

    /** True when the app may open a GATT connection. */
    fun isConnectPermissionGranted(): Boolean

    /**
     * Scans for devices advertising the Nordic UART Service and emits the
     * accumulated list on every new result.
     *
     * The first emission is an empty list (scan started, nothing seen yet).
     * Collecting starts the scan; cancelling the collector stops it. A scan
     * failure completes the flow with an exception.
     */
    fun scan(): Flow<List<BleEndpoint>>

    /**
     * Opens a GATT link to the device at [address] and subscribes to the
     * UART notify characteristic.
     *
     * Suspends for the duration of connect + discovery + subscription. On
     * failure the implementation must tear the GATT client down and throw, so
     * the controller can turn it into a user-facing message.
     *
     * @throws java.io.IOException if the device is unreachable, the UART
     *   service/characteristics are missing, or the subscription fails.
     */
    suspend fun openTransport(address: String): RfLabTransport

    companion object {
        /**
         * Nordic UART Service and its characteristics, matching the ESP32-C3
         * firmware (`esp32c3-cc1101-lab/src/lab_config.h`):
         *
         * - service `6E400001` — advertised, used as the scan filter.
         * - RX `6E400002` — host → device, WRITE / WRITE_NR (commands out).
         * - TX `6E400003` — device → host, NOTIFY (the COBS capture stream).
         */
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        /** Standard Client Characteristic Configuration Descriptor UUID. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
