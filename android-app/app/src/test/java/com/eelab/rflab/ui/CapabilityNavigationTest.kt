package com.eelab.rflab.ui

import com.eelab.rflab.protocol.CapabilityAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Action routing test: every [CapabilityAction] resolves to the real route
 * of an existing [Destination], or to nothing for [CapabilityAction.NONE] —
 * never a route string that isn't actually in the nav graph.
 */
class CapabilityNavigationTest {

    @Test
    fun noneHasNoRouteOrLabel() {
        assertNull(CapabilityAction.NONE.route())
        assertNull(CapabilityAction.NONE.label())
    }

    @Test
    fun everyNonNoneActionResolvesToAnExistingRoute() {
        // Most actions open a bottom-nav Destination; OPEN_STATIC_CODES opens
        // a parameterized route reached from the protocol catalog instead
        // (the same shape as the catalog's own detail route), so it is not
        // itself a Destination - registered explicitly here rather than
        // silently widening the Destination-route check to match it.
        val knownRoutes = Destination.entries.map { it.route }.toSet() + STATIC_CODES_ROUTE
        for (action in CapabilityAction.entries) {
            if (action == CapabilityAction.NONE) continue
            val route = action.route()
            assertTrue("expected $action to resolve to a route", route != null)
            assertTrue(
                "expected $action's route '$route' to be a real registered route",
                route in knownRoutes,
            )
            assertTrue("expected $action to have a button label", !action.label().isNullOrBlank())
        }
    }

    @Test
    fun openStaticCodesResolvesToTheStaticCodesRoute() {
        assertEquals(STATIC_CODES_ROUTE, CapabilityAction.OPEN_STATIC_CODES.route())
    }

    @Test
    fun specificMappingsAreWhatTheScreensActuallyExpect() {
        assertEquals(Destination.Connect.route, CapabilityAction.OPEN_CONNECT.route())
        assertEquals(Destination.Receive.route, CapabilityAction.OPEN_RECEIVE.route())
        assertEquals(Destination.Send.route, CapabilityAction.OPEN_SEND.route())
        assertEquals(Destination.Keys.route, CapabilityAction.OPEN_KEYS.route())
    }
}
