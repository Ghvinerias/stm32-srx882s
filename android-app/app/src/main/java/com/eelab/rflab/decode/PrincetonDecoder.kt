package com.eelab.rflab.decode

import com.eelab.rflab.protocol.FramePacket
import kotlin.math.abs

/**
 * One decoded Princeton/PT2262-style static-code frame.
 *
 * Unlike [DecodedSignal] (HCS301), there is no rolling counter and nothing
 * to decrypt: [data] *is* the access code, exactly as the remote transmits
 * it every press. [serial]/[button] are [data] split by
 * [PrincetonProtocol.parseFields]'s field convention, not a cryptographic
 * result.
 *
 * @property data the raw [bitCount]-bit code, MSB-first as received.
 * @property bitCount always [PrincetonProtocol.DATA_BITS] (24) for a frame
 *   this decoder accepts; carried anyway so a row can say what it means
 *   without a second lookup.
 * @property serial the upper bits of [data] once the button field is split
 *   out — see [PrincetonProtocol.parseFields] for the exact split rule.
 * @property button the button field split out of [data].
 * @property timestampUs device capture timestamp, passed through from the
 *   source [FramePacket], or 0 when decoding a bare pulse array.
 * @property sequence packet sequence number, passed through, or null when
 *   decoding a bare pulse array.
 */
data class PrincetonFrame(
    val data: Long,
    val bitCount: Int,
    val serial: Long,
    val button: Int,
    val timestampUs: Long = 0L,
    val sequence: Int? = null,
)

/**
 * Decoder for the Princeton/PT2262-style family of static (non-rolling)
 * remotes — the same code is transmitted on every press, so decoding it is
 * reading an access credential directly off the air, not a diagnostic on a
 * credential the user separately holds the key to. This object only reads
 * pulses already captured through the existing transport; it does not
 * change what the firmware captures or ships (see `hw_capture.c` /
 * `lab_capture.c`, both already generic, variable-length pulse capture with
 * no protocol-specific framing).
 *
 * Ported from `lib/subghz/protocols/princeton.c` in
 * `DarkFlippers/unleashed-firmware`, pinned commit
 * `0d295f581c1e34cd56e2e590ea85625a64c2b724`
 * (SHA-256 `0ead02510505062b2e698b1fad6c451bc043e456e5c6081d9d771777b0f5982d`),
 * same provenance practice as `KeeLoqLearning.kt` /
 * `docs/KEELOQ_LEARNING_PROVENANCE.md`. Only the *decoder* side is ported —
 * see the "no encoder" note below.
 *
 * The upstream decoder is a `feed(level, duration)` state machine called
 * once per edge; [decodeBits] is a direct, edge-by-edge port of that state
 * machine run over an already-captured pulse array instead of a live GPIO
 * callback. [FramePacket.pulses] stores alternating level durations with
 * the starting level given separately
 * ([FramePacket.initialLevel]/`PacketCodec.kt:242`); [levelAt] reconstructs
 * the per-edge level the upstream code reads directly from hardware.
 *
 * **Encoding/transmission is explicitly not implemented.** Upstream's
 * `princeton.c` also contains an encoder (building a transmit pulse train
 * from a chosen serial/button) and this project's architecture already has
 * a transmit path (`BatchSendEngine`) that a future change *could* wire a
 * Princeton encoder into — but turning "read a static code" directly into
 * "transmit that code" for a device whose static code was simply overheard
 * (no user-held secret involved, unlike the HCS301 key-based flows) is
 * exactly the automatic-cloning shape this project has consistently declined
 * to ship. No `encodeFrame`/similar function exists here, and the capability
 * matrix records that as [com.eelab.rflab.protocol.CapabilityAction.NONE],
 * not a button.
 */
object PrincetonProtocol {

    const val TE_SHORT_US = 390
    const val TE_LONG_US = 1170
    private const val TE_DELTA_US = 300
    const val DATA_BITS = 24

    private enum class Step { RESET, SAVE_DURATION, CHECK_DURATION }

    private fun withinTolerance(value: Int, target: Int, tolerance: Int): Boolean =
        abs(value - target) < tolerance

    /** The level of pulse index [i], reconstructed from the alternating-level convention. */
    private fun levelAt(i: Int, initialLevel: Int): Boolean = ((initialLevel xor (i and 1)) and 1) != 0

    /**
     * Protocol recognition: does this capture contain the Princeton sync
     * gap at all? A cheap signal-shape check with no bit extraction and no
     * repeat-confidence requirement — just "worth trying to decode", kept
     * separate from [decodeBits] so a caller (or test) can tell "this isn't
     * Princeton-shaped at all" apart from "it's Princeton-shaped but the
     * bits didn't check out".
     *
     * The sync gap is a low pulse of about `TE_SHORT_US * 36` with a wide
     * (`* 36`) tolerance band, exactly as upstream's `PrincetonDecoderStepReset`
     * case checks it.
     */
    fun recognize(pulses: IntArray, initialLevel: Int): Boolean {
        for (i in pulses.indices) {
            if (!levelAt(i, initialLevel) &&
                withinTolerance(pulses[i], TE_SHORT_US * 36, TE_DELTA_US * 36)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Frame decoding: the edge-by-edge state machine, returning a validated
     * [DATA_BITS]-bit code or null.
     *
     * "Validated" means the same thing it means upstream: two consecutive
     * repeats must decode to the exact same [DATA_BITS]-bit value before
     * either is accepted (`last_data == decode_data`, `princeton.c:436`).
     * Noise/a one-off bit error is round down to "no frame", not reported
     * at reduced confidence.
     *
     * This only recognizes exactly [DATA_BITS] bits per repeat, matching
     * upstream's `min_count_bit_for_found` comparison — the "second
     * encoding type" upstream parses with more data bits by first stripping
     * a fixed button byte is not something the *decoder* state machine
     * special-cases either; it only affects [parseFields] after the fact.
     */
    internal fun decodeBits(pulses: IntArray, initialLevel: Int): Long? {
        var step = Step.RESET
        var teLast = 0
        var decodeData = 0L
        var decodeCountBit = 0
        var lastData = 0L

        for (i in pulses.indices) {
            val level = levelAt(i, initialLevel)
            val duration = pulses[i]
            when (step) {
                Step.RESET -> {
                    if (!level && withinTolerance(duration, TE_SHORT_US * 36, TE_DELTA_US * 36)) {
                        step = Step.SAVE_DURATION
                        decodeData = 0L
                        decodeCountBit = 0
                    }
                }

                Step.SAVE_DURATION -> {
                    if (level) {
                        teLast = duration
                        step = Step.CHECK_DURATION
                    }
                    // else: stay in SAVE_DURATION, matching upstream's bare `if (level)`.
                }

                Step.CHECK_DURATION -> {
                    if (!level) {
                        if (duration >= TE_LONG_US * 2) {
                            // Repeat/frame boundary.
                            if (decodeCountBit == DATA_BITS) {
                                if (lastData == decodeData && lastData != 0L) {
                                    return decodeData
                                }
                                lastData = decodeData
                            }
                            step = Step.SAVE_DURATION
                            decodeData = 0L
                            decodeCountBit = 0
                        } else {
                            val isZero = withinTolerance(teLast, TE_SHORT_US, TE_DELTA_US) &&
                                withinTolerance(duration, TE_LONG_US, TE_DELTA_US * 3)
                            val isOne = withinTolerance(teLast, TE_LONG_US, TE_DELTA_US * 3) &&
                                withinTolerance(duration, TE_SHORT_US, TE_DELTA_US)
                            when {
                                isZero -> {
                                    decodeData = (decodeData shl 1)
                                    decodeCountBit++
                                    step = Step.SAVE_DURATION
                                }

                                isOne -> {
                                    decodeData = (decodeData shl 1) or 1L
                                    decodeCountBit++
                                    step = Step.SAVE_DURATION
                                }

                                else -> step = Step.RESET
                            }
                        }
                    } else {
                        step = Step.RESET
                    }
                }
            }
        }
        return null
    }

    /**
     * Field parsing: split a validated [DATA_BITS]-bit [data] into
     * (serial, button), per upstream's
     * `subghz_protocol_princeton_check_remote_controller` (`princeton.c:306`).
     *
     * Most Princeton remotes use a 4-bit button field (the `else` branch);
     * three fixed 8-bit button byte values (`0x30`, `0xC0`, and — with a
     * zero-extend fix upstream applies — `0x03`/`0x0C`) are a second,
     * documented encoding some remotes use instead, where the serial is 8
     * bits narrower. Ported as a direct `if`/`else if` chain to match
     * upstream exactly rather than a cleaner-looking reformulation that
     * might silently diverge on an edge case.
     */
    internal fun parseFields(data: Long): Pair<Long, Int> {
        val low8 = data and 0xFFL
        return when {
            low8 == 0x30L || low8 == 0xC0L -> (data ushr 8) to low8.toInt()
            low8 == 0x03L || low8 == 0x0CL -> (data ushr 8) to (low8.toInt() or 0xF0)
            else -> (data ushr 4) to (data and 0xFL).toInt()
        }
    }

    /**
     * Decode one frame from its raw pulse array: [recognize] then
     * [decodeBits] then [parseFields], or null if any step fails.
     */
    fun decodeFrame(pulses: IntArray, initialLevel: Int): PrincetonFrame? {
        if (!recognize(pulses, initialLevel)) return null
        val data = decodeBits(pulses, initialLevel) ?: return null
        val (serial, button) = parseFields(data)
        return PrincetonFrame(data = data, bitCount = DATA_BITS, serial = serial, button = button)
    }

    /** Decode a FRAME packet, passing its timestamp and sequence through for display/grouping. */
    fun decodeFrame(frame: FramePacket): PrincetonFrame? =
        decodeFrame(frame.pulses, frame.initialLevel)?.copy(
            timestampUs = frame.timestampUs,
            sequence = frame.sequence,
        )
}
