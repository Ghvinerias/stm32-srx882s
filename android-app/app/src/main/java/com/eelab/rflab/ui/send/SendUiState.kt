package com.eelab.rflab.ui.send

import com.eelab.rflab.protocol.ScenarioCommand
import com.eelab.rflab.queue.SendQueueItem

/**
 * Everything the send screen renders.
 *
 * @property items shared-queue snapshot, top of the send order first.
 * @property manualEntry four-field manual-entry form and its validation errors.
 * @property message short feedback line for the last add/scenario/send action.
 * @property isConnected whether the shared USB session currently has a live
 *   connection. Queue editing stays available offline; sending does not.
 */
data class SendUiState(
    val items: List<SendQueueItem> = emptyList(),
    val manualEntry: ManualEntryState = ManualEntryState(),
    val message: String? = null,
    val isConnected: Boolean = true,
) {
    val canSendAll: Boolean get() = items.isNotEmpty() && isConnected
}

/**
 * Manual hopping/serial/button/frames entry.
 *
 * The text is kept verbatim (not filtered) so invalid input can be reported
 * instead of silently dropped. Errors are populated by
 * [SendViewModel.addManualEntry] and cleared as soon as the field changes.
 *
 * @property framesText decimal frame count, `1..10`.
 */
data class ManualEntryState(
    val hoppingHex: String = "",
    val serialHex: String = "",
    val buttonHex: String = "",
    val framesText: String = SendQueueItem.DEFAULT_FRAME_COUNT.toString(),
    val hoppingError: String? = null,
    val serialError: String? = null,
    val buttonError: String? = null,
    val framesError: String? = null,
) {
    val hasErrors: Boolean
        get() = hoppingError != null ||
            serialError != null ||
            buttonError != null ||
            framesError != null
}

/**
 * The six preset scenario commands exposed by the send screen.
 *
 * Order and characters match the brief: `'r'`, `'s'`, `'z'`, `'p'`, `'x'`,
 * `'d'`. The UI renders one button per entry; the ViewModel supplies the
 * lambda that actually writes the encoded command to the USB link.
 */
enum class PresetScenario(
    val commandChar: Char,
    val label: String,
) {
    STALE_REPLAY(ScenarioCommand.STALE_REPLAY, "Stale replay"),
    SYNTHETIC_STALE(ScenarioCommand.SYNTHETIC_STALE, "Synthetic stale"),
    ZERO_HOP(ScenarioCommand.ZERO_HOP, "Zero hop"),
    PLAINTEXT_COUNTER(ScenarioCommand.PLAINTEXT_COUNTER, "Plaintext counter"),
    RANDOM_HOP(ScenarioCommand.RANDOM_HOP, "Random hop"),
    DIFFERENT_SERIAL(ScenarioCommand.DIFFERENT_SERIAL, "Different serial"),
}
