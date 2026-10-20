package com.eelab.rflab.ui.receive

/**
 * Everything the receive screen renders.
 *
 * @property rows decoded signals, newest first, with repeats collapsed.
 * @property selectedCount number of rows currently checked.
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
) {
    val canAddSelected: Boolean get() = selectedCount > 0
}
