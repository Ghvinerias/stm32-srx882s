package com.eelab.rflab.usb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The app's current USB connection, if any.
 *
 * This is the session-6 seam between the receive screen and the transport:
 * it lets the screen observe [events] without knowing how a connection is
 * opened. Session 9 replaces the placeholder [attach]/[detach] calls with
 * the real permission/connect lifecycle on the Connect screen; until then
 * nothing attaches a connection, so [events] is simply empty and the
 * receive screen stays in its "no signals yet" state.
 *
 * A connection is a hot source: attaching one starts delivering its events,
 * replacing it unsubscribes from the old one first, and detaching returns to
 * an empty stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RfLabSession {

    private val _connection = MutableStateFlow<RfLabConnection?>(null)

    /** The connection currently in use, or null while disconnected. */
    val connection: StateFlow<RfLabConnection?> = _connection.asStateFlow()

    /**
     * Transport events from the current connection, or nothing while there
     * is none. Collectors see packets only from the moment they subscribe,
     * matching [RfLabConnection.events].
     */
    val events: Flow<TransportEvent> = _connection.flatMapLatest { current ->
        current?.events ?: emptyFlow()
    }

    /**
     * Makes [connection] current, or clears the session when null.
     *
     * Replacing a connection does not close the previous one; call [detach]
     * first if it should be shut down.
     *
     * TODO(session 9): call this from the Connect screen once permission has
     * been granted and [RfLabConnection.open] succeeds, and detach on
     * disconnect.
     */
    fun attach(connection: RfLabConnection?) {
        _connection.value = connection
    }

    /** Closes and clears the current connection, if any. */
    fun detach() {
        val previous = _connection.value
        _connection.value = null
        previous?.close()
    }
}
