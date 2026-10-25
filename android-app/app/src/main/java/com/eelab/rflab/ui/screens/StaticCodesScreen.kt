package com.eelab.rflab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.eelab.rflab.ui.receive.StaticCodeProtocol
import com.eelab.rflab.ui.receive.StaticCodeRow
import com.eelab.rflab.ui.receive.StaticCodeUiState
import com.eelab.rflab.ui.receive.StaticCodeViewModel
import com.eelab.rflab.usb.RfLabSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live list of decoded Princeton/PT2262-style static codes.
 *
 * Read-only by design: a static code has no counter to track and this app
 * does not transmit one (see `PrincetonProtocol`'s doc comment on why
 * encoding isn't implemented) — so unlike [ReceiveScreen] there is no
 * selection and no "add to send queue".
 */
@Composable
fun StaticCodesScreen(
    modifier: Modifier = Modifier,
    viewModel: StaticCodeViewModel = viewModel(
        factory = StaticCodeViewModel.factory(
            transportEvents = RfLabSession.events,
            connected = RfLabSession.isConnected,
        ),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StaticCodesScreen(state = state, onClear = viewModel::clearReceived, modifier = modifier)
}

@Composable
internal fun StaticCodesScreen(
    state: StaticCodeUiState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Header(state = state, onClear = onClear)
        if (state.rows.isEmpty()) {
            EmptyState(modifier = Modifier)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.id }) { row -> StaticCodeCard(row) }
            }
        }
    }
}

@Composable
private fun Header(state: StaticCodeUiState, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Static codes", style = MaterialTheme.typography.titleLarge)
            Text(
                "Protocols: Princeton/PT2262 & EV1527 (one 24-bit encoding, shown with " +
                    "both field readings) and Holtek HT12 (12-bit). Decoded directly, not " +
                    "decrypted — there is no key and no rolling counter for these families.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (!state.isConnected) {
                    "Not connected"
                } else if (state.rows.isEmpty()) {
                    "Waiting for frames"
                } else if (state.rows.size == 1) {
                    "1 code"
                } else {
                    "${state.rows.size} codes"
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
        if (state.canClear) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Delete, contentDescription = "Clear received codes")
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
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
        Text(
            text = "No static codes decoded yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Only Princeton/PT2262-style frames are recognized here. Connect the " +
                "receiver and start it from the Connect tab to start listening.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StaticCodeCard(row: StaticCodeRow) {
    val timestamp = remember(row.firstSeenMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(row.firstSeenMs))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.repeatCount > 1) {
                    Text(
                        "  x${row.repeatCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            when (row.protocol) {
                StaticCodeProtocol.PRINCETON_EV1527 -> {
                    Text(
                        "PT2262/EV1527  code 0x%06X (%d bit)".format(row.data, row.bitCount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "PT2262  serial 0x%X  button 0x%X".format(row.serial, row.button),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "EV1527  addr 0x%05X  data 0x%X".format(row.address, row.dataNibble),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                StaticCodeProtocol.HT12 -> {
                    Text(
                        "Holtek HT12  code 0x%03X (%d bit)".format(row.data, row.bitCount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "HT12  addr/DIP 0x%02X  data 0x%X".format(row.address, row.dataNibble),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
