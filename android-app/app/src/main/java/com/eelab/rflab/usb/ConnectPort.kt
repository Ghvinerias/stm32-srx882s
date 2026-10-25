package com.eelab.rflab.usb

import kotlinx.coroutines.flow.Flow

/**
 * Outcome of one user-initiated connection attempt.
 *
 * The distinction matters to the Connect screen: a denied permission is a
 * normal, retryable answer from the user, while [Failed] means the board was
 * missing or could not be opened.
 */
sealed interface ConnectAttempt {

    /** The connection is open and attached to [RfLabSession]. */
    data object Connected : ConnectAttempt

    /** The user (or the 15 s dialog timeout) denied USB permission. */
    data object PermissionDenied : ConnectAttempt

    /** The board was not found, or opening it failed. [message] is user-facing. */
    data class Failed(val message: String) : ConnectAttempt
}

/**
 * The connection lifecycle the Connect screen drives.
 *
 * This is the minimal seam between the UI and Android's USB APIs: the
 * ViewModel sees only attempt outcomes and the transport event stream, never
 * `UsbManager`/`Activity`, so it is testable on the JVM. The production
 * implementation is [UsbConnectController].
 *
 * Implementations own the session wiring: a successful [connect] must make
 * the opened connection current in [RfLabSession], and [disconnect] must
 * close and clear it. [events] is normally [RfLabSession.events], so it keeps
 * switching with the attached connection.
 */
interface ConnectPort {

    /** Transport events from the current connection (empty while detached). */
    val events: Flow<TransportEvent>

    /** True when a live connection is currently attached. */
    fun isConnected(): Boolean

    /**
     * Finds the board, requests permission if needed, opens the connection
     * and attaches it to the session.
     *
     * Suspends while the system permission dialog is up. Cancelling the
     * calling coroutine aborts the wait; a connection that finished opening
     * after cancellation is closed rather than attached.
     */
    suspend fun connect(): ConnectAttempt

    /** Closes and detaches the current connection, if any. Idempotent. */
    fun disconnect()
}
