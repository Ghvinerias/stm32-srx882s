package com.eelab.rflab.favorites

import com.eelab.rflab.queue.SendQueueItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Named snapshots of the send queue, backed by a [FavoriteStorage].
 *
 * The initial list is read from storage in the constructor, so a repository
 * built after a process restart is immediately populated. Every mutation is
 * persisted before the in-memory [favorites] flow changes, keeping the disk
 * copy from lagging behind what the UI shows.
 *
 * Ordering is newest-created first. Saving an existing name overwrites that
 * favorite in place: it keeps its [FavoriteQueue.id] and list position, only
 * its items are replaced. New names are prepended.
 *
 * Name matching is exact after trimming surrounding whitespace and is
 * case-sensitive ("Front door" and "front door" are different favorites).
 *
 * Instances are process-scoped (one per app process in production). Methods
 * are synchronized so a save/delete pair from two coroutines cannot interleave
 * their read-modify-write sequence.
 *
 * @param storage persistence seam; tests inject an in-memory fake.
 */
class FavoritesRepository(
    private val storage: FavoriteStorage,
) {

    private val _favorites = MutableStateFlow(storage.loadAll())

    /** Saved favorites, newest-created first. */
    val favorites: StateFlow<List<FavoriteQueue>> = _favorites.asStateFlow()

    /** Next id for a new favorite, derived from whatever storage already holds. */
    private var nextId: Long = (_favorites.value.maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * Saves [items] as a favorite named [name], replacing a same-named
     * favorite in place.
     *
     * The runtime queue fields (id/status) are intentionally dropped: a
     * favorite is a protocol template, not a live queue entry.
     *
     * @throws IllegalArgumentException if [name] is blank. Callers in the UI
     *   validate first; this is the repository's backstop.
     */
    @Synchronized
    fun save(name: String, items: List<SendQueueItem>): FavoriteQueue {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "favorite name must not be blank" }

        val snapshot = items.map { item ->
            FavoriteItem(
                hopping = item.hopping,
                serial = item.serial,
                button = item.button,
                frameCount = item.frameCount,
            )
        }
        val existing = _favorites.value.firstOrNull { it.name == trimmedName }
        val saved = if (existing == null) {
            FavoriteQueue(id = nextId++, name = trimmedName, items = snapshot)
        } else {
            existing.copy(items = snapshot)
        }

        val updated = if (existing == null) {
            listOf(saved) + _favorites.value
        } else {
            _favorites.value.map { favorite ->
                if (favorite.id == existing.id) saved else favorite
            }
        }
        commit(updated)
        return saved
    }

    /** Deletes the favorite with [id]; no-op for an unknown id. */
    @Synchronized
    fun delete(id: Long) {
        val current = _favorites.value
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return
        commit(updated)
    }

    /**
     * Persists first, then publishes: if the storage write throws, the
     * in-memory flow keeps describing exactly what is on disk.
     */
    private fun commit(favorites: List<FavoriteQueue>) {
        storage.saveAll(favorites)
        _favorites.value = favorites
    }
}
