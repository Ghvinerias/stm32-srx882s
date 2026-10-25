package com.eelab.rflab.queue

import com.eelab.rflab.protocol.TxCommand
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

    @Test
    fun enqueueFromTxCommandCopiesProtocolFields() {
        val repository = SendQueueRepository()

        val item = repository.enqueue(TxCommand(0xAAAA_AAAAL, 0x0111_1111L, 0x3, 7))

        assertEquals(0xAAAA_AAAAL, item.hopping)
        assertEquals(0x0111_1111L, item.serial)
        assertEquals(0x3, item.button)
        assertEquals(7, item.frameCount)
        assertEquals(SendItemStatus.PENDING, item.status)
    }

    @Test
    fun frameCountDefaultsToFiveAndClampsToProtocolRange() {
        val repository = SendQueueRepository()
        val item = repository.enqueue(1, 2, 3)

        assertEquals(SendQueueItem.DEFAULT_FRAME_COUNT, item.frameCount)

        repository.setFrameCount(item.id, 0)
        assertEquals(1, repository.items.value.single().frameCount)

        repository.setFrameCount(item.id, 999)
        assertEquals(255, repository.items.value.single().frameCount)

        repository.setFrameCount(item.id, 6)
        assertEquals(6, repository.items.value.single().frameCount)
    }

    @Test
    fun moveUpAndDownReorderWithoutRunningPastTheEnds() {
        val repository = SendQueueRepository()
        val first = repository.enqueue(0xA, 1, 1)
        val second = repository.enqueue(0xB, 2, 2)
        val third = repository.enqueue(0xC, 3, 3)

        assertTrue(repository.moveUp(second.id))
        assertEquals(listOf(second, first, third), repository.items.value)

        assertFalse(repository.moveUp(second.id))
        assertEquals(listOf(second, first, third), repository.items.value)

        assertTrue(repository.moveDown(second.id))
        assertEquals(listOf(first, second, third), repository.items.value)

        assertFalse(repository.moveDown(third.id))
        assertFalse(repository.moveUp(-1))
    }

    @Test
    fun setStatusOnlyChangesTheRequestedItem() {
        val repository = SendQueueRepository()
        val first = repository.enqueue(0xA, 1, 1)
        val second = repository.enqueue(0xB, 2, 2)

        assertTrue(repository.setStatus(first.id, SendItemStatus.SENDING))
        assertEquals(SendItemStatus.SENDING, repository.items.value[0].status)
        assertEquals(SendItemStatus.PENDING, repository.items.value[1].status)

        assertTrue(repository.setStatus(first.id, SendItemStatus.ACKED_OK))
        assertEquals(SendItemStatus.ACKED_OK, repository.items.value[0].status)
        assertFalse(repository.setStatus(-1, SendItemStatus.TIMEOUT))

        // Status changes must not disturb the queued payload or frame count.
        assertEquals(first.hopping, repository.items.value[0].hopping)
        assertEquals(first.frameCount, repository.items.value[0].frameCount)
        assertEquals(second, repository.items.value[1])
    }
}
