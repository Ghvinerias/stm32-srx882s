package com.eelab.rflab.decode

/**
 * Running estimate of a transmitter's symbol-rate scale factor.
 *
 * This is a direct port of `estimate_scale()` in
 * `tools/decode_hcs301.py`: pool **every data cell** (`pulses[12..]`) from
 * every frame seen so far, take the median of that pool, and divide by
 * [MID_US]. The MID class is the most common cell (adjacent bits are equal
 * about half the time), so the median lands on MID and gives a robust
 * estimate of the transmitter's clock offset. This is deliberately *not* an
 * average of the preamble edges used by other decoders in this repo.
 *
 * The estimate is only trusted once at least [MIN_SAMPLES] pooled values
 * exist; otherwise, or if the computed scale falls outside
 * [MIN_SCALE]..[MAX_SCALE], the scale is 1.0 ("trust the reference
 * constants, don't decode noise").
 *
 * The estimator is incremental: feed it each frame as it arrives and read
 * [scale]. Adding all frames of a capture and reading [scale] once produces
 * exactly the value the Python reference computes for that capture.
 */
class ScaleEstimator {

    private val lock = Any()
    private val dataCells = ArrayList<Int>()
    private var cachedScale: Double? = null

    /** Number of pooled data-cell widths currently held. */
    val sampleCount: Int
        get() = synchronized(lock) { dataCells.size }

    /**
     * The scale factor to use for the next decode.
     *
     * Recomputed lazily after each [addFrame]; returns exactly
     * `estimate_scale()` for the frames added so far.
     */
    val scale: Double
        get() = synchronized(lock) {
            cachedScale ?: estimateScale(dataCells).also { cachedScale = it }
        }

    /**
     * Add one frame's pulses to the pool.
     *
     * Only frames with exactly [FRAME_PULSE_COUNT] entries are pooled. The
     * Python reference pools every frame, but it runs on clean SRX882
     * captures; the CC1101 receiver forwards a steady stream of short noise
     * frames, and their cells would drag the median off the MID class and
     * mis-scale (and so reject) every real frame. The pool is also bounded to
     * the most recent [MAX_POOLED] cells so a long session cannot grow it
     * without limit.
     */
    fun addFrame(pulses: IntArray) {
        if (pulses.size != FRAME_PULSE_COUNT) return
        synchronized(lock) {
            for (i in DATA_CELL_START until pulses.size) {
                dataCells.add(pulses[i])
            }
            if (dataCells.size > MAX_POOLED) {
                dataCells.subList(0, dataCells.size - MAX_POOLED).clear()
            }
            cachedScale = null
        }
    }

    /** Add several frames in order. Equivalent to calling [addFrame] each. */
    fun addFrames(frames: Iterable<IntArray>) {
        for (pulses in frames) {
            addFrame(pulses)
        }
    }

    /** Drop all pooled values and return to the uncalibrated state. */
    fun reset() {
        synchronized(lock) {
            dataCells.clear()
            cachedScale = null
        }
    }

    companion object {
        /** Fewer pooled data cells than this means "not enough to calibrate". */
        const val MIN_SAMPLES = 8

        /** Most recent data cells kept in the pool (about 60 frames). */
        const val MAX_POOLED = 65 * 60

        /** Lower bound of a plausible scale; outside this we fall back to 1.0. */
        const val MIN_SCALE = 0.85

        /** Upper bound of a plausible scale; outside this we fall back to 1.0. */
        const val MAX_SCALE = 1.15

        /**
         * Pure form of the estimate over an already-pooled list of data-cell
         * widths.
         *
         * The median uses the same even-count rule as Python's
         * `statistics.median`: average of the two middle values. Returns 1.0
         * when there are fewer than [MIN_SAMPLES] values or when the result
         * is outside `[0.85, 1.15]`.
         */
        fun estimateScale(dataCellWidths: Collection<Int>): Double {
            if (dataCellWidths.size < MIN_SAMPLES) return 1.0

            val sorted = dataCellWidths.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 1) {
                sorted[middle].toDouble()
            } else {
                (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
            }

            val scale = median / MID_US
            // Reject nonsense rather than silently decoding noise.
            return if (scale in MIN_SCALE..MAX_SCALE) scale else 1.0
        }

        /**
         * Convenience for a whole capture: the exact value the Python tool
         * would compute for the same frames.
         */
        fun estimateScaleForFrames(frames: Iterable<IntArray>): Double {
            val pooled = ArrayList<Int>()
            for (pulses in frames) {
                if (pulses.size != FRAME_PULSE_COUNT) continue
                for (i in DATA_CELL_START until pulses.size) {
                    pooled.add(pulses[i])
                }
            }
            return estimateScale(pooled)
        }
    }
}
