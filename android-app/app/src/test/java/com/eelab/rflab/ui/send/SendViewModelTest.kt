package com.eelab.rflab.ui.send

import com.eelab.rflab.protocol.ScenarioCommand
import com.eelab.rflab.protocol.TxCommand
import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Queue management, manual-entry validation and preset-scenario tests for
 * [SendViewModel].
 *
 * The scenario and batch lambdas are captured per test, so nothing touches
 * the USB layer (which does not exist until session 8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var sendQueue: SendQueueRepository
    private val sentScenarios = mutableListOf<ScenarioCommand>()
    private val sentBatches = mutableListOf<List<SendQueueItem>>()

    @Before
    fun setUp() {
        sendQueue = SendQueueRepository()
        sentScenarios.clear()
        sentBatches.clear()
    }

    private fun newViewModel(): SendViewModel = SendViewModel(
        sendQueue = sendQueue,
        scenarioSender = { sentScenarios += it },
        batchSender = { sentBatches += it },
    )

    private fun fillValidEntry(
        viewModel: SendViewModel,
        hopping: String = "63FD859D",
        serial: String = "0FB58C0C",
        button: String = "2",
        frames: String = "5",
    ) {
        viewModel.onHoppingChange(hopping)
        viewModel.onSerialChange(serial)
        viewModel.onButtonChange(button)
        viewModel.onFramesChange(frames)
    }

    // --- Manual entry -------------------------------------------------------

    @Test
    fun validManualEntryBuildsTxCommandAndClearsForm() {
        val viewModel = newViewModel()
        fillValidEntry(viewModel, frames = "7")

        assertTrue(viewModel.addManualEntry())

        val item = sendQueue.items.value.single()
        assertEquals(0x63FD859DL, item.hopping)
        assertEquals(0x0FB58C0CL, item.serial)
        assertEquals(0x2, item.button)
        assertEquals(7, item.frameCount)
        assertEquals(SendItemStatus.PENDING, item.status)
        assertEquals(
            TxCommand(0x63FD859DL, 0x0FB58C0CL, 0x2, 7),
            item.toTxCommand(),
        )

        // The form resets and the screen sees the shared queue immediately.
        assertEquals(ManualEntryState(), viewModel.uiState.value.manualEntry)
        assertEquals(listOf(item), viewModel.uiState.value.items)
        assertNotNull(viewModel.uiState.value.message)
    }

    @Test
    fun freshFormDefaultsToFiveFrames() {
        val viewModel = newViewModel()

        assertEquals("5", viewModel.uiState.value.manualEntry.framesText)

        fillValidEntry(viewModel, frames = "5")
        assertTrue(viewModel.addManualEntry())
        assertEquals(SendQueueItem.DEFAULT_FRAME_COUNT, sendQueue.items.value.single().frameCount)
    }

    @Test
    fun rejectsNonHexHopping() {
        val viewModel = newViewModel()
        fillValidEntry(viewModel, hopping = "0xZZZZ")

        assertFalse(viewModel.addManualEntry())

        assertTrue(sendQueue.items.value.isEmpty())
        assertNotNull(viewModel.uiState.value.manualEntry.hoppingError)
        assertEquals("Fix the highlighted fields", viewModel.uiState.value.message)
    }

    @Test
    fun rejectsHoppingWiderThan32Bits() {
        val viewModel = newViewModel()
        fillValidEntry(viewModel, hopping = "100000000") // 33 bits

        assertFalse(viewModel.addManualEntry())

        assertTrue(sendQueue.items.value.isEmpty())
        assertNotNull(viewModel.uiState.value.manualEntry.hoppingError)
    }

    @Test
    fun rejectsSerialWiderThan28Bits() {
        val viewModel = newViewModel()
        fillValidEntry(viewModel, serial = "10000000") // 29 bits

        assertFalse(viewModel.addManualEntry())

        assertTrue(sendQueue.items.value.isEmpty())
        assertNotNull(viewModel.uiState.value.manualEntry.serialError)
    }

    @Test
    fun rejectsButtonThatIsNotASingleHexNibble() {
        val viewModel = newViewModel()
        fillValidEntry(viewModel, button = "10")

        assertFalse(viewModel.addManualEntry())
        assertNotNull(viewModel.uiState.value.manualEntry.buttonError)

        fillValidEntry(viewModel, button = "G")
        assertFalse(viewModel.addManualEntry())
        assertNotNull(viewModel.uiState.value.manualEntry.buttonError)
        assertTrue(sendQueue.items.value.isEmpty())
    }

    @Test
    fun rejectsFramesOutsideOneToTen() {
        val viewModel = newViewModel()

        for (invalid in listOf("0", "11", "-1", "ten")) {
            fillValidEntry(viewModel, frames = invalid)

            assertFalse("frames '$invalid' must be rejected", viewModel.addManualEntry())
            assertNotNull(
                "frames '$invalid' must set an error",
                viewModel.uiState.value.manualEntry.framesError,
            )
        }

        assertTrue(sendQueue.items.value.isEmpty())
    }

    @Test
    fun rejectsEmptyFields() {
        val viewModel = newViewModel()

        assertFalse(viewModel.addManualEntry())

        val entry = viewModel.uiState.value.manualEntry
        assertNotNull(entry.hoppingError)
        assertNotNull(entry.serialError)
        assertNotNull(entry.buttonError)
    }

    @Test
    fun editingAFieldClearsItsPreviousError() {
        val viewModel = newViewModel()
        fillValidEntry(viewModel, hopping = "not-hex")
        assertFalse(viewModel.addManualEntry())
        assertNotNull(viewModel.uiState.value.manualEntry.hoppingError)

        viewModel.onHoppingChange("1")

        assertNull(viewModel.uiState.value.manualEntry.hoppingError)
    }

    // --- Queue management ---------------------------------------------------

    @Test
    fun queueAddedByReceiveScreenIsExposedAsUiState() {
        val viewModel = newViewModel()

        val item = sendQueue.enqueue(0xAAAA_AAAAL, 0x0111_1111L, 0x1)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(item), viewModel.uiState.value.items)
    }

    @Test
    fun moveUpAndDownReorderTheQueue() {
        val viewModel = newViewModel()
        val first = sendQueue.enqueue(0xA, 1, 1)
        val second = sendQueue.enqueue(0xB, 2, 2)
        val third = sendQueue.enqueue(0xC, 3, 3)

        viewModel.moveItemUp(second.id)
        assertEquals(
            listOf(second.id, first.id, third.id),
            sendQueue.items.value.map { it.id },
        )
        assertEquals(
            listOf(second.id, first.id, third.id),
            viewModel.uiState.value.items.map { it.id },
        )

        // Already at the front: no change.
        viewModel.moveItemUp(second.id)
        assertEquals(second.id, sendQueue.items.value.first().id)

        viewModel.moveItemDown(second.id)
        assertEquals(
            listOf(first.id, second.id, third.id),
            sendQueue.items.value.map { it.id },
        )

        // Already at the back: no change.
        viewModel.moveItemDown(third.id)
        assertEquals(third.id, sendQueue.items.value.last().id)
    }

    @Test
    fun removeItemDropsOnlyThatItem() {
        val viewModel = newViewModel()
        val first = sendQueue.enqueue(0xA, 1, 1)
        val second = sendQueue.enqueue(0xB, 2, 2)

        viewModel.removeItem(first.id)

        assertEquals(listOf(second), sendQueue.items.value)
        assertEquals(listOf(second), viewModel.uiState.value.items)
    }

    @Test
    fun frameCountEditClampsToProtocolRange() {
        val viewModel = newViewModel()
        val item = sendQueue.enqueue(0xA, 1, 1)

        viewModel.setFrameCount(item.id, 0)
        assertEquals(1, sendQueue.items.value.single().frameCount)

        viewModel.setFrameCount(item.id, 99)
        assertEquals(10, sendQueue.items.value.single().frameCount)

        viewModel.setFrameCount(item.id, 7)
        assertEquals(7, sendQueue.items.value.single().frameCount)
        assertEquals(7, viewModel.uiState.value.items.single().frameCount)
    }

    // --- Preset scenarios ---------------------------------------------------

    @Test
    fun eachPresetSendsItsScenarioCommand() {
        val viewModel = newViewModel()

        PresetScenario.entries.forEach(viewModel::sendPreset)

        assertEquals(
            listOf('r', 's', 'z', 'p', 'x', 'd'),
            sentScenarios.map { it.commandChar },
        )
        assertEquals(
            listOf(
                ScenarioCommand(ScenarioCommand.STALE_REPLAY),
                ScenarioCommand(ScenarioCommand.SYNTHETIC_STALE),
                ScenarioCommand(ScenarioCommand.ZERO_HOP),
                ScenarioCommand(ScenarioCommand.PLAINTEXT_COUNTER),
                ScenarioCommand(ScenarioCommand.RANDOM_HOP),
                ScenarioCommand(ScenarioCommand.DIFFERENT_SERIAL),
            ),
            sentScenarios,
        )
    }

    @Test
    fun staleReplayPresetUsesTheProtocolConstant() {
        val viewModel = newViewModel()

        viewModel.sendPreset(PresetScenario.STALE_REPLAY)

        assertEquals(ScenarioCommand('r'), sentScenarios.single())
        assertTrue(viewModel.uiState.value.message!!.contains("session 8"))
    }

    // --- Session-8 stub seams ----------------------------------------------

    @Test
    fun sendAllHandsTheQueueSnapshotToTheInjectedBatchSender() {
        val viewModel = newViewModel()
        val first = sendQueue.enqueue(0xA, 1, 1)
        val second = sendQueue.enqueue(0xB, 2, 2)

        viewModel.sendAll()

        assertEquals(listOf(listOf(first, second)), sentBatches)
        assertTrue(viewModel.uiState.value.message!!.contains("session 8"))
    }

    @Test
    fun sendAllWithAnEmptyQueueDoesNothing() {
        val viewModel = newViewModel()

        viewModel.sendAll()

        assertTrue(sentBatches.isEmpty())
        assertNull(viewModel.uiState.value.message)
    }
}
