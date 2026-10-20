package com.eelab.rflab.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Production [ConnectPort]: Android USB enumeration + permission + open.
 *
 * The whole flow is the one described in `AGENT_BRIEF.md`, "Connecting to the
 * device": find VID `0xCAFE` / PID `0x4001` in [UsbManager.deviceList],
 * request permission through [UsbPermission], then open with
 * [RfLabConnection.open] and publish the result through [RfLabSession] so the
 * receive and send screens immediately share it.
 *
 * ### Why only an application Context is needed
 *
 * `requestPermission()` does not need an Activity: the system dialog is
 * shown by the platform, and its answer comes back to the receiver that
 * [UsbPermission] registers on this context via a `PendingIntent` broadcast.
 * Holding `context.applicationContext` therefore avoids leaking the Activity
 * and lets the ViewModel outlive configuration changes.
 *
 * Opening the port performs blocking USB calls, so it runs on [ioDispatcher]
 * rather than the main thread. The permission wait itself is a coroutine
 * suspension and runs on the caller's dispatcher.
 *
 * @param context any context; only its application context is retained.
 * @param ioDispatcher dispatcher for the blocking open/close calls.
 */
class UsbConnectController(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectPort {

    private val appContext: Context = context.applicationContext

    override val events: Flow<TransportEvent> = RfLabSession.events

    override fun isConnected(): Boolean = RfLabSession.connection.value?.isOpen == true

    override suspend fun connect(): ConnectAttempt {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return ConnectAttempt.Failed("USB host support is unavailable on this device")

        val device = UsbDeviceFinder.findDevice(usbManager)
            ?: return ConnectAttempt.Failed(
                "No board found (USB 0xCAFE:0x4001). Plug in the STM32 board " +
                    "and check the OTG cable.",
            )

        val granted = try {
            UsbPermission.requestPermission(appContext, usbManager, device)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "USB permission request failed", error)
            return ConnectAttempt.Failed(
                "USB permission request failed: ${error.describe()}",
            )
        }
        if (!granted) return ConnectAttempt.PermissionDenied

        val connection = try {
            // NonCancellable: if the user taps Disconnect while the blocking
            // open is in progress, let it finish and close the result below
            // instead of leaking an opened port that nobody owns.
            withContext(ioDispatcher + NonCancellable) {
                RfLabConnection.open(usbManager, device)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not open ${device.deviceName}", error)
            return ConnectAttempt.Failed(
                "Could not open the board: ${error.describe()}",
            )
        }

        if (!currentCoroutineContext().isActive) {
            connection.close()
            return ConnectAttempt.Failed("Connection cancelled")
        }

        RfLabSession.attach(connection)
        return ConnectAttempt.Connected
    }

    override fun disconnect() {
        RfLabSession.detach()
    }

    private companion object {
        const val TAG = "UsbConnectController"
    }
}

private fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
