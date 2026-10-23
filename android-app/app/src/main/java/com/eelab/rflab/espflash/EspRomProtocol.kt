package com.eelab.rflab.espflash

import java.util.ArrayDeque
import java.util.Locale

/**
 * Wire constants of the ESP32 ROM serial bootloader.
 *
 * Sources, all from esptool 5.3.1 (`esptool/loader.py`, `esptool/cmds.py`,
 * `esptool/targets/esp32.py`, `esptool/targets/esp32c3.py`):
 *
 * - command numbers: `ESPLoader.ESP_CMDS`;
 * - `FLASH_WRITE_SIZE` = `0x400`, `FLASH_SECTOR_SIZE` = `0x1000`,
 *   `ESP_IMAGE_MAGIC` = `0xE9`, `ESP_CHECKSUM_MAGIC` = `0xEF`;
 * - the 4 MiB flash size and app slots come from this project's `no_fs`
 *   partition scheme (README §4) and the shipped merged image.
 */
object EspFlashLayout {

    /** First byte of an ESP32 application image / merged image. */
    const val IMAGE_MAGIC: Int = 0xE9

    /** Seed of the ROM's XOR checksum (`ESPLoader.ESP_CHECKSUM_MAGIC`). */
    const val CHECKSUM_SEED: Int = 0xEF

    /** ROM `FLASH_DATA` payload size (`ESPLoader.FLASH_WRITE_SIZE`). */
    const val BLOCK_SIZE: Int = 0x400

    /** NOR flash erase sector (`ESPLoader.FLASH_SECTOR_SIZE`). */
    const val SECTOR_SIZE: Int = 0x1000

    /** Size of the C3 SuperMini's in-package flash (4 MiB). */
    const val FLASH_SIZE_BYTES: Long = 0x40_0000L

    /** Size of `*-merged.bin` (the whole 4 MiB flash image). */
    const val MERGED_IMAGE_SIZE: Int = 0x40_0000

    /** `app0` offset from the project's `PartitionScheme=no_fs` table. */
    const val APP0_OFFSET: Long = 0x1_0000L

    /** Size of each application slot in the `no_fs` table (`0x1F0000`). */
    const val APP_SLOT_SIZE: Int = 0x1F_0000
}

/** ROM command numbers (`ESPLoader.ESP_CMDS`). */
internal object EspRomCommand {
    const val FLASH_BEGIN = 0x02
    const val FLASH_DATA = 0x03
    const val FLASH_END = 0x04
    const val SYNC = 0x08
    const val WRITE_REG = 0x09
    const val READ_REG = 0x0A
    const val SPI_SET_PARAMS = 0x0B
    const val SPI_ATTACH = 0x0D
    const val SPI_FLASH_MD5 = 0x13
}

/**
 * Timeout rules, copied from `esptool/loader.py`:
 *
 * ```
 * DEFAULT_TIMEOUT              = 3 s
 * MAX_TIMEOUT                  = 2 * CHIP_ERASE_TIMEOUT (240 s)
 * SYNC_TIMEOUT                 = 0.1 s
 * MD5_TIMEOUT_PER_MB           = 8 s
 * ERASE_REGION_TIMEOUT_PER_MB  = 30 s
 * ```
 *
 * `timeout_per_mb()` scales by decimal megabytes (`size / 1e6`), so a 4 MiB
 * (4,194,304 byte) erase gets 30 * 4.194304 = 125.829 s, not 120 s.
 * The result has a floor of [DEFAULT_TIMEOUT_MS].
 */
object EspTimeouts {

    const val DEFAULT_TIMEOUT_MS: Long = 3_000L
    const val MAX_TIMEOUT_MS: Long = 240_000L
    const val SYNC_TIMEOUT_MS: Long = 100L
    const val MD5_TIMEOUT_PER_MB_MS: Long = 8_000L
    const val ERASE_REGION_TIMEOUT_PER_MB_MS: Long = 30_000L

    /** esptool's `timeout_per_mb`, truncated to whole milliseconds. */
    fun perMb(secondsPerMbMs: Long, sizeBytes: Long, defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS): Long {
        val scaled = secondsPerMbMs.toDouble() * (sizeBytes.toDouble() / 1_000_000.0)
        return maxOf(defaultTimeoutMs.toDouble(), scaled).toLong()
    }
}

/**
 * Tunables for [EspRomSession]. The defaults mirror esptool 5.3.1; tests use
 * much smaller values so timeout paths execute instantly.
 *
 * @property defaultTimeoutMs response timeout for ordinary commands.
 * @property syncTimeoutMs response timeout for one SYNC attempt (0.1 s).
 * @property syncAttempts connect attempts (esptool `connect_attempts` = 7).
 * @property syncResponseCount responses the C3 ROM emits per SYNC (esptool
 *   reads one, then drains 7 more).
 * @property syncDrainTimeoutMs per-response timeout while draining those
 *   extra SYNC replies.
 * @property eraseRegionTimeoutPerMbMs `FLASH_BEGIN` timeout scaling.
 * @property md5TimeoutPerMbMs `SPI_FLASH_MD5` timeout scaling.
 * @property writeBlockAttempts `FLASH_DATA` transmissions per block (3).
 * @property responsePollMs maximum single blocking read; short so
 *   cancellation and per-command deadlines stay responsive.
 * @property maxTimeoutMs cap applied to every command timeout.
 */
data class EspRomConfig(
    val defaultTimeoutMs: Long = EspTimeouts.DEFAULT_TIMEOUT_MS,
    val syncTimeoutMs: Long = EspTimeouts.SYNC_TIMEOUT_MS,
    val syncAttempts: Int = 7,
    val syncResponseCount: Int = 8,
    val syncDrainTimeoutMs: Long = 20L,
    val eraseRegionTimeoutPerMbMs: Long = EspTimeouts.ERASE_REGION_TIMEOUT_PER_MB_MS,
    val md5TimeoutPerMbMs: Long = EspTimeouts.MD5_TIMEOUT_PER_MB_MS,
    val writeBlockAttempts: Int = 3,
    val responsePollMs: Long = 200L,
    val maxTimeoutMs: Long = EspTimeouts.MAX_TIMEOUT_MS,
) {
    init {
        require(defaultTimeoutMs > 0)
        require(syncTimeoutMs > 0)
        require(syncAttempts > 0)
        require(syncResponseCount >= 1)
        require(syncDrainTimeoutMs >= 0)
        require(writeBlockAttempts > 0)
        require(responsePollMs in 1..10_000)
    }
}

/** One decoded ROM response frame (`<BBHI` header plus payload). */
internal data class EspResponse(
    val op: Int,
    val value: Long,
    /** Bytes after the 8-byte header: status bytes, then any response data. */
    val data: ByteArray,
)

/**
 * Synchronous client for the ESP32-C3 ROM bootloader protocol.
 *
 * The call sequence used by [EspRomFlasher] is the one esptool's
 * `write_flash(..., no_stub=True)` performs (`esptool/cmds.py`):
 *
 * 1. `SYNC` (`07 07 12 20` + 32 × `55`);
 * 2. disable the RTC/SWD watchdogs (`ESP32C3ROM.disable_watchdogs`, required
 *    because USB-Serial/JTAG does not reset them and they can fire while
 *    flash is being erased);
 * 3. `SPI_ATTACH` (8-byte ROM form: 4-byte value + 4 reserved/legacy bytes);
 * 4. `SPI_SET_PARAMS` (fl_id, total size, block, sector, page, status mask);
 * 5. `FLASH_BEGIN` (erase happens here, so its timeout scales with size);
 * 6. one `FLASH_DATA` per 0x400-byte block, XOR checksum seeded 0xEF;
 * 7. `SPI_FLASH_MD5` over the written range, 32 ASCII hex bytes;
 * 8. `FLASH_END` with `reboot=0` (reboot; `1` would stay in the bootloader).
 *
 * No flasher-stub upload is needed: `FLASH_BEGIN/DATA/END` and
 * `SPI_FLASH_MD5` are ROM commands on ESP32 and later, and compressed writes
 * (`FLASH_DEFL_*`) are stub-only (`stub_and_esp32_function_only` in
 * `loader.py`), so this client deliberately writes uncompressed blocks.
 *
 * All methods block; callers run them on an IO dispatcher and pass
 * [isCancelled] so a Cancel tap is noticed between command polls and blocks.
 */
class EspRomSession(
    private val stream: RomByteStream,
    private val config: EspRomConfig = EspRomConfig(),
    private val isCancelled: () -> Boolean = { false },
) {

    private val decoder = SlipDecoder()

    private val readBuffer = ByteArray(READ_BUFFER_BYTES)

    private val pendingFrames = ArrayDeque<ByteArray>()

    /** Sends SYNC and consumes the ROM's eight replies; throws on failure. */
    fun sync() {
        stream.flushInput()
        var last: EspTimeoutException? = null
        repeat(config.syncAttempts) {
            checkCancelled()
            try {
                sendPacket(EspRomCommand.SYNC, SYNC_DATA, checksum = 0)
                awaitResponse(EspRomCommand.SYNC, config.syncTimeoutMs)
                drainSyncReplies()
                return
            } catch (timeout: EspTimeoutException) {
                last = timeout
            }
        }
        throw EspSyncException(
            "The ROM loader did not answer SYNC after ${config.syncAttempts} attempts",
            last,
        )
    }

    /** Reads a 32-bit register (`READ_REG`, value in the response header). */
    fun readReg(address: Long): Long {
        val response = command(EspRomCommand.READ_REG, le32(address))
        return response.value and 0xFFFF_FFFFL
    }

    /** Writes a 32-bit register, optionally masked, like esptool's `write_reg`. */
    fun writeReg(
        address: Long,
        value: Long,
        mask: Long = 0xFFFF_FFFFL,
        delayUs: Long = 0,
    ) {
        command(
            EspRomCommand.WRITE_REG,
            le32(address) + le32(value) + le32(mask) + le32(delayUs),
        )
    }

    /**
     * Disables the RTC watchdog and enables SWD auto-feed, exactly as
     * `ESP32C3ROM.disable_watchdogs()` does when `uses_usb_jtag_serial()`.
     */
    fun disableWatchdogs() {
        writeReg(RTC_CNTL_WDTWPROTECT_REG, RTC_CNTL_WDT_WKEY)
        writeReg(RTC_CNTL_WDTCONFIG0_REG, 0)
        writeReg(RTC_CNTL_WDTWPROTECT_REG, 0)

        writeReg(RTC_CNTL_SWD_WPROTECT_REG, RTC_CNTL_SWD_WKEY)
        val autoFeed = readReg(RTC_CNTL_SWD_CONF_REG) or RTC_CNTL_SWD_AUTO_FEED_EN
        writeReg(RTC_CNTL_SWD_CONF_REG, autoFeed)
        writeReg(RTC_CNTL_SWD_WPROTECT_REG, 0)
    }

    /**
     * Arms the RTC watchdog so the chip resets shortly after the call
     * (`ESP32C3ROM.watchdog_reset`), the USB-JTAG-safe alternative to a
     * DTR/RTS hard reset. Not needed on the normal path — `FLASH_END` asks the
     * ROM to reboot — but kept available for a board left in the loader.
     */
    fun watchdogReset() {
        writeReg(RTC_CNTL_WDTWPROTECT_REG, RTC_CNTL_WDT_WKEY)
        writeReg(RTC_CNTL_WDTCONFIG1_REG, 2_000)
        writeReg(
            RTC_CNTL_WDTCONFIG0_REG,
            (1L shl 31) or (5L shl 28) or (1L shl 8) or 2L,
        )
        writeReg(RTC_CNTL_WDTWPROTECT_REG, 0)
    }

    /**
     * `SPI_ATTACH` with the default SPI pins.
     *
     * The ROM form adds four bytes after the value (esptool's
     * `flash_spi_attach`: `is_legacy=0` plus three reserved zeroes); the stub
     * form omits them, which is why this is not shared with a stub fallback.
     */
    fun spiAttach() {
        command(
            EspRomCommand.SPI_ATTACH,
            le32(0) + byteArrayOf(0, 0, 0, 0),
        )
    }

    /**
     * `SPI_SET_PARAMS` for [totalSize] bytes, with esptool's hard-coded
     * geometry: 64 KiB block, 4 KiB sector, 256-byte page, status mask
     * `0xFFFF` (`ESPLoader.flash_set_parameters`).
     */
    fun setFlashParameters(totalSize: Long) {
        command(
            EspRomCommand.SPI_SET_PARAMS,
            le32(0) + le32(totalSize) + le32(64L * 1024) + le32(4L * 1024) +
                le32(256) + le32(0xFFFF),
        )
    }

    /**
     * Starts a flash write and returns the number of 0x400-byte blocks.
     *
     * The ROM erases before responding, so the timeout scales with
     * [imageSize] (`ERASE_REGION_TIMEOUT_PER_MB`). The five-word parameter
     * block is the extended ROM format esptool sends for every chip except
     * ESP32/ESP8266; the last word is the "encrypted write" flag (always 0
     * here).
     */
    fun flashBegin(imageSize: Long, offset: Long): Int {
        require(imageSize > 0) { "imageSize must be positive" }
        require(offset >= 0 && offset % 4 == 0L) { "offset must be 4-byte aligned" }
        val numBlocks = (imageSize + EspFlashLayout.BLOCK_SIZE - 1) / EspFlashLayout.BLOCK_SIZE
        require(numBlocks <= Int.MAX_VALUE) { "image is too large" }

        val timeout = minOf(
            EspTimeouts.perMb(
                config.eraseRegionTimeoutPerMbMs,
                imageSize,
                config.defaultTimeoutMs,
            ),
            config.maxTimeoutMs,
        )
        command(
            EspRomCommand.FLASH_BEGIN,
            le32(imageSize) + le32(numBlocks) + le32(EspFlashLayout.BLOCK_SIZE.toLong()) +
                le32(offset) + le32(0),
            timeoutMs = timeout,
        )
        return numBlocks.toInt()
    }

    /**
     * Writes one block. [data] may be shorter than the declared block size
     * only if the caller has not padded it; the flasher always pads to
     * `0x400` because the ROM expects full blocks. The command's sequence
     * number determines the address (`offset + seq * 0x400`), so retries must
     * reuse the same [seq] and bytes — this method does that internally up to
     * `writeBlockAttempts` times, like esptool's `flash_block`.
     */
    fun flashBlock(data: ByteArray, seq: Int) {
        require(data.isNotEmpty() && data.size <= EspFlashLayout.BLOCK_SIZE) {
            "block must be 1..${EspFlashLayout.BLOCK_SIZE} bytes"
        }
        var last: EspFlashException? = null
        repeat(config.writeBlockAttempts) {
            checkCancelled()
            try {
                command(
                    EspRomCommand.FLASH_DATA,
                    le32(data.size.toLong()) + le32(seq.toLong()) + le32(0) + le32(0) + data,
                    checksum = checksum(data),
                )
                return
            } catch (timeout: EspTimeoutException) {
                last = timeout
            } catch (status: EspStatusException) {
                last = status
            }
        }
        throw last ?: EspFlashException("FLASH_DATA failed without a cause")
    }

    /**
     * Leaves flash mode. `FLASH_END` takes `int(not reboot)`: `0` reboots into
     * the application, `1` stays in the ROM loader (`ESPLoader.flash_finish`).
     */
    fun flashEnd(reboot: Boolean) {
        command(
            EspRomCommand.FLASH_END,
            le32(if (reboot) 0 else 1),
        )
    }

    /**
     * `SPI_FLASH_MD5` over [address]..[address]+[size].
     *
     * The ROM answers with 32 ASCII hex characters inside the response
     * payload (after them come the two status bytes and two reserved bytes);
     * esptool asks for `resp_data_len=32` and decodes UTF-8.
     */
    fun flashMd5(address: Long, size: Long): String {
        val timeout = minOf(
            EspTimeouts.perMb(config.md5TimeoutPerMbMs, size, config.defaultTimeoutMs),
            config.maxTimeoutMs,
        )
        val response = command(
            EspRomCommand.SPI_FLASH_MD5,
            le32(address) + le32(size) + le32(0) + le32(0),
            timeoutMs = timeout,
            responseDataLength = MD5_ASCII_LENGTH,
        )
        require(response.data.size >= MD5_ASCII_LENGTH) {
            "MD5 response is too short: ${response.data.size} bytes"
        }
        val text = response.data.copyOfRange(0, MD5_ASCII_LENGTH)
            .toString(Charsets.US_ASCII)
            .lowercase(Locale.US)
        if (text.length != MD5_ASCII_LENGTH || text.any { it !in HEX_DIGITS }) {
            throw EspProtocolException("The ROM returned a malformed MD5: '$text'")
        }
        return text
    }

    // --- Command plumbing ---------------------------------------------------

    private fun command(
        op: Int,
        data: ByteArray,
        checksum: Int = 0,
        timeoutMs: Long = config.defaultTimeoutMs,
        responseDataLength: Int = 0,
    ): EspResponse {
        checkCancelled()
        sendPacket(op, data, checksum)
        val response = awaitResponse(op, minOf(timeoutMs, config.maxTimeoutMs))
        validateStatus(op, response, responseDataLength)
        return response
    }

    private fun sendPacket(op: Int, data: ByteArray, checksum: Int) {
        val packet = ByteArray(8 + data.size)
        packet[0] = 0
        packet[1] = op.toByte()
        putLe16(packet, 2, data.size)
        putLe32(packet, 4, checksum.toLong() and 0xFFFF_FFFFL)
        data.copyInto(packet, 8)
        stream.write(Slip.encode(packet))
    }

    private fun awaitResponse(op: Int, timeoutMs: Long): EspResponse {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val frame = nextFrame(deadline)
                ?: throw EspTimeoutException(opName(op), timeoutMs)
            val response = parseResponse(frame) ?: continue
            if (response.op == op) return response
            // A stale reply (e.g. an extra SYNC) is skipped, exactly as
            // esptool's `command()` ignores frames for another operation.
        }
    }

    private fun nextFrame(deadlineNanos: Long): ByteArray? {
        while (true) {
            pendingFrames.pollFirst()?.let { return it }
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) return null
            checkCancelled()
            val waitMs = remainingMs.coerceAtMost(config.responsePollMs).coerceAtLeast(1L)
            val read = stream.read(readBuffer, waitMs.toInt())
            if (read > 0) {
                pendingFrames.addAll(decoder.feed(readBuffer, 0, read))
            }
        }
    }

    /**
     * Parses the 8-byte response header. Frames shorter than the header or
     * with a direction byte other than `0x01` are ignored, mirroring
     * esptool's `command()` loop.
     */
    private fun parseResponse(frame: ByteArray): EspResponse? {
        if (frame.size < 8) return null
        if ((frame[0].toInt() and 0xFF) != RESPONSE_DIRECTION) return null
        val op = frame[1].toInt() and 0xFF
        val value = getLe32(frame, 4)
        return EspResponse(op = op, value = value, data = frame.copyOfRange(8, frame.size))
    }

    private fun validateStatus(op: Int, response: EspResponse, responseDataLength: Int) {
        if (response.data.size < responseDataLength + STATUS_BYTES_LENGTH) {
            throw EspProtocolException(
                "Short ${opName(op)} response: ${response.data.size} bytes",
            )
        }
        val status = response.data[responseDataLength].toInt() and 0xFF
        if (status != 0) {
            val reason = response.data[responseDataLength + 1].toInt() and 0xFF
            throw EspStatusException(opName(op), status, reason)
        }
    }

    /**
     * esptool reads one SYNC reply, then calls `command()` seven more times
     * without sending anything to consume the ROM's eight replies. Missing
     * extra replies (a stub, or a ROM variant) are tolerated.
     */
    private fun drainSyncReplies() {
        repeat(config.syncResponseCount - 1) {
            try {
                awaitResponse(EspRomCommand.SYNC, config.syncDrainTimeoutMs)
            } catch (_: EspTimeoutException) {
                // No more replies; the first one was enough to prove SYNC.
            }
        }
    }

    private fun checkCancelled() {
        if (isCancelled()) throw EspCancelledException()
    }

    companion object {
        private const val READ_BUFFER_BYTES = 4096
        private const val RESPONSE_DIRECTION = 0x01
        private const val STATUS_BYTES_LENGTH = 2
        private const val MD5_ASCII_LENGTH = 32

        private val HEX_DIGITS = ('0'..'9') + ('a'..'f')

        /**
         * SYNC argument (`ESPLoader.sync`): `07 07 12 20` followed by 32
         * bytes of `0x55`.
         */
        internal val SYNC_DATA: ByteArray =
            byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }

        // ESP32C3ROM watchdog registers (targets/esp32c3.py).
        internal const val RTC_CNTL_BASE_REG: Long = 0x6000_8000L
        internal const val RTC_CNTL_WDTCONFIG0_REG: Long = RTC_CNTL_BASE_REG + 0x90
        internal const val RTC_CNTL_WDTCONFIG1_REG: Long = RTC_CNTL_BASE_REG + 0x94
        internal const val RTC_CNTL_WDTWPROTECT_REG: Long = RTC_CNTL_BASE_REG + 0xA8
        internal const val RTC_CNTL_WDT_WKEY: Long = 0x50D8_3AA1L
        internal const val RTC_CNTL_SWD_CONF_REG: Long = RTC_CNTL_BASE_REG + 0xAC
        internal const val RTC_CNTL_SWD_WPROTECT_REG: Long = RTC_CNTL_BASE_REG + 0xB0
        internal const val RTC_CNTL_SWD_WKEY: Long = 0x8F1D_312AL
        internal const val RTC_CNTL_SWD_AUTO_FEED_EN: Long = 1L shl 31

        /**
         * XOR checksum over [data] with esptool's seed, used as the `chk`
         * field of `FLASH_DATA` (`ESPLoader.checksum`).
         */
        fun checksum(data: ByteArray, state: Int = EspFlashLayout.CHECKSUM_SEED): Int {
            var value = state and 0xFF
            for (byte in data) {
                value = value xor (byte.toInt() and 0xFF)
            }
            return value and 0xFF
        }

        private fun opName(op: Int): String = when (op) {
            EspRomCommand.FLASH_BEGIN -> "FLASH_BEGIN"
            EspRomCommand.FLASH_DATA -> "FLASH_DATA"
            EspRomCommand.FLASH_END -> "FLASH_END"
            EspRomCommand.SYNC -> "SYNC"
            EspRomCommand.WRITE_REG -> "WRITE_REG"
            EspRomCommand.READ_REG -> "READ_REG"
            EspRomCommand.SPI_SET_PARAMS -> "SPI_SET_PARAMS"
            EspRomCommand.SPI_ATTACH -> "SPI_ATTACH"
            EspRomCommand.SPI_FLASH_MD5 -> "SPI_FLASH_MD5"
            else -> "command 0x%02X".format(op)
        }

        internal fun le32(value: Long): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

        internal fun putLe16(destination: ByteArray, offset: Int, value: Int) {
            destination[offset] = (value and 0xFF).toByte()
            destination[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        internal fun putLe32(destination: ByteArray, offset: Int, value: Long) {
            destination[offset] = (value and 0xFF).toByte()
            destination[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            destination[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            destination[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        internal fun getLe32(source: ByteArray, offset: Int): Long =
            (source[offset].toLong() and 0xFF) or
                ((source[offset + 1].toLong() and 0xFF) shl 8) or
                ((source[offset + 2].toLong() and 0xFF) shl 16) or
                ((source[offset + 3].toLong() and 0xFF) shl 24)
    }
}

/** Base type for every failure produced by the ROM protocol layer. */
open class EspFlashException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** No SYNC response after all configured attempts. */
class EspSyncException(message: String, cause: Throwable? = null) : EspFlashException(message, cause)

/** A command did not produce its response before the deadline. */
class EspTimeoutException(
    val operation: String,
    val timeoutMs: Long,
) : EspFlashException("$operation timed out after ${timeoutMs} ms")

/** The ROM answered with a non-zero status byte. */
class EspStatusException(
    val operation: String,
    val status: Int,
    val reason: Int,
) : EspFlashException(
    "$operation failed (status=0x%02X, reason=0x%02X)".format(status, reason),
)

/** The response bytes do not follow the ROM protocol. */
class EspProtocolException(message: String, cause: Throwable? = null) :
    EspFlashException(message, cause)

/** The user cancelled a running flash. */
class EspCancelledException : EspFlashException("Flash cancelled")
