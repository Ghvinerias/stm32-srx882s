package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.DecodedSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection-ordering and clear tests for [ReceivedSignalAccumulator].
 *
 * Rows are fed as distinct hopping words, so each decode starts its own row
 * without depending on the repeat-grouping window. The list is newest first,
 * which makes the difference between display order and selection order
 * explicit in these tests.
 */
class ReceivedSignalAccumulatorTest {

    @Test
    fun selectionIsCappedAtFifteenRows() {
        val accumulator = accumulatorWithRows(16)
        val rows = accumulator.rows() // newest first
        val firstFifteen = rows.take(15)

        firstFifteen.forEach { assertTrue(accumulator.toggleSelection(it.id)) }

        assertEquals(15, accumulator.selectedCount)
        assertEquals(15, accumulator.selectedRows().size)
        assertEquals(
            "orders are 1..15 in click order",
            (1..15).toList(),
            accumulator.selectedRows().map { it.selectionOrder },
        )

        val sixteenth = rows[15]
        assertFalse(
            "a 16th selection is ignored",
            accumulator.toggleSelection(sixteenth.id),
        )
        assertEquals(15, accumulator.selectedCount)
        assertNull(row(accumulator, sixteenth.id).selectionOrder)

        // Freeing a slot makes the same row selectable again.
        assertTrue(accumulator.toggleSelection(firstFifteen.first().id))
        assertTrue(accumulator.toggleSelection(sixteenth.id))
        assertEquals(15, accumulator.selectedCount)
        assertEquals(15, row(accumulator, sixteenth.id).selectionOrder)
    }

    @Test
    fun deselectingAMiddleRowRenumbersTheRestContiguously() {
        val accumulator = accumulatorWithRows(3)
        val rows = accumulator.rows() // newest first: rows[2] is oldest
        val a = rows[2]
        val b = rows[1]
        val c = rows[0]

        accumulator.toggleSelection(a.id)
        accumulator.toggleSelection(b.id)
        accumulator.toggleSelection(c.id)
        assertEquals(listOf(1, 2, 3), orders(accumulator))

        accumulator.toggleSelection(b.id)

        assertEquals(2, accumulator.selectedCount)
        assertEquals(1, row(accumulator, a.id).selectionOrder)
        assertEquals(2, row(accumulator, c.id).selectionOrder)
        assertNull(row(accumulator, b.id).selectionOrder)
        assertEquals("no gaps after renumbering", listOf(1, 2), orders(accumulator))

        // A new selection appends after the renumbered rows.
        accumulator.toggleSelection(b.id)
        assertEquals(3, row(accumulator, b.id).selectionOrder)
        assertEquals(listOf(1, 2, 3), orders(accumulator))
    }

    @Test
    fun selectedRowsReturnsSelectionOrderNotListOrder() {
        val accumulator = accumulatorWithRows(3)
        val rows = accumulator.rows() // newest first
        val oldest = rows[2]
        val newest = rows[0]
        val middle = rows[1]

        accumulator.toggleSelection(oldest.id)
        accumulator.toggleSelection(newest.id)
        accumulator.toggleSelection(middle.id)

        assertEquals(
            "first selected comes first, regardless of display order",
            listOf(oldest.id, newest.id, middle.id),
            accumulator.selectedRows().map { it.id },
        )
        assertEquals(
            "rows() still reports display order",
            listOf(newest.id, middle.id, oldest.id),
            accumulator.rows().map { it.id },
        )
    }

    @Test
    fun clearWipesRowsAndSelection() {
        val accumulator = accumulatorWithRows(3)
        accumulator.toggleSelection(accumulator.rows()[0].id)
        accumulator.toggleSelection(accumulator.rows()[1].id)

        accumulator.clear()

        assertTrue(accumulator.rows().isEmpty())
        assertTrue(accumulator.selectedRows().isEmpty())
        assertEquals(0, accumulator.selectedCount)
        assertFalse("nothing left to clear", accumulator.clearSelection())
    }

    /** Feeds [count] distinct hopping words, one per row. */
    private fun accumulatorWithRows(count: Int): ReceivedSignalAccumulator {
        val accumulator = ReceivedSignalAccumulator()
        for (i in 0 until count) {
            accumulator.accept(
                signal = DecodedSignal(
                    hopping = 0x1000_0000L + i,
                    serial = 0x0FB58C0CL,
                    button = 0x2,
                    vlow = false,
                    repeat = false,
                ),
                receivedAtMs = i * 1_000L,
            )
        }
        return accumulator
    }

    private fun row(
        accumulator: ReceivedSignalAccumulator,
        id: Long,
    ): ReceivedSignalRow = accumulator.rows().first { it.id == id }

    /** Selection orders in selection order, for contiguity assertions. */
    private fun orders(accumulator: ReceivedSignalAccumulator): List<Int?> =
        accumulator.selectedRows().map { it.selectionOrder }
}
