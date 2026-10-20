package com.eelab.rflab.ui.receive

/**
 * Everything the receive screen renders.
 *
 * @property rows decoded signals, newest first, with repeats collapsed.
 * @property selectedCount number of rows currently checked.
 * @property queueSize number of items already in the shared send queue;
 *   shown as feedback after "add to send queue".
 */
data class ReceiveUiState(
    val rows: List<ReceivedSignalRow> = emptyList(),
    val selectedCount: Int = 0,
    val queueSize: Int = 0,
) {
    val canAddSelected: Boolean get() = selectedCount > 0
}
