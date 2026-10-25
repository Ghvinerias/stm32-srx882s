package com.eelab.rflab.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the highest sync counter seen per serial, so the "send the next
 * code" action can default to last + 1 for the user's own receiver, and
 * survive an app restart.
 *
 * [observe] is fed the counter decrypted from each received frame (only when a
 * key that matches the remote is selected — the caller decides that). It keeps
 * the maximum per serial, using 16-bit wraparound distance so a counter that
 * has rolled past 0xFFFF is still recognised as newer. [next] returns the
 * value to transmit (max + 1, wrapping at 16 bits), or null when nothing has
 * been seen for that serial yet, in which case the UI falls back to manual
 * entry.
 */
class CounterTracker(
    private val store: CounterStore,
) {

    private val _counters = MutableStateFlow(store.loadAll())

    /** Highest counter observed per serial. */
    val counters: StateFlow<Map<Long, Int>> = _counters.asStateFlow()

    /** Record a counter seen for [serial]; keeps the newer of the two. */
    @Synchronized
    fun observe(serial: Long, counter: Int) {
        val value = counter and 0xFFFF
        val current = _counters.value[serial]
        if (current != null && !isNewer(value, current)) return
        commit(_counters.value + (serial to value))
    }

    /** Highest counter seen for [serial], or null if none. */
    fun highest(serial: Long): Int? = _counters.value[serial]

    /** Next counter to send for [serial] (highest + 1, wrapping), or null. */
    fun next(serial: Long): Int? = highest(serial)?.let { (it + 1) and 0xFFFF }

    /** Forget [serial]'s tracked counter (e.g. after a manual resync). */
    @Synchronized
    fun forget(serial: Long) {
        if (!_counters.value.containsKey(serial)) return
        commit(_counters.value - serial)
    }

    private fun commit(updated: Map<Long, Int>) {
        store.saveAll(updated)
        _counters.value = updated
    }

    private companion object {
        /**
         * True when [candidate] is ahead of [current] in 16-bit counter space.
         * Anything within half the space forward counts as newer; this keeps
         * the tracker advancing across the 0xFFFF -> 0 wrap without treating a
         * stale repeat as progress.
         */
        fun isNewer(candidate: Int, current: Int): Boolean {
            val delta = (candidate - current) and 0xFFFF
            return delta in 1..0x7FFF
        }
    }
}
