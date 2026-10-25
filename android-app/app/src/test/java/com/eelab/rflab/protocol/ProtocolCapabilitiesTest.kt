package com.eelab.rflab.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Checks on [ProtocolCapabilities.matrix] itself, not on the subsystems it
 * describes (those are covered by their own tests, e.g. `KeeLoqCipherTest`,
 * `Hcs301DecoderTest`, `KeeLoqLearningIsolationTest`). This test is about
 * the matrix staying an honest, internally consistent claim:
 *
 *  - every row's flags obey [ProtocolCapability]'s own ordering invariant
 *    (enforced again here, not just trusted from the `init` block),
 *  - the one deliberate "tested but not UI-connected" row (manufacturer
 *    key derivation) still reads that way and doesn't quietly flip to
 *    looking integrated,
 *  - no row claims hardware verification this phase, since no hardware was
 *    attached while this phase's tests ran.
 */
class ProtocolCapabilitiesTest {

    private val matrix = ProtocolCapabilities.matrix

    @Test
    fun matrixIsNonEmptyAndRowsAreUnique() {
        assertTrue(matrix.isNotEmpty())
        val keys = matrix.map { it.protocol to it.function }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyRowHasNonBlankEvidence() {
        matrix.forEach { row ->
            assertTrue("${row.protocol}/${row.function} has blank evidence", row.evidence.isNotBlank())
        }
    }

    @Test
    fun stageNeverExceedsWhatIsActuallyClaimedTrue() {
        matrix.forEach { row ->
            when (row.stage) {
                CapabilityStage.VERIFIED_ON_HARDWARE -> assertTrue(row.verifiedOnHardware)
                CapabilityStage.TESTED_AUTOMATICALLY -> assertTrue(row.testedAutomatically)
                CapabilityStage.CONNECTED_TO_UI -> assertTrue(row.connectedToUi)
                CapabilityStage.PRESENT_IN_SOURCE -> assertTrue(row.presentInSource)
                CapabilityStage.NOT_IMPLEMENTED -> assertFalse(row.presentInSource)
            }
        }
    }

    @Test
    fun noRowClaimsHardwareVerificationThisPhase() {
        // This phase's verification was build + unit tests only; no CC1101,
        // STM32, or SRX882 hardware was attached. If a row flips this true,
        // the matrix would be overclaiming relative to what was actually run.
        matrix.forEach { row ->
            assertFalse(
                "${row.protocol}/${row.function} claims hardware verification " +
                    "that was not performed this phase",
                row.verifiedOnHardware,
            )
        }
    }

    @Test
    fun manufacturerLearningIsTestedButNotConnectedToUi() {
        val row = matrix.firstOrNull { it.function.contains("Manufacturer key derivation") }
        if (row == null) {
            fail("expected a manufacturer key derivation row in the matrix")
            return
        }
        assertTrue("expected manufacturer learning to be present in source", row.presentInSource)
        assertFalse(
            "manufacturer key derivation must stay isolated from the UI " +
                "(see KeeLoqLearningIsolationTest) - this row flipping true would " +
                "mean the matrix no longer matches that guarantee",
            row.connectedToUi,
        )
        assertTrue("expected manufacturer learning to have automated tests", row.testedAutomatically)
    }

    @Test
    fun princetonDecodeRowsAreConnectedAndEncodingRowIsNotImplemented() {
        val princetonRows = matrix.filter { it.protocol == "Princeton (PT2262-style static code)" }
        assertTrue("expected Princeton rows in the matrix", princetonRows.isNotEmpty())

        val recognition = princetonRows.first { it.function.contains("recognition") }
        assertTrue(recognition.presentInSource)
        assertTrue(recognition.connectedToUi)
        assertTrue(recognition.testedAutomatically)

        val decode = princetonRows.first { it.function.contains("Frame decode") }
        assertTrue(decode.presentInSource)
        assertTrue(decode.connectedToUi)
        assertTrue(decode.testedAutomatically)

        val encoding = princetonRows.first { it.function.contains("Encoding") }
        assertFalse("encoding/transmission must not be claimed as implemented", encoding.presentInSource)
        assertFalse(encoding.connectedToUi)
        assertEquals(CapabilityAction.NONE, encoding.action)
    }

    @Test
    fun ev1527IsImplementedWiredAndTestedButNotHardwareVerified() {
        val rows = matrix.filter { it.protocol.contains("EV1527") }
        assertTrue("expected EV1527 recognition + decode rows", rows.size >= 2)
        rows.forEach { row ->
            assertTrue("EV1527 '${row.function}' must be present in source", row.presentInSource)
            assertTrue("EV1527 '${row.function}' must be UI-wired", row.connectedToUi)
            assertTrue("EV1527 '${row.function}' must be tested", row.testedAutomatically)
            assertFalse("EV1527 '${row.function}' must not claim hardware", row.verifiedOnHardware)
            assertEquals(CapabilityAction.OPEN_STATIC_CODES, row.action)
        }
    }

    @Test
    fun ht12IsImplementedWiredAndTestedButNotHardwareVerified() {
        val rows = matrix.filter { it.protocol.contains("Holtek HT12") }
        assertTrue("expected HT12 recognition + decode rows", rows.size >= 2)
        rows.forEach { row ->
            assertTrue("HT12 '${row.function}' must be present in source", row.presentInSource)
            assertTrue("HT12 '${row.function}' must be UI-wired", row.connectedToUi)
            assertTrue("HT12 '${row.function}' must be tested", row.testedAutomatically)
            assertFalse("HT12 '${row.function}' must not claim hardware", row.verifiedOnHardware)
            assertEquals(CapabilityAction.OPEN_STATIC_CODES, row.action)
        }
    }

    @Test
    fun hcs361RecognitionRemainsUnimplementedAndNonCryptographic() {
        val row = matrix.first { it.protocol.contains("HCS361") }
        assertFalse(row.presentInSource)
        assertFalse(row.connectedToUi)
        assertEquals(CapabilityAction.NONE, row.action)
        // The documented scope is recognition only; derivation/transmission
        // must not be implied as a direction here.
        assertTrue(row.function.lowercase().contains("recognition"))
    }

    @Test
    fun everyRowHasAPreciseDispositionNotABareNotImplemented() {
        // Every absent row must say *why* - blocked vs deliberately out of
        // scope - never resting on an unqualified absence.
        matrix.filter { !it.presentInSource }.forEach { row ->
            val d = row.disposition
            assertTrue(
                "${row.protocol}/${row.function} must be BLOCKED or OUT_OF_SCOPE, was $d",
                d == CapabilityDisposition.BLOCKED || d == CapabilityDisposition.OUT_OF_SCOPE,
            )
        }
    }

    @Test
    fun princetonEncodingIsOutOfScopeNotBlocked() {
        val row = matrix.first {
            it.protocol.contains("Princeton") && it.function.contains("Encoding")
        }
        assertEquals(CapabilityDisposition.OUT_OF_SCOPE, row.disposition)
    }

    @Test
    fun hcs361IsBlockedByAMissingDependencyNotOutOfScope() {
        val row = matrix.first { it.protocol.contains("HCS361") }
        assertEquals(CapabilityDisposition.BLOCKED, row.disposition)
    }

    @Test
    fun implementedStaticCodeDecodersReadAsImplementedNotHardwareVerified() {
        val rows = matrix.filter {
            (it.protocol.contains("EV1527") || it.protocol.contains("Holtek HT12")) &&
                it.function.contains("decode", ignoreCase = true)
        }
        assertTrue(rows.isNotEmpty())
        rows.forEach {
            assertEquals(
                "${it.protocol}/${it.function}",
                CapabilityDisposition.IMPLEMENTED_NOT_HARDWARE_VERIFIED,
                it.disposition,
            )
        }
    }

    @Test
    fun constructorRejectsOutOfScopeOnAPresentRow() {
        assertThrows {
            ProtocolCapability(
                protocol = "test",
                function = "contradiction",
                presentInSource = true,
                connectedToUi = false,
                testedAutomatically = false,
                verifiedOnHardware = false,
                evidence = "n/a",
                outOfScope = true,
            )
        }
    }

    @Test
    fun atLeastOneRowDocumentsAnUnimplementedProtocol() {
        // The matrix is required to be a complete answer to "what protocols
        // does this support", including the ones it doesn't - not just a
        // list of what exists.
        assertTrue(matrix.any { !it.presentInSource })
    }

    @Test
    fun constructorRejectsUiClaimWithoutSourceClaim() {
        assertThrows {
            ProtocolCapability(
                protocol = "test",
                function = "impossible claim",
                presentInSource = false,
                connectedToUi = true,
                testedAutomatically = false,
                verifiedOnHardware = false,
                evidence = "n/a",
            )
        }
    }

    @Test
    fun constructorRejectsBlankEvidence() {
        assertThrows {
            ProtocolCapability(
                protocol = "test",
                function = "no evidence",
                presentInSource = true,
                connectedToUi = false,
                testedAutomatically = false,
                verifiedOnHardware = false,
                evidence = "   ",
            )
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
