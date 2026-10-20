package com.eelab.rflab.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthetic frames for the decoder's rejection rules and bit-field layout.
 *
 * The valid frames here are generated from a bit sequence using the same
 * cell rules the protocol defines, so they exercise the real classification
 * path rather than hard-coding one capture.
 */
class Hcs301DecoderTest {

    // --- Valid frame construction -------------------------------------------

    /** 66 bits packed LSB-first, matching the wire field layout. */
    private fun bitsFor(
        hopping: Long,
        serial: Long,
        button: Int,
        vlow: Boolean,
        repeat: Boolean,
    ): IntArray {
        val bits = IntArray(BIT_COUNT)

        fun put(start: Int, count: Int, value: Long) {
            for (i in 0 until count) {
                bits[start + i] = ((value shr i) and 1L).toInt()
            }
        }

        put(0, 32, hopping)
        put(32, 28, serial)
        put(60, 4, button.toLong())
        bits[64] = if (vlow) 1 else 0
        bits[65] = if (repeat) 1 else 0
        return bits
    }

    /**
     * Encode a bit sequence into 77 pulse widths using nominal constants.
     * Preamble edges are arbitrary — the decoder only looks at index 11
     * onwards — but they are set to plausible short values.
     */
    private fun encode(bits: IntArray, scale: Double = 1.0): IntArray {
        require(bits.size == BIT_COUNT)
        val pulses = IntArray(FRAME_PULSE_COUNT) { (SHORT_US * scale).toInt() }
        pulses[PREAMBLE_EDGES] = if (bits[0] == 0) {
            (HEADER_ZERO_US * scale).toInt()
        } else {
            (HEADER_ONE_US * scale).toInt()
        }
        for (i in 0 until 65) {
            val duration = when {
                bits[i] == 0 && bits[i + 1] == 1 -> SHORT_US
                bits[i] == 1 && bits[i + 1] == 0 -> LONG_US
                else -> MID_US
            }
            pulses[DATA_CELL_START + i] = (duration * scale).toInt()
        }
        return pulses
    }

    private fun allBits(value: Int): IntArray = IntArray(BIT_COUNT) { value }

    // --- Bit layout ---------------------------------------------------------

    @Test
    fun decodesKnownBitPatternRoundTrip() {
        val hopping = 0xDEADBEEFL
        val serial = 0x0ABCDEFL
        val bits = bitsFor(hopping, serial, button = 0xA, vlow = true, repeat = true)

        val signal = Hcs301Decoder.decodeFrame(encode(bits))

        requireNotNull(signal)
        assertEquals(hopping, signal.hopping)
        assertEquals(serial, signal.serial)
        assertEquals(0xA, signal.button)
        assertEquals(true, signal.vlow)
        assertEquals(true, signal.repeat)
    }

    @Test
    fun extractsFieldsLsbFirst() {
        // hopping only in the top bit, serial only in its top bit, button 0x8.
        val bits = bitsFor(
            hopping = 1L shl 31,
            serial = 1L shl 27,
            button = 0x8,
            vlow = false,
            repeat = false,
        )

        val signal = requireNotNull(Hcs301Decoder.decodeFrame(encode(bits)))

        assertEquals(0x80000000L, signal.hopping)
        assertEquals(0x08000000L, signal.serial)
        assertEquals(0x8, signal.button)
        assertEquals(false, signal.vlow)
        assertEquals(false, signal.repeat)
    }

    @Test
    fun allZeroBitsDecodeToZeroFields() {
        val signal = requireNotNull(Hcs301Decoder.decodeFrame(encode(allBits(0))))

        assertEquals(0L, signal.hopping)
        assertEquals(0L, signal.serial)
        assertEquals(0, signal.button)
        assertEquals(false, signal.vlow)
        assertEquals(false, signal.repeat)
    }

    @Test
    fun allOneBitsDecodeToMaxFields() {
        val signal = requireNotNull(Hcs301Decoder.decodeFrame(encode(allBits(1))))

        assertEquals(0xFFFFFFFFL, signal.hopping)
        assertEquals(0x0FFFFFFFL, signal.serial)
        assertEquals(0xF, signal.button)
        assertEquals(true, signal.vlow)
        assertEquals(true, signal.repeat)
    }

    @Test
    fun headerOneSelectsLeadingOneBit() {
        // Header 4170 is the "one" cell; with all-MID data every bit stays 1.
        val pulses = IntArray(FRAME_PULSE_COUNT) { MID_US }
        pulses[PREAMBLE_EDGES] = HEADER_ONE_US

        val signal = requireNotNull(Hcs301Decoder.decodeFrame(pulses))
        assertEquals(0xFFFFFFFFL, signal.hopping)
    }

    // --- Wrong length -------------------------------------------------------

    @Test
    fun rejectsWrongLengthFrames() {
        val valid = encode(bitsFor(1, 2, 3, false, false))

        assertNull(Hcs301Decoder.decodeFrame(IntArray(0)))
        assertNull(Hcs301Decoder.decodeFrame(valid.copyOf(FRAME_PULSE_COUNT - 1)))
        assertNull(Hcs301Decoder.decodeFrame(valid.copyOf(FRAME_PULSE_COUNT + 1)))
        assertNull(Hcs301Decoder.decodeFrame(IntArray(76)))
        assertNull(Hcs301Decoder.decodeFrame(IntArray(78)))
    }

    // --- Header rejection ---------------------------------------------------

    @Test
    fun rejectsHeaderOutsideTolerance() {
        val pulses = encode(allBits(0))
        pulses[PREAMBLE_EDGES] = 3000
        assertNull(Hcs301Decoder.decodeFrame(pulses))

        pulses[PREAMBLE_EDGES] = 5200
        assertNull(Hcs301Decoder.decodeFrame(pulses))
    }

    @Test
    fun headerToleranceBoundaryIsInclusive() {
        // 4512 * 1.10 = 4963.2: 4963 is inside, 4964 is outside both classes.
        // (All data cells are MID, so the decoded fields stay all-zero.)
        val pulses = encode(allBits(0))
        pulses[PREAMBLE_EDGES] = 4963
        assertEquals(0L, requireNotNull(Hcs301Decoder.decodeFrame(pulses)).hopping)

        pulses[PREAMBLE_EDGES] = 4964
        assertNull(Hcs301Decoder.decodeFrame(pulses))
    }

    @Test
    fun headerDistanceTiePrefersZero() {
        // 4341 is exactly 171 us from both header centres. The reference
        // uses `|h-zero| <= |h-one|`, so the tie resolves to bit 0 -- this
        // is what makes an uncalibrated ~4% slow capture fail (the real
        // header was a "one" cell), so the tie rule is behaviourally visible.
        val allMid = encode(allBits(0))
        allMid[PREAMBLE_EDGES] = 4341
        assertEquals(0L, requireNotNull(Hcs301Decoder.decodeFrame(allMid)).hopping)

        // Pattern whose first data cell is LONG (a 1 -> 0 transition): with
        // bit0 tied to 0 it is an illegal transition and the frame rejects.
        val startsWithLong = encode(bitsFor(1, 2, 3, false, false))
        startsWithLong[PREAMBLE_EDGES] = 4341
        assertNull(Hcs301Decoder.decodeFrame(startsWithLong))
    }

    // --- Data-cell rejection ------------------------------------------------

    @Test
    fun rejectsDataCellOutsideAllClasses() {
        val pulses = encode(allBits(0))
        pulses[DATA_CELL_START + 5] = 2500
        assertNull(Hcs301Decoder.decodeFrame(pulses))
    }

    @Test
    fun rejectsLongWhenPreviousBitIsZero() {
        // All-zero bits: a LONG cell claims a 1 -> 0 transition, but bits[0]=0.
        val pulses = encode(allBits(0))
        pulses[DATA_CELL_START] = LONG_US
        assertNull(Hcs301Decoder.decodeFrame(pulses))
    }

    @Test
    fun rejectsShortWhenPreviousBitIsOne() {
        // All-one bits: a SHORT cell claims a 0 -> 1 transition, but bits[0]=1.
        val pulses = encode(allBits(1))
        pulses[DATA_CELL_START] = SHORT_US
        assertNull(Hcs301Decoder.decodeFrame(pulses))
    }

    @Test
    fun rejectsSecondShortTransitionAfterAOne() {
        // First SHORT makes bit 1 = 1; a second SHORT would need bit 1 = 0.
        val pulses = encode(allBits(0))
        pulses[DATA_CELL_START] = SHORT_US
        pulses[DATA_CELL_START + 1] = SHORT_US
        assertNull(Hcs301Decoder.decodeFrame(pulses))
    }

    @Test
    fun dataCellRejectionUsesMidToleranceForEveryClass() {
        // The reference rejects when the nearest-class distance exceeds
        // MID * TOLERANCE (113.8 us), not 10% of the class that matched.
        // For a SHORT cell that boundary is 795 + 113.8 = 908.8.
        val inside = encode(allBits(0))
        inside[DATA_CELL_START] = 908
        assertEquals(2L, requireNotNull(Hcs301Decoder.decodeFrame(inside)).hopping and 2L)

        val outside = encode(allBits(0))
        outside[DATA_CELL_START] = 909
        assertNull(Hcs301Decoder.decodeFrame(outside))
    }

    // --- Scale interaction --------------------------------------------------

    @Test
    fun scaledFrameDecodesWithMatchingScale() {
        val bits = bitsFor(0x12345678L, 0x00FEDCBAL, button = 0x5, vlow = false, repeat = true)
        // 8% slow: the ~1482 us LONG cells drift past MID's 113.8 us window.
        val pulses = encode(bits, scale = 1.08)

        assertNull(Hcs301Decoder.decodeFrame(pulses, scale = 1.0))
        val signal = requireNotNull(Hcs301Decoder.decodeFrame(pulses, scale = 1.08))
        assertEquals(0x12345678L, signal.hopping)
        assertEquals(0x00FEDCBAL, signal.serial)
        assertEquals(0x5, signal.button)
        assertEquals(true, signal.repeat)
        assertEquals(1.08, signal.scale, 0.0)
    }

    // --- FramePacket overload ----------------------------------------------

    @Test
    fun framePacketOverloadPassesMetadataThrough() {
        val bits = bitsFor(0xCAFEBABEL, 0x0000123L, button = 0x1, vlow = true, repeat = false)
        val frame = com.eelab.rflab.protocol.FramePacket(
            version = 1,
            flags = 0,
            sequence = 4242,
            timestampUs = 123_456_789L,
            droppedFrames = 3,
            pulses = encode(bits),
            initialLevel = 1,
            receiverId = 1,
        )

        val signal = requireNotNull(Hcs301Decoder.decodeFrame(frame))

        assertEquals(0xCAFEBABEL, signal.hopping)
        assertEquals(4242, signal.sequence)
        assertEquals(123_456_789L, signal.timestampUs)
        assertEquals(1.0, signal.scale, 0.0)
    }
}
