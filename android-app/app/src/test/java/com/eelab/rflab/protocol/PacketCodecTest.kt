package com.eelab.rflab.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Packet vectors are hand-built from the byte tables in
 * `android-app/AGENT_BRIEF.md`. Expected whole-packet hex strings below were
 * produced independently of [PacketCodec]'s encoder (CRC-16 over the listed
 * body bytes, little-endian), so the byte-for-byte tests catch any layout
 * drift; the CRC values themselves are cross-checked against
 * [Crc16.compute] in [withCrc], which is already covered by [Crc16Test].
 */
class PacketCodecTest {

    // ---- FRAME / STATUS -------------------------------------------------

    @Test
    fun decodesFramePacketWithPulses() {
        val pulses = intArrayOf(4512, 4170, 795, 1138)
        val body = raw {
            u8(PROTOCOL_VERSION)
            u8(PacketType.FRAME)
            u16(PacketFlags.CAPTURE_TRUNCATED or PacketFlags.CLOCK_FALLBACK)
            u16(0x1234)
            u16(PacketCodec.HEADER_SIZE + pulses.size * 2 + PacketCodec.CRC_SIZE)
            u32(0x1234_5678L)
            u16(7)
            u16(pulses.size)
            u8(1)
            u8(1)
            pulses.forEach { u16(it) }
        }
        val raw = withCrc(body)

        // Whole-packet check pins every offset in the FRAME table.
        assertEquals(
            "0101010134121c0078563412070004000101a0114a101b037204414b",
            raw.toHex(),
        )

        val packet = PacketCodec.decode(raw).packetAs<FramePacket>()
        assertEquals(PROTOCOL_VERSION, packet.version)
        assertEquals(
            PacketFlags.CAPTURE_TRUNCATED or PacketFlags.CLOCK_FALLBACK,
            packet.flags,
        )
        assertEquals(0x1234, packet.sequence)
        assertEquals(0x1234_5678L, packet.timestampUs)
        assertEquals(7, packet.droppedFrames)
        assertArrayEquals(pulses, packet.pulses)
        assertEquals(1, packet.initialLevel)
        assertEquals(1, packet.receiverId)

        // Decoded twice, the packets must be structurally equal even though
        // the pulse arrays are different instances.
        assertEquals(
            PacketCodec.decode(raw).packetOrFail(),
            PacketCodec.decode(raw).packetOrFail(),
        )
    }

    @Test
    fun decodesStatusPacketWithReassembledCounters() {
        val body = raw {
            u8(PROTOCOL_VERSION)
            u8(PacketType.STATUS)
            u16(PacketFlags.CLOCK_FALLBACK or PacketFlags.CAPTURE_INIT_FAILED)
            u16(0x00FF)
            u16(
                PacketCodec.HEADER_SIZE +
                    PacketCodec.STATUS_PAYLOAD_WORDS * 2 +
                    PacketCodec.CRC_SIZE,
            )
            u32(0) // timestamp is always 0 for STATUS
            u16(9) // dropped_frames / capture drops
            u16(PacketCodec.STATUS_PAYLOAD_WORDS)
            u8(0) // initial_level is always 0 for STATUS
            u8(1)
            u16(0x5678) // clock_hz low16
            u16(0x1234) // clock_hz high16
            u16(0xCCDD) // total_edges low16
            u16(0xAABB) // total_edges high16
            u16(7) // dropped_packets
        }
        val raw = withCrc(body)

        assertEquals(
            "01020003ff001e000000000009000500000178563412ddccbbaa07007d49",
            raw.toHex(),
        )

        val packet = PacketCodec.decode(raw).packetAs<StatusPacket>()
        assertEquals(PROTOCOL_VERSION, packet.version)
        assertEquals(
            PacketFlags.CLOCK_FALLBACK or PacketFlags.CAPTURE_INIT_FAILED,
            packet.flags,
        )
        assertEquals(0x00FF, packet.sequence)
        assertEquals(9, packet.droppedFrames)
        assertEquals(0x1234_5678L, packet.clockHz)
        assertEquals(0xAABB_CCDDL, packet.totalEdges)
        assertEquals(7, packet.droppedPackets)
    }

    @Test
    fun frameWithNoPulsesDecodesToEmptyArray() {
        val raw = withCrc(raw {
            u8(PROTOCOL_VERSION)
            u8(PacketType.FRAME)
            u16(0)
            u16(1)
            u16(PacketCodec.HEADER_SIZE + PacketCodec.CRC_SIZE)
            u32(0)
            u16(0)
            u16(0)
            u8(0)
            u8(1)
        })

        val packet = PacketCodec.decode(raw).packetAs<FramePacket>()
        assertArrayEquals(IntArray(0), packet.pulses)
    }

    // ---- ACK ------------------------------------------------------------

    @Test
    fun decodesAckPackets() {
        val accepted = PacketCodec.decode(
            hex("01050301f65c"),
        ).packetAs<AckPacket>()
        assertEquals(PacketType.TX_COMMAND, accepted.forType)
        assertTrue(accepted.accepted)

        val rejected = PacketCodec.decode(
            hex("0105040040d5"),
        ).packetAs<AckPacket>()
        assertEquals(PacketType.SCENARIO_COMMAND, rejected.forType)
        assertEquals(false, rejected.accepted)
    }

    // ---- TX_COMMAND -----------------------------------------------------

    @Test
    fun encodeTxCommandMatchesBriefLayout() {
        val encoded = PacketCodec.encodeTxCommand(
            TxCommand(
                hopping = 0x1147_66B9L,
                serial = 0x0FB5_8C0CL,
                button = 2,
                frameCount = 5,
            ),
        )

        // version 1, type 3, hopping LE, serial LE, button 2, frames 5, CRC.
        assertEquals("0103b96647110c8cb50f0205d27a", encoded.toHex())
    }

    @Test
    fun txCommandRoundTripsThroughEncodeAndDecode() {
        val command = TxCommand(
            hopping = 0xDEAD_BEEFL,
            serial = 0x0F5A_5A5AL,
            button = 0xF,
            frameCount = 10,
        )
        val encoded = PacketCodec.encodeTxCommand(command)

        assertEquals(PacketCodec.TX_COMMAND_SIZE, encoded.size)
        assertEquals(command, PacketCodec.decode(encoded).packetAs<TxCommand>())
    }

    @Test
    fun txSerialIsMaskedTo28Bits() {
        val command = TxCommand(
            hopping = 0x0000_0001L,
            serial = 0xFFFF_FFFFL,
            button = 0,
            frameCount = 1,
        )
        val decoded = PacketCodec.decode(
            PacketCodec.encodeTxCommand(command),
        ).packetAs<TxCommand>()

        // The firmware masks the wire field to 28 bits; decode mirrors it.
        assertEquals(0x0FFF_FFFFL, decoded.serial)
    }

    // ---- SCENARIO_COMMAND ----------------------------------------------

    @Test
    fun encodeScenarioCommandMatchesBriefLayout() {
        val expected = mapOf(
            ScenarioCommand.STALE_REPLAY to "010472bd69",
            ScenarioCommand.SYNTHETIC_STALE to "0104739c79",
            ScenarioCommand.ZERO_HOP to "01047ab5e8",
            ScenarioCommand.PLAINTEXT_COUNTER to "010470ff49",
            ScenarioCommand.RANDOM_HOP to "010478f7c8",
            ScenarioCommand.DIFFERENT_SERIAL to "0104644a1b",
        )

        for ((commandChar, expectedHex) in expected) {
            val encoded = PacketCodec.encodeScenarioCommand(ScenarioCommand(commandChar))
            assertEquals("scenario '$commandChar'", expectedHex, encoded.toHex())
            assertEquals(
                ScenarioCommand(commandChar),
                PacketCodec.decode(encoded).packetAs<ScenarioCommand>(),
            )
        }
    }

    // ---- Failures -------------------------------------------------------

    @Test
    fun crcMismatchIsRejected() {
        val raw = PacketCodec.encodeTxCommand(
            TxCommand(0x1147_66B9L, 0x0FB5_8C0CL, 2, 5),
        )
        raw[4] = (raw[4].toInt() xor 0x01).toByte() // corrupt the hopping word

        val failure = PacketCodec.decode(raw) as PacketDecodeResult.Failure.CrcMismatch
        assertEquals(
            Crc16.compute(raw.copyOfRange(0, raw.size - PacketCodec.CRC_SIZE)),
            failure.expected,
        )
        assertEquals(0x7AD2, failure.actual)
    }

    @Test
    fun unknownTypeIsRejected() {
        val raw = withCrc(raw {
            u8(PROTOCOL_VERSION)
            u8(9)
            u8(0)
        })
        assertEquals(
            PacketDecodeResult.Failure.UnknownType(9),
            PacketCodec.decode(raw),
        )
    }

    @Test
    fun truncatedBuffersAreRejected() {
        assertEquals(
            PacketDecodeResult.Failure.Truncated(PacketCodec.SCENARIO_COMMAND_SIZE, 0),
            PacketCodec.decode(ByteArray(0)),
        )
        assertEquals(
            PacketDecodeResult.Failure.Truncated(PacketCodec.SCENARIO_COMMAND_SIZE, 4),
            PacketCodec.decode(ByteArray(4)),
        )

        // A CRC-valid FRAME buffer that stops inside the 18-byte header.
        val shortFrame = withCrc(raw {
            u8(PROTOCOL_VERSION)
            u8(PacketType.FRAME)
            u16(0)
            u16(0)
            u16(PacketCodec.HEADER_SIZE + PacketCodec.CRC_SIZE)
            u16(0)
        })
        assertEquals(
            PacketDecodeResult.Failure.Truncated(
                PacketCodec.HEADER_SIZE + PacketCodec.CRC_SIZE,
                shortFrame.size,
            ),
            PacketCodec.decode(shortFrame),
        )
    }

    @Test
    fun malformedLayoutsAreRejected() {
        // Unsupported version byte.
        assertMalformed(
            PacketCodec.decode(withCrc(raw {
                u8(2)
                u8(PacketType.ACK)
                u8(PacketType.TX_COMMAND)
                u8(1)
            })),
            "version",
        )

        // Header's declared total length disagrees with the buffer.
        assertMalformed(
            PacketCodec.decode(withCrc(raw {
                u8(PROTOCOL_VERSION)
                u8(PacketType.FRAME)
                u16(0)
                u16(0)
                u16(99)
                u32(0)
                u16(0)
                u16(0)
                u8(0)
                u8(1)
            })),
            "declared packet length",
        )

        // STATUS must carry exactly five words.
        assertMalformed(
            PacketCodec.decode(withCrc(raw {
                u8(PROTOCOL_VERSION)
                u8(PacketType.STATUS)
                u16(0)
                u16(0)
                u16(
                    PacketCodec.HEADER_SIZE + 4 * 2 + PacketCodec.CRC_SIZE,
                )
                u32(0)
                u16(0)
                u16(4)
                u8(0)
                u8(1)
                repeat(4) { u16(0) }
            })),
            "STATUS payload",
        )

        // TX_COMMAND must be exactly 14 bytes.
        assertMalformed(
            PacketCodec.decode(withCrc(raw {
                u8(PROTOCOL_VERSION)
                u8(PacketType.TX_COMMAND)
                repeat(9) { u8(0) }
            })),
            "TX_COMMAND must be",
        )

        // ACK must be exactly 6 bytes.
        assertMalformed(
            PacketCodec.decode(withCrc(raw {
                u8(PROTOCOL_VERSION)
                u8(PacketType.ACK)
                u8(PacketType.TX_COMMAND)
            })),
            "ACK must be",
        )

        // Unknown scenario character.
        assertMalformed(
            PacketCodec.decode(withCrc(raw {
                u8(PROTOCOL_VERSION)
                u8(PacketType.SCENARIO_COMMAND)
                u8('q'.code)
            })),
            "scenario",
        )
    }

    @Test
    fun outOfRangeCommandFieldsAreRejected() {
        // Constructor guards (the app never puts these on the wire).
        assertThrows { TxCommand(0, 0, button = 0x10, frameCount = 1) }
        assertThrows { TxCommand(0, 0, button = 0, frameCount = 0) }
        assertThrows { TxCommand(0, 0, button = 0, frameCount = 11) }
        assertThrows { ScenarioCommand('u') }

        // And a hand-crafted frame with an illegal button nibble is rejected
        // by the decoder rather than constructing an invalid TxCommand.
        val body = mutableListOf<Int>()
        body += PROTOCOL_VERSION
        body += PacketType.TX_COMMAND
        repeat(8) { body += 0 } // hopping + serial
        body += 0x10 // button
        body += 1 // frame count
        assertMalformed(
            PacketCodec.decode(withCrc(ByteArray(body.size) { body[it].toByte() })),
            "button",
        )
    }

    // ---- Session-2 interop ---------------------------------------------

    @Test
    fun rawPacketsSurviveCobsEncodeDecodeRoundTrip() {
        val command = TxCommand(0x1147_66B9L, 0x0FB5_8C0CL, 2, 5)
        val raw = PacketCodec.encodeTxCommand(command)

        // This is what session 4's transport will do: COBS-encode the raw
        // packet, append the 0x00 delimiter on the wire, then COBS-decode on
        // receipt before handing the buffer to PacketCodec.decode.
        val encoded = Cobs.encode(raw)
        assertTrue("COBS body must not contain 0x00", encoded.none { it == 0.toByte() })
        assertArrayEquals(raw, Cobs.decode(encoded))

        assertEquals(
            command,
            PacketCodec.decode(Cobs.decode(encoded)).packetAs<TxCommand>(),
        )
    }

    // ---- Helpers --------------------------------------------------------

    private fun assertMalformed(result: PacketDecodeResult, contains: String) {
        assertTrue("expected Malformed, got $result", result is PacketDecodeResult.Failure.Malformed)
        val reason = (result as PacketDecodeResult.Failure.Malformed).reason
        assertTrue("reason '$reason' should mention '$contains'", reason.contains(contains))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    private fun PacketDecodeResult.packetOrFail(): Packet {
        assertTrue("expected Success, got $this", this is PacketDecodeResult.Success)
        return (this as PacketDecodeResult.Success).packet
    }

    private inline fun <reified T : Packet> PacketDecodeResult.packetAs(): T {
        val packet = packetOrFail()
        assertTrue(
            "expected ${T::class.simpleName}, got ${packet::class.simpleName}",
            packet is T,
        )
        return packet as T
    }

    /** Appends a little-endian CRC-16 over [body]. */
    private fun withCrc(body: ByteArray): ByteArray {
        val crc = Crc16.compute(body)
        return body + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
        )
    }

    private fun raw(block: RawBuilder.() -> Unit): ByteArray = RawBuilder().apply(block).build()

    /** Minimal little-endian byte builder for hand-constructing packets. */
    private class RawBuilder {
        private val bytes = ArrayList<Byte>()

        fun u8(value: Int) = apply { bytes.add((value and 0xFF).toByte()) }

        fun u16(value: Int) = apply {
            bytes.add((value and 0xFF).toByte())
            bytes.add(((value ushr 8) and 0xFF).toByte())
        }

        fun u32(value: Long) = apply {
            for (shift in 0..24 step 8) {
                bytes.add(((value ushr shift) and 0xFF).toByte())
            }
        }

        fun build(): ByteArray = bytes.toByteArray()
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "hex string must have even length: $value" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
}
