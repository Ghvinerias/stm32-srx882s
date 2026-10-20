package com.eelab.rflab.ui.connect

/**
 * Coarse connection state shown by the Connect screen.
 *
 * [PERMISSION_DENIED] is kept separate from [ERROR] because it is a normal
 * user answer that can be retried, not a transport failure.
 */
enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    PERMISSION_DENIED,
    CONNECTED,
    ERROR,
}

/**
 * Link quality derived from the device's ~1/s STATUS heartbeat.
 *
 * Only meaningful while [ConnectionPhase.CONNECTED]:
 * - [WAITING]  connected, no STATUS inside the stale window yet (or none
 *              since reconnect).
 * - [LIVE]     a STATUS arrived inside the window.
 * - [STALE]    nothing arrived for [StatusHeartbeat.DEFAULT_STALE_AFTER_MS].
 */
enum class LinkLiveness {
    WAITING,
    LIVE,
    STALE,
}

/**
 * The diagnostic values reassembled from a STATUS payload.
 *
 * Optional to display, per the brief; kept as plain values so the UI layer
 * does not depend on the protocol packet type.
 */
data class StatusDiagnostics(
    val clockHz: Long,
    val totalEdges: Long,
    val droppedPackets: Int,
    val sequence: Int,
)

/**
 * Everything the Connect screen renders.
 *
 * @property phase connection lifecycle state.
 * @property liveness heartbeat-derived link quality (see [LinkLiveness]).
 * @property lastStatusAgeMs milliseconds since the last STATUS, or since the
 *   connection was established before the first one; null while disconnected.
 * @property diagnostics most recent STATUS values, if any have arrived.
 * @property message user-facing detail for permission denial / errors.
 */
data class ConnectUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val liveness: LinkLiveness = LinkLiveness.WAITING,
    val lastStatusAgeMs: Long? = null,
    val diagnostics: StatusDiagnostics? = null,
    val message: String? = null,
) {
    val isConnected: Boolean get() = phase == ConnectionPhase.CONNECTED
    val canConnect: Boolean get() = phase != ConnectionPhase.CONNECTING && !isConnected
    val canDisconnect: Boolean get() = phase == ConnectionPhase.CONNECTING || isConnected
}
