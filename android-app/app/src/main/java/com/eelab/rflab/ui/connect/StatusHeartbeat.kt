package com.eelab.rflab.ui.connect

/**
 * Liveness tracking for the device's ~1/s STATUS heartbeat.
 *
 * Pure Kotlin and driven entirely by an injected "now", so the fresh/stale
 * transitions are unit-tested on the JVM without timers or a device. One
 * instance belongs to one connection attempt: [onConnected] starts the clock
 * (STATUS is expected within a second), [onStatus] refreshes it, and
 * [reset] returns to the disconnected state.
 *
 * Staleness threshold: **3 s** without a STATUS while connected. The device
 * sends one every second, so this tolerates two lost heartbeats before the
 * screen says the link is stale. The UI's liveness ticker polls
 * [livenessAt] twice a second; this class itself never schedules anything.
 *
 * Before the first STATUS, the connection time is used as the reference, so
 * a board that opens but never sends anything still goes stale after
 * [staleAfterMs] instead of claiming to be live forever.
 */
class StatusHeartbeat(
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {

    init {
        require(staleAfterMs > 0) { "staleAfterMs must be positive: $staleAfterMs" }
    }

    private var connectedAtMs: Long? = null
    private var lastStatusAtMs: Long? = null

    /** True once at least one STATUS has arrived since [onConnected]/[reset]. */
    val hasStatus: Boolean get() = lastStatusAtMs != null

    /** Starts (or restarts) the heartbeat window for a new connection. */
    fun onConnected(nowMs: Long) {
        connectedAtMs = nowMs
        lastStatusAtMs = null
    }

    /** Records a STATUS arrival and refreshes the window. */
    fun onStatus(nowMs: Long) {
        if (connectedAtMs == null) connectedAtMs = nowMs
        lastStatusAtMs = nowMs
    }

    /** Returns to the disconnected state. */
    fun reset() {
        connectedAtMs = null
        lastStatusAtMs = null
    }

    /**
     * Time since the last STATUS, or since connecting before the first one.
     * Null while disconnected. Never negative if the clock steps backwards.
     */
    fun ageOfLastStatusMs(nowMs: Long): Long? {
        val reference = lastStatusAtMs ?: connectedAtMs ?: return null
        return (nowMs - reference).coerceAtLeast(0L)
    }

    /** Current liveness at [nowMs]. */
    fun livenessAt(nowMs: Long): LinkLiveness {
        val reference = lastStatusAtMs ?: connectedAtMs ?: return LinkLiveness.WAITING
        val ageMs = (nowMs - reference).coerceAtLeast(0L)
        return when {
            ageMs >= staleAfterMs -> LinkLiveness.STALE
            lastStatusAtMs != null -> LinkLiveness.LIVE
            else -> LinkLiveness.WAITING
        }
    }

    companion object {
        /**
         * Missing this long means the link is stale. Two dropped 1/s
         * heartbeats are tolerated, the third is not.
         */
        const val DEFAULT_STALE_AFTER_MS: Long = 3_000L
    }
}
