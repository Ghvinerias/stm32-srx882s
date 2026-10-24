package com.eelab.rflab.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's KeeLoq keystore: a persisted list of named keys plus the id of
 * the currently selected one.
 *
 * Mirrors `FavoritesRepository`: the list is read from [storage] in the
 * constructor, every mutation is persisted before the in-memory flow changes,
 * and methods are synchronized so concurrent edits cannot interleave. Keys are
 * newest-first. Selection is kept only in memory (it is a UI convenience, not
 * worth persisting), and defaults to the first key.
 *
 * @param storage persistence seam; tests inject an in-memory fake.
 */
class KeeLoqKeyRepository(
    private val storage: KeyStorage,
) {

    private val _keys = MutableStateFlow(storage.loadAll())

    /** Stored keys, newest-added first. */
    val keys: StateFlow<List<KeeLoqKey>> = _keys.asStateFlow()

    private val _selectedId = MutableStateFlow(_keys.value.firstOrNull()?.id)

    /** Id of the active key, or null when the store is empty. */
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private var nextId: Long = (_keys.value.maxOfOrNull { it.id } ?: 0L) + 1L

    /** The currently selected key, or null when none is selected/stored. */
    fun selected(): KeeLoqKey? = _keys.value.firstOrNull { it.id == _selectedId.value }

    /**
     * Add a key named [name] with raw 64-bit [value]. A blank name is
     * rejected. Adding the first key selects it.
     */
    @Synchronized
    fun add(name: String, value: Long): KeeLoqKey {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "key name must not be blank" }
        val created = KeeLoqKey(id = nextId++, name = trimmed, key = value)
        commit(listOf(created) + _keys.value)
        if (_selectedId.value == null) _selectedId.value = created.id
        return created
    }

    /** Rename the key with [id]; no-op for an unknown id or blank name. */
    @Synchronized
    fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = _keys.value
        if (current.none { it.id == id }) return
        commit(current.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    /** Delete the key with [id]; re-selects another key if the active one went. */
    @Synchronized
    fun delete(id: Long) {
        val current = _keys.value
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return
        commit(updated)
        if (_selectedId.value == id) _selectedId.value = updated.firstOrNull()?.id
    }

    /** Select the key with [id]; ignored if no such key exists. */
    @Synchronized
    fun select(id: Long) {
        if (_keys.value.any { it.id == id }) _selectedId.value = id
    }

    private fun commit(updated: List<KeeLoqKey>) {
        storage.saveAll(updated)
        _keys.value = updated
    }
}
