package com.eelab.rflab.ui.send

import com.eelab.rflab.favorites.FavoriteQueue
import com.eelab.rflab.protocol.ScenarioCommand
import com.eelab.rflab.queue.SendQueueItem

/**
 * Everything the send screen renders.
 *
 * @property items shared-queue snapshot, top of the send order first.
 * @property manualEntry four-field manual-entry form and its validation errors.
 * @property message short feedback line for the last add/send/clear/favorite
 *   action.
 * @property isSending a batch is in flight; the screen offers Stop instead of Send All.
 * @property isConnected whether the shared USB session currently has a live
 *   connection. Queue editing stays available offline; sending does not.
 * @property favorites saved queue snapshots, newest first.
 * @property favoriteName text currently typed into the save-as-favorite field.
 */
data class SendUiState(
    val items: List<SendQueueItem> = emptyList(),
    val manualEntry: ManualEntryState = ManualEntryState(),
    val message: String? = null,
    val isConnected: Boolean = true,
    val isSending: Boolean = false,
    val favorites: List<FavoriteQueue> = emptyList(),
    val favoriteName: String = "",
    val nextCode: NextCodeState = NextCodeState(),
) {
    val canSendAll: Boolean get() = items.isNotEmpty() && isConnected && !isSending

    val canClearQueue: Boolean get() = items.isNotEmpty()

    /** Saving needs something to snapshot and a non-blank name. */
    val canSaveFavorite: Boolean get() = items.isNotEmpty() && favoriteName.isNotBlank()
}

/**
 * Manual hopping/serial/button/frames entry.
 *
 * The text is kept verbatim (not filtered) so invalid input can be reported
 * instead of silently dropped. Errors are populated by
 * [SendViewModel.addManualEntry] and cleared as soon as the field changes.
 *
 * @property framesText decimal frame count, `1..255`.
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
 * "Next code" form: transmit the next valid rolling code to a receiver the
 * user holds the key for.
 *
 * The user enters the target serial, button and frame count; the counter is
 * filled automatically from the per-serial tracker (last seen + 1) and shown
 * in [autoCounterHint], or the user types an override. The app computes the
 * encrypted hop with the selected key — the user never handles ciphertext.
 *
 * @property keyName the selected key's name, or null when no key is selected
 *   (the action is disabled and the UI points to the Keys tab).
 * @property autoCounterHint the auto counter that will be used when the
 *   override is blank, or null when nothing has been seen for this serial yet.
 */
data class NextCodeState(
    val serialHex: String = "",
    val buttonHex: String = "",
    val framesText: String = SendQueueItem.DEFAULT_FRAME_COUNT.toString(),
    val counterOverrideHex: String = "",
    val serialError: String? = null,
    val buttonError: String? = null,
    val framesError: String? = null,
    val counterError: String? = null,
    val keyName: String? = null,
    val autoCounterHint: Int? = null,
) {
    val canQueue: Boolean get() = keyName != null && serialHex.isNotBlank() && buttonHex.isNotBlank()
}

/**
 * The six preset scenario commands defined by the brief.
 *
 * Order and characters match the brief: `'r'`, `'s'`, `'z'`, `'p'`, `'x'`,
 * `'d'`. Kept as the canonical preset list for the protocol/engine even though
 * the send screen no longer renders buttons for them; one-shot sends remain
 * available through [com.eelab.rflab.send.BatchSendEngine.sendScenario].
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
