package com.eelab.rflab.crypto

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyPurpose.usageDescription] backs the "which existing receive/decode
 * functions use the selected key" line on the Keys screen. Checks it says
 * the right thing for each purpose, not just that it returns *something*.
 */
class KeyPurposeUsageDescriptionTest {

    @Test
    fun deviceKeyDescribesReceiveAndSendUsage() {
        val text = KeyPurpose.DEVICE.usageDescription()
        assertTrue(text.contains("Receive", ignoreCase = true))
        assertTrue(text.contains("Send", ignoreCase = true))
    }

    @Test
    fun manufacturerKeyExplicitlyDeniesReceiveAndSendUsage() {
        val text = KeyPurpose.MANUFACTURER.usageDescription()
        assertTrue(text.contains("Not used", ignoreCase = true))
    }

    @Test
    fun theTwoPurposesDescribeDifferentThings() {
        assertNotEquals(KeyPurpose.DEVICE.usageDescription(), KeyPurpose.MANUFACTURER.usageDescription())
    }
}
