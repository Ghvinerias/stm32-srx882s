package com.eelab.rflab.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scale estimation rules, cross-checked against `estimate_scale()` in
 * `tools/decode_hcs301.py` (median of pooled data cells, minimum sample
 * count, plausibility clamp).
 */
class ScaleEstimatorTest {

    @Test
    fun fewerThanEightSamplesIsUncalibrated() {
        val estimator = ScaleEstimator()
        repeat(ScaleEstimator.MIN_SAMPLES - 1) {
            estimator.addFrame(IntArray(DATA_CELL_START + 1) { MID_US })
        }

        assertEquals(7, estimator.sampleCount)
        assertEquals(1.0, estimator.scale, 0.0)
    }

    @Test
    fun eighthSampleEnablesEstimation() {
        val estimator = ScaleEstimator()
        repeat(ScaleEstimator.MIN_SAMPLES) {
            estimator.addFrame(IntArray(DATA_CELL_START + 1) { 1184 })
        }

        assertEquals(1184.0 / MID_US, estimator.scale, 1e-12)
    }

    @Test
    fun medianOfOddSampleCountUsesMiddleValue() {
        val values = listOf(900, 1000, 1100, 1200, 1300, 1400, 1500) + listOf(1138, 1138)
        // sorted: 900,1000,1100,1138,1138,1200,1300,1400,1500 -> median 1138
        assertEquals(1.0, ScaleEstimator.estimateScale(values), 1e-12)
    }

    @Test
    fun medianOfEvenSampleCountAveragesTwoMiddleValues() {
        val values = listOf(1000, 1000, 1000, 1100, 1200, 1200, 1200, 1200)
        assertEquals(1150.0 / MID_US, ScaleEstimator.estimateScale(values), 1e-12)
    }

    @Test
    fun implausibleScalesFallBackToOne() {
        assertEquals(1.0, ScaleEstimator.estimateScale(List(8) { 2000 }), 0.0)
        assertEquals(1.0, ScaleEstimator.estimateScale(List(8) { 800 }), 0.0)
    }

    @Test
    fun boundsAreInclusive() {
        // 0.85 * 1138 = 967.3 and 1.15 * 1138 = 1308.7, so the nearest
        // integer widths inside the window are 968 and 1308.
        assertEquals(968.0 / MID_US, ScaleEstimator.estimateScale(List(8) { 968 }), 1e-12)
        assertEquals(1308.0 / MID_US, ScaleEstimator.estimateScale(List(8) { 1308 }), 1e-12)
        // Just outside on the low side falls back to 1.0.
        assertEquals(1.0, ScaleEstimator.estimateScale(List(8) { 950 }), 0.0)
    }

    @Test
    fun onlyDataCellsArePooled() {
        val pulses = IntArray(FRAME_PULSE_COUNT) { 9999 }
        for (i in DATA_CELL_START until FRAME_PULSE_COUNT) {
            pulses[i] = 1200
        }

        val estimator = ScaleEstimator()
        estimator.addFrame(pulses)

        assertEquals(65, estimator.sampleCount)
        assertEquals(1200.0 / MID_US, estimator.scale, 1e-12)
    }

    @Test
    fun incrementalMatchesPureEstimate() {
        val frames = List(5) { IntArray(FRAME_PULSE_COUNT) { i -> if (i < DATA_CELL_START) 500 else 1100 + (i % 7) } }
        val estimator = ScaleEstimator()
        estimator.addFrames(frames)

        assertEquals(
            ScaleEstimator.estimateScaleForFrames(frames),
            estimator.scale,
            0.0,
        )
        assertEquals(frames.size * 65, estimator.sampleCount)
    }

    @Test
    fun resetReturnsToUncalibrated() {
        val estimator = ScaleEstimator()
        estimator.addFrame(IntArray(FRAME_PULSE_COUNT) { 1184 })
        assertTrue(estimator.scale != 1.0)

        estimator.reset()
        assertEquals(0, estimator.sampleCount)
        assertEquals(1.0, estimator.scale, 0.0)
    }
}
