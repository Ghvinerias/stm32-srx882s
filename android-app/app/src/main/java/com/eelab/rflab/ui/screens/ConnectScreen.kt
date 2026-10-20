package com.eelab.rflab.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Placeholder for the connection screen.
 *
 * TODO: session 9 — permission/connect button, connected/disconnected indicator
 *       driven by the ~1/s STATUS heartbeat, optional clock_hz/total_edges
 *       diagnostics.
 * TODO: session 4 — USB enumeration (VID 0xCAFE / PID 0x4001) and
 *       CdcAcmSerialDriver setup belong below the UI layer.
 */
@Composable
fun ConnectScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Connect",
        sessionNote = "Device connection UI arrives in sessions 4 and 9.",
        modifier = modifier,
    )
}
