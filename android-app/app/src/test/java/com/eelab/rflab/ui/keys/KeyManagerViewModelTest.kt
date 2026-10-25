package com.eelab.rflab.ui.keys

import com.eelab.rflab.crypto.KeeLoqKey
import com.eelab.rflab.crypto.KeeLoqKeyRepository
import com.eelab.rflab.crypto.KeyStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyManagerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeStorage : KeyStorage {
        var saved: List<KeeLoqKey> = emptyList()
        override fun loadAll() = saved
        override fun saveAll(keys: List<KeeLoqKey>) { saved = keys }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun parseKeyHexAcceptsFormatsAndRejectsBadInput() {
        assertEquals(0x1122334455667788uL.toLong(), KeyManagerViewModel.parseKeyHex("1122334455667788"))
        assertEquals(0xABuL.toLong(), KeyManagerViewModel.parseKeyHex("0xAB"))
        assertEquals(0x5CEC6701B79FD949uL.toLong(), KeyManagerViewModel.parseKeyHex("5CEC:6701:B79F:D949"))
        assertNull(KeyManagerViewModel.parseKeyHex(""))
        assertNull(KeyManagerViewModel.parseKeyHex("11223344556677889")) // 17 digits
        assertNull(KeyManagerViewModel.parseKeyHex("nothex"))
    }

    @Test
    fun addKeyValidatesAndStores() = runTest(dispatcher) {
        val repo = KeeLoqKeyRepository(FakeStorage())
        val vm = KeyManagerViewModel(repo)

        vm.onNameChange("Garage")
        vm.onKeyHexChange("5CEC6701B79FD949")
        assertTrue(vm.addKey())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.keys.size)
        assertEquals("Garage", vm.uiState.value.keys.first().name)
        assertEquals(0x5CEC6701B79FD949uL.toLong(), vm.uiState.value.keys.first().key)
        // Form cleared after a successful add.
        assertEquals("", vm.uiState.value.nameInput)
        assertEquals("", vm.uiState.value.keyHexInput)
    }

    @Test
    fun addKeyRejectsBlankNameAndBadHex() = runTest(dispatcher) {
        val repo = KeeLoqKeyRepository(FakeStorage())
        val vm = KeyManagerViewModel(repo)

        vm.onNameChange("  ")
        vm.onKeyHexChange("ABCD")
        assertFalse(vm.addKey())
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Name required", vm.uiState.value.nameError)

        vm.onNameChange("ok")
        vm.onKeyHexChange("xyz")
        assertFalse(vm.addKey())
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Enter 1-16 hex digits", vm.uiState.value.keyError)
        assertTrue(repo.keys.value.isEmpty())
    }

    @Test
    fun selectAndDeleteFlowThroughToState() = runTest(dispatcher) {
        val repo = KeeLoqKeyRepository(FakeStorage())
        val vm = KeyManagerViewModel(repo)
        vm.onNameChange("A"); vm.onKeyHexChange("1"); vm.addKey()
        vm.onNameChange("B"); vm.onKeyHexChange("2"); vm.addKey()
        dispatcher.scheduler.advanceUntilIdle()

        val b = vm.uiState.value.keys.first { it.name == "B" }
        vm.selectKey(b.id)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(b.id, vm.uiState.value.selectedId)

        vm.uiState.value.keys.forEach { vm.deleteKey(it.id) }
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.keys.isEmpty())
        assertNull(vm.uiState.value.selectedId)
    }
}
