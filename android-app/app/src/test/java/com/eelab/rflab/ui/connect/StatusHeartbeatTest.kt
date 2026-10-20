package com.eelab.rflab.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fresh/stale transitions for the STATUS heartbeat.
 *
 * Everything is driven by explicit "now" values, so there are no sleeps and
 * no Android/USB dependencies. The device sends STATUS once a second; the
 * default threshold is 3 s.
 */
class StatusHeartbeatTest {

    @Test
    fun disconnectedTrackerIsWaitingWithNoAge() {
        val heartbeat = StatusHeartbeat()

        assertFalse(heartbeat.hasStatus)
        assertEquals(LinkLiveness.WAITING, heartbeat.livenessAt(10_000L))
        assertNull(heartbeat.ageOfLastStatusMs(10_000L))
    }

    @Test
    fun connectedWithoutStatusStaysWaitingUntilTheGraceWindowExpires() {
        val heartbeat = StatusHeartbeat()
        heartbeat.onConnected(1_000L)

        assertEquals(LinkLiveness.WAITING, heartbeat.livenessAt(1_000L))
        assertEquals(LinkLiveness.WAITING, heartbeat.livenessAt(3_999L))
        assertEquals(2_999L, heartbeat.ageOfLastStatusMs(3_999L))

        // Exactly at the threshold it is stale, not fresh: no STATUS for 3 s.
        assertEquals(LinkLiveness.STALE, heartbeat.livenessAt(4_000L))
        assertEquals(3_000L, heartbeat.ageOfLastStatusMs(4_000L))
    }

    @Test
    fun firstStatusMarksLiveAndRefreshesTheWindow() {
        val heartbeat = StatusHeartbeat()
        heartbeat.onConnected(1_000L)

        heartbeat.onStatus(2_500L)
        assertTrue(heartbeat.hasStatus)
        assertEquals(LinkLiveness.LIVE, heartbeat.livenessAt(2_500L))
        assertEquals(0L, heartbeat.ageOfLastStatusMs(2_500L))

        assertEquals(LinkLiveness.LIVE, heartbeat.livenessAt(5_499L))
        assertEquals(LinkLiveness.STALE, heartbeat.livenessAt(5_500L))

        // A late STATUS immediately recovers the link.
        heartbeat.onStatus(5_600L)
        assertEquals(LinkLiveness.LIVE, heartbeat.livenessAt(5_600L))
        assertTrue(heartbeat.hasStatus)
    }

    @Test
    fun statusWithoutAnExplicitConnectStartsTheWindow() {
        val heartbeat = StatusHeartbeat()

        heartbeat.onStatus(7_000L)

        assertEquals(LinkLiveness.LIVE, heartbeat.livenessAt(7_000L))
        assertEquals(LinkLiveness.STALE, heartbeat.livenessAt(10_000L))
    }

    @Test
    fun resetReturnsToDisconnectedWaiting() {
        val heartbeat = StatusHeartbeat()
        heartbeat.onConnected(1_000L)
        heartbeat.onStatus(1_500L)

        heartbeat.reset()

        assertFalse(heartbeat.hasStatus)
        assertEquals(LinkLiveness.WAITING, heartbeat.livenessAt(2_000L))
        assertNull(heartbeat.ageOfLastStatusMs(2_000L))
    }

    @Test
    fun thresholdIsInjectable() {
        val heartbeat = StatusHeartbeat(staleAfterMs = 500L)
        heartbeat.onConnected(0L)

        assertEquals(LinkLiveness.WAITING, heartbeat.livenessAt(499L))
        assertEquals(LinkLiveness.STALE, heartbeat.livenessAt(500L))
    }

    @Test
    fun clockGoingBackwardsNeverProducesNegativeAge() {
        val heartbeat = StatusHeartbeat()
        heartbeat.onStatus(5_000L)

        assertEquals(0L, heartbeat.ageOfLastStatusMs(4_000L))
        assertEquals(LinkLiveness.LIVE, heartbeat.livenessAt(4_000L))
    }
}
