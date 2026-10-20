package com.eelab.rflab.ui.send

import com.eelab.rflab.favorites.FavoriteQueue
import com.eelab.rflab.favorites.FavoriteStorage
import com.eelab.rflab.favorites.FavoritesRepository
import com.eelab.rflab.protocol.TxCommand
import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.testing.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Queue management, manual-entry validation and clear-queue tests for
 * [SendViewModel].
 *
 * The batch lambda is captured per test, so nothing touches the USB layer;
 * session 8's sequential engine is tested separately in
 * `BatchSendEngineTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var sendQueue: SendQueueRepository
    private lateinit var favoritesStorage: RecordingFavoriteStorage
    private lateinit var favoritesRepository: FavoritesRepository
    private val sentBatches = mutableListOf<List<SendQueueItem>>()

    @Before
    fun setUp() {
        sendQueue = SendQueueRepository()
        favoritesStorage = RecordingFavoriteStorage()
        favoritesRepository = FavoritesRepository(favoritesStorage)
        sentBatches.clear()
    }

    private fun newViewModel(
        connectionStatus: Flow<Boolean> = flowOf(true),
    ): SendViewModel = SendViewModel(
        sendQueue = sendQueue,
        batchSender = { items, onStatusChange ->
            sentBatches += items
            // Stand-in for the engine's per-item terminal callbacks.
            items.forEach { onStatusChange(it.id, SendItemStatus.ACKED_OK) }
        },
        favoritesRepository = favoritesRepository,
        connectionStatus = connectionStatus,
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

    @Test
    fun clearQueueDropsEveryItemAndUpdatesUiState() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1)
        sendQueue.enqueue(0xB, 2, 2)
        assertTrue(viewModel.uiState.value.canClearQueue)

        viewModel.clearQueue()

        assertTrue(sendQueue.items.value.isEmpty())
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertFalse(viewModel.uiState.value.canClearQueue)
        assertFalse(viewModel.uiState.value.canSendAll)
    }

    @Test
    fun clearQueueIsRefusedWhileABatchIsSending() {
        val gate = CompletableDeferred<Unit>()
        val viewModel = SendViewModel(
            sendQueue = sendQueue,
            batchSender = { _, _ -> gate.await() },
            favoritesRepository = favoritesRepository,
            connectionStatus = flowOf(true),
        )
        val item = sendQueue.enqueue(0xA, 1, 1)

        viewModel.sendAll()
        viewModel.clearQueue()

        assertEquals(listOf(item), sendQueue.items.value)
        assertEquals(listOf(item), viewModel.uiState.value.items)
        assertTrue(
            viewModel.uiState.value.message!!.contains("Can't clear the queue"),
        )

        // Let the batch finish so no coroutine outlives the test.
        gate.complete(Unit)
    }

    // --- Favorites ----------------------------------------------------------

    @Test
    fun saveFavoriteSnapshotsTheQueueAndExposesItInUiState() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1, frameCount = 3)
        sendQueue.enqueue(0xB, 2, 2)

        assertFalse(viewModel.uiState.value.canSaveFavorite)
        viewModel.onFavoriteNameChange("Front door")
        assertTrue(viewModel.uiState.value.canSaveFavorite)

        viewModel.saveFavorite(viewModel.uiState.value.favoriteName)

        val favorite = viewModel.uiState.value.favorites.single()
        assertEquals("Front door", favorite.name)
        assertEquals(2, favorite.items.size)
        assertEquals(3, favorite.items.first().frameCount)
        assertEquals("", viewModel.uiState.value.favoriteName)
        assertTrue(viewModel.uiState.value.message!!.contains("Front door"))
        // The snapshot reached the injected storage, not just the flow.
        assertEquals(listOf(favorite), favoritesStorage.loadAll())
    }

    @Test
    fun saveFavoriteNoOpsForBlankName() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1)

        viewModel.saveFavorite("   ")

        assertTrue(favoritesRepository.favorites.value.isEmpty())
        assertTrue(viewModel.uiState.value.favorites.isEmpty())
        assertEquals(0, favoritesStorage.saveCount)
    }

    @Test
    fun saveFavoriteNoOpsForEmptyQueue() {
        val viewModel = newViewModel()

        viewModel.saveFavorite("Front door")

        assertTrue(favoritesRepository.favorites.value.isEmpty())
        assertTrue(viewModel.uiState.value.favorites.isEmpty())
        assertEquals(0, favoritesStorage.saveCount)
    }

    @Test
    fun savingTheSameNameAgainUpdatesTheExistingFavorite() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1)
        viewModel.saveFavorite("Front door")
        val original = viewModel.uiState.value.favorites.single()

        viewModel.clearQueue()
        sendQueue.enqueue(0xB, 2, 2)
        sendQueue.enqueue(0xC, 3, 3)
        viewModel.saveFavorite("Front door")

        val updated = viewModel.uiState.value.favorites.single()
        assertEquals(original.id, updated.id)
        assertEquals(2, updated.items.size)
        assertEquals(listOf(updated), favoritesStorage.loadAll())
    }

    @Test
    fun loadFavoriteAppendsToTheExistingQueue() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1, frameCount = 3)
        sendQueue.enqueue(0xB, 2, 2)
        viewModel.saveFavorite("Saved")
        val favorite = viewModel.uiState.value.favorites.single()

        // A different item is queued after saving; loading must not replace it.
        viewModel.clearQueue()
        val existing = sendQueue.enqueue(0xC, 3, 3)
        viewModel.loadFavorite(favorite.id)

        val items = sendQueue.items.value
        assertEquals(3, items.size)
        assertEquals(existing.id, items.first().id)
        assertEquals(
            favorite.items.map { Triple(it.hopping, it.serial, it.button) },
            items.drop(1).map { Triple(it.hopping, it.serial, it.button) },
        )
        // Loaded entries are fresh pending queue items, not stale snapshots.
        assertEquals(SendItemStatus.PENDING, items[1].status)
        assertTrue(items[1].id > existing.id)
        assertEquals(items, viewModel.uiState.value.items)
        assertTrue(viewModel.uiState.value.message!!.contains("Appended 2 item(s)"))
    }

    @Test
    fun loadFavoriteKeepsSavedFrameCounts() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1, frameCount = 8)
        viewModel.saveFavorite("Eight frames")
        val favorite = viewModel.uiState.value.favorites.single()
        viewModel.clearQueue()

        viewModel.loadFavorite(favorite.id)

        assertEquals(8, sendQueue.items.value.single().frameCount)
    }

    @Test
    fun loadFavoriteAppendsEvenWhenTheSameQueueIsStillLoaded() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1)
        viewModel.saveFavorite("Duplicate me")
        val favorite = viewModel.uiState.value.favorites.single()

        viewModel.loadFavorite(favorite.id)

        assertEquals(2, sendQueue.items.value.size)
        assertEquals(2, viewModel.uiState.value.items.size)
    }

    @Test
    fun loadUnknownFavoriteIsANoOp() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1)

        viewModel.loadFavorite(999L)

        assertEquals(1, sendQueue.items.value.size)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun deleteFavoriteRemovesItFromUiStateAndStorage() {
        val viewModel = newViewModel()
        sendQueue.enqueue(0xA, 1, 1)
        viewModel.saveFavorite("Front door")
        val favorite = viewModel.uiState.value.favorites.single()

        viewModel.deleteFavorite(favorite.id)

        assertTrue(viewModel.uiState.value.favorites.isEmpty())
        assertTrue(favoritesStorage.loadAll().isEmpty())
        assertTrue(viewModel.uiState.value.message!!.contains("Deleted"))
    }

    @Test
    fun favoritesLoadedFromStorageArePresentAtConstruction() {
        val existing = FavoriteQueue(
            id = 5,
            name = "Persisted",
            items = listOf(com.eelab.rflab.favorites.FavoriteItem(0xA, 1, 1, 5)),
        )
        favoritesStorage = RecordingFavoriteStorage(listOf(existing))
        favoritesRepository = FavoritesRepository(favoritesStorage)

        val viewModel = newViewModel()

        assertEquals(listOf(existing), viewModel.uiState.value.favorites)
    }

    // --- Session-8 stub seams ----------------------------------------------

    @Test
    fun sendAllHandsTheQueueSnapshotToTheInjectedBatchSender() {
        val viewModel = newViewModel()
        val first = sendQueue.enqueue(0xA, 1, 1)
        val second = sendQueue.enqueue(0xB, 2, 2)

        viewModel.sendAll()

        assertEquals(listOf(listOf(first, second)), sentBatches)
        // The injected sender's terminal callbacks reached the shared queue.
        assertEquals(
            listOf(SendItemStatus.ACKED_OK, SendItemStatus.ACKED_OK),
            sendQueue.items.value.map { it.status },
        )
        assertTrue(viewModel.uiState.value.message!!.contains("2 ACKed OK"))
    }

    @Test
    fun sendAllWithAnEmptyQueueDoesNothing() {
        val viewModel = newViewModel()

        viewModel.sendAll()

        assertTrue(sentBatches.isEmpty())
        assertNull(viewModel.uiState.value.message)
    }

    // --- Session-9 connection gating ---------------------------------------

    @Test
    fun sendAllIsRefusedWhileDisconnected() {
        val viewModel = newViewModel(connectionStatus = flowOf(false))
        sendQueue.enqueue(0xA, 1, 1)

        assertFalse(viewModel.uiState.value.canSendAll)

        viewModel.sendAll()

        assertTrue(sentBatches.isEmpty())
        assertEquals(SendViewModel.NOT_CONNECTED_MESSAGE, viewModel.uiState.value.message)
    }

    @Test
    fun connectionStatusFlowDrivesSendAvailability() {
        val status = MutableStateFlow(false)
        val viewModel = newViewModel(connectionStatus = status)

        assertFalse(viewModel.uiState.value.isConnected)
        assertFalse(viewModel.uiState.value.canSendAll)

        sendQueue.enqueue(0xA, 1, 1)
        assertFalse(viewModel.uiState.value.canSendAll)

        status.value = true
        assertTrue(viewModel.uiState.value.isConnected)
        assertTrue(viewModel.uiState.value.canSendAll)

        status.value = false
        assertFalse(viewModel.uiState.value.isConnected)
        assertFalse(viewModel.uiState.value.canSendAll)
    }

    // --- Link failure during a batch ---------------------------------------

    @Test
    fun batchSenderFailureLeavesNoItemSendingAndIsReported() {
        val viewModel = SendViewModel(
            sendQueue = sendQueue,
            batchSender = { items, onStatusChange ->
                onStatusChange(items[0].id, SendItemStatus.SENDING)
                throw IOException("USB connection is closed")
            },
            favoritesRepository = favoritesRepository,
            connectionStatus = flowOf(true),
        )
        sendQueue.enqueue(0xA, 1, 1)
        sendQueue.enqueue(0xB, 2, 2)

        viewModel.sendAll()

        assertEquals(
            listOf(SendItemStatus.FAILED, SendItemStatus.PENDING),
            sendQueue.items.value.map { it.status },
        )
        val message = viewModel.uiState.value.message!!
        assertTrue(message.contains("1 link error"))
        assertTrue(message.contains("1 not sent"))
    }

    @Test
    fun abortedBatchSummarisesSentTimedOutAndUnattemptedItems() {
        val viewModel = SendViewModel(
            sendQueue = sendQueue,
            batchSender = { items, onStatusChange ->
                onStatusChange(items[0].id, SendItemStatus.ACKED_OK)
                onStatusChange(items[1].id, SendItemStatus.TIMEOUT)
                onStatusChange(items[2].id, SendItemStatus.FAILED)
                // items[3] is never attempted: it stays PENDING.
            },
            favoritesRepository = favoritesRepository,
            connectionStatus = flowOf(true),
        )
        repeat(4) { index -> sendQueue.enqueue(0xAL + index, 1, 1) }

        viewModel.sendAll()

        val message = viewModel.uiState.value.message!!
        assertTrue(message.contains("Batch stopped"))
        assertTrue(message.contains("1 ACKed OK"))
        assertTrue(message.contains("1 timed out"))
        assertTrue(message.contains("1 link error"))
        assertTrue(message.contains("1 not sent"))
    }
}

/** In-memory persistence fake used to assert save/delete reached storage. */
private class RecordingFavoriteStorage(
    initial: List<FavoriteQueue> = emptyList(),
) : FavoriteStorage {

    private var stored: List<FavoriteQueue> = initial.toList()

    var saveCount: Int = 0
        private set

    override fun loadAll(): List<FavoriteQueue> = stored

    override fun saveAll(favorites: List<FavoriteQueue>) {
        stored = favorites.toList()
        saveCount++
    }
}
