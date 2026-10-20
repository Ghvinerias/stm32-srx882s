package com.eelab.rflab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.ui.connect.ConnectUiState
import com.eelab.rflab.ui.connect.ConnectViewModel
import com.eelab.rflab.ui.connect.ConnectionPhase
import com.eelab.rflab.ui.connect.LinkLiveness
import com.eelab.rflab.ui.connect.StatusDiagnostics
import com.eelab.rflab.usb.ConnectPort
import java.util.Locale

/**
 * USB connection screen.
 *
 * The permission/connect button drives the real lifecycle through
 * [ConnectPort] (`UsbConnectController` in production): find the board,
 * request USB permission, open the CDC-ACM port and attach it to the shared
 * session. A colored dot and the STATUS heartbeat (fresh/stale, 3 s
 * threshold) show whether the link is actually alive, and the optional
 * clock/edge diagnostics from STATUS are shown once the first heartbeat
 * arrives.
 *
 * @param port connection seam supplied by the app composition root.
 */
@Composable
fun ConnectScreen(
    port: ConnectPort,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = viewModel(factory = ConnectViewModel.factory(port)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ConnectScreen(
        state = state,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        modifier = modifier,
    )
}

/**
 * Stateless body, kept separate so the status/diagnostic UI can be previewed
 * or exercised without a ViewModel or a USB device.
 */
@Composable
internal fun ConnectScreen(
    state: ConnectUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Device connection",
            style = MaterialTheme.typography.titleLarge,
        )

        ConnectionStatusCard(state)

        if (state.isConnected) {
            DiagnosticsCard(state.diagnostics)
        }

        ConnectionControls(
            state = state,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        state.message?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Text(
            text = "Plug the board in over USB OTG. Android asks for permission the " +
                "first time; the grant is reused until the device is unplugged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionStatusCard(state: ConnectUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(statusColor(state), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = phaseLabel(state.phase),
                    style = MaterialTheme.typography.titleMedium,
                )
                statusDetail(state)?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: StatusDiagnostics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "STATUS diagnostics",
                style = MaterialTheme.typography.titleMedium,
            )

            if (diagnostics == null) {
                Text(
                    text = "Waiting for the first STATUS packet…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DiagnosticRow("Clock", "${formatCount(diagnostics.clockHz)} Hz")
                DiagnosticRow("Total edges", formatCount(diagnostics.totalEdges))
                DiagnosticRow("Dropped packets", formatCount(diagnostics.droppedPackets.toLong()))
                DiagnosticRow("STATUS sequence", "#${formatCount(diagnostics.sequence.toLong())}")
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ConnectionControls(
    state: ConnectUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            ConnectionPhase.CONNECTING -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connecting…")
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }

            ConnectionPhase.CONNECTED -> {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disconnect")
                }
            }

            else -> {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.phase == ConnectionPhase.DISCONNECTED) {
                            "Connect"
                        } else {
                            "Try again"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun statusColor(state: ConnectUiState): Color = when {
    state.phase == ConnectionPhase.CONNECTED && state.liveness == LinkLiveness.LIVE ->
        MaterialTheme.colorScheme.primary

    state.phase == ConnectionPhase.CONNECTED && state.liveness == LinkLiveness.STALE ->
        MaterialTheme.colorScheme.error

    state.phase == ConnectionPhase.CONNECTED ->
        MaterialTheme.colorScheme.tertiary

    state.phase == ConnectionPhase.CONNECTING ->
        MaterialTheme.colorScheme.tertiary

    state.phase == ConnectionPhase.PERMISSION_DENIED ||
        state.phase == ConnectionPhase.ERROR ->
        MaterialTheme.colorScheme.error

    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun phaseLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.DISCONNECTED -> "Disconnected"
    ConnectionPhase.CONNECTING -> "Connecting…"
    ConnectionPhase.PERMISSION_DENIED -> "USB permission denied"
    ConnectionPhase.CONNECTED -> "Connected"
    ConnectionPhase.ERROR -> "Connection error"
}

private fun statusDetail(state: ConnectUiState): String? = when (state.phase) {
    ConnectionPhase.CONNECTING ->
        "Looking for the board and requesting USB permission…"

    ConnectionPhase.CONNECTED -> when (state.liveness) {
        LinkLiveness.WAITING -> "Waiting for the first STATUS heartbeat…"
        LinkLiveness.LIVE ->
            "Heartbeat OK — last STATUS ${formatAge(state.lastStatusAgeMs)} ago"

        LinkLiveness.STALE ->
            "No STATUS for ${formatAge(state.lastStatusAgeMs)} — link may be down"
    }

    else -> null
}

private fun formatAge(ageMs: Long?): String = when {
    ageMs == null -> "unknown"
    ageMs < 1_000L -> "$ageMs ms"
    else -> String.format(Locale.US, "%.1f s", ageMs / 1_000.0)
}

private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
