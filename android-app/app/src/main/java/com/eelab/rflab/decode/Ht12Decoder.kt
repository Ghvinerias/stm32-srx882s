package com.eelab.rflab.decode

import com.eelab.rflab.protocol.FramePacket
import kotlin.math.abs

/**
 * One decoded Holtek HT12-family (HT12E/HT12A) static-code frame.
 *
 * Like [PrincetonFrame]/[Ev1527Frame] there is no rolling counter and nothing
 * to decrypt: [data] *is* the 12-bit code as transmitted. [address]/[button]
 * are [data] split by HT12's fixed field convention (8 address/DIP bits + 4
 * data bits), not a cryptographic result.
 *
 * @property data the raw 12-bit code, MSB-first as received.
 * @property bitCount always [Ht12Protocol.DATA_BITS] (12).
 * @property address the upper 8 bits — HT12's address/DIP field (`cnt`
 *   upstream).
 * @property button the low 4 bits — HT12's data/button field (`btn` upstream).
 * @property timestampUs device capture timestamp, passed through, or 0.
 * @property sequence packet sequence number, passed through, or null.
 */
data class Ht12Frame(
    val data: Long,
    val bitCount: Int,
    val address: Long,
    val button: Int,
    val timestampUs: Long = 0L,
    val sequence: Int? = null,
)

/**
 * Decoder for the Holtek HT12E/HT12A family of static (non-rolling) remotes.
 *
 * Ported from `lib/subghz/protocols/holtek_ht12x.c` in
 * `DarkFlippers/unleashed-firmware`, pinned commit
 * `0d295f581c1e34cd56e2e590ea85625a64c2b724`
 * (SHA-256 `c26eb3455729b88443821006c372d387cb515d9afbed0279bd84596bacb76e04`),
 * the same pinned-commit provenance practice [PrincetonProtocol] documents.
 * Only the *decoder* side (`feed()` + `check_remote_controller()`) is ported; the
 * encoder in that file is deliberately **not** ported — see the "decode-only"
 * note below.
 *
 * HT12 is a genuinely different frame from the 24-bit PT2262/EV1527 PWM: 12
 * bits (8 address/DIP + 4 data), a distinct `te_short`/`te_long` of 320/640µs,
 * and a start bit after the preamble. So unlike [Ev1527Protocol] it cannot
 * reuse the Princeton core; this is its own edge-by-edge state machine, a
 * direct port of upstream's `feed(level, duration)` run over an
 * already-captured pulse array (same adaptation [PrincetonProtocol] makes).
 *
 * The upstream bit encoding, from the same `feed()`:
 * - a `1` bit is a long low (~`te_long`) then a short high (~`te_short`),
 * - a `0` bit is a short low (~`te_short`) then a long high (~`te_long`),
 * accumulated MSB-first (`subghz_protocol_blocks_add_bit`). A frame is
 * accepted only when it is exactly [DATA_BITS] bits and two consecutive
 * repeats decode to the same non-zero value (`last_data == decode_data &&
 * last_data`), the identical confidence rule [PrincetonProtocol] uses.
 *
 * **Decode-only.** Upstream's `holtek_ht12x.c` also contains an encoder; it is
 * not ported here, for the same reason [PrincetonProtocol] omits its encoder —
 * an HT12 static code has no owner-held secret, so turning "overheard an HT12
 * code" into "transmit that code" is the automatic-cloning shape this project
 * does not ship. The capability matrix records encoding/transmission as
 * [com.eelab.rflab.protocol.CapabilityAction.NONE].
 */
object Ht12Protocol {

    const val TE_SHORT_US = 320
    const val TE_LONG_US = 640
    private const val TE_DELTA_US = 200
    const val DATA_BITS = 12
    const val ADDRESS_BITS = 8
    const val DATA_NIBBLE_BITS = 4

    private enum class Step { RESET, FOUND_START_BIT, SAVE_DURATION, CHECK_DURATION }

    private fun diff(value: Int, target: Int): Int = abs(value - target)

    /** The level of pulse index [i], reconstructed from the alternating-level convention. */
    private fun levelAt(i: Int, initialLevel: Int): Boolean = ((initialLevel xor (i and 1)) and 1) != 0

    /**
     * Protocol recognition: does this capture contain the HT12 preamble gap?
     * A cheap signal-shape check (a low pulse of about `te_short * 28`, wide
     * tolerance `te_delta * 20`) exactly as upstream's
     * `Holtek_HT12XDecoderStepReset` case checks it — kept separate from
     * [decodeBits] so a caller can tell "not HT12-shaped at all" from
     * "HT12-shaped but the bits didn't check out".
     */
    fun recognize(pulses: IntArray, initialLevel: Int): Boolean {
        for (i in pulses.indices) {
            if (!levelAt(i, initialLevel) && diff(pulses[i], TE_SHORT_US * 28) < TE_DELTA_US * 20) {
                return true
            }
        }
        return false
    }

    /**
     * Frame decoding: the edge-by-edge state machine, returning a validated
     * [DATA_BITS]-bit code or null. A direct port of upstream's `feed()`; the
     * `te` running-average upstream keeps is purely for its displayed "Te:"
     * value and does not affect accept/reject, so it is omitted here.
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
                    if (!level && diff(duration, TE_SHORT_US * 28) < TE_DELTA_US * 20) {
                        step = Step.FOUND_START_BIT
                    }
                }

                Step.FOUND_START_BIT -> {
                    if (level && diff(duration, TE_SHORT_US) < TE_DELTA_US) {
                        step = Step.SAVE_DURATION
                        decodeData = 0L
                        decodeCountBit = 0
                    } else {
                        step = Step.RESET
                    }
                }

                Step.SAVE_DURATION -> {
                    if (!level) {
                        if (duration >= TE_SHORT_US * 10 + TE_DELTA_US) {
                            // Frame/repeat boundary (also the next repeat's preamble).
                            if (decodeCountBit == DATA_BITS) {
                                if (lastData == decodeData && lastData != 0L) {
                                    return decodeData
                                }
                                lastData = decodeData
                            }
                            decodeData = 0L
                            decodeCountBit = 0
                            step = Step.FOUND_START_BIT
                        } else {
                            teLast = duration
                            step = Step.CHECK_DURATION
                        }
                    } else {
                        step = Step.RESET
                    }
                }

                Step.CHECK_DURATION -> {
                    if (level) {
                        val isOne = diff(teLast, TE_LONG_US) < TE_DELTA_US * 2 &&
                            diff(duration, TE_SHORT_US) < TE_DELTA_US
                        val isZero = diff(teLast, TE_SHORT_US) < TE_DELTA_US &&
                            diff(duration, TE_LONG_US) < TE_DELTA_US * 2
                        when {
                            isOne -> {
                                decodeData = (decodeData shl 1) or 1L
                                decodeCountBit++
                                step = Step.SAVE_DURATION
                            }

                            isZero -> {
                                decodeData = decodeData shl 1
                                decodeCountBit++
                                step = Step.SAVE_DURATION
                            }

                            else -> step = Step.RESET
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
     * Field parsing, per upstream's
     * `subghz_protocol_holtek_th12x_check_remote_controller`: the low 4 bits
     * are the button/data field (`btn`), the next 8 bits are the address/DIP
     * field (`cnt`).
     */
    fun parseFields(data: Long): Pair<Long, Int> =
        ((data ushr DATA_NIBBLE_BITS) and 0xFFL) to (data and 0xFL).toInt()

    /** Decode one frame from its raw pulse array, or null if any step fails. */
    fun decodeFrame(pulses: IntArray, initialLevel: Int): Ht12Frame? {
        if (!recognize(pulses, initialLevel)) return null
        val data = decodeBits(pulses, initialLevel) ?: return null
        val (address, button) = parseFields(data)
        return Ht12Frame(data = data, bitCount = DATA_BITS, address = address, button = button)
    }

    /** Decode a FRAME packet, passing its timestamp and sequence through for display/grouping. */
    fun decodeFrame(frame: FramePacket): Ht12Frame? =
        decodeFrame(frame.pulses, frame.initialLevel)?.copy(
            timestampUs = frame.timestampUs,
            sequence = frame.sequence,
        )
}
