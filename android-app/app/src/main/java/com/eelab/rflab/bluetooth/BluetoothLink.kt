package com.eelab.rflab.bluetooth

import com.eelab.rflab.usb.RfLabTransport
import java.util.UUID

/**
 * A Bluetooth device the user can pick on the Connect screen.
 *
 * A plain data class on purpose: [BluetoothLink] and
 * [BluetoothConnectController] must stay free of Android types so their
 * outcome logic is testable on the JVM with fakes, the same way
 * [com.eelab.rflab.usb.ConnectPort] keeps `UsbManager` away from the
 * ViewModel. The production [AndroidBluetoothLink] maps `BluetoothDevice`
 * into this at the edge.
 *
 * @property name the user-visible name, or null when the device has none
 *   (the UI falls back to [address]).
 * @property address the stable MAC address, e.g. `AA:BB:CC:DD:EE:FF`. This is
 *   the identity the controller is given; names are not unique.
 */
data class BluetoothEndpoint(
    val name: String?,
    val address: String,
)

/**
 * The Android Bluetooth operations [BluetoothConnectController] needs.
 *
 * This is the injectable seam for the Bluetooth side, mirroring how
 * [com.eelab.rflab.usb.UsbDeviceFinder] isolated `UsbManager`:
 *
 * - [isConnectPermissionGranted] answers whether `BLUETOOTH_CONNECT` is
 *   available. Below API 31 it is an install-time grant, so the production
 *   implementation always returns true there.
 * - [bondedDevices] lists already-paired devices. The app never scans, which
 *   is why no location permission is required on any Android version.
 * - [openTransport] performs the blocking RFCOMM connect and returns the
 *   ready-to-use byte stream. It must not touch [com.eelab.rflab.usb.RfLabSession];
 *   attaching the resulting connection is the controller's job.
 *
 * Implementations may block in [openTransport]; the controller calls it on an
 * IO dispatcher.
 */
interface BluetoothLink {

    /** True when the app may talk to bonded Bluetooth devices. */
    fun isConnectPermissionGranted(): Boolean

    /** Already-paired devices, in the order the platform reports them. */
    fun bondedDevices(): List<BluetoothEndpoint>

    /**
     * Opens an SPP/RFCOMM link to the bonded device at [address].
     *
     * Suspends for the duration of the blocking connect. On failure the
     * implementation must close any socket it created and throw, so the
     * controller can turn it into a user-facing message.
     *
     * @throws java.io.IOException if the device is unreachable or the socket
     *   cannot be opened.
     */
    suspend fun openTransport(address: String): RfLabTransport

    companion object {
        /**
         * Serial Port Profile (SPP) UUID, the well-known RFCOMM service the
         * ESP32 sketch advertises (`ESP32-KeeLoq-Lab`).
         */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
