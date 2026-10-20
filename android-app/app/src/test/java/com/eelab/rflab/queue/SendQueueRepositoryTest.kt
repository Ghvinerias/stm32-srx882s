package com.eelab.rflab.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendQueueRepositoryTest {

    @Test
    fun enqueueAppendsInOrderWithUniqueIds() {
        val repository = SendQueueRepository()

        val first = repository.enqueue(0xAAAA_AAAAL, 0x0111_1111L, 0x1)
        val second = repository.enqueue(0xBBBB_BBBBL, 0x0222_2222L, 0x2)

        assertEquals(listOf(first, second), repository.items.value)
        assertTrue(first.id != second.id)
        assertEquals(0xAAAA_AAAAL, repository.items.value[0].hopping)
        assertEquals(0x0222_2222L, repository.items.value[1].serial)
    }

    @Test
    fun removeOnlyDropsTheRequestedItem() {
        val repository = SendQueueRepository()
        val first = repository.enqueue(1, 2, 3)
        val second = repository.enqueue(4, 5, 6)

        assertTrue(repository.remove(first.id))
        assertEquals(listOf(second), repository.items.value)
        assertFalse(repository.remove(first.id))
    }

    @Test
    fun clearEmptiesTheQueue() {
        val repository = SendQueueRepository()
        repository.enqueue(1, 2, 3)

        repository.clear()

        assertTrue(repository.items.value.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsButtonOutsideNibble() {
        SendQueueRepository().enqueue(1, 2, 0x10)
    }
}
