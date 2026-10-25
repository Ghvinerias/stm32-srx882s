package com.eelab.rflab.espflash

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.eelab.rflab.usb.RfLabSession
import com.eelab.rflab.usb.UsbDeviceFinder
import com.eelab.rflab.usb.UsbPermission
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A USB flash that failed before or during the ROM exchange.
 *
 * @property message what went wrong.
 * @property recovery a concrete next step for the user; shown under the
 *   error and deliberately mentions the manual BOOT+RESET fallback because
 *   the USB-JTAG reset depends on the running firmware.
 */
class UsbFlashException(
    override val message: String,
    val recovery: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Runs a complete "pick an image, enter the ROM loader, write, verify,
 * reboot" flash over USB-C OTG.
 *
 * This is the Android-facing half of the feature; the wire protocol lives in
 * [EspRomSession] / [EspRomFlasher] and is exercised on the JVM by the tests.
 * The steps here are the ones that need a real [UsbManager]:
 *
 * 1. **Detach the app link.** `RfLabSession.detach()` closes the CDC-ACM
 *    connection the Connect screen opened, because two owners cannot claim
 *    the same USB interface.
 * 2. **Find and open the C3.** [UsbDeviceFinder] matches Espressif's
 *    `0x303A:0x1001`; [UsbPermission] shows the system dialog if needed.
 * 3. **Toggle DTR/RTS.** [UsbJtagReset] mirrors esptool's
 *    `USBJTAGSerialReset`; this is what makes the chip boot the ROM loader
 *    without touching the BOOT button.
 * 4. **Wait for re-enumeration.** USB-Serial/JTAG drops off the bus during
 *    reset and comes back as a *new* [UsbDevice] with a new `deviceId`, so
 *    permission has to be requested again. The old device is tracked and a
 *    device that stays put is retried as a last resort.
 * 5. **Flash.** Open the new device, hand its port to [EspRomFlasher], and
 *    close the port afterwards. The ROM reboots the chip on `FLASH_END`.
 *
 * Every blocking call runs on [ioDispatcher]; [flash] suspends. A user
 * Cancel sets the flag that [EspRomFlasher] polls between blocks and response
 * reads, so the UI is not blocked behind a 125-second erase.
 *
 * The public [flash] returns an [EspFlashResult] and never throws for an
 * ordinary failure; [flashOrThrow] is the exception-based form used by this
 * class itself.
 *
 * ### What is not attempted
 *
 * - No flasher stub is uploaded. `FLASH_BEGIN/DATA/END` and `SPI_FLASH_MD5`
 *   are ROM commands on the C3, and the stub-only compressed write format
 *   would add a second protocol and a second failure mode for no benefit on
 *   a 4 MiB image.
 * - No UART/USB bridge (CP2102/CH340) support. The brief targets the C3's
 *   native USB-C port; a bridge would still work with this sequence only if
 *   its DTR/RTS were wired to EN/GPIO9, which is not guaranteed.
 */
class UsbEspFlashController(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val config: EspRomConfig = EspRomConfig(),
    private val permissionTimeoutMs: Long = UsbPermission.DEFAULT_TIMEOUT_MS,
    private val reenumerationTimeoutMs: Long = REENUMERATION_TIMEOUT_MS,
    private val reenumerationPollMs: Long = 100L,
) : UsbFlashRunner {

    private val appContext: Context = context.applicationContext

    /**
     * Flashes [image] and maps the outcome to [EspFlashResult].
     *
     * A user Cancel ([EspCancelledException]) becomes
     * [EspFlashResult.Cancelled]; every other failure becomes
     * [EspFlashResult.Failure] with a recovery hint. Coroutine cancellation
     * (the ViewModel being cleared) is rethrown so structured concurrency is
     * preserved.
     */
    override suspend fun flash(
        image: FlashImage,
        onProgress: (EspFlashProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): EspFlashResult = try {
        EspFlashResult.Success(flashOrThrow(image, onProgress, isCancelled))
    } catch (cancelled: EspCancelledException) {
        EspFlashResult.Cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: UsbFlashException) {
        EspFlashResult.Failure(error.message, error.recovery)
    } catch (error: Exception) {
        EspFlashResult.Failure(
            message = error.message ?: error.javaClass.simpleName,
            recoveryHint = recoveryFor(error),
        )
    }

    /**
     * Flashes [image] and returns the ROM's verified MD5.
     *
     * @param onProgress called from the IO thread; hop to the main thread in
     *   the caller if needed.
     * @param isCancelled polled by the flasher, between blocks, and inside
     *   response reads.
     * @throws UsbFlashException for transport failures with a recovery hint.
     * @throws EspFlashException for protocol failures.
     * @throws EspCancelledException when [isCancelled] turned true.
     */
    private suspend fun flashOrThrow(
        image: FlashImage,
        onProgress: (EspFlashProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): EspFlashSummary = withContext(ioDispatcher) {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw UsbFlashException(
                "This phone does not expose USB host support",
                RECOVERY_NO_USB_HOST,
            )

        checkCancelled(isCancelled)

        // The Connect screen's CDC link owns the interface; release it first.
        notify(
            onProgress,
            EspFlashPhase.ENTERING_BOOTLOADER,
            0,
            image.bytes.size.toLong(),
            "Closing the app link and resetting the board into download mode",
        )
        RfLabSession.detach()

        val original = findC3(usbManager)
            ?: throw UsbFlashException(
                "No ESP32-C3 found on USB",
                "Plug the board into the phone with a data-capable OTG " +
                    "adapter, then tap Flash again. The C3's native USB port " +
                    "must be the one connected (not a UART header).",
            )

        notify(
            onProgress,
            EspFlashPhase.WAITING_FOR_PERMISSION,
            0,
            image.bytes.size.toLong(),
            "Waiting for the USB permission dialog",
        )
        requestPermission(usbManager, original)

        val first = openPort(usbManager, original)
        try {
            checkCancelled(isCancelled)
            UsbJtagReset.reset(first.stream)
        } finally {
            first.close()
        }

        notify(
            onProgress,
            EspFlashPhase.WAITING_FOR_DEVICE,
            0,
            image.bytes.size.toLong(),
            "Waiting for the ROM loader to re-enumerate",
        )
        val loaderDevice = awaitReenumeration(usbManager, original, isCancelled)
        requestPermission(usbManager, loaderDevice)

        val loader = openPort(usbManager, loaderDevice)
        try {
            checkCancelled(isCancelled)
            return@withContext EspRomFlasher(
                stream = loader.stream,
                config = config,
                isCancelled = isCancelled,
            ).flash(image.bytes, image.offset, onProgress)
        } finally {
            loader.close()
        }
    }

    /** Returns the first C3 (vendor `0x303A`) present, or null. */
    private fun findC3(usbManager: UsbManager): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == UsbDeviceFinder.C3_VENDOR_ID
        }

    private suspend fun requestPermission(usbManager: UsbManager, device: UsbDevice) {
        val granted = try {
            UsbPermission.requestPermission(
                appContext,
                usbManager,
                device,
                permissionTimeoutMs,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw UsbFlashException(
                "Could not ask Android for USB permission: ${error.describe()}",
                RECOVERY_PERMISSION,
                error,
            )
        }
        if (!granted) {
            throw UsbFlashException(
                "USB permission was denied",
                "Tap Flash again and allow access to the USB device. If the " +
                    "dialog does not appear, unplug the board, plug it back in " +
                    "and check the system notification for a USB permission " +
                    "prompt.",
            )
        }
    }

    /**
     * Waits for the chip to come back after the USB-JTAG reset.
     *
     * A board whose firmware does not implement the USB-Serial/JTAG control
     * lines (for example one running a bare TinyUSB CDC stack) never enters
     * the loader; the original device simply stays on the bus. That case is
     * indistinguishable from a very quick re-enumeration at this level, so
     * once the nominal timeout expires the original device is returned and
     * the SYNC stage produces the "hold BOOT" hint.
     */
    private suspend fun awaitReenumeration(
        usbManager: UsbManager,
        original: UsbDevice,
        isCancelled: () -> Boolean,
    ): UsbDevice {
        val deadline = System.nanoTime() + reenumerationTimeoutMs * 1_000_000L
        var originalDisappeared = false
        while (System.nanoTime() < deadline) {
            checkCancelled(isCancelled)
            val devices = usbManager.deviceList.values
                .filter { it.vendorId == UsbDeviceFinder.C3_VENDOR_ID }

            if (devices.none { it.deviceId == original.deviceId }) {
                originalDisappeared = true
            }

            // Prefer a genuinely new device. Once the original is gone, any
            // C3 device (including one that reuses the old ID) is the loader.
            devices.firstOrNull { it.deviceId != original.deviceId }?.let { return it }
            if (originalDisappeared) {
                devices.firstOrNull()?.let { return it }
            }
            delay(reenumerationPollMs)
        }

        // Last resort: the reset may have worked without a USB disconnect
        // (or the phone kept the same deviceId). Try a SYNC on it; a board
        // still running the app fails there with the manual-mode hint.
        usbManager.deviceList.values
            .firstOrNull { it.deviceId == original.deviceId }
            ?.let { return it }

        throw UsbFlashException(
            "The board did not reappear after the reset",
            RECOVERY_NOT_IN_LOADER,
        )
    }

    private fun openPort(usbManager: UsbManager, device: UsbDevice): OpenedPort {
        val driver = UsbDeviceFinder.createDriver(device)
        val port = driver.ports.firstOrNull()
            ?: throw UsbFlashException(
                "The USB device has no CDC-ACM interface",
                "Use the ESP32-C3's native USB port. If a USB-UART adapter " +
                    "is attached instead, this app cannot drive its reset " +
                    "lines.",
            )
        val connection = usbManager.openDevice(device)
            ?: throw UsbFlashException(
                "Android refused to open the USB device",
                RECOVERY_PERMISSION,
            )
        return try {
            port.open(connection)
            UsbSerialRomByteStream.configure(port)
            OpenedPort(port, connection)
        } catch (error: Exception) {
            runCatching { port.close() }
            runCatching { connection.close() }
            throw UsbFlashException(
                "Could not open the board's USB serial port: ${error.describe()}",
                "Unplug the board, plug it back in, and tap Flash again. If " +
                    "this keeps happening, another app may still hold the USB " +
                    "device; close it or reboot the phone.",
                error,
            )
        }
    }

    private fun notify(
        onProgress: (EspFlashProgress) -> Unit,
        phase: EspFlashPhase,
        bytes: Long,
        total: Long,
        note: String,
    ) {
        onProgress(
            EspFlashProgress(
                phase = phase,
                bytesWritten = bytes,
                totalBytes = total,
                note = note,
            ),
        )
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw EspCancelledException()
    }

    /** Chooses the hint shown under a failure. */
    private fun recoveryFor(error: Throwable): String = when {
        error.isSyncFailure() -> RECOVERY_NOT_IN_LOADER
        error is IOException -> RECOVERY_DISCONNECTED
        else -> RECOVERY_GENERIC
    }

    /** An opened port plus the device connection that owns it. */
    private class OpenedPort(
        private val port: com.hoho.android.usbserial.driver.UsbSerialPort,
        private val connection: UsbDeviceConnection,
    ) {

        val stream = UsbSerialRomByteStream(port)

        fun close() {
            runCatching { port.close() }
            runCatching { connection.close() }
        }
    }

    companion object {
        /** How long to wait for the ROM loader's new USB device. */
        const val REENUMERATION_TIMEOUT_MS: Long = 12_000L

        /** Manual fallback used when SYNC never succeeds. */
        const val RECOVERY_NOT_IN_LOADER: String =
            "Hold BOOT (GPIO9), tap RESET/EN, release BOOT, then tap Flash " +
                "over USB again. Make sure the C3's native USB port is the " +
                "one connected, not a UART header."

        private const val RECOVERY_NO_USB_HOST =
            "This feature needs USB host (OTG) support. Check that the phone " +
                "supports USB-C OTG and that the cable is a data cable, then " +
                "use the OTA path instead."

        private const val RECOVERY_PERMISSION =
            "Unplug the board, plug it back in, and tap Flash again. Android " +
                "must grant USB permission to this app before the port can be " +
                "opened."

        private const val RECOVERY_DISCONNECTED =
            "The board disappeared during the flash. Reconnect the USB cable " +
                "and tap Flash again; the ROM loader is still reachable."

        private const val RECOVERY_GENERIC =
            "Unplug the board, plug it back in, and tap Flash again. If the " +
                "board does not enter download mode by itself, hold BOOT, tap " +
                "RESET, release BOOT, then tap Flash over USB."
    }
}

private fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/**
 * True when [this] looks like the failure esptool describes for a board that
 * is not in the loader: a SYNC timeout rather than a USB-level error.
 */
internal fun Throwable.isSyncFailure(): Boolean =
    this is EspSyncException || (this is EspTimeoutException && operation == "SYNC")
