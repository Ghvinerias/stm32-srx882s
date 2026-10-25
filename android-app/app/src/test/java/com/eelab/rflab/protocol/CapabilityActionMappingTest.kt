package com.eelab.rflab.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability-to-screen mapping test: checks the correspondence between a
 * [ProtocolCapability]'s flags and its [ProtocolCapability.action] — the
 * exact contract Phase 2's dashboard depends on to avoid fake buttons.
 */
class CapabilityActionMappingTest {

    private val matrix = ProtocolCapabilities.matrix

    @Test
    fun everyConnectedToUiRowHasARealAction() {
        matrix.filter { it.connectedToUi }.forEach { row ->
            assertTrue(
                "${row.protocol}/${row.function} is connectedToUi=true but has no action",
                row.action != CapabilityAction.NONE,
            )
        }
    }

    @Test
    fun noRowDisconnectedFromUiHasAnAction() {
        matrix.filter { !it.connectedToUi }.forEach { row ->
            assertEquals(
                "${row.protocol}/${row.function} is not connected to the UI but has an action",
                CapabilityAction.NONE,
                row.action,
            )
        }
    }

    @Test
    fun manufacturerLearningRowHasNoAction() {
        val row = matrix.first { it.function.contains("Manufacturer key derivation") }
        assertEquals(CapabilityAction.NONE, row.action)
        assertFalse(row.connectedToUi)
        assertTrue(row.presentInSource)
    }

    @Test
    fun unimplementedRowsHaveNoAction() {
        matrix.filter { !it.presentInSource }.forEach { row ->
            assertEquals(CapabilityAction.NONE, row.action)
        }
    }

    @Test
    fun constructorRejectsAnActionWithoutUiConnection() {
        try {
            ProtocolCapability(
                protocol = "test",
                function = "impossible action",
                presentInSource = true,
                connectedToUi = false,
                testedAutomatically = false,
                verifiedOnHardware = false,
                evidence = "n/a",
                action = CapabilityAction.OPEN_RECEIVE,
            )
            org.junit.Assert.fail("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
