package com.eelab.rflab.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeeLoqHopTest {

    private val key = 0x5CEC6701B79FD949L

    @Test
    fun buildThenDecryptRecoversFields() {
        val hop = KeeLoqHop.build(
            key = key,
            button = 0x2,
            discrimination = 0x1AB,
            counter = 0x1234,
            overflow = 0x1,
        )
        val info = KeeLoqHop.decrypt(hop, key)
        assertEquals(0x1234, info.counter)
        assertEquals(0x1AB, info.discrimination)
        assertEquals(0x1, info.overflow)
        assertEquals(0x2, info.buttonInHop)
    }

    @Test
    fun counterAdvancesAcrossConsecutiveHops() {
        val disc = 0x055
        val a = KeeLoqHop.decrypt(KeeLoqHop.build(key, 0x8, disc, 100), key)
        val b = KeeLoqHop.decrypt(KeeLoqHop.build(key, 0x8, disc, 101), key)
        assertEquals(100, a.counter)
        assertEquals(101, b.counter)
    }

    @Test
    fun matchesClearButtonIsTrueForCorrectKeyAndFalseForWrong() {
        val hop = KeeLoqHop.build(key, button = 0x4, discrimination = 0x10, counter = 7)
        // Correct key: the hop's button nibble equals the clear button.
        assertTrue(KeeLoqHop.decrypt(hop, key).matchesClearButton(0x4))
        // A different key decrypts to unrelated plaintext whose button nibble
        // will almost never be 0x4.
        assertFalse(KeeLoqHop.decrypt(hop, key xor 0x1).matchesClearButton(0x4))
    }

    @Test
    fun discriminationFromSerialTakesLow10Bits() {
        assertEquals(0x3FF, KeeLoqHop.discriminationFromSerial(0x0FB58FFFL))
        assertEquals(0x000, KeeLoqHop.discriminationFromSerial(0x0FB58C00L))
    }
}
