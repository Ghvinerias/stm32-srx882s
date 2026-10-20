package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.CaptureFixtures
import com.eelab.rflab.decode.DecodedSignal
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.testing.MainDispatcherRule
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

    // --- Transport -> decoder -> rows --------------------------------------

    @Test
    fun decodesRealFramePacketsAndGroupsThemByPress() = runTest {
        val frames = CaptureFixtures.load("r1-btn-1")
        val events = flowOf(
            *frames.map { TransportEvent.PacketReceived(it) }.toTypedArray(),
        )

        val viewModel = ReceiveViewModel(events, sendQueue, clock = { 1_000L })

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
    }
}
