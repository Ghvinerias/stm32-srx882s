package com.eelab.rflab.bluetooth

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
 * [ConnectPort] over Bluetooth Classic SPP, targeting one bonded device.
 *
 * This is the Bluetooth twin of [com.eelab.rflab.usb.UsbConnectController]:
 * same interface, same session wiring, same cancellation rules. Where USB
 * enumerates a VID/PID pair, Bluetooth connects to a device the user picked
 * from the bonded list, so the target address is supplied by the caller (the
 * Connect screen; see session 3 of `PLAN_BLUETOOTH.md`) rather than searched
 * for here.
 *
 * All Android touches are behind [BluetoothLink], so the outcome logic in
 * [connect] — permission, selection, failure mapping, cancel-then-close,
 * attach — runs on the JVM against a fake link. Opening a real RFCOMM socket
 * needs the phone and a bonded ESP32 and is only exercised on device, the
 * same on-device gap [com.eelab.rflab.usb.RfLabConnection] documents for USB.
 *
 * ### Failure mapping
 *
 * - No `BLUETOOTH_CONNECT` → [ConnectAttempt.PermissionDenied]; the UI asks
 *   for it and retries.
 * - Nothing selected / selected device no longer bonded →
 *   [ConnectAttempt.Failed] with a message telling the user what to do.
 * - Socket open throws → [ConnectAttempt.Failed] carrying the platform
 *   message, since "connection refused / timeout" is genuinely useful here.
 *
 * A connection is published through [RfLabSession] only after the transport is
 * open, so Receive/Send start sharing the stream immediately; the heartbeat
 * then comes from the board's once-a-second STATUS packets over RFCOMM, just
 * as over USB. The ESP32 build has no RF receive path, so the Receive screen
 * simply stays empty on this transport — expected, not a fault.
 *
 * @param link Android Bluetooth operations, or a fake in tests.
 * @param targetAddress MAC of the bonded device to connect to, or null when
 *   the user has not selected one yet.
 * @param ioDispatcher dispatcher for the blocking socket connect; also given
 *   to [RfLabConnection] for its read loop and writes.
 */
class BluetoothConnectController(
    private val link: BluetoothLink,
    private val targetAddress: String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectPort {

    override val events: Flow<TransportEvent> = RfLabSession.events

    override fun isConnected(): Boolean = RfLabSession.connection.value?.isOpen == true

    override suspend fun connect(): ConnectAttempt {
        // On API 31+ this is the runtime grant; below 31 the production link
        // reports true because the legacy permissions are install-time.
        if (!link.isConnectPermissionGranted()) return ConnectAttempt.PermissionDenied

        val address = targetAddress?.takeIf { it.isNotBlank() }
            ?: return ConnectAttempt.Failed(NO_DEVICE_SELECTED)

        val bonded = try {
            link.bondedDevices()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not list bonded devices", error)
            return ConnectAttempt.Failed(
                "Could not read the paired Bluetooth devices: ${error.describe()}",
            )
        }
        if (bonded.none { it.address == address }) {
            return ConnectAttempt.Failed(
                "The selected Bluetooth device ($address) is no longer paired. " +
                    "Pair the board in Android settings and pick it again.",
            )
        }

        // Defensive, exactly as UsbConnectController does: if a stale
        // connection is still in the session (for example the Connect
        // ViewModel was recreated), close it before replacing it so the old
        // socket is never leaked.
        RfLabSession.detach()

        val transport: RfLabTransport = try {
            // NonCancellable: if the user taps Disconnect while the blocking
            // RFCOMM connect is in progress, let it finish and close the
            // result below instead of leaking an opened socket nobody owns.
            withContext(ioDispatcher + NonCancellable) {
                link.openTransport(address)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not connect to $address", error)
            return ConnectAttempt.Failed("Could not connect over Bluetooth: ${error.describe()}")
        }

        if (!currentCoroutineContext().isActive) {
            // The socket finished connecting after the caller was cancelled:
            // nobody will collect its events, so shut it down.
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
        const val TAG = "BluetoothConnect"

        const val NO_DEVICE_SELECTED =
            "No Bluetooth device selected. Pair the board and pick it first."
    }
}

/**
 * Message-or-class-name, matching the helper in `UsbConnectController`.
 *
 * Platform exceptions such as `IOException` from an RFCOMM connect often
 * carry a useful message ("Connection refused", "socket closed"); fall back to
 * the type name when they do not.
 */
private fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
