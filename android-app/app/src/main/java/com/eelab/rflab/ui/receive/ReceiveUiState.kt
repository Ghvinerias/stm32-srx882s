package com.eelab.rflab.ui.receive

import com.eelab.rflab.protocol.RadioMode

/**
 * Everything the receive screen renders.
 *
 * @property rows decoded signals, newest first, with repeats collapsed.
 * @property selectedCount number of rows currently selected for the send
 *   queue; selection is capped at
 *   [ReceivedSignalAccumulator.MAX_SELECTED_ROWS].
 * @property queueSize number of items already in the shared send queue;
 *   shown as feedback after "add to send queue".
 * @property decodeErrorCount malformed/foreign frames the transport rejected
 *   since this screen started. They are ignored for decoding — a corrupt
 *   frame must never wedge the list — but surfacing the count makes a noisy
 *   or desynchronised link visible instead of silent.
 */
data class ReceiveUiState(
    val rows: List<ReceivedSignalRow> = emptyList(),
    val selectedCount: Int = 0,
    val queueSize: Int = 0,
    val decodeErrorCount: Int = 0,
    /** The device's radio mode as last reported in STATUS (IDLE until proven otherwise). */
    val mode: RadioMode = RadioMode.IDLE,
    val isConnected: Boolean = false,
    /**
     * FRAMEs are arriving while the device reports IDLE (e.g. the STM32
     * reference board, which never reports a mode). Frames are shown anyway;
     * this only keeps the control from claiming the radio is off.
     */
    val framesWhileIdle: Boolean = false,
    /** A MODE command is awaiting its ACK. */
    val modeBusy: Boolean = false,
    /** Last mode-change failure, shown under the control. */
    val modeMessage: String? = null,
) {
    /** Start/Stop receiving is only offered when connected, idle of requests and not mid-burst. */
    val canToggleReceive: Boolean get() = isConnected && !modeBusy && mode != RadioMode.TX

    val canAddSelected: Boolean get() = selectedCount > 0

    val canClearReceived: Boolean get() = rows.isNotEmpty()
}
