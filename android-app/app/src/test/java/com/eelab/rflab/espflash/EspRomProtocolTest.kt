package com.eelab.rflab.espflash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-level tests for [EspRomSession].
 *
 * Each expectation is checked against the packet the client actually put on
 * the wire (decoded by [FakeEspRom]) and against the constants esptool 5.3.1
 * uses: `esptool/loader.py`, `esptool/cmds.py` and
 * `esptool/targets/esp32c3.py`.
 */
class EspRomProtocolTest {

    private val fake = FakeEspRom()

    private fun config(
        syncAttempts: Int = 7,
        writeBlockAttempts: Int = 3,
    ) = EspRomConfig(
        defaultTimeoutMs = 50,
        syncTimeoutMs = 30,
        syncAttempts = syncAttempts,
        syncDrainTimeoutMs = 2,
        eraseRegionTimeoutPerMbMs = 1_000,
        md5TimeoutPerMbMs = 1_000,
        writeBlockAttempts = writeBlockAttempts,
        responsePollMs = 1,
        maxTimeoutMs = 60_000,
    )

    private fun session(
        config: EspRomConfig = config(),
        cancelled: () -> Boolean = { false },
    ) = EspRomSession(fake, config, cancelled)

    // ---- Checksum ---------------------------------------------------------

    @Test
    fun `checksum starts at the esptool seed`() {
        assertEquals(0xEF, EspRomSession.checksum(ByteArray(0)))
    }

    @Test
    fun `checksum xors every byte into the seed`() {
        // 0xEF ^ 0x00 ^ 0x01 ^ 0x02 = 0xEC
        assertEquals(0xEC, EspRomSession.checksum(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun `checksum of the seed byte is zero`() {
        assertEquals(0x00, EspRomSession.checksum(byteArrayOf(0xEF.toByte())))
    }

    @Test
    fun `checksum accepts an explicit running state`() {
        // 0x30 ^ 0x10 ^ 0x20 = 0x00
        assertEquals(0x00, EspRomSession.checksum(byteArrayOf(0x10, 0x20), state = 0x30))
    }

    // ---- Timeout scaling (esptool timeout_per_mb) -------------------------

    @Test
    fun `erase timeout scales by decimal megabytes`() {
        // 30 s/MB * (4 MiB / 1e6) = 125.82912 s, truncated to milliseconds.
        assertEquals(
            125_829L,
            EspTimeouts.perMb(
                EspTimeouts.ERASE_REGION_TIMEOUT_PER_MB_MS,
                EspFlashLayout.FLASH_SIZE_BYTES,
            ),
        )
    }

    @Test
    fun `md5 timeout scales by decimal megabytes`() {
        // 8 s/MB * 4.194304 MB = 33.554432 s.
        assertEquals(
            33_554L,
            EspTimeouts.perMb(
                EspTimeouts.MD5_TIMEOUT_PER_MB_MS,
                EspFlashLayout.FLASH_SIZE_BYTES,
            ),
        )
    }

    @Test
    fun `timeout never drops below the default`() {
        assertEquals(
            EspTimeouts.DEFAULT_TIMEOUT_MS,
            EspTimeouts.perMb(EspTimeouts.MD5_TIMEOUT_PER_MB_MS, 1_000L),
        )
    }

    @Test
    fun `flash begin uses the erase scaled timeout`() {
        fake.ignore(EspRomCommand.FLASH_BEGIN)
        val session = session()
        session.sync()

        val error = assertThrows(EspTimeoutException::class.java) {
            session.flashBegin(0x10_000L, 0)
        }

        assertEquals("FLASH_BEGIN", error.operation)
        // 1000 ms/MB * 0.065536 MB = 65 ms, above the 50 ms default.
        assertEquals(65L, error.timeoutMs)
    }

    // ---- SYNC -------------------------------------------------------------

    @Test
    fun `sync sends the esptool payload and drains all replies`() {
        session().sync()

        val sync = fake.packetsFor(EspRomCommand.SYNC).single()
        assertArrayEquals(
            byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 },
            sync.payload,
        )
        assertEquals(0L, sync.checksum)
        assertEquals(1, fake.flushCount)
        assertEquals(0, fake.pendingBytes)
    }

    @Test
    fun `sync retries until the rom answers`() {
        fake.ignore(EspRomCommand.SYNC, times = 2)

        session(config(syncAttempts = 4)).sync()

        assertEquals(3, fake.packetsFor(EspRomCommand.SYNC).size)
    }

    @Test
    fun `sync reports failure after every attempt`() {
        fake.ignore(EspRomCommand.SYNC)

        val error = assertThrows(EspSyncException::class.java) {
            session(config(syncAttempts = 2)).sync()
        }

        assertEquals(2, fake.packetsFor(EspRomCommand.SYNC).size)
        assertTrue(error.isSyncFailure())
        assertTrue(error.message!!.contains("2 attempts"))
    }

    @Test
    fun `stale sync replies are skipped by the next command`() {
        fake.syncReplyCount = 12
        fake.registers[0x6000_80ACL] = 0x8000_0000L
        val session = session()
        session.sync()

        assertEquals(0x8000_0000L, session.readReg(0x6000_80ACL))
    }

    // ---- Register access --------------------------------------------------

    @Test
    fun `read reg returns the value in the response header`() {
        fake.registers[0x6000_80ACL] = 0x8000_0000L

        assertEquals(0x8000_0000L, session().readReg(0x6000_80ACL))
        assertEquals(
            0x6000_80ACL,
            EspRomSession.getLe32(fake.packetsFor(EspRomCommand.READ_REG).single().payload, 0),
        )
    }

    @Test
    fun `write reg sends address value mask and delay`() {
        session().writeReg(0x6000_80A8L, 0x50D8_3AA1L, mask = 0xFFFF_FFFFL, delayUs = 10)

        val packet = fake.packetsFor(EspRomCommand.WRITE_REG).single()
        assertEquals(0x6000_80A8L, packet.word(0))
        assertEquals(0x50D8_3AA1L, packet.word(4))
        assertEquals(0xFFFF_FFFFL, packet.word(8))
        assertEquals(10L, packet.word(12))
        assertEquals(0x50D8_3AA1L, fake.registers[0x6000_80A8L])
    }

    @Test
    fun `disable watchdogs matches the esp32c3 sequence`() {
        session().disableWatchdogs()

        assertEquals(
            listOf(
                EspRomCommand.WRITE_REG,
                EspRomCommand.WRITE_REG,
                EspRomCommand.WRITE_REG,
                EspRomCommand.WRITE_REG,
                EspRomCommand.READ_REG,
                EspRomCommand.WRITE_REG,
                EspRomCommand.WRITE_REG,
            ),
            fake.packets.map { it.op },
        )

        val writes = fake.packetsFor(EspRomCommand.WRITE_REG)
        assertEquals(0x6000_80A8L, writes[0].word(0))
        assertEquals(0x50D8_3AA1L, writes[0].word(4))
        assertEquals(0x6000_8090L, writes[1].word(0))
        assertEquals(0x6000_80A8L, writes[2].word(0))
        assertEquals(0L, writes[2].word(4))

        assertEquals(0x6000_80B0L, writes[3].word(0))
        assertEquals(0x8F1D_312AL, writes[3].word(4))

        // Read-modify-write of the SWD auto-feed bit (bit 31).
        assertEquals(0x6000_80ACL, writes[4].word(0))
        assertEquals(0x8000_0000L, writes[4].word(4))

        assertEquals(0x6000_80B0L, writes[5].word(0))
        assertEquals(0L, writes[5].word(4))
    }

    @Test
    fun `watchdog reset arms the rtc watchdog`() {
        session().watchdogReset()

        val writes = fake.packetsFor(EspRomCommand.WRITE_REG)
        assertEquals(0x6000_8094L, writes[1].word(0))
        assertEquals(2_000L, writes[1].word(4))
        assertEquals(0x6000_8090L, writes[2].word(0))
        // (1 << 31) | (5 << 28) | (1 << 8) | 2
        assertEquals(0xD000_0102L, writes[2].word(4))
    }

    // ---- Flash setup ------------------------------------------------------

    @Test
    fun `spi attach sends the eight byte rom form`() {
        session().spiAttach()

        assertArrayEquals(ByteArray(8), fake.spiAttachPayload)
    }

    @Test
    fun `spi set params matches the esptool geometry`() {
        session().setFlashParameters(0x40_0000L)

        assertEquals(
            listOf(0L, 0x40_0000L, 0x1_0000L, 0x1_000L, 0x100L, 0xFFFFL),
            fake.spiSetParams,
        )
    }

    @Test
    fun `flash begin sends five words and returns the block count`() {
        val blocks = session().flashBegin(imageSize = 0x401L, offset = 0x1_0000L)

        assertEquals(2, blocks)
        val packet = fake.packetsFor(EspRomCommand.FLASH_BEGIN).single()
        assertEquals(0x401L, packet.word(0))
        assertEquals(2L, packet.word(4))
        assertEquals(0x400L, packet.word(8))
        assertEquals(0x1_0000L, packet.word(12))
        assertEquals(0L, packet.word(16))
    }

    @Test
    fun `flash begin rejects a misaligned offset`() {
        assertThrows(IllegalArgumentException::class.java) {
            session().flashBegin(imageSize = 0x400L, offset = 0x2L)
        }
        assertFalse(fake.saw(EspRomCommand.FLASH_BEGIN))
    }

    @Test
    fun `flash block sends sequence and xor checksum`() {
        val data = ByteArray(0x400) { (it and 0xFF).toByte() }

        session().flashBlock(data, seq = 5)

        val packet = fake.packetsFor(EspRomCommand.FLASH_DATA).single()
        assertEquals(0x400L, packet.word(0))
        assertEquals(5L, packet.word(4))
        assertEquals(0L, packet.word(8))
        assertEquals(0L, packet.word(12))
        assertArrayEquals(data, packet.payload.copyOfRange(16, 16 + data.size))
        assertEquals(EspRomSession.checksum(data).toLong(), packet.checksum)
    }

    @Test
    fun `flash block rejects an oversized block`() {
        assertThrows(IllegalArgumentException::class.java) {
            session().flashBlock(ByteArray(0x401), seq = 0)
        }
    }

    @Test
    fun `flash block retries once after a timeout`() {
        fake.ignore(EspRomCommand.FLASH_DATA, times = 1)

        session(config(writeBlockAttempts = 3)).flashBlock(ByteArray(0x400), seq = 0)

        assertEquals(2, fake.packetsFor(EspRomCommand.FLASH_DATA).size)
    }

    @Test
    fun `flash block gives up after the configured attempts`() {
        fake.ignore(EspRomCommand.FLASH_DATA)

        assertThrows(EspTimeoutException::class.java) {
            session(config(writeBlockAttempts = 2)).flashBlock(ByteArray(0x400), seq = 0)
        }
        assertEquals(2, fake.packetsFor(EspRomCommand.FLASH_DATA).size)
    }

    @Test
    fun `flash block retries a status error`() {
        fake.flashDataFailuresRemaining = 1

        session(config(writeBlockAttempts = 2)).flashBlock(ByteArray(0x400), seq = 0)

        assertEquals(2, fake.packetsFor(EspRomCommand.FLASH_DATA).size)
    }

    @Test
    fun `flash block surfaces a persistent status error`() {
        fake.fail(EspRomCommand.FLASH_DATA, status = 0x05)

        val error = assertThrows(EspStatusException::class.java) {
            session(config(writeBlockAttempts = 2)).flashBlock(ByteArray(0x400), seq = 0)
        }

        assertEquals("FLASH_DATA", error.operation)
        assertEquals(0x05, error.status)
    }

    @Test
    fun `flash end sends the inverted reboot flag`() {
        val session = session()

        session.flashEnd(reboot = true)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), fake.flashEndPayload)

        session.flashEnd(reboot = false)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), fake.flashEndPayload)
    }

    @Test
    fun `flash md5 requests the range and lowercases the reply`() {
        fake.md5Override = "ABCDEF0123456789ABCDEF0123456789"

        val md5 = session().flashMd5(address = 0x1_0000L, size = 0x1234L)

        assertEquals("abcdef0123456789abcdef0123456789", md5)
        assertEquals(listOf(0x1_0000L to 0x1234L), fake.md5Requests)
        val packet = fake.packetsFor(EspRomCommand.SPI_FLASH_MD5).single()
        assertEquals(0L, packet.word(8))
        assertEquals(0L, packet.word(12))
    }

    @Test
    fun `flash md5 rejects a non hex reply`() {
        fake.md5Override = "Z".repeat(32)

        assertThrows(EspProtocolException::class.java) {
            session().flashMd5(0, 0x100)
        }
    }

    // ---- Errors and cancellation ------------------------------------------

    @Test
    fun `status error becomes esp status exception`() {
        fake.fail(EspRomCommand.SPI_SET_PARAMS, status = 0x06, reason = 0x02)

        val error = assertThrows(EspStatusException::class.java) {
            session().setFlashParameters(0x40_0000L)
        }

        assertEquals("SPI_SET_PARAMS", error.operation)
        assertEquals(0x06, error.status)
        assertEquals(0x02, error.reason)
    }

    @Test
    fun `a timed out command reports its name and timeout`() {
        fake.ignore(EspRomCommand.READ_REG)

        val error = assertThrows(EspTimeoutException::class.java) {
            session().readReg(0x6000_80ACL)
        }

        assertEquals("READ_REG", error.operation)
        assertEquals(50L, error.timeoutMs)
    }

    @Test
    fun `cancellation before sync sends nothing`() {
        assertThrows(EspCancelledException::class.java) {
            session(cancelled = { true }).sync()
        }

        assertTrue(fake.packets.isEmpty())
    }

    @Test
    fun `cancellation between blocks stops the write`() {
        var cancelled = false
        val session = session(cancelled = { cancelled })
        session.sync()
        session.flashBegin(0x800L, 0)

        cancelled = true

        assertThrows(EspCancelledException::class.java) {
            session.flashBlock(ByteArray(0x400), seq = 0)
        }
        assertTrue(fake.packetsFor(EspRomCommand.FLASH_DATA).isEmpty())
    }
}
