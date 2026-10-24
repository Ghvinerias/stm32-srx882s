package com.eelab.rflab.decode

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cipher is validated two ways: encrypt/decrypt are exact inverses over
 * many random blocks and keys (so the pair is internally consistent and
 * usable in both directions), and a handful of fixed snapshots pin the
 * algorithm so an accidental change to the round structure is caught.
 */
class KeeLoqCipherTest {

    @Test
    fun decryptInvertsEncryptOverRandomInputs() {
        val rnd = java.util.Random(0xC0FFEE)
        repeat(5000) {
            val data = rnd.nextInt()
            val key = rnd.nextLong()
            assertEquals(data, KeeLoqCipher.decrypt(KeeLoqCipher.encrypt(data, key), key))
            assertEquals(data, KeeLoqCipher.encrypt(KeeLoqCipher.decrypt(data, key), key))
        }
    }

    @Test
    fun zeroKeyIsStillInvertible() {
        for (data in intArrayOf(0, 1, -1, 0x12345678, 0x7FFFFFFF, -0x80000000)) {
            assertEquals(data, KeeLoqCipher.decrypt(KeeLoqCipher.encrypt(data, 0L), 0L))
        }
    }

    @Test
    fun matchesPublishedKnownAnswerVector() {
        // Known-answer vectors from the KeeLoq cryptanalysis reference
        // implementation (github.com/hadipourh/KeeLoq): encryption maps
        // plaintext -> ciphertext under a 64-bit key. This pins the absolute
        // algorithm (NLF constant, tap positions, key schedule), not just the
        // internal consistency of the pair.
        val key = 0x5CEC6701B79FD949L
        assertEquals(0xE44F4CDF.toInt(), KeeLoqCipher.encrypt(0xF741E2DB.toInt(), key))
        assertEquals(0xF741E2DB.toInt(), KeeLoqCipher.decrypt(0xE44F4CDF.toInt(), key))
        assertEquals(0xA6AC0EA2.toInt(), KeeLoqCipher.encrypt(0x0CA69B92, key))
        assertEquals(0x0CA69B92, KeeLoqCipher.decrypt(0xA6AC0EA2.toInt(), key))
    }
}
