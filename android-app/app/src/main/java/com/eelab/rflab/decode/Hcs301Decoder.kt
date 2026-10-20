package com.eelab.rflab.decode

import com.eelab.rflab.protocol.FramePacket
import kotlin.math.abs

/**
 * Decoder for one HCS301-shaped frame, ported from `decode_frame()` in
 * `tools/decode_hcs301.py`.
 *
 * The decoder works on pulse widths only (microseconds, as captured by the
 * firmware and carried in `FramePacket.pulses`). It reconstructs the 66
 * transmitted bits from a Manchester-like encoding: the header cell carries
 * bit 0, and each of the following 65 cells either holds the previous bit
 * (MID), signals a `0 -> 1` transition (SHORT), or a `1 -> 0` transition
 * (LONG). A frame is rejected as soon as any cell is outside tolerance or a
 * transition contradicts the bit already decoded.
 *
 * Timing must be calibrated per transmitter/capture via [ScaleEstimator];
 * both the header centres and the data-cell centres are multiplied by the
 * scale before classification. Passing 1.0 uses the reference constants
 * unchanged and is correct for captures whose transmitter runs at nominal
 * speed.
 */
object Hcs301Decoder {

    /**
     * Decode one frame from its raw pulse array.
     *
     * @param pulses widths in microseconds; must be exactly
     *   [FRAME_PULSE_COUNT] (77) entries for this protocol shape.
     * @param scale symbol-rate scale from [ScaleEstimator]; 1.0 means
     *   nominal timing.
     * @return the decoded clear-text fields, or null if the frame is the
     *   wrong length, its header is not a valid 0/1 header cell, a data cell
     *   is outside tolerance, or a transition is inconsistent with the bits
     *   decoded so far.
     */
    fun decodeFrame(pulses: IntArray, scale: Double = 1.0): DecodedSignal? {
        if (pulses.size != FRAME_PULSE_COUNT) return null

        // --- Header cell: encodes the first bit. ---
        val header = pulses[PREAMBLE_EDGES]
        val zero = HEADER_ZERO_US * scale
        val one = HEADER_ONE_US * scale
        if (!withinTolerance(header, zero) && !withinTolerance(header, one)) {
            return null
        }
        val bit0 = if (abs(header - zero) <= abs(header - one)) 0 else 1

        // --- Data cells: 65 cells -> 66 bits including bit0. ---
        val bits = IntArray(BIT_COUNT)
        bits[0] = bit0

        for (i in 0 until FRAME_PULSE_COUNT - DATA_CELL_START) {
            val duration = pulses[DATA_CELL_START + i]
            val short = SHORT_US * scale
            val mid = MID_US * scale
            val long = LONG_US * scale

            // Nearest of {short, mid, long}. The tie-break order (long, then
            // mid, then short) reproduces Python's `min((error, kind))`,
            // where equal distances resolve by lexicographic kind: "l" < "m"
            // < "s".
            var error = abs(duration - long)
            var kind = Kind.LONG
            val midError = abs(duration - mid)
            if (midError < error) {
                error = midError
                kind = Kind.MID
            }
            val shortError = abs(duration - short)
            if (shortError < error) {
                error = shortError
                kind = Kind.SHORT
            }

            // The rejection threshold is the MID tolerance for every class,
            // as in the reference implementation.
            if (error > mid * TOLERANCE) return null

            when (kind) {
                Kind.SHORT -> {
                    // 0 -> 1 transition only.
                    if (bits[i] != 0) return null
                    bits[i + 1] = 1
                }

                Kind.LONG -> {
                    // 1 -> 0 transition only.
                    if (bits[i] != 1) return null
                    bits[i + 1] = 0
                }

                Kind.MID -> bits[i + 1] = bits[i]
            }
        }

        return DecodedSignal(
            hopping = bitsToLong(bits, start = 0, count = 32),
            serial = bitsToLong(bits, start = 32, count = 28),
            button = bitsToLong(bits, start = 60, count = 4).toInt(),
            vlow = bits[64] != 0,
            repeat = bits[65] != 0,
            scale = scale,
        )
    }

    /**
     * Decode a FRAME packet's pulses, passing its timestamp and sequence
     * through into the result for display/grouping.
     */
    fun decodeFrame(frame: FramePacket, scale: Double = 1.0): DecodedSignal? =
        decodeFrame(frame.pulses, scale)?.copy(
            timestampUs = frame.timestampUs,
            sequence = frame.sequence,
        )

    /** Read [count] bits starting at [start], least-significant bit first. */
    private fun bitsToLong(bits: IntArray, start: Int, count: Int): Long {
        var value = 0L
        for (i in 0 until count) {
            value = value or (bits[start + i].toLong() shl i)
        }
        return value
    }

    private enum class Kind { SHORT, MID, LONG }
}
