package com.eelab.rflab.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.protocol.StatusPacket
import com.eelab.rflab.usb.ConnectAttempt
import com.eelab.rflab.usb.ConnectPort
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Connection lifecycle and heartbeat state for the Connect screen.
 *
 * [connect] delegates the Android-specific work (enumeration, permission,
 * opening) to [ConnectPort], then reflects the outcome as [ConnectionPhase].
 * On success the port has already attached the connection to
 * `RfLabSession`, so the Receive and Send screens start seeing the same
 * stream immediately; this ViewModel only observes it for the heartbeat.
 *
 * Liveness is NOT driven by the presence of a connection alone: the board
 * must keep sending its ~1/s STATUS packet. A ticker re-evaluates
 * [StatusHeartbeat] every [LIVENESS_TICK_MS], so a silent link becomes
 * [LinkLiveness.STALE] even with no new packets (see
 * [StatusHeartbeat.DEFAULT_STALE_AFTER_MS], 3 s).
 *
 * The ViewModel deliberately does not disconnect in [onCleared]: the
 * connection belongs to the app-wide session, and leaving the Connect tab
 * must not kill the receive stream. Re-creating the ViewModel while a link
 * is already open starts in [ConnectionPhase.CONNECTED] and waits for the
 * next STATUS before calling the link live.
 *
 * @param port USB connection seam; production is
 *   [com.eelab.rflab.usb.UsbConnectController].
 * @param clock wall-clock milliseconds, injectable for deterministic tests.
 * @param heartbeat fresh/stale tracker, injectable to shorten the threshold
 *   in tests.
 */
class ConnectViewModel(
    private val port: ConnectPort,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val heartbeat: StatusHeartbeat = StatusHeartbeat(),
) : ViewModel() {

    private val initiallyConnected = port.isConnected()

    private var connectJob: Job? = null

    private val _uiState = MutableStateFlow(
        if (initiallyConnected) {
            ConnectUiState(phase = ConnectionPhase.CONNECTED)
        } else {
            ConnectUiState()
        },
    )

    /** Connection phase, heartbeat liveness and STATUS diagnostics. */
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    init {
        if (initiallyConnected) heartbeat.onConnected(clock())

        viewModelScope.launch {
            port.events.collect(::onTransportEvent)
        }
        viewModelScope.launch {
            while (isActive) {
                delay(LIVENESS_TICK_MS)
                refreshLiveness()
            }
        }
    }

    /**
     * Starts a connection attempt.
     *
     * Ignored while one is already running or while connected. A denied
     * permission or a failed open leaves the app disconnected and retryable;
     * [ConnectUiState.canConnect] stays true in those phases.
     */
    fun connect() {
        if (!_uiState.value.canConnect) return

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _uiState.value = ConnectUiState(phase = ConnectionPhase.CONNECTING)
            heartbeat.reset()

            val attempt = try {
                port.connect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ConnectAttempt.Failed(error.message ?: "Connection failed")
            }

            if (!isActive) {
                // Disconnect cancelled us while the port was opening; make
                // sure nothing is left attached.
                port.disconnect()
                return@launch
            }

            when (attempt) {
                ConnectAttempt.Connected -> {
                    heartbeat.onConnected(clock())
                    _uiState.value = ConnectUiState(
                        phase = ConnectionPhase.CONNECTED,
                        liveness = LinkLiveness.WAITING,
                    )
                }

                ConnectAttempt.PermissionDenied -> {
                    heartbeat.reset()
                    _uiState.value = ConnectUiState(
                        phase = ConnectionPhase.PERMISSION_DENIED,
                        message = "USB permission was denied or the prompt timed out. " +
                            "Tap Connect to ask again.",
                    )
                }

                is ConnectAttempt.Failed -> {
                    heartbeat.reset()
                    _uiState.value = ConnectUiState(
                        phase = ConnectionPhase.ERROR,
                        message = attempt.message,
                    )
                }
            }
        }
    }

    /**
     * Cancels an in-flight attempt (the permission wait or port open), then
     * closes the connection and returns to [ConnectionPhase.DISCONNECTED].
     *
     * The session detach happens inside [ConnectPort.disconnect], which also
     * clears the stream the Receive/Send screens are observing.
     */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        port.disconnect()
        heartbeat.reset()
        _uiState.value = ConnectUiState()
    }

    /**
     * Re-evaluates liveness against the injected clock. Called by the ticker
     * and directly by unit tests.
     */
    internal fun refreshLiveness() {
        val now = clock()
        _uiState.update { current ->
            if (current.phase != ConnectionPhase.CONNECTED) {
                current
            } else {
                current.copy(
                    liveness = heartbeat.livenessAt(now),
                    lastStatusAgeMs = heartbeat.ageOfLastStatusMs(now),
                )
            }
        }
    }

    private fun onTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PacketReceived -> {
                val status = event.packet as? StatusPacket ?: return
                if (!_uiState.value.isConnected) return

                heartbeat.onStatus(clock())
                _uiState.update { current ->
                    current.copy(
                        liveness = LinkLiveness.LIVE,
                        lastStatusAgeMs = 0L,
                        diagnostics = StatusDiagnostics(
                            clockHz = status.clockHz,
                            totalEdges = status.totalEdges,
                            droppedPackets = status.droppedPackets,
                            sequence = status.sequence,
                        ),
                    )
                }
            }

            is TransportEvent.ConnectionLost -> {
                if (!_uiState.value.isConnected) return

                // The read loop stopped on an I/O error. Detach so the send
                // path stops offering a dead connection, and surface why.
                heartbeat.reset()
                _uiState.value = ConnectUiState(
                    phase = ConnectionPhase.ERROR,
                    message = "Connection lost: " +
                        (event.cause?.message?.takeIf { it.isNotBlank() }
                            ?: "the board stopped responding"),
                )
                port.disconnect()
            }

            // Malformed frames are not a heartbeat and do not affect the
            // connection state; the Receive screen counts them and shows the
            // total so a noisy link is visible without flapping this state.
            is TransportEvent.DecodeError -> Unit
        }
    }

    companion object {
        /** How often the screen re-checks STATUS age while connected. */
        const val LIVENESS_TICK_MS: Long = 500L

        /**
         * ViewModel factory for the app composition root. Tests construct the
         * ViewModel directly with a fake [ConnectPort].
         */
        fun factory(
            port: ConnectPort,
            clock: () -> Long = { System.currentTimeMillis() },
            heartbeat: StatusHeartbeat = StatusHeartbeat(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConnectViewModel(port, clock, heartbeat) }
        }
    }
}
