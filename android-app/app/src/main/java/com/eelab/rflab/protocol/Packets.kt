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
    const val OTA_CMD = 6
    const val OTA_RESP = 7
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

// ---------------------------------------------------------------------------
// OTA / bootloader protocol (types 6 and 7)
//
// Same framing as types 1-5: `[version=1][type][...][crc16 LE]`, CRC-16/CCITT-
// FALSE over everything before the CRC, then COBS + `0x00`. Layouts and
// semantics come from `esp32c3-cc1101-lab/ota/OTA_PROTOCOL.md`; the normative
// encoder is `ota_ref.py` in the same directory and the golden vectors in
// `OTA_VECTORS.json` (which this app must reproduce byte-for-byte) have their
// own JVM test under `app/src/test/resources`.
// ---------------------------------------------------------------------------

/** Operations of the OTA command/response pair, carried at offset 2. */
object OtaOps {
    const val HELLO = 1
    const val BEGIN = 2
    const val CHUNK = 3
    const val END = 4
    const val ABORT = 5
    const val BOOTLOADER = 6

    /** True for every operation the firmware defines. */
    fun isValid(op: Int): Boolean = op in HELLO..BOOTLOADER
}

/** Status byte of OTA_RESP (type 7), carried at offset 3. */
object OtaStatus {
    const val OK = 0
    const val BAD_STATE = 1
    const val BAD_LENGTH = 2
    const val DISABLED = 3
    const val BAD_MAC = 4
    const val TOO_BIG = 5
    const val BAD_HASH = 6
    const val BAD_OFFSET = 7
    const val FLASH_ERROR = 8

    /** True for every status the firmware defines. */
    fun isValid(status: Int): Boolean = status in OK..FLASH_ERROR
}

/**
 * A host → device OTA command (type 6): a generic [op]/[payload] pair.
 *
 * The payload layout depends only on [op], so this class carries plain bytes
 * and [com.eelab.rflab.protocol.PacketCodec.encodeOtaCommand] wraps them;
 * the factory functions below build each operation's payload.
 *
 * Instances are immutable; equality and hashing compare the payload
 * contents, so commands built independently from the same inputs compare
 * equal (the same guarantee [FramePacket] gives its pulse array).
 */
class OtaCommand internal constructor(val op: Int, val payload: ByteArray) : Packet {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtaCommand) return false
        return op == other.op && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * op + payload.contentHashCode()

    override fun toString(): String =
        "OtaCommand(op=$op, payload=${payload.toHex()})"

    companion object {
        /** Largest CHUNK data length the protocol allows. */
        const val MAX_CHUNK = 192

        const val SHA256_SIZE = 32
        const val MAC_SIZE = 32
        const val NONCE_SIZE = 16

        /** `[size u32][sha256 32][mac 32]`. */
        const val BEGIN_PAYLOAD_SIZE = 4 + SHA256_SIZE + MAC_SIZE

        /** `[mac 32]`. */
        const val BOOTLOADER_PAYLOAD_SIZE = MAC_SIZE

        /** `[offset u32]` header of a CHUNK payload, before the data bytes. */
        const val CHUNK_HEADER_SIZE = 4

        private val EMPTY = ByteArray(0)

        /** `HELLO` — carries no payload, asks for a fresh nonce. */
        fun hello(): OtaCommand = OtaCommand(OtaOps.HELLO, EMPTY)

        /**
         * `BEGIN [size u32][sha256 32][mac 32]`.
         *
         * @param size image size in bytes; the MAC proves the device should
         *   accept it (see [OtaAuth.beginMac] for the exact HMAC input).
         * @param sha256 digest of the whole image, checked again by the
         *   device when END arrives.
         * @param mac HMAC over the secret's session nonce and the size/digest.
         */
        fun begin(size: Long, sha256: ByteArray, mac: ByteArray): OtaCommand {
            require(size in 0..TxCommand.UINT32_MAX) { "size must fit in 32 bits: $size" }
            require(sha256.size == SHA256_SIZE) { "sha256 must be $SHA256_SIZE bytes" }
            require(mac.size == MAC_SIZE) { "mac must be $MAC_SIZE bytes" }

            val payload = ByteArray(BEGIN_PAYLOAD_SIZE)
            putU32(payload, 0, size)
            sha256.copyInto(payload, 4)
            mac.copyInto(payload, 4 + SHA256_SIZE)
            return OtaCommand(OtaOps.BEGIN, payload)
        }

        /**
         * `CHUNK [offset u32][data 1..192]`.
         *
         * @param offset byte offset of [data] in the image; the device only
         *   accepts the value it advertises as `next_offset`.
         * @param data the image bytes at [offset], at most [MAX_CHUNK].
         */
        fun chunk(offset: Long, data: ByteArray): OtaCommand {
            require(offset in 0..TxCommand.UINT32_MAX) { "offset must fit in 32 bits: $offset" }
            require(data.isNotEmpty()) { "chunk data must not be empty" }
            require(data.size <= MAX_CHUNK) {
                "chunk data must be at most $MAX_CHUNK bytes: ${data.size}"
            }
            require(offset <= TxCommand.UINT32_MAX - data.size) {
                "offset $offset plus ${data.size} bytes overflows the 32-bit field"
            }

            val payload = ByteArray(CHUNK_HEADER_SIZE + data.size)
            putU32(payload, 0, offset)
            data.copyInto(payload, CHUNK_HEADER_SIZE)
            return OtaCommand(OtaOps.CHUNK, payload)
        }

        /** `END` — no payload; the device verifies the digest and reboots. */
        fun end(): OtaCommand = OtaCommand(OtaOps.END, EMPTY)

        /** `ABORT` — discards the partial write and returns the device to idle. */
        fun abort(): OtaCommand = OtaCommand(OtaOps.ABORT, EMPTY)

        /** `BOOTLOADER [mac 32]` — reboots into the ROM download mode. */
        fun bootloader(mac: ByteArray): OtaCommand {
            require(mac.size == MAC_SIZE) { "mac must be $MAC_SIZE bytes" }
            return OtaCommand(OtaOps.BOOTLOADER, mac.copyOf())
        }
    }
}

/**
 * A device → host OTA response (type 7): `[op][status][payload]`.
 *
 * [op] echoes the command it answers. The payload layout depends on the
 * operation and status: `HELLO/OK` carries `[caps u8][max_chunk u16]
 * [slot_size u32][nonce 16]`, and `CHUNK` (both OK and BAD_OFFSET) carries
 * `[next_offset u32]`; every other combination defined by the protocol has an
 * empty payload.
 */
class OtaResponse(val op: Int, val status: Int, val payload: ByteArray) : Packet {

    /** True when the device accepted the command. */
    val isOk: Boolean
        get() = status == OtaStatus.OK

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtaResponse) return false
        return op == other.op &&
            status == other.status &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = op
        result = 31 * result + status
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "OtaResponse(op=$op, status=$status, payload=${payload.toHex()})"

    /** Parsed `HELLO/OK` response payload fields. */
    data class HelloInfo(
        /** Capability bitfield; bit 0 set means the OTA protocol is supported. */
        val caps: Int,
        /** Largest chunk data length the device will accept per CHUNK packet. */
        val maxChunk: Int,
        /** Size of the inactive OTA slot in bytes. */
        val slotSize: Long,
        /** 16-byte session nonce; a single-use input to the BEGIN/BOOTLOADER MACs. */
        val nonce: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HelloInfo) return false
            return caps == other.caps &&
                maxChunk == other.maxChunk &&
                slotSize == other.slotSize &&
                nonce.contentEquals(other.nonce)
        }

        override fun hashCode(): Int {
            var result = caps
            result = 31 * result + maxChunk
            result = 31 * result + slotSize.hashCode()
            result = 31 * result + nonce.contentHashCode()
            return result
        }
    }

    /**
     * Parses the HELLO/OK payload, or returns null when this response is not
     * a successful HELLO or its payload does not have the 23-byte layout.
     */
    fun helloInfo(): HelloInfo? {
        if (op != OtaOps.HELLO || !isOk) return null
        if (payload.size != HELLO_PAYLOAD_SIZE) return null
        return HelloInfo(
            caps = readU8(payload, 0),
            maxChunk = readU16(payload, 1),
            slotSize = readU32(payload, 3),
            nonce = payload.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + OtaCommand.NONCE_SIZE),
        )
    }

    /**
     * The device's [next_offset] for a CHUNK response with status OK or
     * BAD_OFFSET, or null when the payload does not carry the 32-bit field.
     */
    fun chunkNextOffset(): Long? {
        if (op != OtaOps.CHUNK) return null
        when (status) {
            OtaStatus.OK, OtaStatus.BAD_OFFSET -> Unit
            else -> return null
        }
        if (payload.size != CHUNK_NEXT_OFFSET_PAYLOAD_SIZE) return null
        return readU32(payload, 0)
    }

    companion object {
        /** `[caps u8][max_chunk u16][slot_size u32][nonce 16]` == 23 bytes. */
        const val HELLO_PAYLOAD_SIZE = 1 + 2 + 4 + OtaCommand.NONCE_SIZE

        /** Where the 16-byte nonce starts inside a HELLO payload. */
        const val NONCE_OFFSET = 7

        /** `[next_offset u32]`. */
        const val CHUNK_NEXT_OFFSET_PAYLOAD_SIZE = 4
    }
}

private fun putU32(target: ByteArray, offset: Int, value: Long) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun readU8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

private fun readU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readU32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        out.append(HEX[(byte.toInt() ushr 4) and 0xF])
        out.append(HEX[byte.toInt() and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
