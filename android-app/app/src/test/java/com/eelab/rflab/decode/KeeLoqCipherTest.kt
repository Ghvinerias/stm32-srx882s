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

    @Test
    fun boundaryValuesEncryptDecrypt() {
        val key = 0x123456789ABCDEF0L
        // All zeros
        assertEquals(0, KeeLoqCipher.decrypt(KeeLoqCipher.encrypt(0, key), key))
        // All ones (as signed int)
        val allOnes = -1
        assertEquals(allOnes, KeeLoqCipher.decrypt(KeeLoqCipher.encrypt(allOnes, key), key))
        // Max positive
        assertEquals(0x7FFFFFFF, KeeLoqCipher.decrypt(KeeLoqCipher.encrypt(0x7FFFFFFF, key), key))
        // Min negative
        assertEquals(0x80000000.toInt(), KeeLoqCipher.decrypt(KeeLoqCipher.encrypt(0x80000000.toInt(), key), key))
    }

    @Test
    fun decryptRoundsZeroRoundsReturnsDataUnchanged() {
        val key = 0x5CEC6701B79FD949L
        val data = 0x12345678
        // decryptRounds with 0 rounds should not transform the data
        assertEquals(data, KeeLoqCipher.decryptRounds(data, key, 0))
    }

    @Test
    fun decryptRoundsPartialRoundsBehavior() {
        val key = 0x5CEC6701B79FD949L
        val data = 0xF741E2DB.toInt()
        // AERF uses 64 rounds; just verify it's deterministic and different from 0/full in general
        val r64 = KeeLoqCipher.decryptRounds(data, key, 64)
        val r0 = KeeLoqCipher.decryptRounds(data, key, 0)
        val r528 = KeeLoqCipher.decrypt(data, key)
        assertEquals(data, r0)
        assertEquals(r64, KeeLoqCipher.decryptRounds(data, key, 64))
        // Different round counts should generally produce different results
        assertEquals(false, r64 == r528)
    }
}
