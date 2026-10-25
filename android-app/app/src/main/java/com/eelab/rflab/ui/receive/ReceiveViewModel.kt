package com.eelab.rflab.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.decode.DecodedSignal
import com.eelab.rflab.decode.Hcs301Decoder
import com.eelab.rflab.decode.ScaleEstimator
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.protocol.RadioMode
import com.eelab.rflab.protocol.StatusPacket
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Turns the transport's packet stream into the receive screen's row list.
 *
 * The pipeline is: [TransportEvent.PacketReceived] -> [FramePacket] ->
 * [ScaleEstimator] (one running pool for this ViewModel, i.e. one per
 * connection session, as the brief requires) -> [Hcs301Decoder]. Valid
 * decodes go into a [ReceivedSignalAccumulator], which collapses repeats.
 *
 * Frame length/noise rejections are simply skipped. [TransportEvent.DecodeError]
 * events (bad COBS, bad CRC, truncated/foreign packets) are counted and shown
 * in the header, but never reach the decoder: a stream of corrupt frames
 * cannot crash the screen or stop later valid frames from being decoded.
 * STATUS/ACK packets are ignored here — the Connect screen owns STATUS and
 * the send engine owns ACKs.
 *
 * Decoding 65 cells per frame is cheap, so it runs on the collecting
 * dispatcher rather than introducing another thread hop; the USB reader is
 * already off the main thread.
 *
 * @param signals decoded signals to accumulate. [create] builds this from
 *   transport events; tests feed controlled decoded signals directly.
 * @param sendQueue shared queue that selected rows are copied into.
 * @param clock host arrival time in milliseconds. Injectable so grouping is
 *   testable without wall-clock sleeps.
 * @param accumulator repeat-collapsing state; inject one to control the
 *   grouping window or seed state in a test.
 * @param decodeErrorCount running count of malformed transport frames.
 */
class ReceiveViewModel(
    signals: Flow<DecodedSignal>,
    private val sendQueue: SendQueueRepository = SendQueueRepository.shared,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val accumulator: ReceivedSignalAccumulator = ReceivedSignalAccumulator(),
    private val decodeErrorCount: StateFlow<Int> = MutableStateFlow(0),
    deviceModes: Flow<RadioMode> = emptyFlow(),
    frameArrivals: Flow<Unit> = emptyFlow(),
    connected: Flow<Boolean> = flowOf(true),
    private val modeSetter: suspend (RadioMode) -> Boolean = { true },
    selectedKey: Flow<com.eelab.rflab.crypto.KeeLoqKey?> = emptyFlow(),
    private val onCounterObserved: (serial: Long, counter: Int) -> Unit = { _, _ -> },
) : ViewModel() {

    /**
     * The key currently selected in the keystore, read synchronously when
     * decorating rows and observing counters. Null when none is selected.
     */
    @Volatile
    private var currentKey: com.eelab.rflab.crypto.KeeLoqKey? = null

    private val _uiState = MutableStateFlow(ReceiveUiState())

    /** Rows, selection and queue size for the screen. */
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            signals.collect { signal ->
                accumulator.accept(signal, clock())
                // With a matching key selected, record the decrypted counter
                // so "send next code" can default to last + 1 for this serial.
                currentKey?.let { key ->
                    val info = com.eelab.rflab.crypto.KeeLoqHop.decrypt(signal.hopping.toInt(), key.key)
                    if (info.matchesClearButton(signal.button)) {
                        onCounterObserved(signal.serial, info.counter)
                    }
                }
                publishRows()
            }
        }
        // Re-decorate rows whenever the selected key changes (switching keys
        // updates the decrypted counter shown against existing rows).
        viewModelScope.launch {
            selectedKey.collect { key ->
                currentKey = key
                publishRows()
            }
        }
        viewModelScope.launch {
            sendQueue.items.collect { items ->
                _uiState.update { it.copy(queueSize = items.size) }
            }
        }
        // The displayed mode always comes from the device's STATUS, never from
        // what we asked for; a lost/absent link reads as Idle so nothing looks
        // like it is receiving after a reconnect or a device reboot.
        viewModelScope.launch {
            deviceModes.collect { mode ->
                _uiState.update {
                    it.copy(
                        mode = mode,
                        framesWhileIdle = it.framesWhileIdle && mode == RadioMode.IDLE,
                    )
                }
            }
        }
        // Frames are never hidden based on mode; just remember that they are
        // arriving while the device says IDLE so the UI does not mislead.
        viewModelScope.launch {
            frameArrivals.collect {
                _uiState.update {
                    if (it.mode == RadioMode.IDLE && !it.framesWhileIdle) it.copy(framesWhileIdle = true) else it
                }
            }
        }
        viewModelScope.launch {
            connected.collect { isUp ->
                _uiState.update {
                    if (isUp) it.copy(isConnected = true)
                    else it.copy(isConnected = false, mode = RadioMode.IDLE, modeBusy = false, framesWhileIdle = false)
                }
            }
        }
        viewModelScope.launch {
            decodeErrorCount.collect { count ->
                _uiState.update { it.copy(decodeErrorCount = count) }
            }
        }
    }

    /**
     * Flips the selection on [rowId]. Selecting records the row's position in
     * the ordered selection; selecting more than
     * [ReceivedSignalAccumulator.MAX_SELECTED_ROWS] rows is ignored.
     */
    /** Start/Stop receiving: asks for RX when the device is idle, IDLE otherwise. */
    fun toggleReceiving() {
        val state = _uiState.value
        if (!state.canToggleReceive) return
        val target = if (state.mode == RadioMode.RX) RadioMode.IDLE else RadioMode.RX
        _uiState.update { it.copy(modeBusy = true, modeMessage = null) }
        viewModelScope.launch {
            val ok = try {
                modeSetter(target)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                false
            }
            _uiState.update {
                it.copy(
                    modeBusy = false,
                    modeMessage = if (ok) null else "Device did not confirm the mode change",
                )
            }
        }
    }

    fun toggleSelection(rowId: Long) {
        if (accumulator.toggleSelection(rowId)) publishRows()
    }

    /** Unchecks every row. */
    fun clearSelection() {
        if (accumulator.clearSelection()) publishRows()
    }

    /** Wipes every accumulated row and the current selection. */
    fun clearReceived() {
        accumulator.clear()
        publishRows()
    }

    /**
     * Copies every selected row into the shared send queue in selection
     * order (the first row selected is enqueued first), then clears the
     * selection so the same press cannot be queued twice by accident.
     *
     * @return the number of items added, 0 when nothing was selected.
     */
    fun addSelectedToQueue(): Int {
        val selected = accumulator.selectedRows()
        if (selected.isEmpty()) return 0

        for (row in selected) {
            sendQueue.enqueue(
                hopping = row.hopping,
                serial = row.serial,
                button = row.button,
            )
        }
        accumulator.clearSelection()
        publishRows()
        return selected.size
    }

    /** Decorate a row with the decrypted counter when the selected key matches it. */
    private fun decorate(row: ReceivedSignalRow): ReceivedSignalRow {
        val key = currentKey ?: return row.copy(decryptedCounter = null, keyMatches = false)
        val info = com.eelab.rflab.crypto.KeeLoqHop.decrypt(row.hopping.toInt(), key.key)
        val matches = info.matchesClearButton(row.button)
        return row.copy(
            decryptedCounter = if (matches) info.counter else null,
            keyMatches = matches,
        )
    }

    private fun publishRows() {
        _uiState.update {
            it.copy(
                rows = accumulator.rows().map(::decorate),
                selectedCount = accumulator.selectedCount,
                // Read the queue directly so the count is correct before the
                // queue's collector runs.
                queueSize = sendQueue.items.value.size,
            )
        }
    }

    companion object {
        /** Repeats of one press arriving within this window share a row. */
        const val REPEAT_GROUP_WINDOW_MS = ReceivedSignalAccumulator.DEFAULT_GROUP_WINDOW_MS

        /**
         * Builds the production ViewModel that decodes `RfLabSession.events`.
         *
         * The decoder and its running [ScaleEstimator] are created here, once
         * per ViewModel: the app root creates this ViewModel as soon as the
         * process starts, so frames are decoded from the moment the first
         * connection attaches, not only after the Receive tab is first opened.
         */
        fun create(
            transportEvents: Flow<TransportEvent>,
            sendQueue: SendQueueRepository = SendQueueRepository.shared,
            clock: () -> Long = { System.currentTimeMillis() },
            connected: Flow<Boolean> = flowOf(true),
            modeSetter: suspend (RadioMode) -> Boolean = { true },
            selectedKey: Flow<com.eelab.rflab.crypto.KeeLoqKey?> = emptyFlow(),
            onCounterObserved: (serial: Long, counter: Int) -> Unit = { _, _ -> },
        ): ReceiveViewModel {
            val decodeErrors = MutableStateFlow(0)
            return ReceiveViewModel(
                deviceModes = deviceModes(transportEvents),
                frameArrivals = transportEvents
                    .filter { it is TransportEvent.PacketReceived && it.packet is FramePacket }
                    .map { },
                connected = connected,
                modeSetter = modeSetter,
                selectedKey = selectedKey,
                onCounterObserved = onCounterObserved,
                signals = decodeSignals(transportEvents, decodeErrors),
                sendQueue = sendQueue,
                clock = clock,
                decodeErrorCount = decodeErrors,
            )
        }

        /** ViewModel factory for [create]; used by the app composition root. */
        fun factory(
            transportEvents: Flow<TransportEvent>,
            sendQueue: SendQueueRepository = SendQueueRepository.shared,
            connected: Flow<Boolean> = flowOf(true),
            modeSetter: suspend (RadioMode) -> Boolean = { true },
            selectedKey: Flow<com.eelab.rflab.crypto.KeeLoqKey?> = emptyFlow(),
            onCounterObserved: (serial: Long, counter: Int) -> Unit = { _, _ -> },
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                create(
                    transportEvents,
                    sendQueue,
                    connected = connected,
                    modeSetter = modeSetter,
                    selectedKey = selectedKey,
                    onCounterObserved = onCounterObserved,
                )
            }
        }

        /** Radio mode from each STATUS; a lost connection reads as [RadioMode.IDLE]. */
        internal fun deviceModes(events: Flow<TransportEvent>): Flow<RadioMode> =
            events.mapNotNull { event ->
                when {
                    event is TransportEvent.ConnectionLost -> RadioMode.IDLE
                    event is TransportEvent.PacketReceived && event.packet is StatusPacket ->
                        (event.packet as StatusPacket).mode
                    else -> null
                }
            }

        /**
         * Feeds every frame's pulses through the running scale estimator
         * (even malformed ones, matching `decode_hcs301.py`) and decodes at
         * the current scale. Rejected frames are dropped; transport-level
         * decode errors are counted and the estimator is reset when the link
         * is lost (the next connection may be a different transmitter).
         */
        internal fun decodeSignals(
            events: Flow<TransportEvent>,
            decodeErrors: MutableStateFlow<Int>,
        ): Flow<DecodedSignal> {
            val estimator = ScaleEstimator()
            return events
                .onEach { event ->
                    when (event) {
                        is TransportEvent.DecodeError ->
                            decodeErrors.update { it + 1 }

                        is TransportEvent.ConnectionLost -> estimator.reset()

                        is TransportEvent.PacketReceived -> Unit
                    }
                }
                .filterIsInstance<TransportEvent.PacketReceived>()
                .mapNotNull { (it.packet as? FramePacket) }
                .mapNotNull { frame ->
                    estimator.addFrame(frame.pulses)
                    Hcs301Decoder.decodeFrame(frame, estimator.scale)
                }
        }
    }
}
