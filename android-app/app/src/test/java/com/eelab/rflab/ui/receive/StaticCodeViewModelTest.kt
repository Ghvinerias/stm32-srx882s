package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.Ht12Frame
import com.eelab.rflab.decode.Ht12Protocol
import com.eelab.rflab.decode.PrincetonFrame
import com.eelab.rflab.decode.PrincetonProtocol
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.testing.MainDispatcherRule
import com.eelab.rflab.usb.DecodeFailure
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StaticCodeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun frame(data: Long) = PrincetonFrame(data = data, bitCount = 24, serial = data ushr 4, button = (data and 0xF).toInt())

    @Test
    fun startsEmptyAndDisconnected() = runTest {
        val vm = StaticCodeViewModel(frames = MutableSharedFlow(), connected = MutableStateFlow(false))
        val state = vm.uiState.value
        assertTrue(state.rows.isEmpty())
        assertFalse(state.isConnected)
        assertFalse(state.canClear)
    }

    @Test
    fun aDecodedFrameBecomesARow() = runTest {
        val frames = MutableSharedFlow<PrincetonFrame>(extraBufferCapacity = 4)
        val vm = StaticCodeViewModel(frames = frames, clock = { 1000L })
        frames.emit(frame(0xABCDEF))
        val state = vm.uiState.value
        assertEquals(1, state.rows.size)
        assertEquals(0xABCDEFL, state.rows[0].data)
        assertTrue(state.canClear)
    }

    @Test
    fun connectionStateIsReflected() = runTest {
        val connected = MutableStateFlow(false)
        val vm = StaticCodeViewModel(frames = MutableSharedFlow(), connected = connected)
        assertFalse(vm.uiState.value.isConnected)
        connected.value = true
        assertTrue(vm.uiState.value.isConnected)
    }

    @Test
    fun clearReceivedWipesRows() = runTest {
        val frames = MutableSharedFlow<PrincetonFrame>(extraBufferCapacity = 4)
        val vm = StaticCodeViewModel(frames = frames, clock = { 1000L })
        frames.emit(frame(0x1))
        assertEquals(1, vm.uiState.value.rows.size)

        vm.clearReceived()

        assertTrue(vm.uiState.value.rows.isEmpty())
        assertFalse(vm.uiState.value.canClear)
    }

    @Test
    fun repeatsWithinTheWindowCollapseIntoOneRow() = runTest {
        val frames = MutableSharedFlow<PrincetonFrame>(extraBufferCapacity = 4)
        var now = 1000L
        val vm = StaticCodeViewModel(frames = frames, clock = { now })
        frames.emit(frame(0x42))
        now += 50
        frames.emit(frame(0x42))
        val state = vm.uiState.value
        assertEquals(1, state.rows.size)
        assertEquals(2, state.rows[0].repeatCount)
    }

    // --- decodeSignals: real TransportEvent integration, non-interference with HCS301 ---

    private fun princetonTwoRepeatPulses(data: Long): IntArray {
        val short = PrincetonProtocol.TE_SHORT_US
        val long = PrincetonProtocol.TE_LONG_US
        val syncGap = short * 30
        fun repeat(): List<Int> {
            val edges = mutableListOf<Int>()
            for (i in 23 downTo 0) {
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
        return (listOf(syncGap) + repeat() + repeat()).toIntArray()
    }

    private fun framePacket(pulses: IntArray, initialLevel: Int = 0) = FramePacket(
        version = 1,
        flags = 0,
        sequence = 1,
        timestampUs = 0L,
        droppedFrames = 0,
        pulses = pulses,
        initialLevel = initialLevel,
        receiverId = 1,
    )

    private fun decodeError(): TransportEvent.DecodeError = TransportEvent.DecodeError(
        failure = DecodeFailure.FrameTooLong(limitBytes = 1024, actualBytes = 2048),
        wireBody = byteArrayOf(0x01, 0x02, 0x03),
    )

    @Test
    fun decodeSignalsExtractsAPrincetonFrameFromARealTransportEvent() = runTest {
        val packet = framePacket(princetonTwoRepeatPulses(0x123456))
        val event = TransportEvent.PacketReceived(packet)
        val decoded = StaticCodeViewModel.decodeSignals(flowOf(event), MutableStateFlow(0)).toList()
        assertEquals(1, decoded.size)
        assertEquals(0x123456L, decoded[0].data)
    }

    @Test
    fun decodeSignalsIgnoresFramesThatDoNotDecodeAsPrinceton() = runTest {
        // HCS301-shaped pulses (fixed-width, all one duration) are not Princeton-shaped.
        val packet = framePacket(IntArray(77) { 1138 })
        val event = TransportEvent.PacketReceived(packet)
        val decoded = StaticCodeViewModel.decodeSignals(flowOf(event), MutableStateFlow(0)).toList()
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun decodeSignalsIgnoresNonPacketEvents() = runTest {
        val event = TransportEvent.ConnectionLost(cause = null)
        val decoded = StaticCodeViewModel.decodeSignals(flowOf(event), MutableStateFlow(0)).toList()
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun decodeSignalsCountsTransportDecodeErrorsButEmitsNoFrameForThem() = runTest {
        val errors = MutableStateFlow(0)
        val decoded = StaticCodeViewModel.decodeSignals(flowOf(decodeError()), errors).toList()
        assertTrue(decoded.isEmpty())
        assertEquals(1, errors.value)
    }

    @Test
    fun aRecognizedNonPrincetonFrameDoesNotCountAsADecodeError() = runTest {
        // A real HCS301 frame decodes fine at the transport level; it just
        // isn't Princeton-shaped. That is not an error.
        val errors = MutableStateFlow(0)
        val packet = framePacket(IntArray(77) { 1138 })
        StaticCodeViewModel.decodeSignals(flowOf(TransportEvent.PacketReceived(packet)), errors).toList()
        assertEquals(0, errors.value)
    }

    @Test
    fun uiStateSurfacesTheTransportDecodeErrorCount() = runTest {
        val frames = MutableSharedFlow<PrincetonFrame>()
        val errors = MutableStateFlow(0)
        val vm = StaticCodeViewModel(frames = frames, decodeErrorCount = errors)
        assertEquals(0, vm.uiState.value.decodeErrorCount)
        errors.value = 7
        assertEquals(7, vm.uiState.value.decodeErrorCount)
    }

    // --- HT12 integration ------------------------------------------------------

    private fun ht12TwoRepeatPulses(data: Long): IntArray {
        val te = Ht12Protocol.TE_SHORT_US
        val header = te * 36
        fun repeat(): List<Int> {
            val edges = mutableListOf(te) // start bit
            for (i in Ht12Protocol.DATA_BITS - 1 downTo 0) {
                if (((data ushr i) and 1L).toInt() == 1) {
                    edges += te * 2; edges += te
                } else {
                    edges += te; edges += te * 2
                }
            }
            return edges
        }
        return (listOf(header) + repeat() + listOf(header) + repeat() + listOf(header)).toIntArray()
    }

    @Test
    fun decodeHt12SignalsExtractsAnHt12FrameFromARealTransportEvent() = runTest {
        val packet = framePacket(ht12TwoRepeatPulses(0xABC))
        val decoded = StaticCodeViewModel.decodeHt12Signals(
            flowOf(TransportEvent.PacketReceived(packet)),
        ).toList()
        assertEquals(1, decoded.size)
        assertEquals(0xABCL, decoded[0].data)
        assertEquals(0xABL, decoded[0].address)
        assertEquals(0xC, decoded[0].button)
    }

    @Test
    fun decodeHt12SignalsIgnoresFramesThatDoNotDecodeAsHt12() = runTest {
        val packet = framePacket(IntArray(77) { 1138 }) // HCS301-shaped
        val decoded = StaticCodeViewModel.decodeHt12Signals(
            flowOf(TransportEvent.PacketReceived(packet)),
        ).toList()
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun anHt12FrameBecomesAnHt12TaggedRow() = runTest {
        val ht12 = MutableSharedFlow<Ht12Frame>(extraBufferCapacity = 4)
        val vm = StaticCodeViewModel(
            frames = MutableSharedFlow(),
            ht12Frames = ht12,
            clock = { 1000L },
        )
        ht12.emit(Ht12Frame(data = 0x123, bitCount = 12, address = 0x12, button = 0x3))
        val rows = vm.uiState.value.rows
        assertEquals(1, rows.size)
        assertEquals(StaticCodeProtocol.HT12, rows[0].protocol)
        assertEquals(0x123L, rows[0].data)
        assertEquals(0x12L, rows[0].address)
        assertEquals(0x3, rows[0].dataNibble)
    }
}
