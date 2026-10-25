package com.eelab.rflab.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeeLoqKeyRepositoryTest {

    /** In-memory [KeyStorage] so repository behaviour runs without Android. */
    private class FakeStorage(initial: List<KeeLoqKey> = emptyList()) : KeyStorage {
        var saved: List<KeeLoqKey> = initial
        override fun loadAll(): List<KeeLoqKey> = saved
        override fun saveAll(keys: List<KeeLoqKey>) { saved = keys }
    }

    @Test
    fun addPersistsAndSelectsFirstKey() {
        val storage = FakeStorage()
        val repo = KeeLoqKeyRepository(storage)

        val k = repo.add("Garage", 0x1122334455667788uL.toLong())

        assertEquals(listOf(k), repo.keys.value)
        assertEquals(k.id, repo.selectedId.value)
        assertEquals(listOf(k), storage.saved)
        assertEquals(0x1122334455667788uL.toLong(), repo.selected()!!.key)
    }

    @Test
    fun keysAreNewestFirstAndSelectionStaysOnFirstAdded() {
        val repo = KeeLoqKeyRepository(FakeStorage())
        val a = repo.add("A", 1)
        val b = repo.add("B", 2)
        assertEquals(listOf(b, a), repo.keys.value)
        // Selection stays on the first key added until the user changes it.
        assertEquals(a.id, repo.selectedId.value)
        repo.select(b.id)
        assertEquals(b.id, repo.selectedId.value)
    }

    @Test
    fun deletingSelectedKeyReselectsAnother() {
        val repo = KeeLoqKeyRepository(FakeStorage())
        val a = repo.add("A", 1)
        repo.add("B", 2)
        repo.select(a.id)

        repo.delete(a.id)

        assertEquals(1, repo.keys.value.size)
        assertEquals(repo.keys.value.first().id, repo.selectedId.value)
    }

    @Test
    fun deletingLastKeyClearsSelection() {
        val repo = KeeLoqKeyRepository(FakeStorage())
        val a = repo.add("only", 1)
        repo.delete(a.id)
        assertTrue(repo.keys.value.isEmpty())
        assertNull(repo.selectedId.value)
        assertNull(repo.selected())
    }

    @Test
    fun blankNameIsRejected() {
        val repo = KeeLoqKeyRepository(FakeStorage())
        assertThrows(IllegalArgumentException::class.java) { repo.add("  ", 1) }
    }

    @Test
    fun existingKeysAreLoadedAtConstruction() {
        val storage = FakeStorage(listOf(KeeLoqKey(id = 5, name = "saved", key = 0xABCDL)))
        val repo = KeeLoqKeyRepository(storage)
        assertEquals(1, repo.keys.value.size)
        assertEquals(5L, repo.selectedId.value)
        // A new id must not collide with the loaded one.
        val added = repo.add("new", 0x10L)
        assertTrue(added.id > 5L)
    }
}
