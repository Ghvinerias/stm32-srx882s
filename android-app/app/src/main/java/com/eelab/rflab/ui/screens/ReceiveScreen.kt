package com.eelab.rflab.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Placeholder for the decoded-signal list.
 *
 * TODO: session 6 — scrolling list fed by sessions 4+5, repeat grouping,
 *       multi-select and "add N to send queue".
 * TODO: sessions 4-5 — packet reassembly and the HCS301 decoder live below
 *       the UI layer and feed this screen.
 */
@Composable
fun ReceiveScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Receive",
        sessionNote = "Decoded signal list arrives in sessions 4-6.",
        modifier = modifier,
    )
}
