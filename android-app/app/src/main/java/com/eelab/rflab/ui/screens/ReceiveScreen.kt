package com.eelab.rflab.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.ui.receive.ReceiveUiState
import com.eelab.rflab.ui.receive.ReceiveViewModel
import com.eelab.rflab.ui.receive.ReceivedSignalRow
import com.eelab.rflab.ui.receive.SignalFormat
import com.eelab.rflab.usb.RfLabSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live list of decoded HCS301 signals.
 *
 * The ViewModel subscribes to the current connection's transport events,
 * decodes FRAME packets and collapses press repeats into one row each.
 * Rows can be checked (tap the row or the checkbox) and copied into the
 * shared send queue in one action.
 *
 * The ViewModel is normally created once by the app composition root (so a
 * connection that is already open feeds this list immediately); with nothing
 * attached this screen shows its empty state. Malformed frames are counted in
 * the header but never interrupt decoding.
 */
@Composable
fun ReceiveScreen(
    modifier: Modifier = Modifier,
    viewModel: ReceiveViewModel = viewModel(
        factory = ReceiveViewModel.factory(RfLabSession.events),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ReceiveScreen(
        state = state,
        onToggleSelection = viewModel::toggleSelection,
        onAddSelectedToQueue = { viewModel.addSelectedToQueue() },
        modifier = modifier,
    )
}

/**
 * Stateless body, kept separate so the row/selection/queue UI can be
 * previewed and tested without a ViewModel or a USB link.
 */
@Composable
internal fun ReceiveScreen(
    state: ReceiveUiState,
    onToggleSelection: (Long) -> Unit,
    onAddSelectedToQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ReceiveHeader(state)

        if (state.rows.isEmpty()) {
            EmptyReceiveState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    ReceivedSignalCard(
                        row = row,
                        onToggleSelection = onToggleSelection,
                    )
                }
            }
        }

        if (state.canAddSelected) {
            AddToQueueBar(
                selectedCount = state.selectedCount,
                onAddSelectedToQueue = onAddSelectedToQueue,
            )
        }
    }
}

@Composable
private fun ReceiveHeader(state: ReceiveUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Received signals",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when (state.rows.size) {
                    0 -> "Waiting for frames"
                    1 -> "1 signal"
                    else -> "${state.rows.size} signals"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.decodeErrorCount > 0) {
                Text(
                    text = if (state.decodeErrorCount == 1) {
                        "1 malformed frame ignored"
                    } else {
                        "${state.decodeErrorCount} malformed frames ignored"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.queueSize > 0) {
            Text(
                text = "Queue: ${state.queueSize}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyReceiveState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "No signals yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Decoded HCS301 frames appear here as they arrive. " +
                "Connect the receiver to start listening.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReceivedSignalCard(
    row: ReceivedSignalRow,
    onToggleSelection: (Long) -> Unit,
) {
    val timestamp = remember(row.firstSeenMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(row.firstSeenMs))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection(row.id) },
        colors = CardDefaults.cardColors(
            containerColor = if (row.selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.selected,
                onCheckedChange = { onToggleSelection(row.id) },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.repeatCount > 1) {
                        Spacer(Modifier.width(8.dp))
                        RepeatBadge(row.repeatCount)
                    }
                }
                Text(
                    text = "Hopping ${SignalFormat.hopping(row.hopping)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Serial ${SignalFormat.serial(row.serial)}  " +
                        "Button ${SignalFormat.button(row.button)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun RepeatBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = "×$count",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun AddToQueueBar(
    selectedCount: Int,
    onAddSelectedToQueue: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = onAddSelectedToQueue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (selectedCount == 1) {
                    "Add 1 to send queue"
                } else {
                    "Add $selectedCount to send queue"
                },
            )
        }
    }
}
