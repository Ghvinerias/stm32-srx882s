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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.eelab.rflab.favorites.FavoriteQueue
import com.eelab.rflab.favorites.FavoritesRepository
import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.send.BatchSendEngine
import com.eelab.rflab.send.SendGraph
import com.eelab.rflab.ui.send.ManualEntryState
import com.eelab.rflab.ui.send.SendUiState
import com.eelab.rflab.ui.send.SendViewModel
import com.eelab.rflab.usb.RfLabSession
import java.util.Locale

/**
 * Send queue and manual-entry screen.
 *
 * The screen renders the shared queue, lets the user add/remove/reorder items,
 * edit each item's frame count and clear the queue, and adds the four-field
 * manual-entry card. The Favorites card saves the current queue under a name
 * and appends a saved queue back onto the current one.
 *
 * "Send All" goes through the app's single [BatchSendEngine] (via
 * [SendGraph]): it walks the queue strictly one item at a time, awaiting each
 * ACK or timeout before writing the next.
 *
 * The send action is gated on [RfLabSession.isConnected], which follows the
 * session's live connection state *and* a link that fails on its own (the
 * read loop's `openState`). Queue editing and clearing still work offline;
 * "Send All" is disabled and explains why when the Connect tab has no live
 * connection.
 *
 * @param sendEngine sequential sender supplied by the app composition root.
 * @param favoritesRepository persisted favorites wired by the app composition
 *   root; the screen only reads/updates it through the ViewModel.
 */
@Composable
fun SendScreen(
    modifier: Modifier = Modifier,
    sendEngine: BatchSendEngine = SendGraph.engine,
    favoritesRepository: FavoritesRepository,
    viewModel: SendViewModel = viewModel(
        factory = SendViewModel.factory(
            batchSender = sendEngine::sendBatch,
            favoritesRepository = favoritesRepository,
            connectionStatus = RfLabSession.isConnected,
            stopSender = sendEngine::stopTransmit,
        ),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SendScreen(
        state = state,
        onHoppingChange = viewModel::onHoppingChange,
        onSerialChange = viewModel::onSerialChange,
        onButtonChange = viewModel::onButtonChange,
        onFramesChange = viewModel::onFramesChange,
        onAddManualEntry = { viewModel.addManualEntry() },
        onRemoveItem = viewModel::removeItem,
        onMoveItemUp = viewModel::moveItemUp,
        onMoveItemDown = viewModel::moveItemDown,
        onFrameCountChange = viewModel::setFrameCount,
        onSendAll = viewModel::sendAll,
        onStop = viewModel::stopSending,
        onClearQueue = viewModel::clearQueue,
        onFavoriteNameChange = viewModel::onFavoriteNameChange,
        onSaveFavorite = { viewModel.saveFavorite(state.favoriteName) },
        onLoadFavorite = viewModel::loadFavorite,
        onDeleteFavorite = viewModel::deleteFavorite,
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
    onRemoveItem: (Long) -> Unit,
    onMoveItemUp: (Long) -> Unit,
    onMoveItemDown: (Long) -> Unit,
    onFrameCountChange: (Long, Int) -> Unit,
    onSendAll: () -> Unit,
    onStop: () -> Unit,
    onClearQueue: () -> Unit,
    onFavoriteNameChange: (String) -> Unit,
    onSaveFavorite: () -> Unit,
    onLoadFavorite: (Long) -> Unit,
    onDeleteFavorite: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SendHeader(state = state, onClearQueue = onClearQueue)

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

            item(key = "favorites") {
                FavoritesCard(
                    name = state.favoriteName,
                    favorites = state.favorites,
                    canSave = state.canSaveFavorite,
                    onNameChange = onFavoriteNameChange,
                    onSave = onSaveFavorite,
                    onLoad = onLoadFavorite,
                    onDelete = onDeleteFavorite,
                )
            }
        }

        SendAllBar(
            itemCount = state.items.size,
            enabled = state.canSendAll,
            connected = state.isConnected,
            sending = state.isSending,
            onSendAll = onSendAll,
            onStop = onStop,
        )
    }
}

@Composable
private fun SendHeader(state: SendUiState, onClearQueue: () -> Unit) {
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
            if (state.canClearQueue) {
                IconButton(onClick = onClearQueue) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear send queue",
                    )
                }
            }
        }
        state.message?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.isConnected) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Not connected — sending is disabled until the board is connected " +
                    "on the Connect tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
                text = "Hex for hopping/serial/button; frames 1-255 (~0.1 s each).",
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
                    label = { Text("Frames (1-255)") },
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
private fun FavoritesCard(
    name: String,
    favorites: List<FavoriteQueue>,
    canSave: Boolean,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onLoad: (Long) -> Unit,
    onDelete: (Long) -> Unit,
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
                text = "Favorites",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Save the current queue under a name, then append it to the " +
                    "queue again later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Favorite name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save current queue as favorite")
            }

            if (favorites.isEmpty()) {
                Text(
                    text = "No favorites saved yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (favorite in favorites) {
                    FavoriteRow(
                        favorite = favorite,
                        onLoad = { onLoad(favorite.id) },
                        onDelete = { onDelete(favorite.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteQueue,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = favorite.name,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = when (favorite.items.size) {
                    1 -> "1 item"
                    else -> "${favorite.items.size} items"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onLoad) {
            Text("Load")
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete favorite ${favorite.name}",
            )
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
                    modifier = Modifier.width(40.dp),
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
        SendItemStatus.FAILED -> "Link failed"
        SendItemStatus.ABORTED -> "Aborted"
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
        SendItemStatus.FAILED,
        SendItemStatus.ABORTED,
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
    connected: Boolean,
    sending: Boolean,
    onSendAll: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (sending) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Stop (abort transmit)")
                }
            }
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
                text = if (!connected) {
                    "Connect the board on the Connect tab to send."
                } else {
                    "One at a time: each item waits for its ACK (timeout grows with frame count)."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (!connected) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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

