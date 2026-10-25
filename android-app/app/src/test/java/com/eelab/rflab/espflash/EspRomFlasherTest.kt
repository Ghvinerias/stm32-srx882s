package com.eelab.rflab.espflash

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for [EspRomFlasher] against [FakeEspRom].
 *
 * These cover the complete esptool `write_flash(no_stub=True)` sequence:
 * SYNC, watchdog disable, SPI setup, FLASH_BEGIN (erase), the block stream,
 * MD5 verification and the reboot.
 */
class EspRomFlasherTest {

    private val fake = FakeEspRom()

    private fun config() = EspRomConfig(
        defaultTimeoutMs = 50,
        syncTimeoutMs = 30,
        syncAttempts = 3,
        syncDrainTimeoutMs = 2,
        eraseRegionTimeoutPerMbMs = 1_000,
        md5TimeoutPerMbMs = 1_000,
        writeBlockAttempts = 3,
        responsePollMs = 1,
        maxTimeoutMs = 60_000,
    )

    private fun flasher(cancelled: () -> Boolean = { false }) =
        EspRomFlasher(fake, config(), cancelled)

    private fun image(size: Int): ByteArray = ByteArray(size) { index ->
        if (index == 0) 0xE9.toByte() else (index and 0xFF).toByte()
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- Happy path -------------------------------------------------------

    @Test
    fun `writes verifies and reboots`() {
        val image = image(0x801)
        val progress = mutableListOf<EspFlashProgress>()

        val summary = flasher().flash(image, offset = 0x1_0000L, onProgress = progress::add)

        val padded = image.copyOf(0x804).also {
            java.util.Arrays.fill(it, image.size, it.size, 0xFF.toByte())
        }
        assertEquals("0x804 bytes are verified", 0x804L, summary.bytesWritten)
        assertEquals(0x1_0000L, summary.targetOffset)
        assertEquals(md5(padded), summary.md5Hex)

        // Flash contains exactly the padded image and erased block tails.
        assertArrayEquals(
            padded,
            fake.flash.copyOfRange(0x1_0000, 0x1_0000 + padded.size),
        )
        assertTrue(
            (0x1_0000 + padded.size until 0x1_0000 + 0xC00)
                .all { fake.flash[it] == 0xFF.toByte() },
        )

        assertEquals(3, fake.packetsFor(EspRomCommand.FLASH_DATA).size)
        assertEquals(listOf(0x1_0000L to 0x804L), fake.md5Requests)
        assertTrue(fake.flashEndRequestedReboot)
    }

    @Test
    fun `sends the commands in esptool order`() {
        flasher().flash(image(0x801), offset = 0x1_0000L)

        val expected = buildList {
            add(EspRomCommand.SYNC)
            // disable watchdogs: 3 WDT writes, then SWD protect/read/write/protect
            repeat(4) { add(EspRomCommand.WRITE_REG) }
            add(EspRomCommand.READ_REG)
            repeat(2) { add(EspRomCommand.WRITE_REG) }
            add(EspRomCommand.SPI_ATTACH)
            add(EspRomCommand.SPI_SET_PARAMS)
            add(EspRomCommand.FLASH_BEGIN)
            repeat(3) { add(EspRomCommand.FLASH_DATA) }
            add(EspRomCommand.SPI_FLASH_MD5)
            add(EspRomCommand.FLASH_END)
        }

        assertEquals(expected, fake.packets.map { it.op })
    }

    @Test
    fun `flash begin describes the padded image and offset`() {
        flasher().flash(image(0x801), offset = 0x1_0000L)

        val begin = fake.packetsFor(EspRomCommand.FLASH_BEGIN).single()
        assertEquals(0x804L, begin.word(0))
        assertEquals(3L, begin.word(4))
        assertEquals(0x400L, begin.word(8))
        assertEquals(0x1_0000L, begin.word(12))
        assertEquals(0L, begin.word(16))
    }

    @Test
    fun `spi setup uses the 4 mib flash geometry`() {
        flasher().flash(image(0x100), offset = 0)
        assertEquals(
            listOf(0L, 0x40_0000L, 0x1_0000L, 0x1_000L, 0x100L, 0xFFFFL),
            fake.spiSetParams,
        )
    }

    @Test
    fun `pads the final block with 0xff`() {
        val image = image(5)

        val summary = flasher().flash(image, offset = 0)

        val padded = image.copyOf(8).also {
            java.util.Arrays.fill(it, image.size, it.size, 0xFF.toByte())
        }
        assertEquals(8L, summary.bytesWritten)
        assertArrayEquals(padded, fake.flash.copyOfRange(0, 8))
        assertTrue((8 until 0x400).all { fake.flash[it] == 0xFF.toByte() })
        assertEquals(md5(padded), summary.md5Hex)
    }

    @Test
    fun `reports the expected progress phases`() {
        val phases = mutableListOf<EspFlashPhase>()
        val written = mutableListOf<Long>()

        flasher().flash(image(0x801), offset = 0x1_0000L) { progress ->
            phases += progress.phase
            written += progress.bytesWritten
        }

        assertEquals(EspFlashPhase.SYNCING, phases.first())
        assertEquals(EspFlashPhase.DONE, phases.last())
        assertTrue(phases.contains(EspFlashPhase.CONFIGURING))
        assertTrue(phases.contains(EspFlashPhase.ERASING))
        assertTrue(phases.contains(EspFlashPhase.VERIFYING))
        assertTrue(phases.contains(EspFlashPhase.REBOOTING))
        assertEquals(3, phases.count { it == EspFlashPhase.WRITING })
        assertEquals(
            listOf(0x400L, 0x800L, 0x804L),
            phases.indices
                .filter { phases[it] == EspFlashPhase.WRITING }
                .map { written[it] },
        )
    }

    @Test
    fun `progress percent spans zero to one hundred`() {
        val progress = EspFlashProgress(
            phase = EspFlashPhase.WRITING,
            bytesWritten = 2_097_152,
            totalBytes = 4_194_304,
        )
        assertEquals(50, progress.percent)
        assertEquals(100, progress.copy(bytesWritten = 4_194_304).percent)
        assertEquals(0, progress.copy(bytesWritten = 0).percent)
        assertEquals(null, progress.copy(totalBytes = 0).percent)
        assertTrue(progress.isActive)
        assertFalse(progress.copy(phase = EspFlashPhase.DONE).isActive)
        assertFalse(progress.copy(phase = EspFlashPhase.IDLE).isActive)
    }

    // ---- Failure paths ----------------------------------------------------

    @Test
    fun `rejects an empty image`() {
        assertThrows(IllegalArgumentException::class.java) {
            flasher().flash(ByteArray(0), offset = 0)
        }
    }

    @Test
    fun `rejects a misaligned offset`() {
        assertThrows(IllegalArgumentException::class.java) {
            flasher().flash(image(4), offset = 2)
        }
    }

    @Test
    fun `md5 mismatch fails the flash`() {
        fake.md5Override = "0".repeat(32)

        val error = assertThrows(EspMd5MismatchException::class.java) {
            flasher().flash(image(0x100), offset = 0)
        }

        assertEquals("0".repeat(32), error.actual)
        assertEquals(md5(image(0x100)), error.expected)
        assertFalse(fake.flashEndPayload != null && fake.flashEndRequestedReboot)
    }

    @Test
    fun `sync failure propagates`() {
        fake.ignore(EspRomCommand.SYNC)

        val error = assertThrows(EspSyncException::class.java) {
            flasher().flash(image(0x10), offset = 0)
        }
        assertTrue(error.isSyncFailure())
        assertFalse(fake.saw(EspRomCommand.SPI_ATTACH))
    }

    @Test
    fun `cancels before the first command`() {
        assertThrows(EspCancelledException::class.java) {
            flasher(cancelled = { true }).flash(image(0x10), offset = 0)
        }
        assertTrue(fake.packets.isEmpty())
    }

    @Test
    fun `cancels between blocks and skips verification`() {
        var cancelled = false

        assertThrows(EspCancelledException::class.java) {
            flasher(cancelled = { cancelled }).flash(image(0x801), offset = 0x1_0000L) {
                if (it.phase == EspFlashPhase.WRITING) cancelled = true
            }
        }

        assertEquals(1, fake.packetsFor(EspRomCommand.FLASH_DATA).size)
        assertFalse(fake.saw(EspRomCommand.SPI_FLASH_MD5))
        assertFalse(fake.saw(EspRomCommand.FLASH_END))
    }

    @Test
    fun `a lost flash end reply still counts as success`() {
        // FLASH_END makes the ROM reboot before it can answer, so the reply
        // may never arrive. The MD5 check already proved the write.
        fake.ignore(EspRomCommand.FLASH_END)

        val summary = flasher().flash(image(0x100), offset = 0)

        assertEquals(md5(image(0x100)), summary.md5Hex)
        assertTrue(fake.saw(EspRomCommand.FLASH_END))
    }

    @Test
    fun `writes blocks with the xor checksum seed`() {
        flasher().flash(image(0x801), offset = 0x1_0000L)

        val blocks = fake.packetsFor(EspRomCommand.FLASH_DATA)
        blocks.forEachIndexed { index, packet ->
            val length = packet.word(0).toInt()
            assertEquals(0x400, length)
            assertEquals(index.toLong(), packet.word(4))
            val data = packet.payload.copyOfRange(16, 16 + length)
            assertEquals(EspRomSession.checksum(data).toLong(), packet.checksum)
        }
    }
}
