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
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiveUiState())

    /** Rows, selection and queue size for the screen. */
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            signals.collect { signal ->
                accumulator.accept(signal, clock())
                publishRows()
            }
        }
        viewModelScope.launch {
            sendQueue.items.collect { items ->
                _uiState.update { it.copy(queueSize = items.size) }
            }
        }
        viewModelScope.launch {
            decodeErrorCount.collect { count ->
                _uiState.update { it.copy(decodeErrorCount = count) }
            }
        }
    }

    /** Flips the checkbox on [rowId]. */
    fun toggleSelection(rowId: Long) {
        if (accumulator.toggleSelection(rowId)) publishRows()
    }

    /** Unchecks every row. */
    fun clearSelection() {
        if (accumulator.clearSelection()) publishRows()
    }

    /**
     * Copies every selected row into the shared send queue, then clears the
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

    private fun publishRows() {
        _uiState.update {
            it.copy(
                rows = accumulator.rows(),
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
        ): ReceiveViewModel {
            val decodeErrors = MutableStateFlow(0)
            return ReceiveViewModel(
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
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { create(transportEvents, sendQueue) }
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
