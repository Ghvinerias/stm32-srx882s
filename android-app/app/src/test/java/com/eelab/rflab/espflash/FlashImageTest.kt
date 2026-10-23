package com.eelab.rflab.espflash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image classification and hashing tests for [FlashImages].
 *
 * The two accepted shapes are the ones the app can address without reading
 * the device's partition table: the 4 MiB merged image at `0x0` and an app
 * image at `app0` (`0x10000`, from the project's `no_fs` scheme).
 */
class FlashImageTest {

    private fun withMagic(size: Int): ByteArray = ByteArray(size).also {
        it[0] = EspFlashLayout.IMAGE_MAGIC.toByte()
    }

    @Test
    fun `rejects an empty file`() {
        val result = FlashImages.classify("empty.bin", ByteArray(0))

        assertTrue(result is FlashImageClassification.Rejected)
        assertTrue((result as FlashImageClassification.Rejected).message.contains("empty"))
    }

    @Test
    fun `rejects a file without the esp image magic`() {
        val bytes = withMagic(0x100)
        bytes[0] = 0x00

        val result = FlashImages.classify("not-esp.bin", bytes)

        assertTrue(result is FlashImageClassification.Rejected)
        assertTrue(
            (result as FlashImageClassification.Rejected).message.contains("0xE9"),
        )
    }

    @Test
    fun `accepts a four mebibyte image as merged at offset zero`() {
        val bytes = withMagic(EspFlashLayout.MERGED_IMAGE_SIZE)

        val image = (FlashImages.classify("merged.bin", bytes) as FlashImageClassification.Accepted)
            .image

        assertTrue(image.isMerged)
        assertEquals(0L, image.offset)
        assertEquals("merged.bin", image.name)
        assertEquals(bytes.size, image.bytes.size)
    }

    @Test
    fun `accepts an app image at the app0 offset`() {
        val image = (
            FlashImages.classify("rflab.bin", withMagic(0x1000)) as
                FlashImageClassification.Accepted
            ).image

        assertEquals(false, image.isMerged)
        assertEquals(EspFlashLayout.APP0_OFFSET, image.offset)
        assertEquals(0x1_0000L, image.offset)
    }

    @Test
    fun `accepts an app image exactly as large as the slot`() {
        val image = (
            FlashImages.classify("slot.bin", withMagic(EspFlashLayout.APP_SLOT_SIZE)) as
                FlashImageClassification.Accepted
            ).image

        assertEquals(false, image.isMerged)
        assertEquals(EspFlashLayout.APP_SLOT_SIZE, image.bytes.size)
    }

    @Test
    fun `rejects an image larger than the app slot that is not the merged image`() {
        val bytes = withMagic(EspFlashLayout.APP_SLOT_SIZE + 1)

        val result = FlashImages.classify("odd.bin", bytes)

        assertTrue(result is FlashImageClassification.Rejected)
        assertTrue(
            (result as FlashImageClassification.Rejected).message.contains("2031616"),
        )
    }

    @Test
    fun `rejects a two mebibyte image`() {
        val result = FlashImages.classify("two-mib.bin", withMagic(0x20_0000))

        assertTrue(result is FlashImageClassification.Rejected)
    }

    @Test
    fun `uses lowercase hex for the sha256`() {
        // Known SHA-256 of "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FlashImages.sha256Hex("abc".toByteArray()),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FlashImages.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `formats bytes as lowercase hex`() {
        assertEquals("00ff10ab", FlashImages.hex(byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0xAB.toByte())))
        assertEquals("", FlashImages.hex(ByteArray(0)))
    }

    @Test
    fun `placement text names the target`() {
        val merged = UsbImageInfoFor(FlashImage.Merged("m.bin", withMagic(0x10)))
        val app = UsbImageInfoFor(FlashImage.App("a.bin", withMagic(0x10)))

        assertTrue(merged.placement.contains("0x0"))
        assertTrue(merged.placement.contains("merged"))
        assertTrue(app.placement.contains("0x10000"))
        assertTrue(app.placement.contains("app0"))
    }

    /**
     * Builds the display summary the UI uses, without depending on Compose:
     * the same mapping [com.eelab.rflab.ui.firmware.UsbFlashUiState.infoFor]
     * performs.
     */
    private fun UsbImageInfoFor(image: FlashImage): com.eelab.rflab.ui.firmware.UsbImageInfo =
        com.eelab.rflab.ui.firmware.UsbImageInfo(
            name = image.name,
            sizeBytes = image.bytes.size,
            sha256Hex = FlashImages.sha256Hex(image.bytes),
            isMerged = image.isMerged,
            offset = image.offset,
        )
}
