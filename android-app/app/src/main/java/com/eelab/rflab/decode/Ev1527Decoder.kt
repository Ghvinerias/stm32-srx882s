package com.eelab.rflab.decode

import com.eelab.rflab.protocol.FramePacket

/**
 * One decoded EV1527/RT1527-style static-code frame.
 *
 * Like [PrincetonFrame] there is no rolling counter and nothing to decrypt:
 * [data] *is* the access code exactly as the remote transmits it on every
 * press. [address]/[button] are [data] split by EV1527's fixed field
 * convention (20-bit address + 4-bit data nibble), not a cryptographic
 * result.
 *
 * @property data the raw 24-bit code, MSB-first as received.
 * @property bitCount always [Ev1527Protocol.DATA_BITS] (24).
 * @property address the upper 20 bits — EV1527's factory-set chip address.
 * @property button the low 4 bits — EV1527's data/key nibble.
 * @property timestampUs device capture timestamp, passed through from the
 *   source [FramePacket], or 0 when decoding a bare pulse array.
 * @property sequence packet sequence number, passed through, or null when
 *   decoding a bare pulse array.
 */
data class Ev1527Frame(
    val data: Long,
    val bitCount: Int,
    val address: Long,
    val button: Int,
    val timestampUs: Long = 0L,
    val sequence: Int? = null,
)

/**
 * Decoder for the EV1527/RT1527 family of static (non-rolling) remotes.
 *
 * **On-air, EV1527 is the same signal as a 24-bit PT2262/Princeton frame.**
 * Both use the identical PWM line code — a short/long (1:3) cell for a `0`, a
 * long/short (3:1) cell for a `1`, and a long low sync gap — over 24 data
 * bits. The difference between the two is *which chip generated the frame*
 * (EV1527 has a 20-bit binary address + 4 data bits; PT2262 uses tri-state
 * address pins and a couple of documented 8-bit button-byte conventions),
 * and that difference is **not recoverable from the captured waveform
 * alone**. So this object deliberately does not try to tell EV1527 and
 * PT2262 apart: [recognize] and the bit extraction are shared with
 * [PrincetonProtocol], and what EV1527 adds is only its own *field
 * interpretation* of the same 24 bits.
 *
 * Because the bit-extraction core is literally [PrincetonProtocol.decodeBits]
 * — the edge-by-edge state machine already differentially verified against a
 * standalone build of upstream's C decoder (see [PrincetonProtocol]'s and
 * `PrincetonDecoderTest`'s doc comments) — EV1527 decoding inherits that
 * verification for free; only [parseFields] (a plain 20/4 bit split) is new
 * here, and it is exhaustively unit-tested.
 *
 * **Decode-only.** As with [PrincetonProtocol], there is no encoder and no
 * transmit path: a static code has no owner-held secret, so turning
 * "overheard an EV1527 code" into "transmit that code" is the automatic-
 * cloning shape this project does not ship. The capability matrix records
 * encoding/transmission as [com.eelab.rflab.protocol.CapabilityAction.NONE].
 */
object Ev1527Protocol {

    /** EV1527 transmits exactly 24 bits, the same width [PrincetonProtocol] decodes. */
    const val DATA_BITS = PrincetonProtocol.DATA_BITS

    /** The factory-set chip address occupies the upper 20 bits. */
    const val ADDRESS_BITS = 20

    /** The data/key field is the low 4 bits. */
    const val DATA_NIBBLE_BITS = 4

    /**
     * Protocol recognition. Shared with [PrincetonProtocol.recognize]: the
     * sync gap EV1527 uses is the same one PT2262 uses, so "this is EV1527-
     * shaped" and "this is Princeton-shaped" are the same question. Kept as a
     * named delegating function so the catalog/tests can refer to EV1527
     * recognition explicitly.
     */
    fun recognize(pulses: IntArray, initialLevel: Int): Boolean =
        PrincetonProtocol.recognize(pulses, initialLevel)

    /**
     * Split a validated 24-bit [data] value into EV1527's fixed fields:
     * the upper [ADDRESS_BITS] (20) as the chip address, the low
     * [DATA_NIBBLE_BITS] (4) as the data nibble. Unlike
     * [PrincetonProtocol.parseFields] there are no 8-bit-button special
     * cases — EV1527's layout is fixed.
     */
    fun parseFields(data: Long): Pair<Long, Int> =
        (data ushr DATA_NIBBLE_BITS) to (data and ((1L shl DATA_NIBBLE_BITS) - 1)).toInt()

    /**
     * Decode one frame from its raw pulse array: [recognize] then the shared
     * [PrincetonProtocol.decodeBits] then [parseFields], or null if any step
     * fails. "Validated" means exactly what it means for Princeton — two
     * consecutive repeats must decode to the same value before it is accepted.
     */
    fun decodeFrame(pulses: IntArray, initialLevel: Int): Ev1527Frame? {
        if (!recognize(pulses, initialLevel)) return null
        val data = PrincetonProtocol.decodeBits(pulses, initialLevel) ?: return null
        val (address, button) = parseFields(data)
        return Ev1527Frame(data = data, bitCount = DATA_BITS, address = address, button = button)
    }

    /** Decode a FRAME packet, passing its timestamp and sequence through for display/grouping. */
    fun decodeFrame(frame: FramePacket): Ev1527Frame? =
        decodeFrame(frame.pulses, frame.initialLevel)?.copy(
            timestampUs = frame.timestampUs,
            sequence = frame.sequence,
        )
}
