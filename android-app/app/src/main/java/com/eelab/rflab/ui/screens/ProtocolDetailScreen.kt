package com.eelab.rflab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eelab.rflab.protocol.CapabilityAction
import com.eelab.rflab.protocol.ProtocolCapabilities
import com.eelab.rflab.protocol.ProtocolCapability
import com.eelab.rflab.ui.label

/**
 * Detail page for one protocol family: every function the capability matrix
 * records for it, with real action buttons where one exists.
 *
 * All four [ProtocolCapability] flags are shown per-function rather than
 * collapsed into one label — "tested automatically" and "connected to UI"
 * are independent facts, and a function can be true on one and false on the
 * other (manufacturer key derivation is tested but deliberately not wired
 * to any screen).
 *
 * @param isConnected the shared session's live connection state, shown next
 *   to any function whose [ProtocolCapability.requiresHardware] is true, so
 *   "this needs hardware" is a current fact, not just a label.
 */
@Composable
fun ProtocolDetailScreen(
    familyName: String,
    isConnected: Boolean,
    onAction: (CapabilityAction) -> Unit,
    modifier: Modifier = Modifier,
    capabilities: List<ProtocolCapability> = ProtocolCapabilities.matrix,
) {
    val rows = capabilities.filter { it.protocol == familyName }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(familyName, style = MaterialTheme.typography.titleLarge)
            if (rows.isEmpty()) {
                Text(
                    "No capability matrix entry matches this protocol name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.function }) { row ->
                FunctionCard(row, isConnected = isConnected, onAction = onAction)
            }
        }
    }
}

@Composable
private fun FunctionCard(
    row: ProtocolCapability,
    isConnected: Boolean,
    onAction: (CapabilityAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.function, style = MaterialTheme.typography.bodyLarge)

            // The one explicit outcome for this row, so a reader never has to
            // infer "why isn't this here" from a bare absence.
            Text(
                row.disposition.label,
                style = MaterialTheme.typography.labelMedium,
                color = when (row.disposition) {
                    com.eelab.rflab.protocol.CapabilityDisposition.BLOCKED,
                    com.eelab.rflab.protocol.CapabilityDisposition.OUT_OF_SCOPE,
                    ->
                        MaterialTheme.colorScheme.onSurfaceVariant

                    else -> MaterialTheme.colorScheme.primary
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlagChip("Source", row.presentInSource)
                FlagChip("UI", row.connectedToUi)
                FlagChip("Tested", row.testedAutomatically)
                FlagChip("Hardware", row.verifiedOnHardware)
            }

            if (row.requiresHardware) {
                Text(
                    if (isConnected) {
                        "Requires a connected device - currently connected."
                    } else {
                        "Requires a connected device - not connected."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            ActionRow(row, onAction)

            // The evidence string is the long part - provenance, upstream
            // commit hashes, which transports touch it. It makes the card
            // unscannable when always shown, so it lives behind a toggle and
            // the four flag chips above carry the at-a-glance status.
            var showEvidence by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showEvidence = !showEvidence },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(if (showEvidence) "Hide details" else "Show details")
            }
            if (showEvidence) {
                Text(
                    row.evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Exactly the three honest outcomes required: a real button to a real
 * screen, an explicit "not available from this interface" for something
 * present but unwired, or an explicit "not implemented" - never a button
 * that fakes an action.
 */
@Composable
private fun ActionRow(row: ProtocolCapability, onAction: (CapabilityAction) -> Unit) {
    val label = row.action.label()
    when {
        label != null -> Button(onClick = { onAction(row.action) }) { Text(label) }

        !row.presentInSource -> Text(
            "Not implemented.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )

        !row.connectedToUi -> Text(
            "Present in source, but not available from the current interface.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(
            "No screen action for this function.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlagChip(label: String, value: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (value) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (value) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = (if (value) "✓ " else "✗ ") + label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
