package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.PrincetonFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class StaticCodeAccumulatorTest {

    private fun frame(data: Long) = PrincetonFrame(data = data, bitCount = 24, serial = data ushr 4, button = (data and 0xF).toInt())

    @Test
    fun firstFrameStartsOneRowWithRepeatCountOne() {
        val acc = StaticCodeAccumulator()
        acc.accept(frame(0x123456), atMs = 1000)
        val rows = acc.rows()
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].repeatCount)
        assertEquals(0x123456L, rows[0].data)
    }

    @Test
    fun identicalFrameWithinWindowIncrementsRepeatCount() {
        val acc = StaticCodeAccumulator(groupWindowMs = 500)
        acc.accept(frame(0x123456), atMs = 1000)
        acc.accept(frame(0x123456), atMs = 1200)
        acc.accept(frame(0x123456), atMs = 1300)
        val rows = acc.rows()
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].repeatCount)
        // firstSeenMs does not move when a repeat joins.
        assertEquals(1000L, rows[0].firstSeenMs)
    }

    @Test
    fun identicalFrameOutsideWindowStartsANewRow() {
        val acc = StaticCodeAccumulator(groupWindowMs = 100)
        acc.accept(frame(0x123456), atMs = 1000)
        acc.accept(frame(0x123456), atMs = 2000)
        assertEquals(2, acc.rows().size)
    }

    @Test
    fun differentDataStartsANewRowEvenWithinWindow() {
        val acc = StaticCodeAccumulator(groupWindowMs = 500)
        acc.accept(frame(0x111111), atMs = 1000)
        acc.accept(frame(0x222222), atMs = 1100)
        val rows = acc.rows()
        assertEquals(2, rows.size)
        // Newest first.
        assertEquals(0x222222L, rows[0].data)
        assertEquals(0x111111L, rows[1].data)
    }

    @Test
    fun rowCarriesBothPt2262AndEv1527FieldReadingsOfTheSameCode() {
        val acc = StaticCodeAccumulator()
        acc.accept(frame(0xABCDEF), atMs = 1000)
        val row = acc.rows()[0]
        // PT2262 reading (from the PrincetonFrame fields).
        assertEquals(0xABCDEL, row.serial)
        assertEquals(0xF, row.button)
        // EV1527 reading (20-bit address + 4-bit data of the same 24-bit code).
        assertEquals(0xABCDEL, row.address)
        assertEquals(0xF, row.dataNibble)
    }

    @Test
    fun clearWipesEveryRow() {
        val acc = StaticCodeAccumulator()
        acc.accept(frame(0x123456), atMs = 1000)
        acc.clear()
        assertEquals(0, acc.rows().size)
    }

    @Test
    fun idsAreAssignedInArrivalOrderAndStayStable() {
        val acc = StaticCodeAccumulator(groupWindowMs = 0)
        acc.accept(frame(0x1), atMs = 1000)
        acc.accept(frame(0x2), atMs = 2000)
        val rows = acc.rows()
        assertEquals(2L, rows[0].id) // newest first
        assertEquals(1L, rows[1].id)
    }
}
