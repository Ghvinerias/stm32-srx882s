package com.eelab.rflab.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.eelab.rflab.R

/**
 * The app's bottom-navigation destinations, in display order.
 *
 * Pulled out of [RfLabApp] so the nav structure itself — routes, order,
 * uniqueness — is plain data a JVM unit test can check without Compose or
 * an instrumented device (see `AppDestinationsTest`). [RfLabApp] only wires
 * each route to a screen; it does not own this list.
 */
enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Connect("connect", R.string.nav_connect, Icons.Default.Settings),
    Receive("receive", R.string.nav_receive, Icons.Default.List),
    Send("send", R.string.nav_send, Icons.Default.Send),
    Keys("keys", R.string.nav_keys, Icons.Default.Lock),
    Capabilities("capabilities", R.string.nav_capabilities, Icons.Default.Info),
    Firmware("firmware", R.string.nav_firmware, Icons.Default.Build),
}
