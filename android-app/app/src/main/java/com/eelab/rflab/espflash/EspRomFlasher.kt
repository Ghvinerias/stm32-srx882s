package com.eelab.rflab.espflash

import java.security.MessageDigest
import java.util.Locale

/**
 * User-visible stage of a USB flash, from the reset that enters the ROM
 * loader through the reboot at the end.
 *
 * The first three stages are driven by [UsbEspFlashController] because they
 * involve Android USB enumeration; the rest come from [EspRomFlasher] once a
 * ROM session is open.
 */
enum class EspFlashPhase {
    /** Nothing running. */
    IDLE,

    /** Detaching the app link and toggling DTR/RTS to enter download mode. */
    ENTERING_BOOTLOADER,

    /** Waiting for the system USB-permission dialog. */
    WAITING_FOR_PERMISSION,

    /** Waiting for the chip to re-enumerate as a new USB device. */
    WAITING_FOR_DEVICE,

    /** Sending SYNC until the ROM loader answers. */
    SYNCING,

    /** SPI attach / flash parameters / disabling watchdogs. */
    CONFIGURING,

    /** FLASH_BEGIN: the ROM erases before it answers. */
    ERASING,

    /** Streaming 0x400-byte FLASH_DATA blocks. */
    WRITING,

    /** SPI_FLASH_MD5 comparison. */
    VERIFYING,

    /** FLASH_END with reboot. */
    REBOOTING,

    /** The write and MD5 verification succeeded. Terminal. */
    DONE,

    /** The flash failed; [EspFlashProgress.note] carries the recovery hint. */
    FAILED,

    /** The user cancelled. Terminal. */
    CANCELLED,
}

/**
 * Progress snapshot for the USB flasher.
 *
 * @property phase current stage.
 * @property bytesWritten bytes handed to the ROM so far (writing phase).
 * @property totalBytes image size after 4-byte alignment, i.e. the MD5 range.
 * @property note human-readable hint or error text.
 */
data class EspFlashProgress(
    val phase: EspFlashPhase = EspFlashPhase.IDLE,
    val bytesWritten: Long = 0,
    val totalBytes: Long = 0,
    val note: String? = null,
) {

    /** 0..100, or null while the total is unknown/zero. */
    val percent: Int?
        get() = if (totalBytes > 0) {
            ((bytesWritten * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }

    /** True while an operation owns the USB device. */
    val isActive: Boolean
        get() = phase in EspFlashPhase.ENTERING_BOOTLOADER..EspFlashPhase.REBOOTING
}

/** Successful completion details shown to the user. */
data class EspFlashSummary(
    /** MD5 of the written (4-byte aligned) image, as reported by the ROM. */
    val md5Hex: String,
    /** Image bytes actually written (excludes block-tail 0xFF padding). */
    val bytesWritten: Long,
    /** Flash offset the image was written to. */
    val targetOffset: Long,
)

/** The ROM reported a different MD5 than the bytes sent. */
class EspMd5MismatchException(
    val expected: String,
    val actual: String,
) : EspFlashException(
    "MD5 verification failed: the app sent $expected but the flash " +
        "contains $actual",
)

/**
 * Drives one complete ROM-loader flash over an already-synced byte stream.
 *
 * Sequence (all constants verified against esptool 5.3.1, see
 * [EspRomSession]'s KDoc):
 *
 * ```
 * SYNC -> disable watchdogs -> SPI_ATTACH -> SPI_SET_PARAMS
 *      -> FLASH_BEGIN (erase) -> FLASH_DATA * N -> SPI_FLASH_MD5 -> FLASH_END(reboot)
 * ```
 *
 * The image is padded with `0xFF` to a 4-byte boundary for the MD5 range and
 * to a full `0x400` block for transmission, matching
 * `esptool/cmds.py:write_flash`. Only the 4-byte-aligned size is verified:
 * the block-tail bytes are the ROM's own padding, exactly as esptool treats
 * them. No `0xFF`-region skipping is attempted — `FLASH_DATA` addresses
 * advance by sequence number within one `FLASH_BEGIN`, so skipping would
 * require extra begin/erase segments and stale-data checks whose savings do
 * not justify the risk on a 4 MiB image.
 *
 * @param stream the open ROM USB-CDC byte stream.
 * @param config protocol tunables; defaults mirror esptool.
 * @param isCancelled polled before SYNC, between blocks, and inside response
 *   reads; a `true` result aborts with [EspCancelledException].
 * @param flashSizeBytes total flash size reported to `SPI_SET_PARAMS`
 *   (4 MiB for the C3 SuperMini's in-package flash).
 */
class EspRomFlasher(
    private val stream: RomByteStream,
    private val config: EspRomConfig = EspRomConfig(),
    private val isCancelled: () -> Boolean = { false },
    private val flashSizeBytes: Long = EspFlashLayout.FLASH_SIZE_BYTES,
) {

    private val progressListeners = mutableListOf<(EspFlashProgress) -> Unit>()

    /**
     * Writes [image] at [offset] and verifies it with the ROM's MD5 command.
     *
     * @return the verified MD5 and byte count.
     * @throws EspSyncException when the chip never answered SYNC.
     * @throws EspTimeoutException / [EspStatusException] on a failed command.
     * @throws EspMd5MismatchException when the flash does not match.
     * @throws EspCancelledException when [isCancelled] turned true.
     */
    fun flash(
        image: ByteArray,
        offset: Long,
        onProgress: (EspFlashProgress) -> Unit = {},
    ): EspFlashSummary {
        require(image.isNotEmpty()) { "image must not be empty" }
        require(offset >= 0 && offset % 4 == 0L) { "offset must be 4-byte aligned" }

        val padded = padTo4(image)
        val total = padded.size.toLong()
        val session = EspRomSession(stream, config, isCancelled)

        notify(onProgress, EspFlashPhase.SYNCING, 0, total, "Entering the ROM loader")
        session.sync()

        notify(
            onProgress,
            EspFlashPhase.CONFIGURING,
            0,
            total,
            "Configuring SPI flash and watchdog",
        )
        // esptool's ESP32C3ROM._post_connect(): USB-JTAG does not reset the
        // RTC/SWD watchdogs, so they must be disabled before a long erase.
        session.disableWatchdogs()
        session.spiAttach()
        session.setFlashParameters(flashSizeBytes)

        notify(
            onProgress,
            EspFlashPhase.ERASING,
            0,
            total,
            "Erasing $total bytes (this can take up to a minute)",
        )
        // FLASH_BEGIN blocks until the ROM's erase finishes. Its timeout is
        // scaled by ERASE_REGION_TIMEOUT_PER_MB inside the session.
        val blockCount = session.flashBegin(total, offset)

        var seq = 0
        var written = 0L
        while (seq < blockCount) {
            checkCancelled()
            val from = seq * EspFlashLayout.BLOCK_SIZE
            val to = minOf(from + EspFlashLayout.BLOCK_SIZE, padded.size)
            val block = ByteArray(EspFlashLayout.BLOCK_SIZE) { 0xFF.toByte() }
            padded.copyInto(block, destinationOffset = 0, startIndex = from, endIndex = to)
            session.flashBlock(block, seq)
            written = to.toLong()
            notify(
                onProgress,
                EspFlashPhase.WRITING,
                written,
                total,
                "Writing ${written.coerceAtMost(total)} of $total bytes",
            )
            seq += 1
        }

        notify(onProgress, EspFlashPhase.VERIFYING, total, total, "Verifying MD5")
        val expected = md5Hex(padded)
        val actual = session.flashMd5(offset, total)
        if (actual != expected) {
            throw EspMd5MismatchException(expected, actual)
        }

        notify(onProgress, EspFlashPhase.REBOOTING, total, total, "Rebooting the board")
        try {
            session.flashEnd(reboot = true)
        } catch (_: EspTimeoutException) {
            // The ROM resets as soon as it accepts FLASH_END, so the reply can
            // be lost when USB-Serial/JTAG disappears. esptool does not send
            // FLASH_END at all for the ROM loader for the same reason; the
            // MD5 check above already proved the write.
        } catch (_: java.io.IOException) {
            // Same as above: the device may already be gone.
        }

        notify(
            onProgress,
            EspFlashPhase.DONE,
            total,
            total,
            "Flashed and verified (MD5 $actual)",
        )
        return EspFlashSummary(md5Hex = actual, bytesWritten = total, targetOffset = offset)
    }

    private fun checkCancelled() {
        if (isCancelled()) throw EspCancelledException()
    }

    private fun notify(
        listener: (EspFlashProgress) -> Unit,
        phase: EspFlashPhase,
        bytes: Long,
        total: Long,
        note: String,
    ) {
        listener(EspFlashProgress(phase = phase, bytesWritten = bytes, totalBytes = total, note = note))
    }

    private fun padTo4(image: ByteArray): ByteArray {
        val remainder = image.size % 4
        if (remainder == 0) return image
        val padded = image.copyOf(image.size + (4 - remainder))
        for (index in image.size until padded.size) {
            padded[index] = 0xFF.toByte()
        }
        return padded
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0xF])
        }
        return out.toString().lowercase(Locale.US)
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
