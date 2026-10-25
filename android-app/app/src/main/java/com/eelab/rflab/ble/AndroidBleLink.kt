package com.eelab.rflab.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.eelab.rflab.usb.RfLabTransport
import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production [BleLink]: `BluetoothLeScanner` + GATT client handling.
 *
 * Kept as thin as the BLE API allows — it maps Android objects into the plain
 * [BleEndpoint] type, runs the scan, and drives the connect → discover →
 * request-MTU → subscribe sequence before handing a ready [BleUartTransport]
 * to [BleConnectController]. All *outcome* logic lives in the controller so it
 * can be unit-tested with a fake link; opening a real GATT link needs the
 * phone and a powered C3 and is only exercised on device.
 *
 * Only the application context is retained, so a controller built in a
 * ViewModel does not leak an Activity across configuration changes.
 *
 * ### Permission model
 *
 * - **Scanning** needs `BLUETOOTH_SCAN` on API 31+, or `ACCESS_FINE_LOCATION`
 *   on API 23–30 (a BLE scan is location-sensitive on those versions). The
 *   manifest declares `BLUETOOTH_SCAN` with `neverForLocation`, so no actual
 *   location permission is requested on API 31+.
 * - **Connecting** needs `BLUETOOTH_CONNECT` on API 31+; below that the
 *   legacy `BLUETOOTH` grant is install-time.
 *
 * @param context any context; only its application context is retained.
 */
class AndroidBleLink(context: Context) : BleLink {

    private val appContext: Context = context.applicationContext

    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter

    override fun isScanPermissionGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun isConnectPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Scans for peripherals advertising the Nordic UART Service and emits the
     * growing device set.
     *
     * Filtered on the service UUID so only lab boards (and any other NUS
     * device) appear; the first emission is empty, then one per new address.
     * The scan stops when the collector is cancelled.
     *
     * @throws SecurityException on API 31+ without `BLUETOOTH_SCAN`; the
     *   caller gates on [isScanPermissionGranted] first, so lint is
     *   suppressed.
     */
    @SuppressLint("MissingPermission") // Callers gate this on the scan permission first.
    override fun scan(): Flow<List<BleEndpoint>> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            trySend(emptyList())
            close(IOException("Bluetooth is off or unavailable"))
            return@callbackFlow
        }

        // LinkedHashMap keeps first-seen order stable so the picker does not
        // reshuffle as duplicate advertisements arrive.
        val found = LinkedHashMap<String, BleEndpoint>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: safeName(device)
                found[device.address] = BleEndpoint(name = name, address = device.address)
                trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("BLE scan failed (code $errorCode)"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleLink.NUS_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        trySend(emptyList())
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission") // The controller checks BLUETOOTH_CONNECT first.
    private fun safeName(device: BluetoothDevice): String? =
        runCatching { device.name }.getOrNull()

    /**
     * Connects GATT to [address], discovers the UART service and subscribes to
     * its notify characteristic.
     *
     * Every failure path closes the GATT client before throwing, so no
     * half-open client is left behind; MTU negotiation is best-effort (a
     * refusal just falls back to the 20-byte default payload).
     *
     * @throws IOException if Bluetooth is off, the connect/discovery times
     *   out, the UART service/characteristics are missing, or the
     *   subscription write fails.
     */
    @SuppressLint("MissingPermission") // The controller checks BLUETOOTH_CONNECT first.
    override suspend fun openTransport(address: String): RfLabTransport {
        val adapter = adapter ?: throw IOException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")

        val device = adapter.getRemoteDevice(address)
        val channel = BleGattChannel()
        val callback = GattCallback(channel)

        val gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not open a GATT client for $address")

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                if (!callback.connected.await()) throw IOException("BLE connection failed")
                if (!callback.servicesDiscovered.await()) {
                    throw IOException("GATT service discovery failed")
                }
            }

            // Best-effort larger MTU; fall back to the 23-byte ATT default
            // (20-byte payload) if the peer or stack declines.
            val mtu = withTimeoutOrNull(MTU_TIMEOUT_MS) {
                if (gatt.requestMtu(PREFERRED_MTU)) callback.mtuChanged.await() else null
            } ?: DEFAULT_ATT_MTU

            val service = gatt.getService(BleLink.NUS_SERVICE_UUID)
                ?: throw IOException("Nordic UART Service not found on the device")
            val rx = service.getCharacteristic(BleLink.NUS_RX_CHAR_UUID)
                ?: throw IOException("UART RX characteristic not found")
            val tx = service.getCharacteristic(BleLink.NUS_TX_CHAR_UUID)
                ?: throw IOException("UART TX characteristic not found")

            if (!gatt.setCharacteristicNotification(tx, true)) {
                throw IOException("Could not enable TX notifications")
            }
            val cccd = tx.getDescriptor(BleLink.CCCD_UUID)
                ?: throw IOException("TX notify descriptor (0x2902) is missing")
            writeNotificationDescriptor(gatt, cccd)
            withTimeout(DESCRIPTOR_TIMEOUT_MS) {
                if (!callback.descriptorWritten.await()) {
                    throw IOException("Could not subscribe to TX notifications")
                }
            }

            val payload = (mtu - ATT_HEADER_BYTES).coerceIn(MIN_PAYLOAD, MAX_PAYLOAD)
            return BleUartTransport(gatt, rx, channel, payload)
        } catch (error: Throwable) {
            channel.markDisconnected()
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
            throw when (error) {
                is IOException -> error
                else -> IOException("BLE setup failed: ${error.message}", error)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // The controller checks BLUETOOTH_CONNECT first.
    private fun writeNotificationDescriptor(
        gatt: BluetoothGatt,
        cccd: BluetoothGattDescriptor,
    ) {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, enable)
        } else {
            cccd.value = enable
            gatt.writeDescriptor(cccd)
        }
    }

    /**
     * GATT callback that resolves the connect sequence's one-shot awaits and
     * feeds notifications/write-completions into the shared [BleGattChannel].
     *
     * Each phase deferred is completed at most once; a disconnect short-
     * circuits any still-pending phase so [openTransport]'s awaits unblock
     * instead of hitting their timeout.
     */
    @SuppressLint("MissingPermission") // The controller checks BLUETOOTH_CONNECT first.
    private class GattCallback(private val channel: BleGattChannel) : BluetoothGattCallback() {

        val connected = CompletableDeferred<Boolean>()
        val servicesDiscovered = CompletableDeferred<Boolean>()
        val mtuChanged = CompletableDeferred<Int>()
        val descriptorWritten = CompletableDeferred<Boolean>()

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                connected.complete(true)
                // Discovery is kicked off here, right after a successful
                // connect; openTransport awaits servicesDiscovered next.
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                completeFalse(connected)
                completeFalse(servicesDiscovered)
                if (!mtuChanged.isCompleted) mtuChanged.complete(0)
                completeFalse(descriptorWritten)
                channel.markDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!servicesDiscovered.isCompleted) {
                servicesDiscovered.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!mtuChanged.isCompleted) {
                mtuChanged.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 0)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!descriptorWritten.isCompleted) {
                descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        // API 33+ notification delivery (value handed in directly).
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            channel.incoming.offer(value.copyOf())
        }

        // Pre-33 notification delivery (value read off the characteristic).
        @Suppress("DEPRECATION")
        @Deprecated("Superseded by the 3-arg overload on API 33+")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { channel.incoming.offer(it.copyOf()) }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int,
        ) {
            channel.completeWrite(status == BluetoothGatt.GATT_SUCCESS)
        }

        private fun completeFalse(deferred: CompletableDeferred<Boolean>) {
            if (!deferred.isCompleted) deferred.complete(false)
        }
    }

    private companion object {
        const val PREFERRED_MTU = 247
        const val DEFAULT_ATT_MTU = 23
        const val ATT_HEADER_BYTES = 3
        const val MIN_PAYLOAD = 20
        const val MAX_PAYLOAD = 244

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MTU_TIMEOUT_MS = 3_000L
        const val DESCRIPTOR_TIMEOUT_MS = 5_000L
    }
}
