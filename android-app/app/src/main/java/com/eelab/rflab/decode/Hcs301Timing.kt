package com.eelab.rflab.decode

/**
 * HCS301-shaped symbol timing constants, in microseconds.
 *
 * These are the measured centres used by `tools/decode_hcs301.py` and
 * `firmware/src/lab_tx.c`. They are scaled per capture by
 * [ScaleEstimator] because the transmitter's encoder runs from an RC
 * oscillator that can be several percent fast or slow.
 */
const val PREAMBLE_EDGES = 11

/** Header (preamble gap) duration that encodes a leading `0` bit. */
const val HEADER_ZERO_US = 4512

/** Header (preamble gap) duration that encodes a leading `1` bit. */
const val HEADER_ONE_US = 4170

/** Data cell for a `0 -> 1` transition. */
const val SHORT_US = 795

/** Data cell for "bit unchanged" (holds the previous bit). */
const val MID_US = 1138

/** Data cell for a `1 -> 0` transition. */
const val LONG_US = 1482

/** Relative tolerance applied to every symbol class, i.e. +/- 10%. */
const val TOLERANCE = 0.10

/**
 * Number of pulse widths in one decodable frame:
 * [PREAMBLE_EDGES] + one header cell + 65 data cells.
 */
const val FRAME_PULSE_COUNT = PREAMBLE_EDGES + 1 + 65

/** Index of the first data cell in a frame's pulse array (pulses[12]). */
const val DATA_CELL_START = PREAMBLE_EDGES + 1

/** Number of bits reconstructed from the 65 data cells (66 total). */
const val BIT_COUNT = 66

/**
 * Relative classification check shared by the header and data cells:
 * `|duration - center| <= center * TOLERANCE`.
 *
 * Mirrors `classify()` in `tools/decode_hcs301.py`; note that the tolerance
 * is a fraction of the (scaled) center, not of the measured duration.
 */
internal fun withinTolerance(duration: Int, centerUs: Double): Boolean =
    kotlin.math.abs(duration - centerUs) <= centerUs * TOLERANCE
