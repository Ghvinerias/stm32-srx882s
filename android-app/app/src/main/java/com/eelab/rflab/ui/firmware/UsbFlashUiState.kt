package com.eelab.rflab.ui.firmware

import com.eelab.rflab.espflash.EspFlashPhase
import com.eelab.rflab.espflash.EspFlashProgress
import com.eelab.rflab.espflash.EspFlashSummary
import com.eelab.rflab.espflash.FlashImage

/** The picked image summarised for display; the bytes stay in the ViewModel. */
data class UsbImageInfo(
    val name: String,
    val sizeBytes: Int,
    val sha256Hex: String,
    val isMerged: Boolean,
    val offset: Long,
) {

    /** One phrase describing exactly where the image will be written. */
    val placement: String
        get() = if (isMerged) {
            "merged 4 MiB image → whole flash at 0x0"
        } else {
            "app image → app0 slot at 0x${offset.toString(16).uppercase()}"
        }
}

/**
 * State of the additive "Flash over USB (no OTA)" card on the Firmware
 * screen.
 *
 * @property image the validated image, or null before one is picked.
 * @property imageError why the picked file was rejected.
 * @property reading true while the document picker's bytes are being read.
 * @property progress live phase/bytes from the ROM flasher.
 * @property summary the MD5 and byte count after a successful flash.
 * @property error user-facing failure text.
 * @property recoveryHint what to try next after [error].
 * @property confirmVisible true while the explicit confirm dialog is open.
 */
data class UsbFlashUiState(
    val image: UsbImageInfo? = null,
    val imageError: String? = null,
    val reading: Boolean = false,
    val progress: EspFlashProgress = EspFlashProgress(),
    val summary: EspFlashSummary? = null,
    val error: String? = null,
    val recoveryHint: String? = null,
    val confirmVisible: Boolean = false,
) {

    /** True while the USB device is owned by a flash. */
    val busy: Boolean
        get() = progress.isActive

    /** True when the user may start a flash. */
    val canFlash: Boolean
        get() = image != null && imageError == null && !busy && !reading

    /** True once a flash finished successfully and nothing is running. */
    val succeeded: Boolean
        get() = summary != null && !busy && error == null

    /** Human-readable phase label for the progress card. */
    val phaseLabel: String
        get() = when (progress.phase) {
            EspFlashPhase.IDLE -> ""
            EspFlashPhase.ENTERING_BOOTLOADER -> "Entering bootloader…"
            EspFlashPhase.WAITING_FOR_PERMISSION -> "Waiting for USB permission…"
            EspFlashPhase.WAITING_FOR_DEVICE -> "Waiting for the ROM loader…"
            EspFlashPhase.SYNCING -> "Syncing with the ROM loader…"
            EspFlashPhase.CONFIGURING -> "Configuring flash…"
            EspFlashPhase.ERASING -> "Erasing…"
            EspFlashPhase.WRITING -> "Writing…"
            EspFlashPhase.VERIFYING -> "Verifying MD5…"
            EspFlashPhase.REBOOTING -> "Rebooting the board…"
            EspFlashPhase.DONE -> "Flashed and verified"
            EspFlashPhase.FAILED -> "Failed"
            EspFlashPhase.CANCELLED -> "Cancelled"
        }

    companion object {

        /** Builds the display summary from a validated [FlashImage]. */
        fun infoFor(image: FlashImage): UsbImageInfo = UsbImageInfo(
            name = image.name,
            sizeBytes = image.bytes.size,
            sha256Hex = com.eelab.rflab.espflash.FlashImages.sha256Hex(image.bytes),
            isMerged = image.isMerged,
            offset = image.offset,
        )
    }
}
