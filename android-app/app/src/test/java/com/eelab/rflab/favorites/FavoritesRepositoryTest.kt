package com.eelab.rflab.favorites

import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Save/overwrite/delete/restart behaviour for [FavoritesRepository].
 *
 * Uses an in-memory [FavoriteStorage] so the repository logic is tested
 * without Android's stubbed SharedPreferences. The thin
 * [SharedPreferencesFavoriteStorage] JSON layer is not exercised here; see
 * `SharedPreferencesFavoriteStorage`'s class doc and the project report for
 * that limitation.
 */
class FavoritesRepositoryTest {

    private val storage = InMemoryFavoriteStorage()
    private val repository = FavoritesRepository(storage)

    private fun item(
        hopping: Long = 0x63FD859DL,
        serial: Long = 0x0FB58C0CL,
        button: Int = 0x2,
        frameCount: Int = 5,
        id: Long = 1,
        status: SendItemStatus = SendItemStatus.PENDING,
    ) = SendQueueItem(
        id = id,
        hopping = hopping,
        serial = serial,
        button = button,
        frameCount = frameCount,
        status = status,
    )

    @Test
    fun saveSnapshotsProtocolFieldsWithoutRuntimeState() {
        val saved = repository.save(
            "Front door",
            listOf(
                item(hopping = 0x1111_1111L, serial = 0x222_2222L, button = 0x1, frameCount = 3),
                item(
                    hopping = 0x3333_3333L,
                    serial = 0x444_4444L,
                    button = 0x9,
                    frameCount = 10,
                    id = 99,
                    status = SendItemStatus.ACKED_OK,
                ),
            ),
        )

        assertEquals("Front door", saved.name)
        assertEquals(1L, saved.id)
        assertEquals(
            listOf(
                FavoriteItem(0x1111_1111L, 0x222_2222L, 0x1, 3),
                FavoriteItem(0x3333_3333L, 0x444_4444L, 0x9, 10),
            ),
            saved.items,
        )
        // The storage write happened as part of the mutation.
        assertEquals(listOf(saved), storage.loadAll())
        assertEquals(listOf(saved), repository.favorites.value)
    }

    @Test
    fun savingSameNameOverwritesInPlaceKeepingId() {
        repository.save("Front door", listOf(item(hopping = 0xAL, serial = 1, button = 1)))
        repository.save("Back door", listOf(item(hopping = 0xBL, serial = 2, button = 2)))
        val original = repository.favorites.value.first { it.name == "Front door" }

        val updated = repository.save(
            "  Front door  ",
            listOf(
                item(hopping = 0xCL, serial = 3, button = 3, frameCount = 8),
                item(id = 2, hopping = 0xDL, serial = 4, button = 4),
            ),
        )

        assertEquals(original.id, updated.id)
        assertEquals(2, repository.favorites.value.size)
        assertEquals(
            listOf("Back door", "Front door"),
            repository.favorites.value.map { it.name },
        )
        val savedFront = repository.favorites.value.first { it.name == "Front door" }
        assertEquals(original.id, savedFront.id)
        assertEquals(
            listOf(
                FavoriteItem(0xCL, 3, 3, 8),
                FavoriteItem(0xDL, 4, 4, SendQueueItem.DEFAULT_FRAME_COUNT),
            ),
            savedFront.items,
        )
        // The persisted copy matches the in-memory flow exactly.
        assertEquals(repository.favorites.value, storage.loadAll())
    }

    @Test
    fun nameMatchingIsCaseSensitive() {
        repository.save("Front door", listOf(item()))
        repository.save("front door", listOf(item(hopping = 0xBL)))

        assertEquals(
            listOf("front door", "Front door"),
            repository.favorites.value.map { it.name },
        )
    }

    @Test
    fun newFavoritesArePrepended() {
        repository.save("First", listOf(item()))
        repository.save("Second", listOf(item()))

        assertEquals(
            listOf("Second", "First"),
            repository.favorites.value.map { it.name },
        )
    }

    @Test
    fun deleteRemovesAndPersists() {
        val first = repository.save("First", listOf(item()))
        val second = repository.save("Second", listOf(item()))

        repository.delete(first.id)

        assertEquals(listOf(second), repository.favorites.value)
        assertEquals(listOf(second), storage.loadAll())
        assertEquals(3, storage.saveCount)
    }

    @Test
    fun deletingUnknownIdIsANoOp() {
        repository.save("First", listOf(item()))

        repository.delete(1234L)

        assertEquals(1, repository.favorites.value.size)
        assertEquals(1, storage.saveCount)
    }

    @Test
    fun constructingAgainstExistingStorageLoadsImmediatelyAndContinuesIds() {
        val existing = FavoriteQueue(
            id = 41,
            name = "After restart",
            items = listOf(FavoriteItem(0xAL, 1, 1, 5)),
        )
        val restartedStorage = InMemoryFavoriteStorage(listOf(existing))

        val restarted = FavoritesRepository(restartedStorage)

        // No save/load call needed: construction reads the persisted list.
        assertEquals(listOf(existing), restarted.favorites.value)

        val added = restarted.save("New", listOf(item()))
        assertEquals(42L, added.id)
        assertEquals(listOf(added, existing), restartedStorage.loadAll())
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNameIsRejected() {
        repository.save("   ", listOf(item()))
    }
}

/** Persistence fake whose [saveAll] replaces the "on-disk" list. */
private class InMemoryFavoriteStorage(
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
