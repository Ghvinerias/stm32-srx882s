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
 * Frame length/noise rejections, STATUS/ACK packets and transport decode
 * errors are ignored here: the receive screen only lists successfully
 * decoded HCS301 frames. (Session 10 owns surfacing link errors.)
 *
 * Decoding 65 cells per frame is cheap, so it runs on the collecting
 * dispatcher rather than introducing another thread hop; the USB reader is
 * already off the main thread.
 *
 * @param signals decoded signals to accumulate. Production code uses the
 *   constructor below that takes transport events; this overload is the
 *   test seam for feeding controlled decoded signals.
 * @param sendQueue shared queue that selected rows are copied into.
 * @param clock host arrival time in milliseconds. Injectable so grouping is
 *   testable without wall-clock sleeps.
 * @param accumulator repeat-collapsing state; inject one to control the
 *   grouping window or seed state in a test.
 */
class ReceiveViewModel(
    signals: Flow<DecodedSignal>,
    private val sendQueue: SendQueueRepository = SendQueueRepository.shared,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val accumulator: ReceivedSignalAccumulator = ReceivedSignalAccumulator(),
) : ViewModel() {

    /**
     * Production constructor: decode [transportEvents] into signals first.
     *
     * Subscribe before the connection opens if every frame matters
     * ([com.eelab.rflab.usb.RfLabConnection.events] has no replay).
     */
    constructor(
        transportEvents: Flow<TransportEvent>,
        sendQueue: SendQueueRepository = SendQueueRepository.shared,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(decodeSignals(transportEvents), sendQueue, clock)

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
         * ViewModel factory wired to the placeholder connection holder.
         * Session 9 replaces [com.eelab.rflab.usb.RfLabSession]'s placeholder
         * with the real connect lifecycle; this call site does not change.
         */
        fun factory(
            transportEvents: Flow<TransportEvent>,
            sendQueue: SendQueueRepository = SendQueueRepository.shared,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReceiveViewModel(transportEvents, sendQueue) }
        }

        /**
         * Feeds every frame's pulses through the running scale estimator
         * (even malformed ones, matching `decode_hcs301.py`) and decodes at
         * the current scale. Rejected frames are dropped.
         */
        private fun decodeSignals(events: Flow<TransportEvent>): Flow<DecodedSignal> {
            val estimator = ScaleEstimator()
            return events
                .filterIsInstance<TransportEvent.PacketReceived>()
                .mapNotNull { (it.packet as? FramePacket) }
                .mapNotNull { frame ->
                    estimator.addFrame(frame.pulses)
                    Hcs301Decoder.decodeFrame(frame, estimator.scale)
                }
        }
    }
}
