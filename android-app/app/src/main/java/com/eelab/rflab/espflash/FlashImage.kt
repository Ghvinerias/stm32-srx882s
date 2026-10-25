package com.eelab.rflab.espflash

import java.security.MessageDigest
import java.util.Locale

/**
 * A firmware image that may be written through the ROM loader.
 *
 * Only two shapes are accepted, because they are the only ones whose target
 * address is known without reading the device's partition table:
 *
 * - [Merged] — the full 4 MiB `*-merged.bin`, written at `0x0`. This is the
 *   primary path: it contains bootloader, partition table, otadata and both
 *   app slots, and is what the project's web flasher writes.
 * - [App] — a `*.bin` application image written to `app0` at `0x10000`, the
 *   offset from the project's `PartitionScheme=no_fs` table (README §4). This
 *   is an advanced option: it does not touch otadata, so a unit whose
 *   bootloader currently selects `app1` keeps running `app1`.
 */
sealed interface FlashImage {

    /** Display name of the picked/downloaded file. */
    val name: String

    /** Complete image bytes. */
    val bytes: ByteArray

    /** Flash address the image is written to. */
    val offset: Long

    /** True for the full 4 MiB image (offset 0). */
    val isMerged: Boolean

    /** Full merged image at `0x0`; see the class KDoc. */
    data class Merged(
        override val name: String,
        override val bytes: ByteArray,
    ) : FlashImage {
        override val offset: Long get() = 0L
        override val isMerged: Boolean get() = true
    }

    /** Application image written to `app0`; see the class KDoc. */
    data class App(
        override val name: String,
        override val bytes: ByteArray,
    ) : FlashImage {
        override val offset: Long get() = EspFlashLayout.APP0_OFFSET
        override val isMerged: Boolean get() = false
    }
}

/** Result of validating a user-picked (or downloaded) image. */
sealed interface FlashImageClassification {

    data class Accepted(val image: FlashImage) : FlashImageClassification

    /** The file is not a usable image; [message] is user-facing. */
    data class Rejected(val message: String) : FlashImageClassification
}

/**
 * Bytes supplied by another flow instead of the document picker.
 *
 * The concurrent "update from the flash server" path downloads and verifies
 * an image; it can hand the result to the USB flasher through
 * `UsbFlashViewModel.offerImage(FlashImageSource(...))` without going through
 * SAF. The flasher still re-runs the same classification, so an app image is
 * never mistaken for a merged one.
 */
data class FlashImageSource(
    val name: String,
    val bytes: ByteArray,
)

/**
 * Image sanity checks shared by the picker and any programmatic source.
 *
 * Mirrors the rules already used by the OTA flow (the merged image is
 * rejected there), but inverted: here a merged image is the *primary* target.
 */
object FlashImages {

    /** `0xE9` plus exactly 4 MiB identifies this project's merged image. */
    fun classify(name: String, bytes: ByteArray): FlashImageClassification {
        if (bytes.isEmpty()) {
            return FlashImageClassification.Rejected("$name is empty")
        }
        if (bytes[0] != EspFlashLayout.IMAGE_MAGIC.toByte()) {
            return FlashImageClassification.Rejected(
                "$name does not start with the ESP image magic 0xE9 — it is " +
                    "not an ESP32 firmware image",
            )
        }
        return when {
            bytes.size == EspFlashLayout.MERGED_IMAGE_SIZE ->
                FlashImageClassification.Accepted(FlashImage.Merged(name, bytes))

            bytes.size <= EspFlashLayout.APP_SLOT_SIZE ->
                FlashImageClassification.Accepted(FlashImage.App(name, bytes))

            else -> FlashImageClassification.Rejected(
                "$name is $bytes.size bytes — neither a 4 MiB merged image " +
                    "(${EspFlashLayout.MERGED_IMAGE_SIZE} bytes) nor an app " +
                    "image (at most ${EspFlashLayout.APP_SLOT_SIZE} bytes)",
            )
        }
    }

    /** Lowercase SHA-256 hex, as shown to the user before flashing. */
    fun sha256Hex(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lowercase hex of [bytes], without separators. */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0xF])
        }
        return out.toString().lowercase(Locale.US)
    }

    private const val HEX = "0123456789abcdef"
}
