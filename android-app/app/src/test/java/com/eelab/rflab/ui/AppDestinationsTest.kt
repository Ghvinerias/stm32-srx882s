package com.eelab.rflab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the bottom-navigation destination list. This is a
 * plain-data test, not a Compose/instrumented one: there is no
 * Robolectric/androidTest setup in this module (see `app/build.gradle.kts`),
 * so it cannot click through the `NavHost` itself. What it can and does
 * check is the contract [RfLabApp] builds the real nav graph from: unique,
 * non-blank routes, and that every screen this phase wired in (Keys,
 * Capabilities) is actually present in the destination list, not just
 * referenced from a `composable(...)` block that nothing points at.
 */
class AppDestinationsTest {

    @Test
    fun everyRouteIsUniqueAndNonBlank() {
        val routes = Destination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it.isNotBlank() })
    }

    @Test
    fun startDestinationIsConnect() {
        // RfLabApp.kt pins this as NavHost's startDestination; keep the two in sync.
        assertEquals(Destination.Connect, Destination.entries.first())
    }

    @Test
    fun capabilitiesAndKeysDestinationsExist() {
        val routes = Destination.entries.map { it.route }
        assertTrue("expected a keys destination", routes.contains(Destination.Keys.route))
        assertTrue("expected a capabilities destination", routes.contains(Destination.Capabilities.route))
    }

    @Test
    fun everyDestinationHasADistinctIcon() {
        // Not a hard requirement of NavigationBarItem, but two destinations
        // sharing an icon is almost certainly a copy-paste mistake in this enum.
        val icons = Destination.entries.map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
    }
}
