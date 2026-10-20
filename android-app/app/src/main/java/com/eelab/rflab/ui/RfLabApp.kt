package com.eelab.rflab.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eelab.rflab.R
import com.eelab.rflab.send.SendGraph
import com.eelab.rflab.ui.screens.ConnectScreen
import com.eelab.rflab.ui.screens.ReceiveScreen
import com.eelab.rflab.ui.screens.SendScreen

private enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Connect("connect", R.string.nav_connect, Icons.Default.Settings),
    Receive("receive", R.string.nav_receive, Icons.Default.List),
    Send("send", R.string.nav_send, Icons.Default.Send),
}

/**
 * Top-level shell: three destinations with bottom navigation.
 *
 * This is also the app-level composition root for the send path: one
 * [SendGraph.engine] instance is created here and handed to the send screen,
 * so batch sends and preset scenarios share the same serialising mutex.
 * Session 9 does the final connection/navigation wiring.
 */
@Composable
fun RfLabApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // One sequential sender for the whole app: never two commands in flight.
    val sendEngine = SendGraph.engine

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Connect.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Connect.route) { ConnectScreen() }
            composable(Destination.Receive.route) { ReceiveScreen() }
            composable(Destination.Send.route) { SendScreen(sendEngine = sendEngine) }
        }
    }
}
