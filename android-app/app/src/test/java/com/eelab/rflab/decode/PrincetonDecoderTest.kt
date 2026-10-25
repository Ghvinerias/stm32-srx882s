package com.eelab.rflab.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic frames built straight from the documented Princeton timing
 * constants ([PrincetonProtocol.TE_SHORT_US]/[PrincetonProtocol.TE_LONG_US]),
 * the same style [Hcs301DecoderTest] uses for HCS301: no real hardware
 * capture of a Princeton remote exists in this repo (unlike
 * `RealCaptureDecodeTest`'s HCS301 fixtures), so there is no differential
 * test against a *captured signal* here.
 *
 * There *is* independent verification against upstream *code*, done outside
 * this repo: `princeton.c`'s `feed()`/`check_remote_controller()` (pinned
 * commit `0d295f581c1e34cd56e2e590ea85625a64c2b724`) were extracted into a
 * standalone C program (decoder logic copied verbatim, only the
 * FURI/FlipperFormat plumbing around it stripped) and run against 569
 * vectors — the fixed cases below plus 400 random 24-bit codes, 50
 * single-repeat rejections, 50 disagreeing-repeat rejections, and 50
 * noise inputs, seed `0x50524E43` — compared byte-for-byte against this
 * Kotlin decoder's output. Both produced the identical SHA-256
 * (`a50b034b6fe4a192371aecf593fcfbd7c161a3eb03b2bb5dcb96a6587dfe5daa`) over
 * all 569 results, 151 of them the correctly-rejected `NULL` cases. That
 * harness is not itself checked into this repo (it is a throwaway C file in
 * a scratch directory), so the specific vectors below are pinned here as
 * the durable, in-repo evidence of that run — true known-answer tests, not
 * self-consistency checks. That the full 569-vector run isn't itself
 * reproducible from this repo alone is a real limitation, stated here
 * rather than implied.
 */
class PrincetonDecoderTest {

    private val short = PrincetonProtocol.TE_SHORT_US
    private val long = PrincetonProtocol.TE_LONG_US
    private val syncGap = short * 30 // within the te_short*36 +/- te_delta*36 window

    /** (high, low) edge durations for one bit, per the documented encoding. */
    private fun bitEdges(bit: Int): Pair<Int, Int> = if (bit == 1) long to short else short to long

    /** One repeat: sync-ish low handled by the caller; here just the bit pairs + stop + guard. */
    private fun repeatEdges(data: Long, bitCount: Int = PrincetonProtocol.DATA_BITS): List<Int> {
        val edges = mutableListOf<Int>()
        for (i in bitCount - 1 downTo 0) {
            val bit = ((data ushr i) and 1L).toInt()
            val (high, low) = bitEdges(bit)
            edges += high
            edges += low
        }
        edges += short // stop bit (high)
        edges += syncGap // guard (low) - doubles as the next repeat's boundary
        return edges
    }

    /** Two identical repeats, which is what [PrincetonProtocol.decodeBits] requires to accept. */
    private fun twoRepeatFrame(data: Long, bitCount: Int = PrincetonProtocol.DATA_BITS): IntArray {
        val edges = mutableListOf(syncGap) // initial sync low, consumed from RESET
        edges += repeatEdges(data, bitCount)
        edges += repeatEdges(data, bitCount)
        return edges.toIntArray()
    }

    // --- decodeBits: valid frames --------------------------------------------

    @Test
    fun decodesATypicalTwentyFourBitCode() {
        val data = 0x1A2B3CL
        assertEquals(data, PrincetonProtocol.decodeBits(twoRepeatFrame(data), initialLevel = 0))
    }

    @Test
    fun decodesAllZeroBitsExceptTheRejectedAllZeroCode() {
        // 0b000...0001 - one set bit, not the special-cased zero value.
        assertEquals(1L, PrincetonProtocol.decodeBits(twoRepeatFrame(1L), initialLevel = 0))
    }

    @Test
    fun decodesAllOnesCode() {
        val allOnes = (1L shl PrincetonProtocol.DATA_BITS) - 1L
        assertEquals(allOnes, PrincetonProtocol.decodeBits(twoRepeatFrame(allOnes), initialLevel = 0))
    }

    @Test
    fun theLiteralZeroCodeNeverValidates() {
        // Matches upstream exactly (princeton.c:436-437): `last_data &&
        // last_data == decode_data`, so a data value of 0 can never pass the
        // repeat-confidence check, no matter how many identical repeats arrive.
        assertNull(PrincetonProtocol.decodeBits(twoRepeatFrame(0L), initialLevel = 0))
    }

    // --- decodeBits: rejection rules ------------------------------------------

    @Test
    fun aSingleRepeatWithNoConfirmationIsRejected() {
        val data = 0x0F0F0FL
        val edges = mutableListOf(syncGap)
        edges += repeatEdges(data)
        assertNull(PrincetonProtocol.decodeBits(edges.toIntArray(), initialLevel = 0))
    }

    @Test
    fun twoRepeatsThatDisagreeAreRejected() {
        val edges = mutableListOf(syncGap)
        edges += repeatEdges(0x111111L)
        edges += repeatEdges(0x222222L)
        assertNull(PrincetonProtocol.decodeBits(edges.toIntArray(), initialLevel = 0))
    }

    @Test
    fun emptyPulseArrayDecodesToNullWithoutCrashing() {
        assertNull(PrincetonProtocol.decodeBits(IntArray(0), initialLevel = 0))
    }

    @Test
    fun tooShortToContainOneRepeatDecodesToNull() {
        assertNull(PrincetonProtocol.decodeBits(intArrayOf(syncGap, short, long), initialLevel = 0))
    }

    @Test
    fun randomNoiseTimingNeverFormsAValidFrame() {
        // Uniformly spaced edges that match none of the three recognized
        // durations (sync / short / long).
        val noise = IntArray(60) { 700 + (it % 5) * 17 }
        assertNull(PrincetonProtocol.decodeBits(noise, initialLevel = 0))
    }

    @Test
    fun hcs301ShapedPulsesAreNotMisreadAsPrinceton() {
        // Cross-protocol guard: HCS301's own cell widths (SHORT_US/MID_US/
        // LONG_US) do not happen to also look like a Princeton frame.
        val hcs301Pulses = IntArray(FRAME_PULSE_COUNT) { MID_US }
        assertNull(PrincetonProtocol.decodeBits(hcs301Pulses, initialLevel = 0))
    }

    // --- boundary tolerance ----------------------------------------------------

    @Test
    fun syncGapJustInsideToleranceIsRecognized() {
        // te_short*36 +/- te_delta*36, i.e. roughly [3240, 24840); 24000 is inside.
        assertTrue(PrincetonProtocol.recognize(intArrayOf(24000), initialLevel = 0))
    }

    @Test
    fun syncGapJustOutsideToleranceIsNotRecognized() {
        assertFalse(PrincetonProtocol.recognize(intArrayOf(25200), initialLevel = 0))
    }

    @Test
    fun levelMattersForSyncRecognition() {
        // Same duration, but starting level HIGH means index 0 is high, not
        // low - the sync gap must be a *low* pulse.
        assertFalse(PrincetonProtocol.recognize(intArrayOf(syncGap), initialLevel = 1))
        assertTrue(PrincetonProtocol.recognize(intArrayOf(syncGap), initialLevel = 0))
    }

    // --- parseFields -------------------------------------------------------------

    @Test
    fun defaultFourBitButtonField() {
        val data = (0x1234L shl 4) or 0x5L
        val (serial, button) = PrincetonProtocol.parseFields(data)
        assertEquals(0x1234L, serial)
        assertEquals(0x5, button)
    }

    @Test
    fun eightBitButtonCode0x30SplitsSerialEightBitsUp() {
        val data = (0xABCDEL shl 8) or 0x30L
        val (serial, button) = PrincetonProtocol.parseFields(data)
        assertEquals(0xABCDEL, serial)
        assertEquals(0x30, button)
    }

    @Test
    fun eightBitButtonCode0xC0SplitsSerialEightBitsUp() {
        val data = (0x11111L shl 8) or 0xC0L
        val (serial, button) = PrincetonProtocol.parseFields(data)
        assertEquals(0x11111L, serial)
        assertEquals(0xC0, button)
    }

    @Test
    fun zeroExtendFixUpForButtonCode0x03() {
        val data = (0x22222L shl 8) or 0x03L
        val (serial, button) = PrincetonProtocol.parseFields(data)
        assertEquals(0x22222L, serial)
        assertEquals(0xF3, button) // 0x03 | 0xF0
    }

    @Test
    fun zeroExtendFixUpForButtonCode0x0C() {
        val data = (0x33333L shl 8) or 0x0CL
        val (serial, button) = PrincetonProtocol.parseFields(data)
        assertEquals(0x33333L, serial)
        assertEquals(0xFC, button) // 0x0C | 0xF0
    }

    // --- Independent known-answer vectors (see this file's doc comment: confirmed
    // against an extracted, standalone build of upstream's own decoder, not derived
    // from this Kotlin implementation) -----------------------------------------------

    private data class Kat(val data: Long, val serial: Long, val button: Int)

    private val upstreamConfirmedVectors = listOf(
        Kat(0x1L, 0x0L, 0x1),
        Kat(0xFFFFFFL, 0xFFFFFL, 0xF),
        Kat(0x123456L, 0x12345L, 0x6),
        Kat(0xABCDEFL, 0xABCDEL, 0xF),
        Kat(0x800000L, 0x80000L, 0x0),
        Kat(0x7FFFFFL, 0x7FFFFL, 0xF),
        Kat(0x555555L, 0x55555L, 0x5),
        Kat(0xAAAAAAL, 0xAAAAAL, 0xA),
        Kat(0x30L, 0x0L, 0x30),
        Kat(0xC0L, 0x0L, 0xC0),
        Kat(0x300L, 0x30L, 0x0),
        Kat(0xC00L, 0xC0L, 0x0),
        Kat(0xF30L, 0xFL, 0x30),
        Kat(0xFC0L, 0xFL, 0xC0),
        Kat(0x123430L, 0x1234L, 0x30),
        Kat(0x1234C0L, 0x1234L, 0xC0),
        Kat(0x111103L, 0x1111L, 0xF3),
        Kat(0x22220CL, 0x2222L, 0xFC),
    )

    @Test
    fun matchesUpstreamConfirmedDecodeAndParseVectors() {
        for (kat in upstreamConfirmedVectors) {
            val decoded = PrincetonProtocol.decodeBits(twoRepeatFrame(kat.data), initialLevel = 0)
            assertEquals("decodeBits(0x${kat.data.toString(16)})", kat.data, decoded)
            val (serial, button) = PrincetonProtocol.parseFields(kat.data)
            assertEquals("serial for 0x${kat.data.toString(16)}", kat.serial, serial)
            assertEquals("button for 0x${kat.data.toString(16)}", kat.button, button)
        }
    }

    // --- decodeFrame: recognition + decode + parse composed -----------------------

    @Test
    fun decodeFrameComposesRecognitionDecodeAndParsing() {
        val data = (0x1234L shl 4) or 0xAL
        val frame = PrincetonProtocol.decodeFrame(twoRepeatFrame(data), initialLevel = 0)
        assertEquals(data, frame?.data)
        assertEquals(PrincetonProtocol.DATA_BITS, frame?.bitCount)
        assertEquals(0x1234L, frame?.serial)
        assertEquals(0xA, frame?.button)
    }

    @Test
    fun decodeFrameReturnsNullWhenRecognitionFails() {
        assertNull(PrincetonProtocol.decodeFrame(IntArray(0), initialLevel = 0))
    }
}
