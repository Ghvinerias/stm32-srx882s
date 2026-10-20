package com.eelab.rflab.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android USB host permission helper.
 *
 * Wraps [UsbManager.requestPermission] and the `BroadcastReceiver` that
 * receives its result as a single suspend function. The receiver is
 * registered only for the duration of the request and is always
 * unregistered, including on cancellation and timeout.
 *
 * The broadcast action includes the device ID, so requests for different
 * devices cannot resolve each other's continuations. [ContextCompat] picks
 * the right `registerReceiver` flags for the platform (API 33+ requires
 * `RECEIVER_NOT_EXPORTED`; the result is delivered to our own
 * `PendingIntent`, so a non-exported receiver is correct).
 */
object UsbPermission {

    /** How long to wait for the system's permission dialog before giving up. */
    const val DEFAULT_TIMEOUT_MS: Long = 15_000L

    private const val ACTION_PREFIX = "com.eelab.rflab.usb.PERMISSION_RESULT"

    /**
     * Requests permission to open [device] and suspends until the user
     * answers.
     *
     * @return `true` if permission was granted (including when it had
     *   already been granted before the call), `false` if the user denied
     *   it or no answer arrived within [timeoutMs].
     */
    suspend fun requestPermission(
        context: Context,
        usbManager: UsbManager,
        device: UsbDevice,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        if (usbManager.hasPermission(device)) return true
        val appContext = context.applicationContext
        return withTimeoutOrNull(timeoutMs) {
            awaitPermissionResult(appContext, usbManager, device)
        } ?: false
    }

    private suspend fun awaitPermissionResult(
        context: Context,
        usbManager: UsbManager,
        device: UsbDevice,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val action = "$ACTION_PREFIX.${device.deviceId}"
        val finished = AtomicBoolean(false)

        lateinit var receiver: BroadcastReceiver
        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != action) return
                // Ignore a stale result for another device (the action is
                // per-device, so this is belt-and-braces).
                val resultDevice = IntentCompat.getParcelableExtra(
                    intent,
                    UsbManager.EXTRA_DEVICE,
                    UsbDevice::class.java,
                )
                if (resultDevice != null && resultDevice.deviceId != device.deviceId) return

                if (!finished.compareAndSet(false, true)) return
                runCatching { context.unregisterReceiver(receiver) }
                continuation.resume(
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                )
            }
        }

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val permissionIntent = Intent(action)
        // On Android 12+ the system adds the result extras to this intent, so
        // the PendingIntent must be mutable. Android 14 rejects *implicit*
        // mutable PendingIntents, so scope the action to this app's package
        // (the receiver is registered here, and the system only needs to
        // deliver the result back to us).
        permissionIntent.setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            permissionIntent,
            flags,
        )

        try {
            usbManager.requestPermission(device, pendingIntent)
        } catch (e: Exception) {
            if (finished.compareAndSet(false, true)) {
                runCatching { context.unregisterReceiver(receiver) }
                continuation.resumeWithException(e)
            }
        }
    }
}
