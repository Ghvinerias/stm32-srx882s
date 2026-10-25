package com.eelab.rflab.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holtek HT12 decode tests.
 *
 * The known-answer vectors here are genuinely independent of this Kotlin
 * decoder: the test frame builder follows upstream's **encoder** spec from the
 * same `holtek_ht12x.c` (pinned commit
 * `0d295f581c1e34cd56e2e590ea85625a64c2b724`,
 * `subghz_protocol_encoder_holtek_th12x_get_upload`): a `te*36` low header, a
 * `te` high start bit, then per data bit either `low te*2 / high te` (a `1`)
 * or `low te / high te*2` (a `0`). [Ht12Protocol.decodeBits] is a port of
 * upstream's independent `feed()` *decoder* from the same file. So a passing
 * vector is upstream-encode → this-decode agreeing — a cross-check of the two
 * upstream specs, not a loop against constants this test invented. No real
 * HT12 radio capture exists in this repo, so none of this is hardware
 * verification.
 */
class Ht12DecoderTest {

    private val te = Ht12Protocol.TE_SHORT_US // upstream's encoder "te"; te_long == te*2

    /** One repeat's edges per upstream's encoder, MSB-first; header low doubles as boundary. */
    private fun repeatEdges(data: Long): List<Int> {
        val edges = mutableListOf<Int>()
        edges += te // start bit (high)
        for (i in Ht12Protocol.DATA_BITS - 1 downTo 0) {
            val bit = ((data ushr i) and 1L).toInt()
            if (bit == 1) {
                edges += te * 2 // low
                edges += te // high
            } else {
                edges += te // low
                edges += te * 2 // high
            }
        }
        return edges
    }

    /** Two identical repeats — what [Ht12Protocol.decodeBits] requires to accept. */
    private fun twoRepeatFrame(data: Long): IntArray {
        val header = te * 36 // low preamble; also the inter-repeat boundary
        val edges = mutableListOf(header)
        edges += repeatEdges(data)
        edges += header
        edges += repeatEdges(data)
        edges += header
        return edges.toIntArray()
    }

    private data class Kat(val data: Long, val address: Long, val button: Int)

    private val vectors = listOf(
        Kat(0x001L, 0x00L, 0x1),
        Kat(0xFFFL, 0xFFL, 0xF),
        Kat(0xABCL, 0xABL, 0xC),
        Kat(0x800L, 0x80L, 0x0),
        Kat(0x7FFL, 0x7FL, 0xF),
        Kat(0x555L, 0x55L, 0x5),
        Kat(0xAAAL, 0xAAL, 0xA),
        Kat(0x123L, 0x12L, 0x3),
    )

    @Test
    fun decodesUpstreamEncoderVectorsAndSplitsFields() {
        for (kat in vectors) {
            val frame = Ht12Protocol.decodeFrame(twoRepeatFrame(kat.data), initialLevel = 0)
            assertEquals("data 0x${kat.data.toString(16)}", kat.data, frame?.data)
            assertEquals("bitCount", Ht12Protocol.DATA_BITS, frame?.bitCount)
            assertEquals("address 0x${kat.data.toString(16)}", kat.address, frame?.address)
            assertEquals("button 0x${kat.data.toString(16)}", kat.button, frame?.button)
        }
    }

    // --- field split -----------------------------------------------------------

    @Test
    fun parseFieldsSplitsLow4AsButtonAndNext8AsAddress() {
        val (address, button) = Ht12Protocol.parseFields(0xABCL)
        assertEquals(0xABL, address)
        assertEquals(0xC, button)
        assertEquals(Ht12Protocol.ADDRESS_BITS + Ht12Protocol.DATA_NIBBLE_BITS, 12)
    }

    // --- confidence + malformed input ------------------------------------------

    @Test
    fun theLiteralZeroCodeNeverValidates() {
        // Upstream requires last_data != 0, so an all-zero code can never pass.
        assertNull(Ht12Protocol.decodeFrame(twoRepeatFrame(0x000L), initialLevel = 0))
    }

    @Test
    fun aSingleRepeatWithoutConfirmationIsRejected() {
        val edges = mutableListOf(te * 36)
        edges += repeatEdges(0xABCL)
        edges += te * 36
        assertNull(Ht12Protocol.decodeBits(edges.toIntArray(), initialLevel = 0))
    }

    @Test
    fun twoDisagreeingRepeatsAreRejected() {
        val edges = mutableListOf(te * 36)
        edges += repeatEdges(0x111L)
        edges += te * 36
        edges += repeatEdges(0x222L)
        edges += te * 36
        assertNull(Ht12Protocol.decodeBits(edges.toIntArray(), initialLevel = 0))
    }

    @Test
    fun emptyAndNoiseInputsDecodeToNull() {
        assertNull(Ht12Protocol.decodeFrame(IntArray(0), initialLevel = 0))
        val noise = IntArray(60) { 1500 + (it % 5) * 23 }
        assertFalse(Ht12Protocol.recognize(noise, initialLevel = 0))
        assertNull(Ht12Protocol.decodeFrame(noise, initialLevel = 0))
    }

    // --- cross-protocol: recognition is loose, decode is strict ----------------

    @Test
    fun recognitionIsTrueForAValidPreamble() {
        assertTrue(Ht12Protocol.recognize(twoRepeatFrame(0xABCL), initialLevel = 0))
    }

    @Test
    fun a24BitPrincetonFrameDoesNotDecodeAsHt12() {
        // Princeton's 390/1170us cells are outside HT12's 320/640 tolerances,
        // so even though the long sync gap passes the loose recognizer, the
        // strict bit decode rejects it.
        val short = PrincetonProtocol.TE_SHORT_US
        val long = PrincetonProtocol.TE_LONG_US
        val syncGap = short * 30
        val edges = mutableListOf(syncGap)
        for (rep in 0 until 2) {
            for (i in 23 downTo 0) {
                edges += long; edges += short // all-ones Princeton bits
            }
            edges += short
            edges += syncGap
        }
        assertNull(Ht12Protocol.decodeFrame(edges.toIntArray(), initialLevel = 0))
    }

    @Test
    fun hcs301ShapedPulsesAreNotMisreadAsHt12() {
        val hcs301Pulses = IntArray(FRAME_PULSE_COUNT) { MID_US }
        assertNull(Ht12Protocol.decodeFrame(hcs301Pulses, initialLevel = 0))
    }
}
