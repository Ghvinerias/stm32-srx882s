package com.eelab.rflab.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ground-truth validation against the Python reference.
 *
 * The capture files under `src/test/resources/fixtures/` are byte-for-byte
 * copies of the `*.ndjson` files in `tools/captures/`. The expected values below were
 * produced by running the authoritative reference on the same files:
 *
 * ```
 * python3 tools/decode_hcs301.py tools/captures/r1-btn-1.ndjson
 * python3 tools/decode_hcs301.py tools/captures/r3-test.ndjson
 * python3 tools/decode_hcs301.py tools/captures/r3-new-test.ndjson
 * ```
 *
 * For each capture the test estimates the scale exactly as the Python tool
 * does (pooled data cells over every frame in the file), decodes every
 * frame, and requires the same hopping/serial/button/vlow/repeat values.
 * `r3-test` matters most for the estimator: its transmitter runs ~4% slow,
 * so the auto scale is 1.0404 rather than 1.0.
 */
class RealCaptureDecodeTest {

    private data class Expected(
        val sequence: Int,
        val hopping: Long,
        val serial: Long,
        val button: Int,
        val vlow: Int,
        val repeat: Int,
    )

    private fun assertCaptureMatchesPython(
        capture: String,
        expectedScale: Double,
        expected: List<Expected>,
    ) {
        val frames = CaptureFixtures.load(capture)
        assertEquals("frame count for $capture", expected.size, frames.size)

        val estimator = ScaleEstimator()
        estimator.addFrames(frames.map { it.pulses })
        assertEquals("scale for $capture", expectedScale, estimator.scale, 1e-12)

        frames.zip(expected).forEach { (frame, exp) ->
            assertEquals("raw pulse count for seq ${frame.sequence}", FRAME_PULSE_COUNT, frame.pulses.size)

            val decoded = Hcs301Decoder.decodeFrame(frame, estimator.scale)
            assertNotNull("seq ${frame.sequence} in $capture should decode", decoded)
            val signal = requireNotNull(decoded)

            assertEquals("sequence", exp.sequence, signal.sequence)
            assertEquals("hopping for seq ${frame.sequence}", exp.hopping, signal.hopping)
            assertEquals("serial for seq ${frame.sequence}", exp.serial, signal.serial)
            assertEquals("button for seq ${frame.sequence}", exp.button, signal.button)
            assertEquals("vlow for seq ${frame.sequence}", exp.vlow != 0, signal.vlow)
            assertEquals("repeat for seq ${frame.sequence}", exp.repeat != 0, signal.repeat)
            assertEquals("timestamp passthrough for seq ${frame.sequence}", frame.timestampUs, signal.timestampUs)
            assertEquals("scale recorded for seq ${frame.sequence}", estimator.scale, signal.scale, 0.0)

            // The bare pulse-array overload must agree with the FramePacket
            // overload on every field decoded from the pulses.
            val bareDecoded = Hcs301Decoder.decodeFrame(frame.pulses, estimator.scale)
            assertNotNull("seq ${frame.sequence} should decode from bare pulses", bareDecoded)
            val bare = requireNotNull(bareDecoded)
            assertEquals(signal.hopping, bare.hopping)
            assertEquals(signal.serial, bare.serial)
            assertEquals(signal.button, bare.button)
            assertEquals(signal.vlow, bare.vlow)
            assertEquals(signal.repeat, bare.repeat)
        }
    }

    @Test
    fun r1Btn1MatchesPython() {
        assertCaptureMatchesPython(
            capture = "r1-btn-1",
            expectedScale = 0.9991212653778558,
            expected = listOf(
                Expected(221, 0x63FD859DL, 0xFB58C0CL, 0x2, 0, 0),
                Expected(222, 0x63FD859DL, 0xFB58C0CL, 0x2, 0, 1),
                Expected(223, 0x63FD859DL, 0xFB58C0CL, 0x2, 0, 1),
                Expected(224, 0x63FD859DL, 0xFB58C0CL, 0x2, 0, 1),
                Expected(227, 0x93C362DEL, 0xFB58C0CL, 0x2, 0, 0),
                Expected(228, 0x93C362DEL, 0xFB58C0CL, 0x2, 0, 1),
                Expected(229, 0x93C362DEL, 0xFB58C0CL, 0x2, 0, 1),
                Expected(230, 0x93C362DEL, 0xFB58C0CL, 0x2, 0, 1),
                Expected(231, 0x93C362DEL, 0xFB58C0CL, 0x2, 0, 1),
                Expected(234, 0x3CC9E216L, 0xFB58C0CL, 0x2, 0, 0),
                Expected(235, 0x3CC9E216L, 0xFB58C0CL, 0x2, 0, 1),
                Expected(236, 0x3CC9E216L, 0xFB58C0CL, 0x2, 0, 1),
                Expected(237, 0x3CC9E216L, 0xFB58C0CL, 0x2, 0, 1),
                Expected(238, 0x3CC9E216L, 0xFB58C0CL, 0x2, 0, 1),
                Expected(241, 0x114766B9L, 0xFB58C0CL, 0x2, 0, 0),
                Expected(242, 0x114766B9L, 0xFB58C0CL, 0x2, 0, 1),
                Expected(243, 0x114766B9L, 0xFB58C0CL, 0x2, 0, 1),
                Expected(244, 0x114766B9L, 0xFB58C0CL, 0x2, 0, 1),
                Expected(246, 0x114766B9L, 0xFB58C0CL, 0x2, 0, 1),
            ),
        )
    }

    @Test
    fun r3TestMatchesPython() {
        assertCaptureMatchesPython(
            capture = "r3-test",
            expectedScale = 1.0404217926186292,
            expected = listOf(
                Expected(4849, 0x9CCA1A64L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4850, 0x9CCA1A64L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4851, 0x9CCA1A64L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4852, 0x9CCA1A64L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4853, 0x9CCA1A64L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4856, 0xFC849C22L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4857, 0xFC849C22L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4858, 0xFC849C22L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4859, 0xFC849C22L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4863, 0x0C38C3B7L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4864, 0x0C38C3B7L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4865, 0x0C38C3B7L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4866, 0x0C38C3B7L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4867, 0x0C38C3B7L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4871, 0x8C23397BL, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4872, 0x8C23397BL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4873, 0x8C23397BL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4874, 0x8C23397BL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4875, 0x8C23397BL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4878, 0x29BBE591L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4879, 0x29BBE591L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4880, 0x29BBE591L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4882, 0x29BBE591L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4883, 0x29BBE591L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4884, 0x29BBE591L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4887, 0x0DD96367L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4888, 0x0DD96367L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4889, 0x0DD96367L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4890, 0x0DD96367L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4891, 0x0DD96367L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4894, 0x5B82A141L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4895, 0x5B82A141L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4896, 0x5B82A141L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4897, 0x5B82A141L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4899, 0x5B82A141L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4901, 0x2B74687DL, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4903, 0x2B74687DL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4904, 0x2B74687DL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4905, 0x2B74687DL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4906, 0x2B74687DL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4909, 0xE6DBA20EL, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4910, 0xE6DBA20EL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4911, 0xE6DBA20EL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4912, 0xE6DBA20EL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4915, 0x536BA778L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4916, 0x536BA778L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4917, 0x536BA778L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4918, 0x536BA778L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4919, 0x536BA778L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4922, 0xAFF21FEAL, 0xFB3EC77L, 0x2, 0, 0),
                Expected(4923, 0xAFF21FEAL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(4924, 0xAFF21FEAL, 0xFB3EC77L, 0x2, 0, 1),
            ),
        )
    }

    @Test
    fun r3NewTestMatchesPython() {
        assertCaptureMatchesPython(
            capture = "r3-new-test",
            expectedScale = 1.0404217926186292,
            expected = listOf(
                Expected(8190, 0xF3700057L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(8191, 0xF3700057L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8192, 0xF3700057L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8193, 0xF3700057L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8195, 0xB791827FL, 0xFB3EC77L, 0x2, 0, 0),
                Expected(8196, 0xB791827FL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8197, 0xB791827FL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8198, 0xB791827FL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8201, 0xAA63149EL, 0xFB3EC77L, 0x2, 0, 0),
                Expected(8202, 0xAA63149EL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8203, 0xAA63149EL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8204, 0xAA63149EL, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8206, 0xAC917244L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(8207, 0xAC917244L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8208, 0xAC917244L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8209, 0xAC917244L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8211, 0xCF3EED68L, 0xFB3EC77L, 0x2, 0, 0),
                Expected(8212, 0xCF3EED68L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8214, 0xCF3EED68L, 0xFB3EC77L, 0x2, 0, 1),
                Expected(8215, 0xCF3EED68L, 0xFB3EC77L, 0x2, 0, 1),
            ),
        )
    }

    /**
     * Same capture, uncalibrated (scale 1.0): the Python reference decodes
     * only 27 of the 52 frames. Its ~4.35 ms header cell sits exactly
     * between the two header centres at nominal scale, and the reference's
     * `<=` tie-break picks bit 0, contradicting the LONG first data cell.
     * This asserts the Kotlin port reproduces that exact failure set size
     * and the specific first failing frame.
     */
    @Test
    fun r3TestUncalibratedDecodesSameSubsetAsPython() {
        val frames = CaptureFixtures.load("r3-test")

        val decoded = frames.mapNotNull { Hcs301Decoder.decodeFrame(it, 1.0) }
        assertEquals(27, decoded.size)

        val firstFailing = frames.first { it.sequence == 4863 }
        assertNull(Hcs301Decoder.decodeFrame(firstFailing, 1.0))
        assertNotNull(Hcs301Decoder.decodeFrame(firstFailing, 1.0404217926186292))
    }
}
