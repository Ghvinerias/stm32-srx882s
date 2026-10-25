package com.eelab.rflab.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CounterTrackerTest {

    private class FakeStore(initial: Map<Long, Int> = emptyMap()) : CounterStore {
        var saved: Map<Long, Int> = initial
        override fun loadAll(): Map<Long, Int> = saved
        override fun saveAll(counters: Map<Long, Int>) { saved = counters }
    }

    private val serial = 0x0FB3EC77L

    @Test
    fun nextIsNullUntilSomethingObserved() {
        val tracker = CounterTracker(FakeStore())
        assertNull(tracker.highest(serial))
        assertNull(tracker.next(serial))
    }

    @Test
    fun observeKeepsHighestAndNextIsPlusOne() {
        val tracker = CounterTracker(FakeStore())
        tracker.observe(serial, 100)
        tracker.observe(serial, 105)
        tracker.observe(serial, 103) // stale, ignored
        assertEquals(105, tracker.highest(serial))
        assertEquals(106, tracker.next(serial))
    }

    @Test
    fun wrapAroundIsRecognisedAsNewer() {
        val tracker = CounterTracker(FakeStore())
        tracker.observe(serial, 0xFFFE)
        tracker.observe(serial, 0x0002) // wrapped past 0xFFFF
        assertEquals(0x0002, tracker.highest(serial))
        assertEquals(0x0003, tracker.next(serial))
    }

    @Test
    fun nextWrapsAtSixteenBits() {
        val tracker = CounterTracker(FakeStore())
        tracker.observe(serial, 0xFFFF)
        assertEquals(0x0000, tracker.next(serial))
    }

    @Test
    fun countersArePersistedAndReloaded() {
        val store = FakeStore()
        CounterTracker(store).observe(serial, 42)
        assertEquals(mapOf(serial to 42), store.saved)
        // A fresh tracker over the same store sees the value.
        assertEquals(42, CounterTracker(store).highest(serial))
    }

    @Test
    fun forgetClearsOneSerial() {
        val tracker = CounterTracker(FakeStore())
        tracker.observe(serial, 10)
        tracker.observe(0x1234L, 20)
        tracker.forget(serial)
        assertNull(tracker.highest(serial))
        assertEquals(20, tracker.highest(0x1234L))
    }

    @Test
    fun separateSerialsAreIndependent() {
        val tracker = CounterTracker(FakeStore())
        tracker.observe(serial, 500)
        tracker.observe(0xAAAAL, 7)
        assertEquals(501, tracker.next(serial))
        assertEquals(8, tracker.next(0xAAAAL))
    }
}
