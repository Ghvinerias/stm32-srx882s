package com.eelab.rflab.favorites

import com.eelab.rflab.queue.SendQueueItem

/**
 * One saved transmit command inside a [FavoriteQueue].
 *
 * Only the protocol-facing fields are stored. A favorite is a template, not a
 * live queue entry: queue ids and [com.eelab.rflab.queue.SendItemStatus] are
 * process-local runtime state, so loading a favorite creates fresh
 * [com.eelab.rflab.queue.SendQueueItem]s in [com.eelab.rflab.queue.SendQueueRepository]
 * instead of restoring stale ids/statuses.
 *
 * @property hopping 32-bit hopping word.
 * @property serial 28-bit serial number.
 * @property button button nibble, `0x0..0xF`.
 * @property frameCount number of frames to transmit, `1..10`.
 */
data class FavoriteItem(
    val hopping: Long,
    val serial: Long,
    val button: Int,
    val frameCount: Int = SendQueueItem.DEFAULT_FRAME_COUNT,
) {

    init {
        require(hopping in 0..SendQueueItem.UINT32_MAX) { "hopping must fit in 32 bits: $hopping" }
        require(serial in 0..SendQueueItem.SERIAL_MAX) { "serial must fit in 28 bits: $serial" }
        require(button in 0..SendQueueItem.BUTTON_MAX) { "button must be 0x0..0xF: $button" }
        require(frameCount in SendQueueItem.MIN_FRAME_COUNT..SendQueueItem.MAX_FRAME_COUNT) {
            "frameCount must be ${SendQueueItem.MIN_FRAME_COUNT}..${SendQueueItem.MAX_FRAME_COUNT}: $frameCount"
        }
    }
}

/**
 * A named snapshot of send-queue commands, persisted across process death.
 *
 * @property id stable identity assigned by [FavoritesRepository]; survives
 *   renames/overwrites so the UI can key lists by it.
 * @property name user-supplied label. Names are matched for overwrite after
 *   trimming surrounding whitespace and are case-sensitive.
 * @property items saved commands in send order; empty is allowed only for
 *   defensive loading of hand-edited storage, because saving is guarded by a
 *   non-empty queue.
 */
data class FavoriteQueue(
    val id: Long,
    val name: String,
    val items: List<FavoriteItem>,
)
