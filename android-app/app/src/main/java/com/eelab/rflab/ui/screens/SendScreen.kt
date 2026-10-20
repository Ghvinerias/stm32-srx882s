package com.eelab.rflab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.ui.send.ManualEntryState
import com.eelab.rflab.ui.send.PresetScenario
import com.eelab.rflab.ui.send.SendUiState
import com.eelab.rflab.ui.send.SendViewModel
import java.util.Locale

/**
 * Send queue and manual-entry screen.
 *
 * Session 7 renders the shared queue, lets the user add/remove/reorder items
 * and edit each item's frame count, adds the four-field manual-entry card and
 * the six preset scenario buttons.
 *
 * The two send actions are deliberately stubbed:
 *
 * ```
 * TODO(session 8): "Send All" currently calls SendViewModel.sendAll(), whose
 * default batchSender only logs. Replace that default with the strict
 * sequential send-then-await-ACK engine.
 * TODO(session 8): preset buttons call SendViewModel.sendPreset(), whose
 * default scenarioSender only logs. Wire it to the USB SCENARIO_COMMAND path.
 * ```
 */
@Composable
fun SendScreen(
    modifier: Modifier = Modifier,
    viewModel: SendViewModel = viewModel(factory = SendViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SendScreen(
        state = state,
        onHoppingChange = viewModel::onHoppingChange,
        onSerialChange = viewModel::onSerialChange,
        onButtonChange = viewModel::onButtonChange,
        onFramesChange = viewModel::onFramesChange,
        onAddManualEntry = { viewModel.addManualEntry() },
        onPresetScenario = viewModel::sendPreset,
        onRemoveItem = viewModel::removeItem,
        onMoveItemUp = viewModel::moveItemUp,
        onMoveItemDown = viewModel::moveItemDown,
        onFrameCountChange = viewModel::setFrameCount,
        onSendAll = viewModel::sendAll,
        modifier = modifier,
    )
}

/**
 * Stateless body, kept separate so the queue and form can be previewed or
 * exercised without a ViewModel.
 */
@Composable
internal fun SendScreen(
    state: SendUiState,
    onHoppingChange: (String) -> Unit,
    onSerialChange: (String) -> Unit,
    onButtonChange: (String) -> Unit,
    onFramesChange: (String) -> Unit,
    onAddManualEntry: () -> Unit,
    onPresetScenario: (PresetScenario) -> Unit,
    onRemoveItem: (Long) -> Unit,
    onMoveItemUp: (Long) -> Unit,
    onMoveItemDown: (Long) -> Unit,
    onFrameCountChange: (Long, Int) -> Unit,
    onSendAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SendHeader(state)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "manual-entry") {
                ManualEntryCard(
                    entry = state.manualEntry,
                    onHoppingChange = onHoppingChange,
                    onSerialChange = onSerialChange,
                    onButtonChange = onButtonChange,
                    onFramesChange = onFramesChange,
                    onAddManualEntry = onAddManualEntry,
                )
            }

            item(key = "presets") {
                PresetScenarioCard(onPresetScenario = onPresetScenario)
            }

            item(key = "queue-header") {
                Text(
                    text = "Queue",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.items.isEmpty()) {
                item(key = "empty-queue") {
                    EmptyQueueState()
                }
            } else {
                itemsIndexed(
                    items = state.items,
                    key = { _, item -> item.id },
                ) { index, item ->
                    QueueItemCard(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.items.lastIndex,
                        onRemove = { onRemoveItem(item.id) },
                        onMoveUp = { onMoveItemUp(item.id) },
                        onMoveDown = { onMoveItemDown(item.id) },
                        onFrameCountChange = { count -> onFrameCountChange(item.id, count) },
                    )
                }
            }
        }

        SendAllBar(
            itemCount = state.items.size,
            enabled = state.canSendAll,
            onSendAll = onSendAll,
        )
    }
}

@Composable
private fun SendHeader(state: SendUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Send queue",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when (state.items.size) {
                    0 -> "Empty"
                    1 -> "1 item"
                    else -> "${state.items.size} items"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.message?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManualEntryCard(
    entry: ManualEntryState,
    onHoppingChange: (String) -> Unit,
    onSerialChange: (String) -> Unit,
    onButtonChange: (String) -> Unit,
    onFramesChange: (String) -> Unit,
    onAddManualEntry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Manual entry",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Hex for hopping/serial/button; frames 1-10.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HexField(
                value = entry.hoppingHex,
                onValueChange = onHoppingChange,
                label = "Hopping (max 8 hex digits)",
                error = entry.hoppingError,
            )
            HexField(
                value = entry.serialHex,
                onValueChange = onSerialChange,
                label = "Serial (max 7 hex digits)",
                error = entry.serialError,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexField(
                    value = entry.buttonHex,
                    onValueChange = onButtonChange,
                    label = "Button (0-F)",
                    error = entry.buttonError,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = entry.framesText,
                    onValueChange = onFramesChange,
                    label = { Text("Frames (1-10)") },
                    singleLine = true,
                    isError = entry.framesError != null,
                    supportingText = errorSupportingText(entry.framesError),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Button(
                onClick = onAddManualEntry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Add to queue")
            }
        }
    }
}

@Composable
private fun HexField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = errorSupportingText(error),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.Characters,
        ),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PresetScenarioCard(onPresetScenario: (PresetScenario) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Preset scenarios",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Sends a SCENARIO_COMMAND directly (session 8 stub).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PresetScenario.entries.chunked(3).forEach { rowPresets ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowPresets.forEach { preset ->
                        OutlinedButton(
                            onClick = { onPresetScenario(preset) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "'${preset.commandChar}'",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    repeat(3 - rowPresets.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueState() {
    Text(
        text = "The queue is empty. Add captured signals on the Receive screen " +
            "or enter a command above.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun QueueItemCard(
    item: SendQueueItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onFrameCountChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hopping ${SendFormat.hopping(item.hopping)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "Serial ${SendFormat.serial(item.serial)}  " +
                            "Button ${SendFormat.button(item.button)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                StatusBadge(item.status)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Frames",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { onFrameCountChange(item.frameCount - 1) },
                    enabled = item.frameCount > SendQueueItem.MIN_FRAME_COUNT,
                ) {
                    Text(
                        text = "−",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    text = item.frameCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(24.dp),
                )
                IconButton(
                    onClick = { onFrameCountChange(item.frameCount + 1) },
                    enabled = item.frameCount < SendQueueItem.MAX_FRAME_COUNT,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increase frames",
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move up",
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move down",
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove from queue",
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: SendItemStatus) {
    val label = when (status) {
        SendItemStatus.PENDING -> "Pending"
        SendItemStatus.SENDING -> "Sending"
        SendItemStatus.ACKED_OK -> "Acked OK"
        SendItemStatus.ACKED_FAILED -> "Acked failed"
        SendItemStatus.TIMEOUT -> "Timeout"
    }
    val (container, content) = when (status) {
        SendItemStatus.PENDING ->
            MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant

        SendItemStatus.SENDING ->
            MaterialTheme.colorScheme.primaryContainer to
                MaterialTheme.colorScheme.onPrimaryContainer

        SendItemStatus.ACKED_OK ->
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer

        SendItemStatus.ACKED_FAILED,
        SendItemStatus.TIMEOUT,
        -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SendAllBar(
    itemCount: Int,
    enabled: Boolean,
    onSendAll: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = onSendAll,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (itemCount == 1) {
                        "Send All (1 item)"
                    } else {
                        "Send All ($itemCount items)"
                    },
                )
            }
            Text(
                text = "Batch engine is stubbed until session 8.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Display formatting matching the wire widths used elsewhere in the app. */
private object SendFormat {
    fun hopping(value: Long): String = "0x%08X".format(Locale.US, value)

    fun serial(value: Long): String = "0x%07X".format(Locale.US, value)

    fun button(value: Int): String = "0x%X".format(Locale.US, value)
}

/** Builds an optional Material supporting-text slot for a field error. */
private fun errorSupportingText(error: String?): (@Composable () -> Unit)? =
    if (error == null) {
        null
    } else {
        { Text(error) }
    }

