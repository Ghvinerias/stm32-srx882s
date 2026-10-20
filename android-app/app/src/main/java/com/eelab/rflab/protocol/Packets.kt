package com.eelab.rflab.protocol

/** Version byte carried at offset 0 of every raw packet. */
const val PROTOCOL_VERSION = 1

/** Packet type discriminators, carried at offset 1 of every raw packet. */
object PacketType {
    const val FRAME = 1
    const val STATUS = 2
    const val TX_COMMAND = 3
    const val SCENARIO_COMMAND = 4
    const val ACK = 5
}

/**
 * Bits in the `flags` field (offset 2) of FRAME and STATUS packets.
 *
 * `CLOCK_FALLBACK` and `CAPTURE_INIT_FAILED` are persistent boot-time flags,
 * so they show up on every packet once set; the other two are per-capture.
 */
object PacketFlags {
    const val CAPTURE_TRUNCATED = 1 shl 0
    const val DMA_OVERRUN = 1 shl 1
    const val CLOCK_FALLBACK = 1 shl 8
    const val CAPTURE_INIT_FAILED = 1 shl 9
}

/**
 * A decoded protocol packet.
 *
 * Instances are produced by [PacketCodec.decode] from a buffer that has
 * already been COBS-decoded and whose CRC has been verified. All multi-byte
 * wire fields are little-endian; that reassembly happens in the codec, so
 * the properties here are ordinary host values.
 */
sealed interface Packet

/**
 * A capture burst (device -> host), type 1.
 *
 * @property version protocol version byte (always [PROTOCOL_VERSION]).
 * @property flags [PacketFlags] bitfield.
 * @property sequence rolling 16-bit packet sequence number.
 * @property timestampUs device capture timestamp, 32-bit microseconds.
 * @property droppedFrames device-side dropped-frame counter (also used as
 *   the capture-drops counter for STATUS).
 * @property pulses the N payload words: pulse widths in microseconds, in the
 *   same order as `pulses_us` in the ndjson captures under `tools/captures/`.
 * @property initialLevel logic level before the first edge.
 * @property receiverId receiver identifier (the firmware always sends 1).
 */
data class FramePacket(
    val version: Int,
    val flags: Int,
    val sequence: Int,
    val timestampUs: Long,
    val droppedFrames: Int,
    val pulses: IntArray,
    val initialLevel: Int,
    val receiverId: Int,
) : Packet {

    // IntArray uses reference equality by default, which would make packets
    // decoded from equal buffers compare unequal; compare the contents
    // instead so tests and callers can rely on structural equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FramePacket) return false
        return version == other.version &&
            flags == other.flags &&
            sequence == other.sequence &&
            timestampUs == other.timestampUs &&
            droppedFrames == other.droppedFrames &&
            pulses.contentEquals(other.pulses) &&
            initialLevel == other.initialLevel &&
            receiverId == other.receiverId
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + flags
        result = 31 * result + sequence
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + droppedFrames
        result = 31 * result + pulses.contentHashCode()
        result = 31 * result + initialLevel
        result = 31 * result + receiverId
        return result
    }

    override fun toString(): String =
        "FramePacket(version=$version, flags=$flags, sequence=$sequence, " +
            "timestampUs=$timestampUs, droppedFrames=$droppedFrames, " +
            "pulses=${pulses.contentToString()}, initialLevel=$initialLevel, " +
            "receiverId=$receiverId)"
}

/**
 * A device heartbeat/diagnostic packet (device -> host), type 2.
 *
 * STATUS uses the same 18-byte header and 5-word payload shape as FRAME, but
 * the five words have fixed meanings rather than being pulses. The codec
 * reassembles them into [clockHz], [totalEdges] and [droppedPackets]; the
 * timestamp and initial-level fields are always 0 for STATUS and are
 * therefore not exposed.
 */
data class StatusPacket(
    val version: Int,
    val flags: Int,
    val sequence: Int,
    val droppedFrames: Int,
    val clockHz: Long,
    val totalEdges: Long,
    val droppedPackets: Int,
) : Packet

/**
 * A transmit request (host -> device), type 3.
 *
 * @property hopping 32-bit hopping word exactly as transmitted. It is still
 *   ciphertext; this app never decrypts it.
 * @property serial serial number. The wire field is 32 bits but the firmware
 *   masks it to 28 bits, and [PacketCodec.encodeTxCommand] mirrors that, so
 *   values wider than 28 bits are truncated on the wire rather than rejected.
 * @property button button nibble, `0x0..0xF`.
 * @property frameCount number of frames to repeat, `1..10`.
 */
data class TxCommand(
    val hopping: Long,
    val serial: Long,
    val button: Int,
    val frameCount: Int,
) : Packet {

    init {
        require(hopping in 0..UINT32_MAX) {
            "hopping must fit in 32 bits: $hopping"
        }
        require(serial in 0..UINT32_MAX) {
            "serial must fit in 32 bits: $serial"
        }
        require(button in 0..BUTTON_MAX) {
            "button must be 0x0..0xF: $button"
        }
        require(frameCount in MIN_FRAME_COUNT..MAX_FRAME_COUNT) {
            "frameCount must be $MIN_FRAME_COUNT..$MAX_FRAME_COUNT: $frameCount"
        }
    }

    companion object {
        /** 28-bit serial limit the firmware applies. */
        const val SERIAL_MASK = 0x0FFF_FFFFL

        /** Largest value representable in the 32-bit wire fields. */
        const val UINT32_MAX = 0xFFFF_FFFFL

        const val BUTTON_MAX = 0xF

        const val MIN_FRAME_COUNT = 1
        const val MAX_FRAME_COUNT = 10
    }
}

/**
 * A preset scenario request (host -> device), type 4.
 *
 * The six characters are the ones `lab_tx_run_scenario` in
 * `firmware/src/lab_tx.c` understands. They are all reachable through this
 * packet type even though the firmware's ASCII console exposes only five by
 * name.
 */
data class ScenarioCommand(val commandChar: Char) : Packet {

    init {
        require(commandChar in SUPPORTED_COMMANDS) {
            "unsupported scenario command '$commandChar'"
        }
    }

    companion object {
        /** Replay a stale codeword. */
        const val STALE_REPLAY = 'r'

        /** Emit a synthetic stale codeword. */
        const val SYNTHETIC_STALE = 's'

        /** Transmit a zero hopping word. */
        const val ZERO_HOP = 'z'

        /** Transmit a plaintext counter as the hopping word. */
        const val PLAINTEXT_COUNTER = 'p'

        /** Transmit a random hopping word. */
        const val RANDOM_HOP = 'x'

        /** Transmit using a different serial number. */
        const val DIFFERENT_SERIAL = 'd'

        /** Every command character accepted by the firmware. */
        val SUPPORTED_COMMANDS: Set<Char> = setOf(
            STALE_REPLAY,
            SYNTHETIC_STALE,
            ZERO_HOP,
            PLAINTEXT_COUNTER,
            RANDOM_HOP,
            DIFFERENT_SERIAL,
        )
    }
}

/**
 * A command acknowledgement (device -> host), type 5.
 *
 * @property forType the command type being acknowledged: 3 (TX_COMMAND) or
 *   4 (SCENARIO_COMMAND).
 * @property accepted true when the command was executed, false when the
 *   firmware rejected or failed it.
 */
data class AckPacket(val forType: Int, val accepted: Boolean) : Packet {

    init {
        require(forType in 0..0xFF) { "forType must be a byte: $forType" }
    }
}
