package com.eelab.rflab.ota.update

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * The trust-on-first-use pin decision, exercised without sockets: first use
 * (no pin yet), matching pin, changed certificate, and case handling.
 */
class TofuPinsTest {

    private val pinned = "aa11bb22cc33dd44ee55ff66aa11bb22cc33dd44ee55ff66aa11bb22cc33dd44e"

    private val same = pinned

    private val differentChain = listOf(
        "ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00",
    )

    @Test
    fun noPinIsFirstUseCarryingTheObservedFingerprint() {
        val decision = TofuPins.decide(null, differentChain)

        assertEquals(
            PinDecision.FirstUse(differentChain.first()),
            decision,
        )
    }

    @Test
    fun emptyObservedChainIsAProgrammingError() {
        assertThrows(IllegalArgumentException::class.java) {
            TofuPins.decide(pinned, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TofuPins.decide(null, emptyList())
        }
    }

    @Test
    fun matchingPinIsAcceptedFromAnywhereInTheChain() {
        assertEquals(PinDecision.Accept, TofuPins.decide(pinned, listOf(same)))
        // A leaf above a matching intermediate/root is still the pinned cert.
        assertEquals(
            PinDecision.Accept,
            TofuPins.decide(pinned, listOf("deadbeef", same)),
        )
    }

    @Test
    fun pinComparisonIsCaseInsensitive() {
        assertEquals(
            PinDecision.Accept,
            TofuPins.decide(pinned, listOf(pinned.uppercase())),
        )
    }

    @Test
    fun changedCertificateIsAMismatch() {
        val decision = TofuPins.decide(pinned, differentChain)

        assertEquals(
            PinDecision.Mismatch(pinned = pinned, observed = differentChain.first()),
            decision,
        )
    }

    @Test
    fun mismatchOutputsAreNormalisedToLowercase() {
        val decision = TofuPins.decide(pinned.uppercase(), listOf(pinned.lowercase() + "0"))

        // Whatever the case confusion, the reported pair is lowercase so the
        // UI can show/compare it verbatim.
        assertEquals(pinned, (decision as PinDecision.Mismatch).pinned)
    }

    @Test
    fun shortHashIsTheFirstEightHexCharacters() {
        assertEquals("aa11bb22", TofuPins.shortHash(pinned))
    }

    @Test
    fun fingerprintDisplaysAsSpaceSeparatedPairs() {
        assertEquals("aa 11 bb 22", TofuPins.formatFingerprint("AA11BB22"))
    }
}
