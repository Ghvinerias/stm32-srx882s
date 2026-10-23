package com.eelab.rflab.protocol

/**
 * Outcome of [PacketCodec.decode].
 *
 * [Failure] is sealed so callers can exhaustively distinguish a corrupted
 * CRC from a structurally bad or unknown packet; [Failure.Malformed] carries
 * a human-readable reason for the cases the brief does not give a dedicated
 * name.
 */
sealed interface PacketDecodeResult {

    /** A well-formed packet whose CRC matched. */
    data class Success(val packet: Packet) : PacketDecodeResult

    /** The packet was recognised but could not be accepted. */
    sealed interface Failure : PacketDecodeResult {

        /**
         * The trailing CRC did not match the CRC over the preceding bytes.
         *
         * @property expected CRC computed over the received body.
         * @property actual CRC as transmitted (little-endian) in the packet.
         */
        data class CrcMismatch(val expected: Int, val actual: Int) : Failure

        /** The type byte at offset 1 is not one of the five known types. */
        data class UnknownType(val type: Int) : Failure

        /**
         * The buffer ended before the packet's layout could be read.
         *
         * @property required smallest buffer size that could hold the packet.
         * @property actual size of the buffer that was supplied.
         */
        data class Truncated(val required: Int, val actual: Int) : Failure

        /**
         * The CRC was valid but the layout was inconsistent: unsupported
         * version, wrong size for the declared type, a declared total length
         * that disagrees with the buffer, or an out-of-range field.
         */
        data class Malformed(val reason: String) : Failure
    }
}

/**
 * Encoder/decoder for the raw packet protocol layer.
 *
 * This layer operates on the bytes *after* COBS decoding: the output of
 * `transport_cobs_decode`, i.e. the COBS body with the trailing `0x00` wire
 * delimiter already stripped. It validates and appends the CRC-16 but does
 * no COBS encoding or delimiter handling itself -- a later transport layer
 * wraps [encodeTxCommand] / [encodeScenarioCommand] output in
 * [Cobs.encode] and appends the delimiter.
 *
 * Layouts (all multi-byte fields little-endian) come from
 * `android-app/AGENT_BRIEF.md` and match `firmware/src/transport.c`:
 *
 * ```
 * FRAME / STATUS : 18-byte header + 2*N payload + 2-byte CRC
 *   [0] version, [1] type, [2] flags u16, [4] sequence u16,
 *   [6] total raw length u16, [8] timestamp_us u32, [12] dropped u16,
 *   [14] payload word count u16, [16] initial_level, [17] receiver_id
 * TX_COMMAND      : 14 bytes (version, type, hopping u32, serial u32,
 *                   button, frame_count, CRC)
 * SCENARIO_COMMAND: 5 bytes (version, type, command_char, CRC)
 * ACK             : 6 bytes (version, type, for_type, accepted, CRC)
 * ```
 */
object PacketCodec {

    /** Size of the FRAME/STATUS header shared by both packet types. */
    const val HEADER_SIZE = 18

    /** Size of the trailing little-endian CRC-16. */
    const val CRC_SIZE = 2

    /** Total size of a raw TX_COMMAND packet. */
    const val TX_COMMAND_SIZE = 14

    /** Total size of a raw SCENARIO_COMMAND packet. */
    const val SCENARIO_COMMAND_SIZE = 5

    /** Total size of a raw ACK packet. */
    const val ACK_SIZE = 6

    /** STATUS always carries exactly this many payload words. */
    const val STATUS_PAYLOAD_WORDS = 5

    /** Smallest packet any direction can produce (SCENARIO_COMMAND). */
    private const val MIN_PACKET_SIZE = SCENARIO_COMMAND_SIZE

    /**
     * Minimum size of a raw OTA command (HELLO/END/ABORT carry no payload):
     * `[version][type=6][op][crc]`.
     */
    const val OTA_CMD_MIN_SIZE = 3 + CRC_SIZE

    /**
     * Minimum size of a raw OTA response: `[version][type=7][op][status][crc]`.
     */
    const val OTA_RESP_MIN_SIZE = 4 + CRC_SIZE

    /**
     * Decodes one raw packet buffer (post-COBS-decode, delimiter removed).
     *
     * The CRC is checked before the type is dispatched. FRAME and STATUS
     * share header parsing and are distinguished by the type byte; STATUS
     * payload words are interpreted as clock/edge/drop counters, never as
     * pulses.
     *
     * Returns [PacketDecodeResult.Success] or a typed
     * [PacketDecodeResult.Failure]; it never throws for malformed input.
     */
    fun decode(raw: ByteArray): PacketDecodeResult {
        if (raw.size < MIN_PACKET_SIZE) {
            return PacketDecodeResult.Failure.Truncated(MIN_PACKET_SIZE, raw.size)
        }

        val expectedCrc = Crc16.compute(raw.copyOfRange(0, raw.size - CRC_SIZE))
        val actualCrc = u16(raw, raw.size - CRC_SIZE)
        if (expectedCrc != actualCrc) {
            return PacketDecodeResult.Failure.CrcMismatch(expectedCrc, actualCrc)
        }

        val version = raw[0].toInt() and 0xFF
        if (version != PROTOCOL_VERSION) {
            return PacketDecodeResult.Failure.Malformed(
                "unsupported protocol version $version",
            )
        }

        return when (val type = raw[1].toInt() and 0xFF) {
            PacketType.FRAME, PacketType.STATUS -> decodeFrameOrStatus(raw, type)
            PacketType.TX_COMMAND -> decodeTxCommand(raw)
            PacketType.SCENARIO_COMMAND -> decodeScenarioCommand(raw)
            PacketType.ACK -> decodeAck(raw)
            PacketType.OTA_CMD -> decodeOtaCommand(raw)
            PacketType.OTA_RESP -> decodeOtaResponse(raw)
            else -> PacketDecodeResult.Failure.UnknownType(type)
        }
    }

    /**
     * Encodes [cmd] as a 14-byte raw TX_COMMAND buffer, CRC included.
     *
     * The serial is masked to 28 bits exactly as the firmware does.
     */
    fun encodeTxCommand(cmd: TxCommand): ByteArray {
        val raw = ByteArray(TX_COMMAND_SIZE)
        raw[0] = PROTOCOL_VERSION.toByte()
        raw[1] = PacketType.TX_COMMAND.toByte()
        putU32(raw, 2, cmd.hopping)
        putU32(raw, 6, cmd.serial and TxCommand.SERIAL_MASK)
        raw[10] = cmd.button.toByte()
        raw[11] = cmd.frameCount.toByte()
        return withCrc(raw)
    }

    /** Encodes [cmd] as a 5-byte raw SCENARIO_COMMAND buffer, CRC included. */
    fun encodeScenarioCommand(cmd: ScenarioCommand): ByteArray {
        val raw = ByteArray(SCENARIO_COMMAND_SIZE)
        raw[0] = PROTOCOL_VERSION.toByte()
        raw[1] = PacketType.SCENARIO_COMMAND.toByte()
        raw[2] = cmd.commandChar.code.toByte()
        return withCrc(raw)
    }

    /**
     * Encodes [command] as a raw OTA_CMD (type 6) buffer, CRC included.
     *
     * `OtaCommand`'s factories validate the payload layout before this is
     * called, and the wire shape `[1][6][op][payload][crc]` is shared with
     * types 1-5; the COBS wrapping and `0x00` delimiter are added by the
     * transport (see `RfLabConnection.sendPacket`).
     */
    fun encodeOtaCommand(command: OtaCommand): ByteArray {
        val raw = ByteArray(3 + command.payload.size + CRC_SIZE)
        raw[0] = PROTOCOL_VERSION.toByte()
        raw[1] = PacketType.OTA_CMD.toByte()
        raw[2] = command.op.toByte()
        command.payload.copyInto(raw, 3)
        return withCrc(raw)
    }

    private fun decodeFrameOrStatus(raw: ByteArray, type: Int): PacketDecodeResult {
        if (raw.size < HEADER_SIZE + CRC_SIZE) {
            return PacketDecodeResult.Failure.Truncated(
                HEADER_SIZE + CRC_SIZE,
                raw.size,
            )
        }

        val version = raw[0].toInt() and 0xFF
        val flags = u16(raw, 2)
        val sequence = u16(raw, 4)
        val declaredLength = u16(raw, 6)
        if (declaredLength != raw.size) {
            return PacketDecodeResult.Failure.Malformed(
                "declared packet length $declaredLength != buffer size ${raw.size}",
            )
        }

        val payloadWords = u16(raw, 14)
        val expectedLength = HEADER_SIZE + payloadWords * 2 + CRC_SIZE
        if (expectedLength != raw.size) {
            return PacketDecodeResult.Failure.Malformed(
                "payload word count $payloadWords implies $expectedLength bytes, " +
                    "but buffer is ${raw.size}",
            )
        }

        val payload = IntArray(payloadWords) { index ->
            u16(raw, HEADER_SIZE + index * 2)
        }
        val droppedFrames = u16(raw, 12)

        return when (type) {
            PacketType.FRAME -> PacketDecodeResult.Success(
                FramePacket(
                    version = version,
                    flags = flags,
                    sequence = sequence,
                    timestampUs = u32(raw, 8),
                    droppedFrames = droppedFrames,
                    pulses = payload,
                    initialLevel = raw[16].toInt() and 0xFF,
                    receiverId = raw[17].toInt() and 0xFF,
                ),
            )

            else -> {
                if (payloadWords != STATUS_PAYLOAD_WORDS) {
                    return PacketDecodeResult.Failure.Malformed(
                        "STATUS payload must be $STATUS_PAYLOAD_WORDS words, " +
                            "got $payloadWords",
                    )
                }
                PacketDecodeResult.Success(
                    StatusPacket(
                        version = version,
                        flags = flags,
                        sequence = sequence,
                        droppedFrames = droppedFrames,
                        clockHz = combineU16Pair(payload[0], payload[1]),
                        totalEdges = combineU16Pair(payload[2], payload[3]),
                        droppedPackets = payload[4],
                    ),
                )
            }
        }
    }

    private fun decodeTxCommand(raw: ByteArray): PacketDecodeResult {
        if (raw.size != TX_COMMAND_SIZE) {
            return PacketDecodeResult.Failure.Malformed(
                "TX_COMMAND must be $TX_COMMAND_SIZE bytes, got ${raw.size}",
            )
        }

        val button = raw[10].toInt() and 0xFF
        if (button > TxCommand.BUTTON_MAX) {
            return PacketDecodeResult.Failure.Malformed(
                "button must be 0x0..0xF: $button",
            )
        }
        val frameCount = raw[11].toInt() and 0xFF
        if (frameCount !in TxCommand.MIN_FRAME_COUNT..TxCommand.MAX_FRAME_COUNT) {
            return PacketDecodeResult.Failure.Malformed(
                "frameCount must be ${TxCommand.MIN_FRAME_COUNT}.." +
                    "${TxCommand.MAX_FRAME_COUNT}: $frameCount",
            )
        }

        return PacketDecodeResult.Success(
            TxCommand(
                hopping = u32(raw, 2),
                // The device always masks this field, so normalise on decode
                // too: what callers see is what the firmware would execute.
                serial = u32(raw, 6) and TxCommand.SERIAL_MASK,
                button = button,
                frameCount = frameCount,
            ),
        )
    }

    private fun decodeScenarioCommand(raw: ByteArray): PacketDecodeResult {
        if (raw.size != SCENARIO_COMMAND_SIZE) {
            return PacketDecodeResult.Failure.Malformed(
                "SCENARIO_COMMAND must be $SCENARIO_COMMAND_SIZE bytes, " +
                    "got ${raw.size}",
            )
        }

        val commandChar = (raw[2].toInt() and 0xFF).toChar()
        if (commandChar !in ScenarioCommand.SUPPORTED_COMMANDS) {
            return PacketDecodeResult.Failure.Malformed(
                "unsupported scenario command '$commandChar'",
            )
        }

        return PacketDecodeResult.Success(ScenarioCommand(commandChar))
    }

    private fun decodeAck(raw: ByteArray): PacketDecodeResult {
        if (raw.size != ACK_SIZE) {
            return PacketDecodeResult.Failure.Malformed(
                "ACK must be $ACK_SIZE bytes, got ${raw.size}",
            )
        }

        return PacketDecodeResult.Success(
            AckPacket(
                forType = raw[2].toInt() and 0xFF,
                accepted = (raw[3].toInt() and 0xFF) != 0,
            ),
        )
    }

    /**
     * Decodes one raw OTA_CMD (type 6) buffer, CRC already verified.
     *
     * The device never sends type 6, but decoding it is symmetrical with the
     * other types (and lets tests echo commands back through the same path).
     * Structural rules per operation come from `ota_ref.py`:
     *
     * ```
     * op 1 HELLO / 4 END / 5 ABORT : no payload
     * op 2 BEGIN                   : exactly 68 bytes (size + sha + mac)
     * op 3 CHUNK                   : offset + 1..192 data bytes
     * op 6 BOOTLOADER              : exactly 32 bytes (mac)
     * ```
     */
    private fun decodeOtaCommand(raw: ByteArray): PacketDecodeResult {
        if (raw.size < OTA_CMD_MIN_SIZE) {
            return PacketDecodeResult.Failure.Truncated(OTA_CMD_MIN_SIZE, raw.size)
        }

        val op = raw[2].toInt() and 0xFF
        if (!OtaOps.isValid(op)) {
            return PacketDecodeResult.Failure.Malformed("unknown OTA op $op")
        }

        val payloadSize = raw.size - 3 - CRC_SIZE
        val payload = raw.copyOfRange(3, raw.size - CRC_SIZE)
        val requiredSize = when (op) {
            OtaOps.HELLO, OtaOps.END, OtaOps.ABORT -> 0
            OtaOps.BEGIN -> OtaCommand.BEGIN_PAYLOAD_SIZE
            OtaOps.BOOTLOADER -> OtaCommand.BOOTLOADER_PAYLOAD_SIZE
            else -> null // CHUNK: a range, checked below
        }
        if (requiredSize != null && payloadSize != requiredSize) {
            return PacketDecodeResult.Failure.Malformed(
                "OTA op $op payload must be $requiredSize bytes, got $payloadSize",
            )
        }
        if (op == OtaOps.CHUNK) {
            val dataBytes = payloadSize - OtaCommand.CHUNK_HEADER_SIZE
            if (dataBytes !in 1..OtaCommand.MAX_CHUNK) {
                return PacketDecodeResult.Failure.Malformed(
                    "CHUNK data must be 1..${OtaCommand.MAX_CHUNK} bytes, got $dataBytes",
                )
            }
        }

        return PacketDecodeResult.Success(OtaCommand(op, payload))
    }

    /**
     * Decodes one raw OTA_RESP (type 7) buffer into an [OtaResponse].
     *
     * `op` echoes the answered command and [OtaStatus.isValid] bounds the
     * status byte. Payload shapes are validated only where the protocol pins
     * them: `HELLO/OK` is the fixed 23-byte capabilities+nonce block and a
     * `CHUNK` reply (OK or BAD_OFFSET) always carries the 32-bit
     * `next_offset` the host needs to stay in step; everything else is left
     * opaque.
     */
    private fun decodeOtaResponse(raw: ByteArray): PacketDecodeResult {
        if (raw.size < OTA_RESP_MIN_SIZE) {
            return PacketDecodeResult.Failure.Truncated(OTA_RESP_MIN_SIZE, raw.size)
        }

        val op = raw[2].toInt() and 0xFF
        if (!OtaOps.isValid(op)) {
            return PacketDecodeResult.Failure.Malformed("unknown OTA op $op")
        }
        val status = raw[3].toInt() and 0xFF
        if (!OtaStatus.isValid(status)) {
            return PacketDecodeResult.Failure.Malformed("unknown OTA status $status")
        }

        val payload = raw.copyOfRange(4, raw.size - CRC_SIZE)
        if (op == OtaOps.HELLO && status == OtaStatus.OK) {
            if (payload.size != OtaResponse.HELLO_PAYLOAD_SIZE) {
                return PacketDecodeResult.Failure.Malformed(
                    "HELLO/OK payload must be ${OtaResponse.HELLO_PAYLOAD_SIZE} bytes, " +
                        "got ${payload.size}",
                )
            }
        }
        if (op == OtaOps.CHUNK &&
            (status == OtaStatus.OK || status == OtaStatus.BAD_OFFSET) &&
            payload.size != OtaResponse.CHUNK_NEXT_OFFSET_PAYLOAD_SIZE
        ) {
            return PacketDecodeResult.Failure.Malformed(
                "CHUNK $status payload must be " +
                    "${OtaResponse.CHUNK_NEXT_OFFSET_PAYLOAD_SIZE} bytes, got ${payload.size}",
            )
        }

        return PacketDecodeResult.Success(OtaResponse(op, status, payload))
    }

    /** Appends a little-endian CRC over everything preceding it. */
    private fun withCrc(raw: ByteArray): ByteArray {
        val crcOffset = raw.size - CRC_SIZE
        val crc = Crc16.compute(raw.copyOfRange(0, crcOffset))
        putU16(raw, crcOffset, crc)
        return raw
    }

    private fun u16(raw: ByteArray, offset: Int): Int =
        (raw[offset].toInt() and 0xFF) or
            ((raw[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(raw: ByteArray, offset: Int): Long =
        (raw[offset].toLong() and 0xFF) or
            ((raw[offset + 1].toLong() and 0xFF) shl 8) or
            ((raw[offset + 2].toLong() and 0xFF) shl 16) or
            ((raw[offset + 3].toLong() and 0xFF) shl 24)

    private fun combineU16Pair(low: Int, high: Int): Long =
        (low.toLong() and 0xFFFF) or ((high.toLong() and 0xFFFF) shl 16)

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
