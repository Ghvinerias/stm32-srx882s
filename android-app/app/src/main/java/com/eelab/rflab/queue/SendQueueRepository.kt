package com.eelab.rflab.queue

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One signal waiting to be transmitted.
 *
 * This is the session-6 minimum the receive screen needs: the captured
 * hopping/serial/button plus an id so the send screen can key a list and
 * remove individual entries. Session 7 owns the rest of the queue model
 * (editable frame count, per-item send state, manual-entry items).
 *
 * @property id process-local unique id, assigned by [SendQueueRepository].
 * @property hopping 32-bit hopping word as captured.
 * @property serial serial number as captured.
 * @property button button nibble, `0x0..0xF`.
 */
data class SendQueueItem(
    val id: Long,
    val hopping: Long,
    val serial: Long,
    val button: Int,
) {

    init {
        require(hopping in 0..UINT32_MAX) { "hopping must fit in 32 bits: $hopping" }
        require(serial in 0..UINT32_MAX) { "serial must fit in 32 bits: $serial" }
        require(button in 0..BUTTON_MAX) { "button must be 0x0..0xF: $button" }
    }

    companion object {
        const val UINT32_MAX = 0xFFFF_FFFFL
        const val BUTTON_MAX = 0xF
    }
}

/**
 * Shared send queue, filled from the receive screen and consumed by the send
 * screen (session 7).
 *
 * Deliberately a plain in-memory, process-scoped object rather than a DI
 * framework: this app has a single queue for the lifetime of the process.
 * [shared] is the instance the UI uses; tests construct their own for
 * isolation.
 *
 * All methods are thread-safe. [items] is a hot [StateFlow]: later sessions
 * can render it directly and send the batch from its snapshot.
 */
class SendQueueRepository {

    private val _items = MutableStateFlow<List<SendQueueItem>>(emptyList())

    /** Current queue, oldest first. */
    val items: StateFlow<List<SendQueueItem>> = _items.asStateFlow()

    private val nextId = AtomicLong(1L)

    /** Appends one captured signal and returns the stored item. */
    fun enqueue(hopping: Long, serial: Long, button: Int): SendQueueItem {
        val item = SendQueueItem(
            id = nextId.getAndIncrement(),
            hopping = hopping,
            serial = serial,
            button = button,
        )
        _items.update { it + item }
        return item
    }

    /** Removes the item with [id]; returns true if it was present. */
    fun remove(id: Long): Boolean {
        var removed = false
        _items.update { current ->
            current.filterNot { it.id == id }.also { removed = it.size != current.size }
        }
        return removed
    }

    /** Drops every queued item. */
    fun clear() {
        _items.value = emptyList()
    }

    companion object {
        /** The queue shared by the receive and send screens. */
        val shared: SendQueueRepository by lazy { SendQueueRepository() }
    }
}
