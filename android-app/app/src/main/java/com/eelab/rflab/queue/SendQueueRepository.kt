package com.eelab.rflab.queue

import com.eelab.rflab.protocol.TxCommand
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-item state of a queued transmit request.
 *
 * New items start in [PENDING]; the sequential send engine owns every
 * transition from there.
 */
enum class SendItemStatus {
    /** Queued, not sent yet (also: still queued after a batch aborted). */
    PENDING,

    /** Currently on the wire, waiting for its ACK. */
    SENDING,

    /** The device ACKed this item with `accepted = 1`. */
    ACKED_OK,

    /** The device ACKed this item with `accepted = 0`. */
    ACKED_FAILED,

    /** No ACK arrived before the batch engine's timeout. */
    TIMEOUT,

    /**
     * The USB link failed (device unplugged/reset) before or while this item
     * could be confirmed. Distinct from [TIMEOUT]: the command may never have
     * reached the board, and nothing is known about whether it was executed.
     */
    FAILED,

    /**
     * The device stopped this burst early (ACK `accepted = 2`), normally
     * because the user pressed Stop. Ends the batch; later items stay [PENDING].
     */
    ABORTED,
}

/**
 * One signal waiting to be transmitted.
 *
 * @property id process-local unique id, assigned by [SendQueueRepository].
 * @property hopping 32-bit hopping word as captured or entered manually.
 * @property serial 28-bit serial number as captured or entered manually.
 * @property button button nibble, `0x0..0xF`.
 * @property frameCount number of frames to transmit, `1..255`. Editable from
 *   the send screen; [SendQueueRepository.setFrameCount] applies the clamp.
 * @property status batch-send progress. Starts [SendItemStatus.PENDING] and is
 *   only changed by the batch send engine.
 */
data class SendQueueItem(
    val id: Long,
    val hopping: Long,
    val serial: Long,
    val button: Int,
    val frameCount: Int = DEFAULT_FRAME_COUNT,
    val status: SendItemStatus = SendItemStatus.PENDING,
) {

    init {
        require(hopping in 0..UINT32_MAX) { "hopping must fit in 32 bits: $hopping" }
        require(serial in 0..SERIAL_MAX) { "serial must fit in 28 bits: $serial" }
        require(button in 0..BUTTON_MAX) { "button must be 0x0..0xF: $button" }
        require(frameCount in MIN_FRAME_COUNT..MAX_FRAME_COUNT) {
            "frameCount must be $MIN_FRAME_COUNT..$MAX_FRAME_COUNT: $frameCount"
        }
    }

    /** Converts this queue item into the protocol command the send engine sends. */
    fun toTxCommand(): TxCommand = TxCommand(
        hopping = hopping,
        serial = serial,
        button = button,
        frameCount = frameCount,
    )

    companion object {
        const val UINT32_MAX = 0xFFFF_FFFFL
        const val SERIAL_MAX = TxCommand.SERIAL_MASK
        const val BUTTON_MAX = 0xF

        const val MIN_FRAME_COUNT = TxCommand.MIN_FRAME_COUNT
        const val MAX_FRAME_COUNT = TxCommand.MAX_FRAME_COUNT

        /** Sensible default for both captured and manually entered items. */
        const val DEFAULT_FRAME_COUNT = 5
    }
}

/**
 * Shared send queue, filled from the receive screen, managed by the send
 * screen, then drained by the batch engine.
 *
 * Deliberately a plain in-memory, process-scoped object rather than a DI
 * framework: this app has a single queue for the lifetime of the process.
 * [shared] is the instance the UI uses; tests construct their own for
 * isolation.
 *
 * All methods are thread-safe. [items] is a hot [StateFlow]: the send screen
 * renders it directly and the batch engine can snapshot it.
 */
class SendQueueRepository {

    private val _items = MutableStateFlow<List<SendQueueItem>>(emptyList())

    /** Current queue, top of the send order first. */
    val items: StateFlow<List<SendQueueItem>> = _items.asStateFlow()

    private val nextId = AtomicLong(1L)

    /** Appends one captured signal and returns the stored item. */
    fun enqueue(
        hopping: Long,
        serial: Long,
        button: Int,
        frameCount: Int = SendQueueItem.DEFAULT_FRAME_COUNT,
    ): SendQueueItem {
        val item = SendQueueItem(
            id = nextId.getAndIncrement(),
            hopping = hopping,
            serial = serial,
            button = button,
            frameCount = frameCount,
        )
        _items.update { it + item }
        return item
    }

    /**
     * Appends a manual [command] built by the protocol layer.
     *
     * Keeps the send screen from copying fields between the two models: the
     * [TxCommand] constructor has already validated every range.
     */
    fun enqueue(command: TxCommand): SendQueueItem = enqueue(
        hopping = command.hopping,
        serial = command.serial,
        button = command.button,
        frameCount = command.frameCount,
    )

    /** Removes the item with [id]; returns true if it was present. */
    fun remove(id: Long): Boolean {
        var removed = false
        _items.update { current ->
            current.filterNot { it.id == id }.also { removed = it.size != current.size }
        }
        return removed
    }

    /**
     * Moves the item with [id] one place towards the front of the queue.
     *
     * @return true if the queue changed; false for an unknown id or an item
     *   already at the front.
     */
    fun moveUp(id: Long): Boolean = moveBy(id, -1)

    /**
     * Moves the item with [id] one place towards the back of the queue.
     *
     * @return true if the queue changed; false for an unknown id or an item
     *   already at the back.
     */
    fun moveDown(id: Long): Boolean = moveBy(id, +1)

    /**
     * Sets [frameCount] on the item with [id], clamped to the protocol's
     * `1..255` range.
     *
     * @return true if the item exists.
     */
    fun setFrameCount(id: Long, frameCount: Int): Boolean {
        val clamped = frameCount.coerceIn(
            SendQueueItem.MIN_FRAME_COUNT,
            SendQueueItem.MAX_FRAME_COUNT,
        )
        return updateItem(id) { it.copy(frameCount = clamped) }
    }

    /**
     * Sets the batch-send [status] on the item with [id].
     *
     * Called by the send engine before/after each item, on timeout and when
     * the link fails mid-batch.
     *
     * @return true if the item exists.
     */
    fun setStatus(id: Long, status: SendItemStatus): Boolean =
        updateItem(id) { it.copy(status = status) }

    /** Drops every queued item. */
    fun clear() {
        _items.value = emptyList()
    }

    private fun moveBy(id: Long, delta: Int): Boolean {
        var moved = false
        _items.update { current ->
            val index = current.indexOfFirst { it.id == id }
            val target = index + delta
            if (index < 0 || target !in current.indices) {
                current
            } else {
                moved = true
                current.toMutableList().apply { add(target, removeAt(index)) }
            }
        }
        return moved
    }

    private inline fun updateItem(
        id: Long,
        transform: (SendQueueItem) -> SendQueueItem,
    ): Boolean {
        var updated = false
        _items.update { current ->
            current.map { item ->
                if (item.id == id) {
                    updated = true
                    transform(item)
                } else {
                    item
                }
            }
        }
        return updated
    }

    companion object {
        /** The queue shared by the receive and send screens. */
        val shared: SendQueueRepository by lazy { SendQueueRepository() }
    }
}
