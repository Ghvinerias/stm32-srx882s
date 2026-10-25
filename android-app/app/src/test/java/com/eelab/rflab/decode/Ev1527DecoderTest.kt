package com.eelab.rflab.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EV1527 decode tests.
 *
 * EV1527's bit extraction *is* [PrincetonProtocol.decodeBits] (the two share
 * one on-air encoding — see [Ev1527Protocol]'s doc comment), which is already
 * independently differential-tested against a standalone build of upstream's
 * C decoder in [PrincetonDecoderTest]. These tests therefore focus on what is
 * genuinely new for EV1527: the fixed 20-bit-address / 4-bit-data field split
 * ([Ev1527Protocol.parseFields]), plus confirmation that the shared
 * recognition/decode path behaves identically when reached through
 * [Ev1527Protocol]. The frame builder matches [PrincetonDecoderTest]'s, so a
 * valid Princeton frame and a valid EV1527 frame are, correctly, the same
 * pulses.
 */
class Ev1527DecoderTest {

    private val short = PrincetonProtocol.TE_SHORT_US
    private val long = PrincetonProtocol.TE_LONG_US
    private val syncGap = short * 30

    private fun repeatEdges(data: Long): List<Int> {
        val edges = mutableListOf<Int>()
        for (i in Ev1527Protocol.DATA_BITS - 1 downTo 0) {
            val bit = ((data ushr i) and 1L).toInt()
            if (bit == 1) {
                edges += long; edges += short
            } else {
                edges += short; edges += long
            }
        }
        edges += short
        edges += syncGap
        return edges
    }

    private fun twoRepeatFrame(data: Long): IntArray =
        (listOf(syncGap) + repeatEdges(data) + repeatEdges(data)).toIntArray()

    // --- field split (the genuinely new logic) --------------------------------

    @Test
    fun parseFieldsSplitsUpper20BitsAsAddressAndLow4AsData() {
        val (address, data) = Ev1527Protocol.parseFields(0xABCDEFL)
        assertEquals(0xABCDEL, address)
        assertEquals(0xF, data)
    }

    @Test
    fun parseFieldsHandlesAllZeroAndAllOneBoundaries() {
        val (addrLo, dataLo) = Ev1527Protocol.parseFields(0x000000L)
        assertEquals(0x0L, addrLo)
        assertEquals(0x0, dataLo)

        val (addrHi, dataHi) = Ev1527Protocol.parseFields(0xFFFFFFL)
        assertEquals(0xFFFFFL, addrHi)
        assertEquals(0xF, dataHi)
    }

    @Test
    fun parseFieldsNeverReadsOutsideThe24BitCode() {
        // Address is 20 bits, data 4 bits; nothing above bit 23 exists in a
        // real frame, but the split must still mask cleanly if handed more.
        val (address, data) = Ev1527Protocol.parseFields(0x123456L)
        assertEquals(0x12345L, address)
        assertEquals(0x6, data)
        assertEquals(Ev1527Protocol.ADDRESS_BITS + Ev1527Protocol.DATA_NIBBLE_BITS, 24)
    }

    // --- decode via the shared Princeton core ---------------------------------

    @Test
    fun decodeFrameDecodesAndSplitsATypicalCode() {
        val frame = Ev1527Protocol.decodeFrame(twoRepeatFrame(0x1A2B3CL), initialLevel = 0)
        assertEquals(0x1A2B3CL, frame?.data)
        assertEquals(Ev1527Protocol.DATA_BITS, frame?.bitCount)
        assertEquals(0x1A2B3L, frame?.address)
        assertEquals(0xC, frame?.button)
    }

    @Test
    fun decodeMatchesThePrincetonCoreOnTheSameFrame() {
        // Same pulses must decode to the same 24-bit value through either
        // object; only the field interpretation differs.
        val pulses = twoRepeatFrame(0xABCDEFL)
        val ev = Ev1527Protocol.decodeFrame(pulses, initialLevel = 0)
        val pt = PrincetonProtocol.decodeFrame(pulses, initialLevel = 0)
        assertEquals(pt?.data, ev?.data)
    }

    @Test
    fun recognitionIsSharedWithPrinceton() {
        val pulses = twoRepeatFrame(0x0F0F0FL)
        assertEquals(
            PrincetonProtocol.recognize(pulses, initialLevel = 0),
            Ev1527Protocol.recognize(pulses, initialLevel = 0),
        )
        assertTrue(Ev1527Protocol.recognize(pulses, initialLevel = 0))
    }

    // --- malformed input ------------------------------------------------------

    @Test
    fun emptyPulsesDecodeToNull() {
        assertNull(Ev1527Protocol.decodeFrame(IntArray(0), initialLevel = 0))
    }

    @Test
    fun singleRepeatWithoutConfirmationIsRejected() {
        val edges = (listOf(syncGap) + repeatEdges(0x112233L)).toIntArray()
        assertNull(Ev1527Protocol.decodeFrame(edges, initialLevel = 0))
    }

    @Test
    fun randomNoiseNeverDecodes() {
        val noise = IntArray(60) { 700 + (it % 5) * 17 }
        assertFalse(Ev1527Protocol.recognize(noise, initialLevel = 0))
        assertNull(Ev1527Protocol.decodeFrame(noise, initialLevel = 0))
    }

    @Test
    fun hcs301ShapedPulsesAreNotMisreadAsEv1527() {
        val hcs301Pulses = IntArray(FRAME_PULSE_COUNT) { MID_US }
        assertNull(Ev1527Protocol.decodeFrame(hcs301Pulses, initialLevel = 0))
    }
}
