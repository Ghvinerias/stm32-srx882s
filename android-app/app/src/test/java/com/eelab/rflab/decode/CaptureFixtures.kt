package com.eelab.rflab.decode

import com.eelab.rflab.protocol.FramePacket
import java.io.InputStream

/**
 * Loads real capture fixtures copied verbatim from `tools/captures/` (the
 * `*.ndjson` files) into `src/test/resources/fixtures/`.
 *
 * Only the fields needed to rebuild a [FramePacket] are parsed, with small
 * regexes instead of pulling a JSON dependency into the app's test classpath.
 * The capture files are NDJSON with one compact JSON object per line.
 */
object CaptureFixtures {

    private val pulsesRegex = Regex("\"pulses_us\"\\s*:\\s*\\[([^\\]]*)\\]")
    private val sequenceRegex = Regex("\"sequence\"\\s*:\\s*(\\d+)")
    private val timestampRegex = Regex("\"timestamp_us\"\\s*:\\s*(\\d+)")
    private val versionRegex = Regex("\"version\"\\s*:\\s*(\\d+)")
    private val flagsRegex = Regex("\"flags\"\\s*:\\s*(\\d+)")
    private val droppedFramesRegex = Regex("\"dropped_frames\"\\s*:\\s*(\\d+)")
    private val initialLevelRegex = Regex("\"initial_level\"\\s*:\\s*(\\d+)")
    private val receiverIdRegex = Regex("\"receiver_id\"\\s*:\\s*(\\d+)")

    /** Load every `"type":"frame"` line of a capture, in file order. */
    fun load(name: String): List<FramePacket> {
        val path = "/fixtures/$name.ndjson"
        val stream: InputStream = requireNotNull(
            CaptureFixtures::class.java.getResourceAsStream(path),
        ) { "missing capture fixture $path" }

        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.contains("\"type\":\"frame\"") }
                .map { parseFrame(it) }
                .toList()
        }
    }

    private fun parseFrame(line: String): FramePacket {
        val pulses = pulsesRegex.find(line)?.groupValues?.get(1)
            ?.trim()
            ?.let { body ->
                if (body.isEmpty()) {
                    IntArray(0)
                } else {
                    body.split(',').map { it.trim().toInt() }.toIntArray()
                }
            }
            ?: error("frame line has no pulses_us array")

        return FramePacket(
            version = line.findInt(versionRegex) ?: 1,
            flags = line.findInt(flagsRegex) ?: 0,
            sequence = line.findInt(sequenceRegex) ?: error("frame line has no sequence"),
            timestampUs = (line.findInt(timestampRegex) ?: 0).toLong(),
            droppedFrames = line.findInt(droppedFramesRegex) ?: 0,
            pulses = pulses,
            initialLevel = line.findInt(initialLevelRegex) ?: 0,
            receiverId = line.findInt(receiverIdRegex) ?: 1,
        )
    }

    private fun String.findInt(regex: Regex): Int? =
        regex.find(this)?.groupValues?.get(1)?.toInt()
}
