package com.eelab.rflab.ui.send

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.protocol.TxCommand
import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.queue.SendQueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SendViewModel"

/**
 * State and actions for the send queue screen.
 *
 * Owns queue management (add, remove, reorder, frame-count editing, clearing),
 * manual hopping/serial/button/frames entry, and drives the real sequential
 * send engine through one injected sender:
 *
 * - [batchSender] drains the queue strictly one item at a time, reporting
 *   per-item status through the callback (which updates the shared queue), and
 *   waits for each item's ACK or timeout before writing the next.
 *
 * The sender maps a dead USB link to [SendItemStatus.FAILED]; a batch that
 * stops because of one leaves the unattempted items [SendItemStatus.PENDING]
 * and the summary below says how many were not sent.
 *
 * The production wiring lives in [com.eelab.rflab.send.SendGraph] and
 * [com.eelab.rflab.ui.RfLabApp]. Defaults stay inert so the screen can be
 * previewed/tested without a USB link.
 *
 * @param sendQueue shared queue also filled by the receive screen.
 * @param batchSender sequential batch sender; supplied by the app root.
 * @param connectionStatus live USB-session presence. Sending is refused while
 *   false; queue edits remain available. Production wires this from
 *   [com.eelab.rflab.usb.RfLabSession.connection]; the default keeps isolated
 *   tests/previews connected.
 */
class SendViewModel(
    private val sendQueue: SendQueueRepository = SendQueueRepository.shared,
    private val batchSender: suspend (
        List<SendQueueItem>,
        (Long, SendItemStatus) -> Unit,
    ) -> Unit = ::stubBatchSender,
    connectionStatus: Flow<Boolean> = flowOf(true),
) : ViewModel() {

    /** Guards against two overlapping "Send All" coroutines. */
    private var sendBatchJob: Job? = null

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
        viewModelScope.launch {
            connectionStatus.collect { connected ->
                _uiState.update { it.copy(isConnected = connected) }
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

    /**
     * Drops every queued item.
     *
     * Refused while a batch is actively sending: the engine walks a snapshot,
     * so clearing mid-batch would hide items that are still being transmitted
     * and leave the final summary counting against a queue that no longer
     * holds them. Wait for the batch to finish instead.
     */
    fun clearQueue() {
        if (sendBatchJob?.isActive == true) {
            _uiState.update {
                it.copy(message = "Can't clear the queue while a batch is sending")
            }
            return
        }
        sendQueue.clear()
        publishQueue()
    }

    // --- Send actions -------------------------------------------------------

    /**
     * Sends the current queue strictly one item at a time.
     *
     * The injected batch sender performs the send-await-ACK loop and reports
     * each item's status. Status updates go into the shared repository, so the
     * queue collector in [init] repaints the badges as the batch progresses.
     * A second press while a batch is active is ignored.
     *
     * If the batch throws (which the engine avoids, but this is the UI's
     * backstop) any item still marked [SendItemStatus.SENDING] becomes
     * [SendItemStatus.FAILED] and the summary still runs, so a badge can never
     * be left spinning.
     */
    fun sendAll() {
        val snapshot = sendQueue.items.value
        if (snapshot.isEmpty()) return
        if (!_uiState.value.isConnected) {
            _uiState.update { it.copy(message = NOT_CONNECTED_MESSAGE) }
            return
        }
        if (sendBatchJob?.isActive == true) {
            _uiState.update { it.copy(message = "Batch already running") }
            return
        }

        sendBatchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(message = "Sending ${snapshot.size} item(s) sequentially…")
            }

            try {
                batchSender(snapshot) { itemId, status ->
                    // The callback is also how SENDING reaches the UI before
                    // each write, so the badges track the strict one-at-a-time
                    // order.
                    sendQueue.setStatus(itemId, status)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Batch send failed", error)
                // The engine normally reports a terminal status for the item
                // it was on; this covers a sender that threw instead.
                sendQueue.items.value
                    .filter { it.status == SendItemStatus.SENDING }
                    .forEach { sendQueue.setStatus(it.id, SendItemStatus.FAILED) }
            } finally {
                publishQueue()
            }

            val sentIds = snapshot.mapTo(HashSet()) { it.id }
            val finalItems = sendQueue.items.value.filter { it.id in sentIds }
            val ok = finalItems.count { it.status == SendItemStatus.ACKED_OK }
            val failed = finalItems.count { it.status == SendItemStatus.ACKED_FAILED }
            val timedOut = finalItems.count { it.status == SendItemStatus.TIMEOUT }
            val linkLost = finalItems.count { it.status == SendItemStatus.FAILED }
            val notSent = finalItems.count { it.status == SendItemStatus.PENDING }
            _uiState.update {
                it.copy(
                    message = if (linkLost > 0 || notSent > 0) {
                        "Batch stopped: $ok ACKed OK, $failed failed, $timedOut timed out, " +
                            "$linkLost link error(s), $notSent not sent"
                    } else {
                        "Batch done: $ok ACKed OK, $failed failed, $timedOut timed out"
                    },
                )
            }
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
        /** Shown when a send action is attempted with no live USB session. */
        const val NOT_CONNECTED_MESSAGE: String =
            "Not connected — connect the board on the Connect tab"

        /**
         * ViewModel factory wired to the shared queue and the sequential send
         * engine (see [com.eelab.rflab.send.SendGraph]). Tests inject their
         * own senders and connection status.
         */
        fun factory(
            sendQueue: SendQueueRepository = SendQueueRepository.shared,
            batchSender: suspend (
                List<SendQueueItem>,
                (Long, SendItemStatus) -> Unit,
            ) -> Unit = ::stubBatchSender,
            connectionStatus: Flow<Boolean> = flowOf(true),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SendViewModel(sendQueue, batchSender, connectionStatus)
            }
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
 * Fallback batch sender used when no engine is wired. Reports each item as
 * timed out without touching the wire, so the UI has a definite end state.
 */
private suspend fun stubBatchSender(
    items: List<SendQueueItem>,
    onStatusChange: (Long, SendItemStatus) -> Unit,
) {
    Log.i(TAG, "No send engine wired for a ${items.size}-item batch")
    for (item in items) {
        onStatusChange(item.id, SendItemStatus.TIMEOUT)
    }
}
