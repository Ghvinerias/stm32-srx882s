package com.eelab.rflab.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.eelab.rflab.usb.RfLabTransport
import java.io.IOException

/**
 * Production [BluetoothLink]: `BluetoothAdapter` + RFCOMM socket handling.
 *
 * Kept deliberately thin — it maps Android objects into the plain
 * [BluetoothEndpoint] type and performs the blocking socket open. All outcome
 * logic (permission check, device resolution, session attach, cancellation)
 * lives in [BluetoothConnectController] so it can be unit-tested with a fake
 * link; opening a real socket needs the phone, a bonded ESP32 and a live
 * connection, and is only exercised on device.
 *
 * Only the application context is retained, so a controller built in a
 * ViewModel does not leak an Activity across configuration changes.
 *
 * ### Permission model
 *
 * On API 31+ `BLUETOOTH_CONNECT` is a runtime permission required for
 * `bondedDevices`, `BluetoothDevice.name` and
 * `createRfcommSocketToServiceRecord`. Below 31 the legacy
 * `BLUETOOTH`/`BLUETOOTH_ADMIN` permissions are granted at install time and
 * none of these calls need a runtime check. The manifest carries both
 * variants.
 *
 * Discovery is never started or cancelled, so neither `BLUETOOTH_SCAN` nor a
 * location permission appears anywhere — `cancelDiscovery()` is the one call
 * that would need `BLUETOOTH_SCAN` on API 31+, and connecting to an
 * already-bonded device does not require it.
 *
 * @param context any context; only its application context is retained.
 */
class AndroidBluetoothLink(context: Context) : BluetoothLink {

    private val appContext: Context = context.applicationContext

    /**
     * The default adapter, or null on a device without Bluetooth.
     *
     * `BluetoothManager.adapter` needs no permission to read; the adapter is
     * only touched further after the controller has verified
     * `BLUETOOTH_CONNECT`.
     */
    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter

    override fun isConnectPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lists bonded devices, or an empty list with no Bluetooth adapter.
     *
     * @throws SecurityException on API 31+ without `BLUETOOTH_CONNECT`; both
     *   `bondedDevices` and `BluetoothDevice.name` need it. The Connect screen
     *   only calls this after the runtime grant, and the controller re-checks
     *   the permission before connecting, so lint's static check is suppressed.
     */
    @Suppress("MissingPermission") // Callers gate this on BLUETOOTH_CONNECT first.
    override fun bondedDevices(): List<BluetoothEndpoint> {
        val adapter = adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map { device ->
            BluetoothEndpoint(name = device.name, address = device.address)
        }
    }

    /**
     * Connects RFCOMM to the bonded device at [address].
     *
     * The socket is created with the SPP UUID, connected, and handed to
     * [BluetoothSerialTransport]; if `connect()` fails the socket is closed
     * before the exception propagates so no half-open channel is left behind.
     * Discovery is intentionally not cancelled here (see the class KDoc).
     *
     * @throws IOException if Bluetooth is off or unavailable, or the connect
     *   times out / is refused.
     */
    @Suppress("MissingPermission") // The controller checks BLUETOOTH_CONNECT first.
    override suspend fun openTransport(address: String): RfLabTransport {
        val adapter = adapter
            ?: throw IOException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) {
            throw IOException("Bluetooth is turned off")
        }

        // Deliberately no cancelDiscovery(): on API 31+ it needs the
        // BLUETOOTH_SCAN permission, which this app does not hold (it never
        // scans — it connects to already-bonded devices). Calling it threw a
        // SecurityException that aborted the connect before the socket opened.

        val device = adapter.getRemoteDevice(address)
        val socket = device.createRfcommSocketToServiceRecord(BluetoothLink.SPP_UUID)
        try {
            socket.connect()
        } catch (error: IOException) {
            runCatching { socket.close() }
            throw error
        }
        return BluetoothSerialTransport(socket)
    }
}
