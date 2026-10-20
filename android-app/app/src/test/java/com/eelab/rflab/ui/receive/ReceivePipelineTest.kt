package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.CaptureFixtures
import com.eelab.rflab.protocol.Cobs
import com.eelab.rflab.protocol.Crc16
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.protocol.PacketType
import com.eelab.rflab.protocol.PROTOCOL_VERSION
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.testing.MainDispatcherRule
import com.eelab.rflab.usb.PacketReassembler
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end regression across the layers that make up the receive path:
 * real capture pulses -> raw FRAME wire bytes (COBS + `0x00`) ->
 * [PacketReassembler] (arbitrary chunk boundaries) -> [TransportEvent] ->
 * [ReceiveViewModel] (scale estimation + HCS301 decode + repeat grouping).
 *
 * The decoder is already validated against `tools/decode_hcs301.py` in
 * `RealCaptureDecodeTest`; this test proves the *wiring* between sessions
 * 2-6 actually carries real data from the wire to the screen, using the
 * 34-frame USB-path capture `r3-usb-sanity`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceivePipelineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun realCaptureFlowsFromWireBytesToGroupedRows() = runTest {
        val frames = CaptureFixtures.load("r3-usb-sanity")
        val wire = frames.map(::encodeFrame).reduce { a, b -> a + b }

        val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
        val viewModel = ReceiveViewModel.create(
            transportEvents = events,
            sendQueue = SendQueueRepository(),
            clock = { 1_000L },
        )

        // Deliberately awkward chunk sizes: a USB bulk read can split a
        // packet at any byte, and one read can contain several packets.
        val reassembler = PacketReassembler()
        var offset = 0
        var chunk = 1
        while (offset < wire.size) {
            val length = minOf(chunk, wire.size - offset)
            reassembler.feed(wire, offset, length).forEach { events.emit(it) }
            offset += length
            chunk = if (chunk == 13) 1 else chunk + 1
        }
        assertEquals("no partial frame left over", 0, reassembler.bufferedByteCount)

        val state = viewModel.uiState.value
        assertEquals(0, state.decodeErrorCount)

        // Nine distinct hopping words, each press collapsed into one row.
        val rows = state.rows
        assertEquals(9, rows.size)
        assertEquals(frames.size, rows.sumOf { it.repeatCount })
        assertEquals(
            listOf(
                0xAE9EDAFEL,
                0xF1E79643L,
                0x5F69B083L,
                0x60627755L,
                0x68A3CA8FL,
                0xC0F31703L,
                0x59F99831L,
                0x9DB279D7L,
                0x27675251L,
            ),
            rows.map { it.hopping },
        )
        assertTrue(rows.all { it.serial == 0xFB3EC77L })
        assertEquals(listOf(4, 3, 4, 4, 4, 4, 4, 4, 3), rows.map { it.repeatCount })
        assertTrue(rows.all { it.button == 0x2 || it.button == 0x4 || it.button == 0x8 })
    }

    /** Encodes a captured FRAME exactly as the firmware transport would. */
    private fun encodeFrame(frame: FramePacket): ByteArray {
        val payload = frame.pulses
        val raw = ByteArray(18 + payload.size * 2 + 2)
        raw[0] = PROTOCOL_VERSION.toByte()
        raw[1] = PacketType.FRAME.toByte()
        putU16(raw, 2, frame.flags)
        putU16(raw, 4, frame.sequence)
        putU16(raw, 6, raw.size)
        putU32(raw, 8, frame.timestampUs)
        putU16(raw, 12, frame.droppedFrames)
        putU16(raw, 14, payload.size)
        raw[16] = frame.initialLevel.toByte()
        raw[17] = frame.receiverId.toByte()
        payload.forEachIndexed { index, value ->
            putU16(raw, 18 + index * 2, value)
        }
        putU16(raw, raw.size - 2, Crc16.compute(raw.copyOfRange(0, raw.size - 2)))
        // Wire form: COBS-encoded body plus the trailing 0x00 delimiter.
        return Cobs.encode(raw) + PacketReassembler.DELIMITER
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        putU16(target, offset, (value and 0xFFFF).toInt())
        putU16(target, offset + 2, ((value ushr 16) and 0xFFFF).toInt())
    }
}
