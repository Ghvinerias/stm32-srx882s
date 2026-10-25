package com.eelab.rflab.decode

/**
 * The KeeLoq block cipher: a 32-bit non-linear feedback shift register run
 * for 528 rounds under a 64-bit key. This is the public, textbook algorithm
 * (Microchip application notes; the same primitive implemented in many
 * open-source SubGHz stacks).
 *
 * This object is *only* the cipher. It does not know about manufacturer keys
 * or any per-brand key-derivation ("learning") scheme, and it bundles no key
 * material. A key is always supplied by the caller — see [KeyStore], which
 * holds keys the user has entered for systems they own. The cipher is used
 * for two things in this lab:
 *
 *  - **Diagnostics:** decrypt the 32-bit hop block of a captured frame with a
 *    key you hold, to read the counter/discriminator that a plain pulse
 *    decode cannot show.
 *  - **Own-system transmit:** encrypt a chosen counter into a hop block so the
 *    lab can act as a spare remote for a receiver you have the key to.
 *
 * Both directions operate on a key the user provides; the cipher creates no
 * ability to recover a key you do not already have.
 */
object KeeLoqCipher {

    /**
     * The KeeLoq non-linear function, as a 32-entry lookup packed into one
     * word: `NLF(b4,b3,b2,b1,b0) = bit (b4..b0) of this constant`.
     */
    private const val NLF = 0x3A5C742EL

    private fun bit(x: Long, n: Int): Int = ((x ushr n) and 1L).toInt()

    private fun nlfIndex(x: Long, b4: Int, b3: Int, b2: Int, b1: Int, b0: Int): Int =
        (bit(x, b4) shl 4) or (bit(x, b3) shl 3) or (bit(x, b2) shl 2) or
            (bit(x, b1) shl 1) or bit(x, b0)

    /**
     * Encrypt one 32-bit block under [key] (64-bit). Round `r` mixes in key
     * bit `r mod 64`; the feedback bit shifts in at the top.
     */
    fun encrypt(data: Int, key: Long): Int {
        var x = data.toLong() and 0xFFFFFFFFL
        for (r in 0 until 528) {
            val nlf = bit(NLF, nlfIndex(x, 31, 26, 20, 9, 1))
            val keyBit = ((key ushr (r and 63)) and 1L).toInt()
            val feedback = bit(x, 0) xor bit(x, 16) xor keyBit xor nlf
            x = (x ushr 1) or (feedback.toLong() shl 31)
        }
        return x.toInt()
    }

    /**
     * Decrypt one 32-bit block under [key]; the exact inverse of [encrypt].
     * Rounds run the key schedule in reverse (bit `(527 - r) mod 64`) and
     * shift the feedback bit in at the bottom.
     */
    fun decrypt(data: Int, key: Long): Int = decryptRounds(data, key, 528)

    /**
     * [decrypt], but stopping after [rounds] iterations instead of the full
     * 528. A few manufacturer-specific derivations (e.g. AERF) reuse this
     * exact round structure with a truncated count instead of the full
     * cipher.
     */
    fun decryptRounds(data: Int, key: Long, rounds: Int): Int {
        var x = data.toLong() and 0xFFFFFFFFL
        for (r in 0 until rounds) {
            val nlf = bit(NLF, nlfIndex(x, 30, 25, 19, 8, 0))
            val keyBit = ((key ushr ((527 - r) and 63)) and 1L).toInt()
            val feedback = bit(x, 31) xor bit(x, 15) xor keyBit xor nlf
            x = ((x shl 1) and 0xFFFFFFFFL) or feedback.toLong()
        }
        return x.toInt()
    }
}
