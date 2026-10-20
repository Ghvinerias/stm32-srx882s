package com.eelab.rflab.ui.send

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.protocol.ScenarioCommand
import com.eelab.rflab.protocol.TxCommand
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.queue.SendQueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SendViewModel"

/**
 * State and actions for the send queue screen.
 *
 * Session 7 owns queue management (add, remove, reorder, frame-count editing),
 * manual hopping/serial/button/frames entry and the six preset scenario
 * buttons. The two network-facing actions are injectable stubs here:
 *
 * - [scenarioSender] receives one validated [ScenarioCommand] per preset press.
 * - [batchSender] receives a snapshot of the queue when "Send All" is pressed.
 *
 * Both default to a logging no-op. **Session 8 replaces those defaults with
 * the real sequential send-then-await-ACK engine** that encodes through
 * `PacketCodec` and writes to `RfLabConnection`.
 *
 * @param sendQueue shared queue also filled by the receive screen.
 * @param scenarioSender stub seam for preset scenarios; session 8 supplies it.
 * @param batchSender stub seam for "Send All"; session 8 supplies it.
 */
class SendViewModel(
    private val sendQueue: SendQueueRepository = SendQueueRepository.shared,
    private val scenarioSender: (ScenarioCommand) -> Unit = ::stubScenarioSender,
    private val batchSender: (List<SendQueueItem>) -> Unit = ::stubBatchSender,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SendUiState(items = sendQueue.items.value))

    /** Queue snapshot, manual-entry form and feedback for the screen. */
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    init {
        // Keep the UI in sync with items added from the receive screen too.
        viewModelScope.launch {
            sendQueue.items.collect { items ->
                _uiState.update { it.copy(items = items) }
            }
        }
    }

    // --- Manual entry -------------------------------------------------------

    fun onHoppingChange(value: String) {
        updateEntry { it.copy(hoppingHex = value, hoppingError = null) }
    }

    fun onSerialChange(value: String) {
        updateEntry { it.copy(serialHex = value, serialError = null) }
    }

    fun onButtonChange(value: String) {
        updateEntry { it.copy(buttonHex = value, buttonError = null) }
    }

    fun onFramesChange(value: String) {
        updateEntry { it.copy(framesText = value, framesError = null) }
    }

    /**
     * Validates the four manual-entry fields and appends the command.
     *
     * Parsing only maps errors to fields; [TxCommand] remains the authority on
     * protocol ranges and is constructed before anything enters the queue.
     * The serial is additionally capped at 28 bits, matching the firmware's
     * mask (the protocol object accepts a 32-bit wire field and truncates).
     *
     * @return true when the item was queued; false when a field is invalid.
     */
    fun addManualEntry(): Boolean {
        val entry = _uiState.value.manualEntry
        val hopping = parseHex(entry.hoppingHex, "Hopping", TxCommand.UINT32_MAX, "32 bits (8 hex digits)")
        val serial = parseHex(entry.serialHex, "Serial", TxCommand.SERIAL_MASK, "28 bits (7 hex digits)")
        val button = parseButton(entry.buttonHex)
        val frames = parseFrames(entry.framesText)

        val validated = entry.copy(
            hoppingError = hopping.error,
            serialError = serial.error,
            buttonError = button.error,
            framesError = frames.error,
        )

        val hoppingValue = hopping.value
        val serialValue = serial.value
        val buttonValue = button.value
        val framesValue = frames.value
        if (hoppingValue == null || serialValue == null || buttonValue == null || framesValue == null) {
            _uiState.update {
                it.copy(manualEntry = validated, message = "Fix the highlighted fields")
            }
            return false
        }

        val command = try {
            TxCommand(
                hopping = hoppingValue,
                serial = serialValue,
                button = buttonValue,
                frameCount = framesValue,
            )
        } catch (error: IllegalArgumentException) {
            // Defensive: the per-field checks above should have caught this.
            _uiState.update {
                it.copy(
                    manualEntry = validated,
                    message = error.message ?: "Invalid command",
                )
            }
            return false
        }

        sendQueue.enqueue(command)
        _uiState.update {
            it.copy(
                items = sendQueue.items.value,
                manualEntry = ManualEntryState(),
                message = "Added to queue (${sendQueue.items.value.size} queued)",
            )
        }
        return true
    }

    // --- Queue management ---------------------------------------------------

    fun removeItem(itemId: Long) {
        sendQueue.remove(itemId)
        publishQueue()
    }

    fun moveItemUp(itemId: Long) {
        sendQueue.moveUp(itemId)
        publishQueue()
    }

    fun moveItemDown(itemId: Long) {
        sendQueue.moveDown(itemId)
        publishQueue()
    }

    /** Applies the protocol's `1..10` clamp in the repository. */
    fun setFrameCount(itemId: Long, frameCount: Int) {
        sendQueue.setFrameCount(itemId, frameCount)
        publishQueue()
    }

    // --- Stubbed send actions (session 8 seams) -----------------------------

    /**
     * Builds the [ScenarioCommand] for [preset] and hands it to the injected
     * sender. Session 7's default sender only logs the request.
     */
    fun sendPreset(preset: PresetScenario) {
        val command = ScenarioCommand(preset.commandChar)
        // TODO(session 8): default sender only logs; wire this to the USB
        // SCENARIO_COMMAND path (COBS + 0x00 delimiter).
        scenarioSender(command)
        _uiState.update {
            it.copy(
                message = "Scenario '${preset.commandChar}' requested " +
                    "(session 8 wires it to USB)",
            )
        }
    }

    /**
     * Hands the current queue to the injected batch sender.
     *
     * TODO(session 8): replace the default [batchSender] with the strict
     * sequential engine: send one [SendQueueItem], await its ACK or timeout,
     * update its status, then send the next. Never pipeline.
     */
    fun sendAll() {
        val snapshot = sendQueue.items.value
        if (snapshot.isEmpty()) return

        // TODO(session 8): replace this stub call with the real sequential
        // send-then-await-ACK engine. Do not pipeline items.
        batchSender(snapshot)
        _uiState.update {
            it.copy(
                message = "Send All is stubbed for session 8 " +
                    "(${snapshot.size} item(s) waiting)",
            )
        }
    }

    private inline fun updateEntry(transform: (ManualEntryState) -> ManualEntryState) {
        _uiState.update {
            it.copy(manualEntry = transform(it.manualEntry), message = null)
        }
    }

    private fun publishQueue() {
        _uiState.update { it.copy(items = sendQueue.items.value) }
    }

    companion object {
        /**
         * ViewModel factory wired to the shared queue and, for now, the
         * logging stubs. Session 8 passes real senders here.
         */
        fun factory(
            sendQueue: SendQueueRepository = SendQueueRepository.shared,
            scenarioSender: (ScenarioCommand) -> Unit = ::stubScenarioSender,
            batchSender: (List<SendQueueItem>) -> Unit = ::stubBatchSender,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SendViewModel(sendQueue, scenarioSender, batchSender) }
        }
    }
}

/** One parsed manual-entry field: a value, an error message, or neither. */
private data class Parsed<T>(val value: T?, val error: String?)

private fun parseHex(
    text: String,
    label: String,
    maxValue: Long,
    maxDescription: String,
): Parsed<Long> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return Parsed(null, "$label is required")
    if (!trimmed.all { it.isHexDigit() }) {
        return Parsed(null, "$label must be hex (0-9, A-F)")
    }

    val value = trimmed.toLongOrNull(16)
        ?: return Parsed(null, "$label is too large (max $maxDescription)")
    if (value > maxValue) {
        return Parsed(null, "$label must be at most $maxDescription")
    }
    return Parsed(value, null)
}

private fun parseButton(text: String): Parsed<Int> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return Parsed(null, "Button is required")
    if (trimmed.length != 1 || !trimmed[0].isHexDigit()) {
        return Parsed(null, "Button must be one hex digit (0-F)")
    }

    val value = trimmed[0].digitToInt(16)
    if (value > TxCommand.BUTTON_MAX) {
        return Parsed(null, "Button must be 0-F")
    }
    return Parsed(value, null)
}

private fun parseFrames(text: String): Parsed<Int> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return Parsed(null, "Frames is required")

    val value = trimmed.toIntOrNull()
        ?: return Parsed(null, "Frames must be a whole number")
    if (value !in TxCommand.MIN_FRAME_COUNT..TxCommand.MAX_FRAME_COUNT) {
        return Parsed(
            null,
            "Frames must be ${TxCommand.MIN_FRAME_COUNT}-${TxCommand.MAX_FRAME_COUNT}",
        )
    }
    return Parsed(value, null)
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Session-7 default: log the preset request and leave the wire untouched.
 *
 * TODO(session 8): replace with
 * `RfLabConnection.send(PacketCodec.encodeScenarioCommand(command))` (COBS +
 * delimiter included at the transport layer).
 */
private fun stubScenarioSender(command: ScenarioCommand) {
    Log.i(
        TAG,
        "TODO(session 8): preset scenario '${command.commandChar}' requested; " +
            "wire this to the USB send path",
    )
}

/**
 * Session-7 default: "Send All" is deliberately inert.
 *
 * TODO(session 8): replace with the strict sequential send-then-await-ACK
 * engine. Send one item at a time, wait for ACK or ~2 s timeout, update the
 * item's [com.eelab.rflab.queue.SendItemStatus], then continue.
 */
private fun stubBatchSender(items: List<SendQueueItem>) {
    Log.i(
        TAG,
        "TODO(session 8): Send All pressed with ${items.size} queued item(s); " +
            "batch engine not wired yet",
    )
}
