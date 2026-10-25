package com.eelab.rflab.crypto

import com.eelab.rflab.decode.KeeLoqCipher

/**
 * The plaintext fields carried in an HCS301 encrypted hop block, once
 * decrypted with a key the user holds.
 *
 * HCS301 packs the 32-bit encrypted word as (MSB first): a 4-bit button
 * nibble, a 2-bit overflow field, a 10-bit discrimination value, and a 16-bit
 * synchronisation counter. The counter is what advances every press; the
 * button nibble here should equal the button sent in the clear, which is the
 * cross-check used by [matchesClearButton].
 */
data class HopInfo(
    val decrypted: Int,
    val counter: Int,
    val discrimination: Int,
    val overflow: Int,
    val buttonInHop: Int,
) {
    /**
     * True when the button encoded inside the decrypted hop equals the button
     * sent in the clear part of the same frame. A correct key for this remote
     * makes these agree; a wrong key makes them agree only ~1/16 of the time,
     * so this is the practical "is this the right key for this remote" signal.
     */
    fun matchesClearButton(clearButton: Int): Boolean =
        buttonInHop == (clearButton and 0xF)
}

/**
 * Decrypt/encrypt helpers for the HCS301 hop block, operating only on a key
 * the caller supplies. This is diagnostics for frames from a remote you own,
 * and hop construction for transmitting to a receiver you have the key to.
 */
object KeeLoqHop {

    /**
     * Decrypt the 32-bit [hop] block of a captured frame with [key] and split
     * out the HCS301 fields. The [hop] is the `hopping` value a frame decode
     * already produced.
     */
    fun decrypt(hop: Int, key: Long): HopInfo {
        val plain = KeeLoqCipher.decrypt(hop, key)
        return HopInfo(
            decrypted = plain,
            counter = plain and 0xFFFF,
            discrimination = (plain ushr 16) and 0x3FF,
            overflow = (plain ushr 26) and 0x3,
            buttonInHop = (plain ushr 28) and 0xF,
        )
    }

    /**
     * Build an encrypted hop block for transmitting to a receiver you hold
     * [key] for: pack the fields into the HCS301 plaintext layout and encrypt.
     *
     * @param discrimination 10-bit value; HCS301 remotes conventionally use
     *   the low 10 bits of the serial number. Masked to 10 bits.
     * @param overflow 2-bit field, normally 0.
     */
    fun build(
        key: Long,
        button: Int,
        discrimination: Int,
        counter: Int,
        overflow: Int = 0,
    ): Int {
        val plain = ((button and 0xF) shl 28) or
            ((overflow and 0x3) shl 26) or
            ((discrimination and 0x3FF) shl 16) or
            (counter and 0xFFFF)
        return KeeLoqCipher.encrypt(plain, key)
    }

    /** The conventional HCS301 discrimination value: the serial's low 10 bits. */
    fun discriminationFromSerial(serial: Long): Int = (serial and 0x3FF).toInt()
}
