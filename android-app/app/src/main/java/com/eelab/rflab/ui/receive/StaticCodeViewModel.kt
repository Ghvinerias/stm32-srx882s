package com.eelab.rflab.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.decode.Ht12Frame
import com.eelab.rflab.decode.Ht12Protocol
import com.eelab.rflab.decode.PrincetonFrame
import com.eelab.rflab.decode.PrincetonProtocol
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the static-codes screen renders.
 *
 * @property rows decoded Princeton/PT2262-style codes, newest first, with
 *   repeats collapsed.
 * @property isConnected the shared session's live connection state.
 * @property decodeErrorCount malformed/foreign transport frames (bad COBS,
 *   bad CRC, truncated/foreign packets) rejected since this screen started —
 *   the same transport-level signal [ReceiveViewModel] surfaces, counted
 *   here too so a noisy/desynchronised link is visible on this screen as
 *   well, not only on Receive. This is distinct from, and does **not**
 *   count, a FRAME packet that decoded fine at the transport level but
 *   simply isn't Princeton-shaped (e.g. a real HCS301 frame) — that is not
 *   an error, just a different protocol's frame, and stays silent here.
 */
data class StaticCodeUiState(
    val rows: List<StaticCodeRow> = emptyList(),
    val isConnected: Boolean = false,
    val decodeErrorCount: Int = 0,
) {
    val canClear: Boolean get() = rows.isNotEmpty()
}

/**
 * Turns the transport's packet stream into the static-codes screen's row
 * list, in parallel with (not instead of) [ReceiveViewModel]'s HCS301
 * pipeline: both subscribe to the same [TransportEvent] stream and each
 * only reacts to the frames it recognizes
 * ([PrincetonProtocol.decodeFrame] returns null for anything that isn't
 * Princeton-shaped, including real HCS301 frames), so adding this did not
 * require touching [ReceiveViewModel], [com.eelab.rflab.decode.Hcs301Decoder],
 * or any of their existing tests.
 *
 * There is no key, no counter, and no "add to send queue" here — see
 * [StaticCodeRow] and [com.eelab.rflab.decode.PrincetonProtocol]'s doc
 * comments for why.
 */
class StaticCodeViewModel(
    frames: Flow<PrincetonFrame>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    connected: Flow<Boolean> = flowOf(true),
    private val accumulator: StaticCodeAccumulator = StaticCodeAccumulator(),
    private val decodeErrorCount: StateFlow<Int> = MutableStateFlow(0),
    ht12Frames: Flow<Ht12Frame> = emptyFlow(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(StaticCodeUiState())
    val uiState: StateFlow<StaticCodeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            frames.collect { frame ->
                accumulator.accept(frame, clock())
                publish()
            }
        }
        viewModelScope.launch {
            ht12Frames.collect { frame ->
                accumulator.accept(frame, clock())
                publish()
            }
        }
        viewModelScope.launch {
            connected.collect { isUp -> _uiState.update { it.copy(isConnected = isUp) } }
        }
        viewModelScope.launch {
            decodeErrorCount.collect { count -> _uiState.update { it.copy(decodeErrorCount = count) } }
        }
    }

    fun clearReceived() {
        accumulator.clear()
        publish()
    }

    private fun publish() {
        _uiState.update { it.copy(rows = accumulator.rows()) }
    }

    companion object {
        fun create(
            transportEvents: Flow<TransportEvent>,
            connected: Flow<Boolean> = flowOf(true),
            clock: () -> Long = { System.currentTimeMillis() },
        ): StaticCodeViewModel {
            val decodeErrors = MutableStateFlow(0)
            return StaticCodeViewModel(
                frames = decodeSignals(transportEvents, decodeErrors),
                connected = connected,
                clock = clock,
                decodeErrorCount = decodeErrors,
                ht12Frames = decodeHt12Signals(transportEvents),
            )
        }

        fun factory(
            transportEvents: Flow<TransportEvent>,
            connected: Flow<Boolean> = flowOf(true),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { create(transportEvents, connected) }
        }

        /**
         * Counts [TransportEvent.DecodeError] (transport-level: bad COBS, bad
         * CRC, truncated/foreign packets) into [decodeErrors], then tries
         * every FRAME packet against [PrincetonProtocol]; a FRAME that
         * decoded fine at the transport level but just isn't Princeton-shaped
         * is silently dropped here (not an error - see [StaticCodeUiState]).
         */
        internal fun decodeSignals(
            events: Flow<TransportEvent>,
            decodeErrors: MutableStateFlow<Int>,
        ): Flow<PrincetonFrame> =
            events
                .onEach { event ->
                    if (event is TransportEvent.DecodeError) decodeErrors.update { it + 1 }
                }
                .filterIsInstance<TransportEvent.PacketReceived>()
                .mapNotNull { it.packet as? FramePacket }
                .mapNotNull { PrincetonProtocol.decodeFrame(it) }

        /**
         * The HT12 half of the same pipeline: tries every FRAME packet against
         * [Ht12Protocol]. Deliberately does **not** touch the transport decode-
         * error counter — [decodeSignals] already counts
         * [TransportEvent.DecodeError] on this same shared event stream, and a
         * FRAME that simply isn't HT12-shaped (e.g. a Princeton or HCS301
         * frame) is not an error, just another protocol's frame.
         */
        internal fun decodeHt12Signals(events: Flow<TransportEvent>): Flow<Ht12Frame> =
            events
                .filterIsInstance<TransportEvent.PacketReceived>()
                .mapNotNull { it.packet as? FramePacket }
                .mapNotNull { Ht12Protocol.decodeFrame(it) }
    }
}
