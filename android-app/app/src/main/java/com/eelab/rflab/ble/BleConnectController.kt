package com.eelab.rflab.ble

import android.util.Log
import com.eelab.rflab.usb.ConnectAttempt
import com.eelab.rflab.usb.ConnectPort
import com.eelab.rflab.usb.RfLabConnection
import com.eelab.rflab.usb.RfLabSession
import com.eelab.rflab.usb.RfLabTransport
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * [ConnectPort] over BLE (Nordic UART Service), targeting one scanned device.
 *
 * This is the BLE sibling of
 * [com.eelab.rflab.bluetooth.BluetoothConnectController]: same interface, same
 * session wiring, same cancellation rules. The difference is discovery —
 * where the SPP controller connects to a *bonded* device, the C3's NUS
 * peripheral is not bonded, so the Connect screen scans for it (via
 * [BleLink.scan]) and hands the picked address here. This controller only
 * opens and attaches; it does not scan.
 *
 * All Android touches are behind [BleLink], so the outcome logic in [connect]
 * — permission, selection, failure mapping, cancel-then-close, attach — runs
 * on the JVM against a fake link. Opening a real GATT link needs the phone and
 * a powered C3 and is only exercised on device, the same on-device gap
 * [RfLabConnection] documents for USB.
 *
 * ### Failure mapping
 *
 * - No `BLUETOOTH_CONNECT` → [ConnectAttempt.PermissionDenied]; the UI asks
 *   for it and retries.
 * - Nothing selected → [ConnectAttempt.Failed] telling the user to scan and
 *   pick a device.
 * - GATT open/subscribe throws → [ConnectAttempt.Failed] carrying the
 *   platform message.
 *
 * A connection is published through [RfLabSession] only after the transport
 * is open, so Receive/Send start sharing the stream immediately; the
 * heartbeat then comes from the board's once-a-second STATUS packets over
 * BLE notifications, just as over USB.
 *
 * @param link Android BLE operations, or a fake in tests.
 * @param targetAddress MAC of the scanned device to connect to, or null when
 *   the user has not picked one yet.
 * @param ioDispatcher dispatcher for the blocking GATT open; also given to
 *   [RfLabConnection] for its read loop and writes.
 */
class BleConnectController(
    private val link: BleLink,
    private val targetAddress: String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectPort {

    override val events: Flow<TransportEvent> = RfLabSession.events

    override fun isConnected(): Boolean = RfLabSession.connection.value?.isOpen == true

    override suspend fun connect(): ConnectAttempt {
        // On API 31+ this is the runtime BLUETOOTH_CONNECT grant; below 31 the
        // production link reports true because it is install-time.
        if (!link.isConnectPermissionGranted()) return ConnectAttempt.PermissionDenied

        val address = targetAddress?.takeIf { it.isNotBlank() }
            ?: return ConnectAttempt.Failed(NO_DEVICE_SELECTED)

        // Defensive, exactly as the USB/SPP controllers do: if a stale
        // connection is still in the session (for example the Connect
        // ViewModel was recreated), close it before replacing it so the old
        // GATT client is never leaked.
        RfLabSession.detach()

        val transport: RfLabTransport = try {
            // NonCancellable: if the user taps Disconnect while the GATT
            // connect is in progress, let it finish and close the result
            // below instead of leaking an opened GATT client nobody owns.
            withContext(ioDispatcher + NonCancellable) {
                link.openTransport(address)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not connect to $address", error)
            return ConnectAttempt.Failed("Could not connect over BLE: ${error.describe()}")
        }

        if (!currentCoroutineContext().isActive) {
            // The GATT link finished connecting after the caller was
            // cancelled: nobody will collect its events, so shut it down.
            runCatching { transport.close() }
            return ConnectAttempt.Failed("Connection cancelled")
        }

        RfLabSession.attach(RfLabConnection(transport, ioDispatcher))
        return ConnectAttempt.Connected
    }

    override fun disconnect() {
        RfLabSession.detach()
    }

    private companion object {
        const val TAG = "BleConnect"

        const val NO_DEVICE_SELECTED =
            "No BLE device selected. Scan and pick the board (KeeLoqLab-C3-…) first."
    }
}

/**
 * Message-or-class-name, matching the helper in the USB/SPP controllers.
 *
 * Platform exceptions from a GATT connect often carry a useful message
 * ("service discovery failed", "connection timed out"); fall back to the type
 * name when they do not.
 */
private fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
