package com.eelab.rflab.usb

import com.eelab.rflab.protocol.AckPacket
import com.eelab.rflab.protocol.Cobs
import com.eelab.rflab.protocol.Crc16
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.protocol.PacketCodec
import com.eelab.rflab.protocol.PacketDecodeResult
import com.eelab.rflab.protocol.PacketFlags
import com.eelab.rflab.protocol.PacketType
import com.eelab.rflab.protocol.PROTOCOL_VERSION
import com.eelab.rflab.protocol.ScenarioCommand
import com.eelab.rflab.protocol.StatusPacket
import com.eelab.rflab.protocol.TxCommand
import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the stream reassembler. No USB and no Android APIs are
 * involved: [PacketReassembler] only consumes bytes, and these tests drive
 * it with wire bytes produced by the real session 2/3 codecs.
 *
 * The framing under test mirrors `transport_cobs_encode` in
 * `firmware/src/transport.c`: COBS body followed by one `0x00` delimiter.
 */
class PacketReassemblerTest {

    // ---- Happy path ------------------------------------------------------

    @Test
    fun decodesSinglePacketDeliveredInOneChunk() {
        val frame = sampleFrame(sequence = 1)
        val reassembler = PacketReassembler()

        val events = reassembler.feed(framed(rawFrame(frame)))

        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
            events,
        )
        assertEquals(0, reassembler.bufferedByteCount)
    }

    @Test
    fun decodesPacketDeliveredOneByteAtATime() {
        val frame = sampleFrame(sequence = 2)
        val wire = framed(rawFrame(frame))
        val reassembler = PacketReassembler()

        val events = mutableListOf<TransportEvent>()
        for (byte in wire) {
            events += reassembler.feed(byteArrayOf(byte))
        }

        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
            events,
        )
    }

    @Test
    fun decodesPacketSplitAtEveryPossibleBoundary() {
        val frame = sampleFrame(sequence = 3)
        val wire = framed(rawFrame(frame))

        for (split in 1 until wire.size) {
            val reassembler = PacketReassembler()

            val first = reassembler.feed(wire, 0, split)
            val second = reassembler.feed(wire, split, wire.size - split)

            assertTrue(
                "packet completed early when split at $split: $first",
                first.isEmpty(),
            )
            assertEquals(
                "split at $split",
                listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
                second,
            )
            assertEquals("split at $split", 0, reassembler.bufferedByteCount)
        }
    }

    @Test
    fun decodesNothingUntilDelimiterArrives() {
        val frame = sampleFrame(sequence = 4)
        val wire = framed(rawFrame(frame))
        val reassembler = PacketReassembler()

        val partial = reassembler.feed(wire, 0, wire.size - 1)

        assertTrue(partial.isEmpty())
        assertEquals(wire.size - 1, reassembler.bufferedByteCount)

        val rest = reassembler.feed(wire, wire.size - 1, 1)

        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
            rest,
        )
    }

    @Test
    fun keepsPacketOrderWhenSeveralArriveInOneChunk() {
        val frame = sampleFrame(sequence = 5)
        val status = sampleStatus(sequence = 6)
        val tx = TxCommand(hopping = 0xDEAD_BEEFL, serial = 0x0123_456L, button = 0xA, frameCount = 3)
        val scenario = ScenarioCommand(ScenarioCommand.ZERO_HOP)
        val ack = AckPacket(forType = PacketType.TX_COMMAND, accepted = true)

        val stream = framed(rawFrame(frame)) +
            framed(rawStatus(status)) +
            framed(PacketCodec.encodeTxCommand(tx)) +
            framed(PacketCodec.encodeScenarioCommand(scenario)) +
            framed(sampleAck(ack))

        val events = PacketReassembler().feed(stream)

        assertEquals(
            listOf<TransportEvent>(
                TransportEvent.PacketReceived(frame),
                TransportEvent.PacketReceived(status),
                TransportEvent.PacketReceived(tx),
                TransportEvent.PacketReceived(scenario),
                TransportEvent.PacketReceived(ack),
            ),
            events,
        )
    }

    @Test
    fun reconstructsPacketsFromRandomChunkBoundaries() {
        val frame = sampleFrame(sequence = 7)
        val status = sampleStatus(sequence = 8)
        val tx = TxCommand(hopping = 0x0102_0304L, serial = 0x00AB_CDEL, button = 0x1, frameCount = 1)
        val scenario = ScenarioCommand(ScenarioCommand.RANDOM_HOP)
        val ack = AckPacket(forType = PacketType.SCENARIO_COMMAND, accepted = false)

        val stream = framed(rawFrame(frame)) +
            framed(rawStatus(status)) +
            framed(PacketCodec.encodeTxCommand(tx)) +
            framed(PacketCodec.encodeScenarioCommand(scenario)) +
            framed(sampleAck(ack))
        val expected = listOf<TransportEvent>(
            TransportEvent.PacketReceived(frame),
            TransportEvent.PacketReceived(status),
            TransportEvent.PacketReceived(tx),
            TransportEvent.PacketReceived(scenario),
            TransportEvent.PacketReceived(ack),
        )

        // USB bulk reads are arbitrary; use many seeds and 1..23-byte chunks
        // so splits land mid-header, mid-payload, mid-CRC and on delimiters.
        for (seed in 0 until 50) {
            val random = Random(seed.toLong())
            val reassembler = PacketReassembler()
            val events = mutableListOf<TransportEvent>()
            var offset = 0
            while (offset < stream.size) {
                val count = minOf(1 + random.nextInt(23), stream.size - offset)
                events += reassembler.feed(stream, offset, count)
                offset += count
            }
            assertEquals("seed $seed", expected, events)
            assertEquals("seed $seed", 0, reassembler.bufferedByteCount)
        }
    }

    @Test
    fun ignoresEmptyBodiesBetweenConsecutiveDelimiters() {
        val frame = sampleFrame(sequence = 9)
        val wire = byteArrayOf(
            PacketReassembler.DELIMITER,
            PacketReassembler.DELIMITER,
        ) + framed(rawFrame(frame)) + byteArrayOf(PacketReassembler.DELIMITER)

        val events = PacketReassembler().feed(wire)

        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
            events,
        )
    }

    // ---- Failure paths ---------------------------------------------------

    @Test
    fun reportsCrcMismatchAndKeepsDecodingFollowingPackets() {
        val good = sampleFrame(sequence = 10)
        val corrupted = PacketCodec.encodeTxCommand(
            TxCommand(hopping = 0x1122_3344L, serial = 0x55L, button = 0x2, frameCount = 2),
        ).copyOf().also { raw ->
            raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0xFF).toByte()
        }

        val events = PacketReassembler().feed(
            framed(corrupted) + framed(rawFrame(good)),
        )

        assertEquals(2, events.size)
        val error = events[0] as TransportEvent.DecodeError
        assertTrue(
            "expected CRC failure, got ${error.failure}",
            error.failure is DecodeFailure.PacketRejected &&
                error.failure.result is PacketDecodeResult.Failure.CrcMismatch,
        )
        assertArrayEquals(Cobs.encode(corrupted), error.wireBody)
        assertArrayEquals(corrupted, error.decodedBody)
        assertEquals(TransportEvent.PacketReceived(good), events[1])
    }

    @Test
    fun reportsInvalidCobsBodyAndResynchronises() {
        // Code byte 0x05 promises four data bytes but only one follows.
        val invalidBody = byteArrayOf(0x05, 0x01)
        val frame = sampleFrame(sequence = 11)
        val wire = invalidBody + PacketReassembler.DELIMITER + framed(rawFrame(frame))

        val events = PacketReassembler().feed(wire)

        assertEquals(2, events.size)
        val error = events[0] as TransportEvent.DecodeError
        assertTrue(
            "expected COBS failure, got ${error.failure}",
            error.failure is DecodeFailure.InvalidCobsBody,
        )
        assertArrayEquals(invalidBody, error.wireBody)
        assertEquals(null, error.decodedBody)
        assertEquals(TransportEvent.PacketReceived(frame), events[1])
    }

    @Test
    fun reportsPacketRejectedByProtocolLayer() {
        // A valid COBS body holding an empty packet decodes to zero bytes,
        // which the packet layer reports as truncated.
        val wire = byteArrayOf(0x01, PacketReassembler.DELIMITER)

        val events = PacketReassembler().feed(wire)

        val error = events.single() as TransportEvent.DecodeError
        assertTrue(error.failure is DecodeFailure.PacketRejected)
        val rejected = (error.failure as DecodeFailure.PacketRejected).result
        assertTrue(rejected is PacketDecodeResult.Failure.Truncated)
        assertEquals(0, error.decodedBody?.size)
    }

    @Test
    fun dropsOversizedUnterminatedFrameAndRecovers() {
        val limit = 64
        val reassembler = PacketReassembler(maxFrameBytes = limit)

        val junk = ByteArray(limit + 1) { 0x42 }
        val overflowEvents = reassembler.feed(junk)

        val error = overflowEvents.single() as TransportEvent.DecodeError
        val tooLong = error.failure as DecodeFailure.FrameTooLong
        assertEquals(limit, tooLong.limitBytes)
        assertEquals(limit + 1, tooLong.actualBytes)
        assertEquals(0, reassembler.bufferedByteCount)

        val ack = AckPacket(forType = PacketType.TX_COMMAND, accepted = true)
        val recovered = reassembler.feed(framed(sampleAck(ack)))
        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(ack)),
            recovered,
        )
    }

    @Test
    fun dropsOversizedDelimitedFrameButKeepsFollowingPacket() {
        val limit = 16
        val reassembler = PacketReassembler(maxFrameBytes = limit)
        val oversizedBody = ByteArray(limit + 5) { 0x55 }
        val ack = AckPacket(forType = PacketType.SCENARIO_COMMAND, accepted = true)

        val events = reassembler.feed(
            oversizedBody + PacketReassembler.DELIMITER + framed(sampleAck(ack)),
        )

        assertEquals(2, events.size)
        val error = events[0] as TransportEvent.DecodeError
        assertEquals(
            DecodeFailure.FrameTooLong(limit, oversizedBody.size),
            error.failure,
        )
        assertEquals(TransportEvent.PacketReceived(ack), events[1])
    }

    @Test
    fun resetDiscardsPartialFrame() {
        val frame = sampleFrame(sequence = 12)
        val wire = framed(rawFrame(frame))
        val reassembler = PacketReassembler()

        reassembler.feed(wire, 0, 5)
        assertEquals(5, reassembler.bufferedByteCount)

        reassembler.reset()
        assertEquals(0, reassembler.bufferedByteCount)

        // The bytes after reset are a fresh frame, not the tail of the old one.
        val events = reassembler.feed(framed(rawFrame(frame)))
        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
            events,
        )
    }

    @Test
    fun feedHonoursOffsetAndLength() {
        val frame = sampleFrame(sequence = 13)
        val wire = framed(rawFrame(frame))
        val padded = ByteArray(3) + wire + ByteArray(2)

        val events = PacketReassembler().feed(padded, offset = 3, length = wire.size)

        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
            events,
        )
    }

    @Test
    fun defaultLimitFitsLargestFirmwareFrame() {
        // HW_CAPTURE_MAX_PULSES is 256, so this is the biggest FRAME the
        // device can emit; the default reassembler cap must not reject it.
        val pulses = IntArray(256) { index -> (index * 37 + 11) and 0xFFFF }
        val frame = sampleFrame(sequence = 14).copy(pulses = pulses)
        val wire = framed(rawFrame(frame))

        assertTrue(
            "encoded max-size frame is ${wire.size} bytes, cap is " +
                PacketReassembler.DEFAULT_MAX_FRAME_BYTES,
            wire.size < PacketReassembler.DEFAULT_MAX_FRAME_BYTES,
        )

        assertEquals(
            listOf<TransportEvent>(TransportEvent.PacketReceived(frame)),
            PacketReassembler().feed(wire),
        )
    }

    // ---- Builders --------------------------------------------------------

    /**
     * Builds a FRAME raw packet from the byte table in
     * `android-app/AGENT_BRIEF.md`. [PacketCodec] has no FRAME encoder, so
     * this mirrors `send_packet` in `firmware/src/transport.c`.
     */
    private fun rawFrame(frame: FramePacket): ByteArray {
        val body = RawBuilder()
        body.u8(frame.version)
        body.u8(PacketType.FRAME)
        body.u16(frame.flags)
        body.u16(frame.sequence)
        body.u16(PacketCodec.HEADER_SIZE + frame.pulses.size * 2 + PacketCodec.CRC_SIZE)
        body.u32(frame.timestampUs)
        body.u16(frame.droppedFrames)
        body.u16(frame.pulses.size)
        body.u8(frame.initialLevel)
        body.u8(frame.receiverId)
        frame.pulses.forEach(body::u16)
        return body.withCrc()
    }

    private fun rawStatus(status: StatusPacket): ByteArray {
        val payload = intArrayOf(
            (status.clockHz and 0xFFFF).toInt(),
            ((status.clockHz ushr 16) and 0xFFFF).toInt(),
            (status.totalEdges and 0xFFFF).toInt(),
            ((status.totalEdges ushr 16) and 0xFFFF).toInt(),
            status.droppedPackets,
        )
        val body = RawBuilder()
        body.u8(status.version)
        body.u8(PacketType.STATUS)
        body.u16(status.flags)
        body.u16(status.sequence)
        body.u16(PacketCodec.HEADER_SIZE + payload.size * 2 + PacketCodec.CRC_SIZE)
        body.u32(0)
        body.u16(status.droppedFrames)
        body.u16(payload.size)
        body.u8(0)
        body.u8(1)
        payload.forEach(body::u16)
        return body.withCrc()
    }

    private fun sampleAck(ack: AckPacket): ByteArray {
        val body = RawBuilder()
        body.u8(PROTOCOL_VERSION)
        body.u8(PacketType.ACK)
        body.u8(ack.forType)
        body.u8(if (ack.accepted) 1 else 0)
        return body.withCrc()
    }

    private fun sampleFrame(sequence: Int): FramePacket = FramePacket(
        version = PROTOCOL_VERSION,
        flags = PacketFlags.CAPTURE_TRUNCATED,
        sequence = sequence,
        timestampUs = 1_234_567L + sequence,
        droppedFrames = 2,
        pulses = intArrayOf(4512, 4170, 795, 1138, 795, 795, 1138, 1482),
        initialLevel = 1,
        receiverId = 1,
    )

    private fun sampleStatus(sequence: Int): StatusPacket = StatusPacket(
        version = PROTOCOL_VERSION,
        flags = PacketFlags.CLOCK_FALLBACK,
        sequence = sequence,
        droppedFrames = 4,
        clockHz = 72_000_000L,
        totalEdges = 0xFFFF_007BL,
        droppedPackets = 9,
    )

    /** Appends the `0x00` transport delimiter after a COBS-encoded body. */
    private fun framed(rawPacket: ByteArray): ByteArray =
        Cobs.encode(rawPacket) + PacketReassembler.DELIMITER

    private class RawBuilder {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int) = apply { out.write(value and 0xFF) }

        fun u16(value: Int) = apply {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        fun u32(value: Long) = apply {
            out.write((value and 0xFF).toInt())
            out.write(((value ushr 8) and 0xFF).toInt())
            out.write(((value ushr 16) and 0xFF).toInt())
            out.write(((value ushr 24) and 0xFF).toInt())
        }

        fun withCrc(): ByteArray {
            val body = out.toByteArray()
            val crc = Crc16.compute(body)
            out.write(crc and 0xFF)
            out.write((crc ushr 8) and 0xFF)
            return out.toByteArray()
        }
    }
}
