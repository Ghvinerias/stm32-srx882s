package com.eelab.rflab.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Placeholder for the send queue.
 *
 * TODO: session 7 — queue model, manual hopping/serial/button/frames entry,
 *       per-item frame count and the six preset scenario buttons.
 * TODO: session 8 — strictly sequential send-then-await-ACK batch engine with
 *       per-item status.
 */
@Composable
fun SendScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Send",
        sessionNote = "Send queue and batch engine arrive in sessions 7-8.",
        modifier = modifier,
    )
}
