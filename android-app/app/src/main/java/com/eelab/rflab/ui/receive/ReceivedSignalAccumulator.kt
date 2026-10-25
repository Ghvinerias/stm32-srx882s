package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.DecodedSignal

/**
 * Accumulates decoded signals and collapses press repeats into rows.
 *
 * A remote's press produces several identical decodes (same hopping, serial
 * and button) within a few milliseconds. A new decode joins the most recent
 * matching row when it arrives no later than [groupWindowMs] after that
 * row's previous decode; otherwise it starts a new row. Grouping by the
 * previous decode rather than the first one means a slow stream of repeats
 * keeps extending the same row for as long as it keeps arriving, which is
 * what a held button looks like.
 *
 * The window is measured on the caller-supplied [accept] timestamp, not on
 * [DecodedSignal.timestampUs]: the device timestamp is a 32-bit microsecond
 * uptime counter that wraps every ~71 minutes, while host arrival time is
 * unambiguous. Keeping the clock out of this class makes grouping
 * deterministic in tests.
 *
 * Rows are ordered newest first by first-seen time. A repeat updates its row
 * in place without moving it, so the list stays stable while a press is
 * repeating.
 *
 * Rows can be selected for "add to send queue". Selection is ordered: each
 * selected row carries a 1-based [ReceivedSignalRow.selectionOrder] in the
 * order it was selected, and [selectedRows] returns them in that order. At
 * most [MAX_SELECTED_ROWS] rows can be selected at once; deselecting one
 * renumbers the rows selected after it so the numbers stay contiguous.
 */
class ReceivedSignalAccumulator(
    private val groupWindowMs: Long = DEFAULT_GROUP_WINDOW_MS,
) {

    private data class Entry(
        val row: ReceivedSignalRow,
        val lastSeenMs: Long,
    )

    /** Newest first. */
    private val entries = ArrayList<Entry>()

    private var nextId = 0L

    init {
        require(groupWindowMs >= 0) { "groupWindowMs must be >= 0: $groupWindowMs" }
    }

    /**
     * Processes one decode received at [receivedAtMs].
     *
     * A non-negative delta within [groupWindowMs] adds a repeat to the most
     * recent matching row; anything else adds a new row at the top. A clock
     * that jumps backwards therefore starts a new row rather than merging
     * across the jump.
     */
    fun accept(signal: DecodedSignal, receivedAtMs: Long) {
        val matchingIndex = entries.indexOfFirst { entry ->
            entry.row.matches(signal) &&
                receivedAtMs - entry.lastSeenMs in 0..groupWindowMs
        }

        if (matchingIndex >= 0) {
            val entry = entries[matchingIndex]
            entries[matchingIndex] = entry.copy(
                row = entry.row.copy(repeatCount = entry.row.repeatCount + 1),
                lastSeenMs = receivedAtMs,
            )
            return
        }

        entries.add(
            0,
            Entry(
                row = ReceivedSignalRow(
                    id = nextId++,
                    firstSeenMs = receivedAtMs,
                    hopping = signal.hopping,
                    serial = signal.serial,
                    button = signal.button,
                    captureTimestampUs = signal.timestampUs,
                ),
                lastSeenMs = receivedAtMs,
            ),
        )
    }

    /** Rows newest first. */
    fun rows(): List<ReceivedSignalRow> = entries.map { it.row }

    /**
     * Selected rows in selection order, first selected first. This is the
     * order "add to send queue" enqueues them in, not the display order.
     */
    fun selectedRows(): List<ReceivedSignalRow> =
        entries.map { it.row }
            .filter { it.selectionOrder != null }
            .sortedBy { it.selectionOrder ?: Int.MAX_VALUE }

    val selectedCount: Int
        get() = entries.count { it.row.selectionOrder != null }

    /**
     * Flips the selection of [rowId]; returns false when the toggle is a
     * no-op.
     *
     * Selecting appends the row to the end of the ordered selection and
     * numbers it after the last selected row. Deselecting removes it and
     * renumbers the rows selected after it, so the remaining numbers stay
     * contiguous from 1. Selecting an unselected row while
     * [MAX_SELECTED_ROWS] rows are already selected is ignored and returns
     * false, as does an unknown id.
     */
    fun toggleSelection(rowId: Long): Boolean {
        val index = entries.indexOfFirst { it.row.id == rowId }
        if (index < 0) return false

        val entry = entries[index]
        val order = entry.row.selectionOrder
        if (order == null) {
            if (selectedCount >= MAX_SELECTED_ROWS) return false
            entries[index] = entry.copy(
                row = entry.row.copy(selectionOrder = selectedCount + 1),
            )
            return true
        }

        entries[index] = entry.copy(row = entry.row.copy(selectionOrder = null))
        renumberAfterDeselect(order)
        return true
    }

    /** Clears every selection; returns true if anything was selected. */
    fun clearSelection(): Boolean {
        if (selectedCount == 0) return false
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.row.selectionOrder != null) {
                entries[i] = entry.copy(row = entry.row.copy(selectionOrder = null))
            }
        }
        return true
    }

    /** Wipes every row, along with the whole selection. */
    fun clear() {
        entries.clear()
    }

    /**
     * Closes the gap left by deselecting the row at [removedOrder] by moving
     * every row selected later down one position. Selection orders are kept
     * contiguous, so numbers above [removedOrder] form an unbroken run.
     */
    private fun renumberAfterDeselect(removedOrder: Int) {
        for (i in entries.indices) {
            val row = entries[i].row
            val order = row.selectionOrder ?: continue
            if (order > removedOrder) {
                entries[i] = entries[i].copy(row = row.copy(selectionOrder = order - 1))
            }
        }
    }

    private fun ReceivedSignalRow.matches(signal: DecodedSignal): Boolean =
        hopping == signal.hopping && serial == signal.serial && button == signal.button

    companion object {
        /**
         * Repeats of one press arrive within milliseconds of each other. The
         * window is deliberately generous compared with the brief's "a few
         * ms" so USB buffering and scheduling jitter cannot split a press,
         * while still separating two deliberate presses of the same button
         * (which are typically hundreds of ms apart).
         */
        const val DEFAULT_GROUP_WINDOW_MS = 500L

        /**
         * Most rows that can be selected at once. The send queue works
         * through its items sequentially, so an accidental "select all"
         * should not turn into an unbounded batch.
         */
        const val MAX_SELECTED_ROWS = 15
    }
}
