package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.CaptureFixtures
import com.eelab.rflab.decode.DecodedSignal
import com.eelab.rflab.protocol.RadioMode
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.testing.MainDispatcherRule
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Grouping, selection and queue tests for [ReceiveViewModel].
 *
 * Input signals carry their own controlled arrival timestamps through an
 * injected clock, so repeat grouping is deterministic and nothing sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var signals: MutableSharedFlow<DecodedSignal>
    private lateinit var sendQueue: SendQueueRepository

    /** Host arrival time handed to the ViewModel by the injected clock. */
    private var nowMs = 0L

    @Before
    fun setUp() {
        signals = MutableSharedFlow(extraBufferCapacity = 32)
        sendQueue = SendQueueRepository()
        nowMs = 1_000L
    }

    private fun newViewModel(): ReceiveViewModel =
        ReceiveViewModel(signals, sendQueue, clock = { nowMs })

    private suspend fun emit(
        atMs: Long,
        signal: DecodedSignal = signal(),
    ) {
        nowMs = atMs
        signals.emit(signal)
    }

    private fun signal(
        hopping: Long = 0x63FD859DL,
        serial: Long = 0x0FB58C0CL,
        button: Int = 0x2,
    ) = DecodedSignal(
        hopping = hopping,
        serial = serial,
        button = button,
        vlow = false,
        repeat = false,
    )

    // --- Repeat grouping ----------------------------------------------------

    @Test
    fun collapsesRepeatsWithinWindowKeepingFirstTimestamp() = runTest {
        val viewModel = newViewModel()

        emit(1_000)
        emit(1_050)
        emit(1_400)

        val rows = viewModel.uiState.value.rows
        assertEquals(1, rows.size)
        assertEquals(3, rows.single().repeatCount)
        assertEquals(1_000, rows.single().firstSeenMs)
        assertEquals(0x63FD859DL, rows.single().hopping)
        assertEquals(0x0FB58C0CL, rows.single().serial)
        assertEquals(0x2, rows.single().button)
    }

    @Test
    fun groupsAtExactWindowBoundaryButNotPastIt() = runTest {
        val viewModel = newViewModel()

        emit(1_000)
        emit(1_500) // exactly 500 ms after the previous decode: still a repeat
        assertEquals(2, viewModel.uiState.value.rows.single().repeatCount)

        emit(2_001) // 501 ms after the previous decode: a new press

        val rows = viewModel.uiState.value.rows
        assertEquals(2, rows.size)
        assertEquals("newest row first", 1, rows[0].repeatCount)
        assertEquals(2_001, rows[0].firstSeenMs)
        assertEquals(2, rows[1].repeatCount)
        assertEquals(1_000, rows[1].firstSeenMs)
    }

    @Test
    fun differentHoppingSerialOrButtonStartsANewRow() = runTest {
        val viewModel = newViewModel()
        val defaultSerial = 0x0FB58C0CL

        // One signal differs from the base in exactly one identity field.
        emit(1_000, signal(hopping = 0xA, serial = defaultSerial, button = 0x2))
        emit(1_100, signal(hopping = 0xB, serial = defaultSerial, button = 0x2))
        emit(1_200, signal(hopping = 0xA, serial = 0xCL, button = 0x2))
        emit(1_300, signal(hopping = 0xA, serial = defaultSerial, button = 0x3))

        val rows = viewModel.uiState.value.rows
        assertEquals(4, rows.size)
        assertEquals(listOf(1, 1, 1, 1), rows.map { it.repeatCount })
        assertEquals(
            setOf(
                Triple(0xAL, defaultSerial, 0x2),
                Triple(0xBL, defaultSerial, 0x2),
                Triple(0xAL, 0xCL, 0x2),
                Triple(0xAL, defaultSerial, 0x3),
            ),
            rows.map { Triple(it.hopping, it.serial, it.button) }.toSet(),
        )
    }

    @Test
    fun interleavedPressesGroupWithTheirOwnPreviousDecode() = runTest {
        val viewModel = newViewModel()
        val pressA = signal(hopping = 0xAAAA_AAAAL)
        val pressB = signal(hopping = 0xBBBB_BBBBL)

        emit(1_000, pressA)
        emit(1_050, pressB)
        emit(1_200, pressA)

        val rows = viewModel.uiState.value.rows
        assertEquals(2, rows.size)
        assertEquals(0xBBBB_BBBBL, rows[0].hopping)
        assertEquals(1, rows[0].repeatCount)
        assertEquals(0xAAAA_AAAAL, rows[1].hopping)
        assertEquals(2, rows[1].repeatCount)
    }

    // --- Selection and send queue -------------------------------------------

    @Test
    fun togglesSelectionPerRow() = runTest {
        val viewModel = newViewModel()
        emit(1_000, signal(hopping = 0xAAAA_AAAAL))
        emit(1_100, signal(hopping = 0xBBBB_BBBBL))

        val rows = viewModel.uiState.value.rows
        val firstId = rows[0].id

        viewModel.toggleSelection(firstId)
        assertEquals(1, viewModel.uiState.value.selectedCount)
        assertTrue(viewModel.uiState.value.rows.first { it.id == firstId }.selected)
        assertFalse(viewModel.uiState.value.rows.first { it.id != firstId }.selected)

        viewModel.toggleSelection(firstId)
        assertEquals(0, viewModel.uiState.value.selectedCount)
    }

    @Test
    fun unknownRowIdIsIgnoredBySelection() = runTest {
        val viewModel = newViewModel()
        emit(1_000)
        val before = viewModel.uiState.value

        viewModel.toggleSelection(999_999L)

        assertEquals(before, viewModel.uiState.value)
    }

    @Test
    fun addsOnlySelectedRowsToQueueThenClearsSelection() = runTest {
        val viewModel = newViewModel()
        val hoppingA = 0xAAAA_AAAAL
        val hoppingB = 0xBBBB_BBBBL
        val signalA = signal(hopping = hoppingA, serial = 0x0111_1111L, button = 0x1)
        val signalB = signal(hopping = hoppingB, serial = 0x0222_2222L, button = 0x2)
        emit(1_000, signalA)
        emit(1_100, signalB)

        val rowsByHopping = viewModel.uiState.value.rows.associateBy { it.hopping }
        viewModel.toggleSelection(rowsByHopping.getValue(hoppingA).id)
        viewModel.toggleSelection(rowsByHopping.getValue(hoppingB).id)
        viewModel.toggleSelection(rowsByHopping.getValue(hoppingA).id) // deselect A
        assertEquals(1, viewModel.uiState.value.selectedCount)

        val added = viewModel.addSelectedToQueue()

        assertEquals(1, added)
        assertEquals(1, sendQueue.items.value.size)
        assertEquals(signalB.hopping, sendQueue.items.value.single().hopping)
        assertEquals(signalB.serial, sendQueue.items.value.single().serial)
        assertEquals(signalB.button, sendQueue.items.value.single().button)
        assertEquals(0, viewModel.uiState.value.selectedCount)
        assertEquals(1, viewModel.uiState.value.queueSize)
        assertTrue(viewModel.uiState.value.rows.none { it.selected })
    }

    @Test
    fun addingWithNothingSelectedIsANoOp() = runTest {
        val viewModel = newViewModel()
        emit(1_000)

        assertEquals(0, viewModel.addSelectedToQueue())
        assertTrue(sendQueue.items.value.isEmpty())
    }

    @Test
    fun addSelectedToQueueEnqueuesInSelectionOrder() = runTest {
        val viewModel = newViewModel()
        val signalA = signal(hopping = 0xAAAA_AAAAL)
        val signalB = signal(hopping = 0xBBBB_BBBBL)
        val signalC = signal(hopping = 0xCCCC_CCCCL)
        emit(1_000, signalA)
        emit(1_100, signalB)
        emit(1_200, signalC)

        // Rows are newest first, so this also proves display order is not
        // what drives the queue.
        val rowsByHopping = viewModel.uiState.value.rows.associateBy { it.hopping }
        viewModel.toggleSelection(rowsByHopping.getValue(signalC.hopping).id)
        viewModel.toggleSelection(rowsByHopping.getValue(signalA.hopping).id)
        viewModel.toggleSelection(rowsByHopping.getValue(signalB.hopping).id)

        val added = viewModel.addSelectedToQueue()

        assertEquals(3, added)
        assertEquals(
            listOf(signalC.hopping, signalA.hopping, signalB.hopping),
            sendQueue.items.value.map { it.hopping },
        )
        assertEquals(0, viewModel.uiState.value.selectedCount)
    }

    @Test
    fun clearReceivedWipesRowsAndSelection() = runTest {
        val viewModel = newViewModel()
        emit(1_000, signal(hopping = 0xAAAA_AAAAL))
        emit(1_100, signal(hopping = 0xBBBB_BBBBL))
        viewModel.toggleSelection(viewModel.uiState.value.rows.first().id)
        assertTrue(viewModel.uiState.value.canClearReceived)

        viewModel.clearReceived()

        val state = viewModel.uiState.value
        assertTrue(state.rows.isEmpty())
        assertEquals(0, state.selectedCount)
        assertFalse(state.canAddSelected)
        assertFalse(state.canClearReceived)
        assertEquals("the send queue is untouched", 0, state.queueSize)
    }

    // --- Transport -> decoder -> rows --------------------------------------

    @Test
    fun decodesRealFramePacketsAndGroupsThemByPress() = runTest {
        val frames = CaptureFixtures.load("r1-btn-1")
        val events = flowOf(
            *frames.map { TransportEvent.PacketReceived(it) }.toTypedArray(),
        )

        val viewModel = ReceiveViewModel.create(events, sendQueue, clock = { 1_000L })

        val rows = viewModel.uiState.value.rows
        assertEquals(4, rows.size)
        // Newest press first; each press produced one hopping word.
        assertEquals(
            listOf(0x114766B9L, 0x3CC9E216L, 0x93C362DEL, 0x63FD859DL),
            rows.map { it.hopping },
        )
        assertEquals(listOf(5, 5, 5, 4), rows.map { it.repeatCount })
        assertTrue(rows.all { it.serial == 0x0FB58C0CL })
        assertTrue(rows.all { it.button == 0x2 })
        assertEquals(0, viewModel.uiState.value.decodeErrorCount)
    }

    // --- Malformed transport frames ----------------------------------------

    @Test
    fun decodeErrorsAreCountedAndDoNotStopLaterFrames() = runTest {
        val frames = CaptureFixtures.load("r1-btn-1")
        val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
        val viewModel = ReceiveViewModel.create(events, sendQueue, clock = { nowMs })

        // A burst of corrupt frames, then real traffic. None of the corrupt
        // events may reach the decoder or stop the stream.
        events.emit(decodeError())
        events.emit(decodeError())
        events.emit(TransportEvent.PacketReceived(frames[0]))
        events.emit(decodeError())
        events.emit(TransportEvent.PacketReceived(frames[1]))
        // A well-formed packet that is simply not an HCS301 frame (too few
        // pulses) is dropped by the decoder, not counted as a decode error.
        events.emit(TransportEvent.PacketReceived(shortFrame()))
        events.emit(TransportEvent.PacketReceived(frames[2]))

        val state = viewModel.uiState.value
        assertEquals(3, state.decodeErrorCount)
        assertEquals(1, state.rows.size)
        assertEquals(3, state.rows.single().repeatCount)
        assertEquals(0x63FD859DL, state.rows.single().hopping)
    }

    @Test
    fun aStreamOfOnlyDecodeErrorsLeavesAnEmptyButResponsiveList() = runTest {
        val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
        val viewModel = ReceiveViewModel.create(events, sendQueue, clock = { nowMs })

        for (i in 0 until 100) {
            events.emit(decodeError())
        }

        assertEquals(0, viewModel.uiState.value.rows.size)
        assertEquals(100, viewModel.uiState.value.decodeErrorCount)
    }

    private fun decodeError(): TransportEvent.DecodeError = TransportEvent.DecodeError(
        failure = com.eelab.rflab.usb.DecodeFailure.FrameTooLong(
            limitBytes = 1024,
            actualBytes = 2048,
        ),
        wireBody = byteArrayOf(0x01, 0x02, 0x03),
    )

    private fun shortFrame(): com.eelab.rflab.protocol.FramePacket =
        com.eelab.rflab.protocol.FramePacket(
            version = 1,
            flags = 0,
            sequence = 99,
            timestampUs = 0L,
            droppedFrames = 0,
            pulses = intArrayOf(4512, 795, 1138),
            initialLevel = 0,
            receiverId = 1,
        )

    // --- Radio mode control -------------------------------------------------

    @Test
    fun displayedModeComesFromDeviceStatusNotFromTheRequest() = runTest {
        val modes = MutableSharedFlow<RadioMode>(extraBufferCapacity = 8)
        val requested = mutableListOf<RadioMode>()
        val viewModel = ReceiveViewModel(
            signals, sendQueue, clock = { nowMs },
            deviceModes = modes,
            connected = flowOf(true),
            modeSetter = { requested += it; true },
        )
        assertEquals(RadioMode.IDLE, viewModel.uiState.value.mode)

        viewModel.toggleReceiving()
        assertEquals(listOf(RadioMode.RX), requested)
        // ACKed, but no STATUS yet: still shown as Idle.
        assertEquals(RadioMode.IDLE, viewModel.uiState.value.mode)

        modes.emit(RadioMode.RX)
        assertEquals(RadioMode.RX, viewModel.uiState.value.mode)

        viewModel.toggleReceiving()
        assertEquals(listOf(RadioMode.RX, RadioMode.IDLE), requested)
    }

    @Test
    fun disconnectShowsIdleAndNothingAutoStartsOnReconnect() = runTest {
        val modes = MutableSharedFlow<RadioMode>(extraBufferCapacity = 8)
        val connected = MutableStateFlow(true)
        val requested = mutableListOf<RadioMode>()
        val viewModel = ReceiveViewModel(
            signals, sendQueue, clock = { nowMs },
            deviceModes = modes,
            connected = connected,
            modeSetter = { requested += it; true },
        )
        modes.emit(RadioMode.RX)
        assertEquals(RadioMode.RX, viewModel.uiState.value.mode)

        connected.value = false
        assertEquals(RadioMode.IDLE, viewModel.uiState.value.mode)
        assertFalse(viewModel.uiState.value.canToggleReceive)

        connected.value = true
        assertEquals(RadioMode.IDLE, viewModel.uiState.value.mode)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun toggleIsBlockedWhileTransmittingAndFailureIsReported() = runTest {
        val modes = MutableSharedFlow<RadioMode>(extraBufferCapacity = 8)
        var calls = 0
        val viewModel = ReceiveViewModel(
            signals, sendQueue, clock = { nowMs },
            deviceModes = modes,
            modeSetter = { calls++; false },
        )
        modes.emit(RadioMode.TX)
        viewModel.toggleReceiving()
        assertEquals(0, calls)

        modes.emit(RadioMode.IDLE)
        viewModel.toggleReceiving()
        assertEquals(1, calls)
        assertTrue(viewModel.uiState.value.modeMessage != null)
        assertFalse(viewModel.uiState.value.modeBusy)
    }

    @Test
    fun framesWhileIdleKeepStartAvailableAndFlagTheMismatch() = runTest {
        val modes = MutableSharedFlow<RadioMode>(extraBufferCapacity = 8)
        val frames = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val requested = mutableListOf<RadioMode>()
        val viewModel = ReceiveViewModel(
            signals, sendQueue, clock = { nowMs },
            deviceModes = modes,
            frameArrivals = frames,
            modeSetter = { requested += it; true },
        )
        // STM32 reference board: streams FRAMEs, reports IDLE forever.
        frames.emit(Unit)
        emit(1_000)
        assertTrue(viewModel.uiState.value.framesWhileIdle)
        assertEquals(1, viewModel.uiState.value.rows.size) // never hidden
        assertTrue(viewModel.uiState.value.canToggleReceive)
        viewModel.toggleReceiving()
        assertEquals(listOf(RadioMode.RX), requested)

        modes.emit(RadioMode.RX)
        assertFalse(viewModel.uiState.value.framesWhileIdle)
    }
}
