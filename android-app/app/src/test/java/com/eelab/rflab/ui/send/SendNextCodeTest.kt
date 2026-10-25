package com.eelab.rflab.ui.send

import com.eelab.rflab.crypto.KeeLoqHop
import com.eelab.rflab.crypto.KeeLoqKey
import com.eelab.rflab.favorites.FavoriteQueue
import com.eelab.rflab.favorites.FavoriteStorage
import com.eelab.rflab.favorites.FavoritesRepository
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Next code" send path: compute the encrypted hop with the selected key and
 * the tracked (or overridden) counter, enqueue it, and advance the tracker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendNextCodeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val key = KeeLoqKey(id = 1, name = "Garage", key = 0x5CEC6701B79FD949L)
    private val serial = 0x0FB58C0CL

    private class FakeFavorites : FavoriteStorage {
        override fun loadAll(): List<FavoriteQueue> = emptyList()
        override fun saveAll(favorites: List<FavoriteQueue>) {}
    }

    private fun newVm(
        selected: KeeLoqKey? = key,
        counters: MutableMap<Long, Int> = mutableMapOf(),
        queue: SendQueueRepository = SendQueueRepository(),
    ) = SendViewModel(
        sendQueue = queue,
        favoritesRepository = FavoritesRepository(FakeFavorites()),
        selectedKey = MutableStateFlow(selected),
        nextCounterFor = { s -> counters[s]?.let { (it + 1) and 0xFFFF } },
        observeCounter = { s, c -> counters[s] = c },
    )

    @Test
    fun autoCounterUsesTrackerAndEncryptsCorrectly() = runTest {
        val counters = mutableMapOf(serial to 0x0200)
        val queue = SendQueueRepository()
        val vm = newVm(counters = counters, queue = queue)

        vm.onNextSerialChange("FB58C0C")
        vm.onNextButtonChange("2")
        vm.onNextFramesChange("3")
        assertTrue(vm.enqueueNextCode())

        val item = queue.items.value.single()
        // Auto counter was 0x200 + 1 = 0x201; the hop must decrypt back to it.
        val info = KeeLoqHop.decrypt(item.hopping.toInt(), key.key)
        assertEquals(0x201, info.counter)
        assertTrue(info.matchesClearButton(0x2))
        assertEquals(serial, item.serial)
        assertEquals(3, item.frameCount)
        // Tracker advanced so the next call would use 0x202.
        assertEquals(0x201, counters[serial])
    }

    @Test
    fun manualOverrideWinsOverTracker() = runTest {
        val counters = mutableMapOf(serial to 0x0010)
        val queue = SendQueueRepository()
        val vm = newVm(counters = counters, queue = queue)

        vm.onNextSerialChange("FB58C0C")
        vm.onNextButtonChange("4")
        vm.onNextCounterOverrideChange("0ABC")
        assertTrue(vm.enqueueNextCode())

        val info = KeeLoqHop.decrypt(queue.items.value.single().hopping.toInt(), key.key)
        assertEquals(0x0ABC, info.counter)
    }

    @Test
    fun noKeySelectedFailsAndQueuesNothing() = runTest {
        val queue = SendQueueRepository()
        val vm = newVm(selected = null, queue = queue)
        vm.onNextSerialChange("FB58C0C")
        vm.onNextButtonChange("2")
        assertFalse(vm.enqueueNextCode())
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun noCounterSeenAndNoOverrideFails() = runTest {
        val queue = SendQueueRepository()
        val vm = newVm(counters = mutableMapOf(), queue = queue)
        vm.onNextSerialChange("FB58C0C")
        vm.onNextButtonChange("2")
        assertFalse(vm.enqueueNextCode())
        assertTrue(queue.items.value.isEmpty())
        assertEquals(
            "No counter seen yet — receive a press or enter one",
            vm.uiState.value.nextCode.counterError,
        )
    }
}
