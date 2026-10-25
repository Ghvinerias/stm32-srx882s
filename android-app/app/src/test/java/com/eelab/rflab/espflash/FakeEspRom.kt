package com.eelab.rflab.espflash

import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

/**
 * A simulated ESP32-C3 ROM bootloader behind [RomByteStream].
 *
 * The production code only sees a byte pipe, so the whole wire protocol can
 * be exercised on the JVM: this fake decodes the SLIP frames the client
 * writes, updates a simulated flash array and register file, and SLIP-encodes
 * the replies a ROM would send. Reads never block: an empty queue returns `0`,
 * which is the [RomByteStream.read] "timeout expired, no data" contract, so
 * timeout tests finish immediately.
 *
 * Everything the tests assert on is either recorded ([packets], [flash],
 * [registers]) or injectable ([ignoreCounts], [statusFailures],
 * [md5Override], [syncReplyCount]).
 *
 * Test-source only; nothing here ships in the app.
 */
internal class FakeEspRom(
    private val flashSize: Int = EspFlashLayout.FLASH_SIZE_BYTES.toInt(),
) : RomByteStream {

    /** One decoded request frame: the SLIP body without the delimiters. */
    data class Packet(
        /** Command byte, one of [EspRomCommand]. */
        val op: Int,
        /** Payload after the 8-byte request header. */
        val payload: ByteArray,
        /** The `chk` field the host sent. */
        val checksum: Long,
    ) {
        /** Little-endian 32-bit word at [byteOffset] of the payload. */
        fun word(byteOffset: Int): Long = EspRomSession.getLe32(payload, byteOffset)
    }

    /** Every request the client sent, in wire order. */
    val packets = mutableListOf<Packet>()

    /** Simulated NOR flash; starts erased (`0xFF`). */
    val flash = ByteArray(flashSize) { 0xFF.toByte() }

    /** Simulated 32-bit register file, zero-initialised. */
    val registers = mutableMapOf<Long, Long>()

    /**
     * Commands to swallow without a reply, keyed by opcode.
     *
     * A finite count decrements per occurrence, which drives the SYNC-retry
     * and FLASH_DATA-retry paths; [Int.MAX_VALUE] never answers.
     */
    val ignoreCounts = mutableMapOf<Int, Int>()

    /** Commands answered with a non-zero status: op -> (status, reason). */
    val statusFailures = mutableMapOf<Int, Pair<Int, Int>>()

    /** How many times FLASH_DATA is answered with a status error, then it succeeds. */
    var flashDataFailuresRemaining: Int = 0

    /** When set, `SPI_FLASH_MD5` reports this string instead of the real MD5. */
    var md5Override: String? = null

    /** Replies per SYNC; the C3 ROM sends 8 and esptool drains them. */
    var syncReplyCount: Int = 8

    /** Largest single read, so tests can split SLIP frames across reads. */
    var maxReadBytes: Int = Int.MAX_VALUE

    /** Offset from the last FLASH_BEGIN. */
    var flashOffset: Long = -1
        private set

    /** Erase size from the last FLASH_BEGIN. */
    var flashEraseSize: Long = -1
        private set

    /** Block size from the last FLASH_BEGIN. */
    var flashBlockSize: Int = -1
        private set

    /** Block count from the last FLASH_BEGIN. */
    var flashNumBlocks: Int = -1
        private set

    /** Payload of the last FLASH_END, or null when none was sent. */
    var flashEndPayload: ByteArray? = null
        private set

    /** Payload of the last SPI_ATTACH, or null. */
    var spiAttachPayload: ByteArray? = null
        private set

    /** Words of the last SPI_SET_PARAMS, or null. */
    var spiSetParams: List<Long>? = null
        private set

    /** `(address, size)` of every SPI_FLASH_MD5 request. */
    val md5Requests = mutableListOf<Pair<Long, Long>>()

    /** True once [close] ran. */
    var closed: Boolean = false
        private set

    /** How many times [flushInput] ran. */
    var flushCount: Int = 0
        private set

    /** True when the client sent a FLASH_END with `reboot` (payload word 0). */
    val flashEndRequestedReboot: Boolean
        get() = flashEndPayload?.let { EspRomSession.getLe32(it, 0) == 0L } ?: false

    private val decoder = SlipDecoder()

    private val outbox = ArrayDeque<Byte>()

    /** Bytes queued for the client but not yet read. */
    val pendingBytes: Int
        get() = outbox.size

    /** Ignores [op] the next [times] times (default: forever). */
    fun ignore(op: Int, times: Int = Int.MAX_VALUE) {
        ignoreCounts[op] = times
    }

    /** Answers [op] with a non-zero status. */
    fun fail(op: Int, status: Int, reason: Int = 0) {
        statusFailures[op] = status to reason
    }

    /** The recorded packets for [op]. */
    fun packetsFor(op: Int): List<Packet> = packets.filter { it.op == op }

    /** True when [op] was sent at least once. */
    fun saw(op: Int): Boolean = packets.any { it.op == op }

    // --- RomByteStream ------------------------------------------------------

    override fun write(bytes: ByteArray) {
        for (frame in decoder.feed(bytes)) {
            val packet = parse(frame) ?: continue
            packets += packet
            handle(packet)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (outbox.isEmpty()) return 0
        val limit = minOf(buffer.size, maxReadBytes)
        var read = 0
        while (read < limit && outbox.isNotEmpty()) {
            buffer[read++] = outbox.removeFirst()
        }
        return read
    }

    override fun flushInput() {
        flushCount += 1
        outbox.clear()
    }

    override fun close() {
        closed = true
    }

    // --- Simulated ROM ------------------------------------------------------

    private fun parse(frame: ByteArray): Packet? {
        if (frame.size < 8) return null
        if ((frame[0].toInt() and 0xFF) != 0) return null
        val op = frame[1].toInt() and 0xFF
        val length = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        if (frame.size != 8 + length) return null
        return Packet(
            op = op,
            payload = frame.copyOfRange(8, frame.size),
            checksum = EspRomSession.getLe32(frame, 4),
        )
    }

    private fun handle(packet: Packet) {
        val op = packet.op

        if (swallow(op)) return

        statusFailures[op]?.let { (status, reason) ->
            respond(op, status = status, reason = reason)
            return
        }

        when (op) {
            EspRomCommand.SYNC -> {
                if (syncReplyCount <= 0) return
                repeat(syncReplyCount) {
                    respond(
                        EspRomCommand.SYNC,
                        value = 0x2012_0707L,
                        data = byteArrayOf(0x07, 0x07, 0x12, 0x20),
                    )
                }
            }

            EspRomCommand.READ_REG -> {
                val address = EspRomSession.getLe32(packet.payload, 0)
                respond(op, value = registers[address] ?: 0L)
            }

            EspRomCommand.WRITE_REG -> {
                val address = EspRomSession.getLe32(packet.payload, 0)
                val value = EspRomSession.getLe32(packet.payload, 4)
                val mask = EspRomSession.getLe32(packet.payload, 8)
                val old = registers[address] ?: 0L
                registers[address] = (old and mask.inv()) or (value and mask)
                respond(op)
            }

            EspRomCommand.SPI_ATTACH -> {
                spiAttachPayload = packet.payload.copyOf()
                respond(op)
            }

            EspRomCommand.SPI_SET_PARAMS -> {
                spiSetParams = (0 until 6).map { EspRomSession.getLe32(packet.payload, it * 4) }
                respond(op)
            }

            EspRomCommand.FLASH_BEGIN -> {
                flashEraseSize = EspRomSession.getLe32(packet.payload, 0)
                flashNumBlocks = EspRomSession.getLe32(packet.payload, 4).toInt()
                flashBlockSize = EspRomSession.getLe32(packet.payload, 8).toInt()
                flashOffset = EspRomSession.getLe32(packet.payload, 12)
                erase(flashOffset, flashEraseSize)
                respond(op)
            }

            EspRomCommand.FLASH_DATA -> {
                if (flashDataFailuresRemaining > 0) {
                    flashDataFailuresRemaining -= 1
                    respond(op, status = 0x05, reason = 0x00)
                    return
                }
                val length = EspRomSession.getLe32(packet.payload, 0).toInt()
                val seq = EspRomSession.getLe32(packet.payload, 4).toInt()
                check(length in 1..EspFlashLayout.BLOCK_SIZE) { "bad FLASH_DATA length $length" }
                check(packet.payload.size == 16 + length) {
                    "FLASH_DATA payload is ${packet.payload.size} bytes, expected ${16 + length}"
                }
                val data = packet.payload.copyOfRange(16, 16 + length)
                check(packet.checksum == EspRomSession.checksum(data).toLong()) {
                    "FLASH_DATA checksum 0x%02X, expected 0x%02X".format(
                        packet.checksum,
                        EspRomSession.checksum(data),
                    )
                }
                // Without a preceding FLASH_BEGIN (block-level unit tests)
                // there is no target address; validate the frame only.
                if (flashOffset >= 0 && flashBlockSize > 0) {
                    val address = flashOffset + seq.toLong() * flashBlockSize
                    data.copyInto(flash, address.toInt())
                }
                respond(op)
            }

            EspRomCommand.SPI_FLASH_MD5 -> {
                val address = EspRomSession.getLe32(packet.payload, 0)
                val size = EspRomSession.getLe32(packet.payload, 4)
                md5Requests += address to size
                respond(
                    op,
                    data = (md5Override ?: md5Hex(address, size)).toByteArray(Charsets.US_ASCII),
                )
            }

            EspRomCommand.FLASH_END -> {
                flashEndPayload = packet.payload.copyOf()
                respond(op)
            }

            else -> respond(op, status = 0x01, reason = 0x00)
        }
    }

    private fun swallow(op: Int): Boolean {
        val remaining = ignoreCounts[op] ?: 0
        if (remaining <= 0) return false
        if (remaining != Int.MAX_VALUE) {
            ignoreCounts[op] = remaining - 1
        }
        return true
    }

    private fun erase(offset: Long, size: Long) {
        val from = offset.toInt().coerceIn(0, flash.size)
        val to = (offset + size).toInt().coerceIn(from, flash.size)
        java.util.Arrays.fill(flash, from, to, 0xFF.toByte())
    }

    private fun md5Hex(address: Long, size: Long): String {
        val from = address.toInt().coerceIn(0, flash.size)
        val to = (address + size).toInt().coerceIn(from, flash.size)
        val digest = MessageDigest.getInstance("MD5").digest(flash.copyOfRange(from, to))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0xF])
        }
        return out.toString().lowercase(Locale.US)
    }

    private fun respond(
        op: Int,
        value: Long = 0,
        data: ByteArray = EMPTY,
        status: Int = 0,
        reason: Int = 0,
    ) {
        // The ROM puts the two status bytes after any response data.
        val body = data + byteArrayOf(status.toByte(), reason.toByte())
        val frame = ByteArray(8 + body.size)
        frame[0] = RESPONSE_DIRECTION
        frame[1] = op.toByte()
        frame[2] = (body.size and 0xFF).toByte()
        frame[3] = ((body.size ushr 8) and 0xFF).toByte()
        EspRomSession.putLe32(frame, 4, value)
        body.copyInto(frame, 8)
        Slip.encode(frame).forEach { outbox.addLast(it) }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val HEX = "0123456789abcdef"
        const val RESPONSE_DIRECTION: Byte = 0x01
    }
}
