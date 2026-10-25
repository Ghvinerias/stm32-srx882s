package com.eelab.rflab.crypto

import com.eelab.rflab.decode.KeeLoqCipher

/**
 * Manufacturer key-derivation ("learning") formulas: turn a manufacturer
 * master key plus a device's serial number (and, for some schemes, a seed
 * read off the device) into that device's own 64-bit KeeLoq key.
 *
 * These are published, widely-reimplemented algorithms (ported line-for-line
 * from `keeloq_common.c` in DarkFlippers/unleashed-firmware, itself a
 * reimplementation of the Microchip HCS20x/30x provisioning scheme). This
 * file contributes no secret: every function takes the manufacturer key as
 * a caller-supplied parameter, exactly like [KeeLoqCipher.encrypt] takes a
 * device key. It ships no manufacturer-key dictionary and does not look one
 * up — the caller must already hold the manufacturer key for the system
 * they are provisioning or servicing.
 *
 * Unlike [KeeLoqCipher]/[KeeLoqHop] (which only need a key for a device the
 * user already identified), a serial number travels in the clear in every
 * over-the-air frame. That means holding a manufacturer key turns *any*
 * captured serial into that device's real key. Use this only for systems
 * you are authorized to provision or service (e.g. as the installer/dealer
 * holding that manufacturer's key) — not for devices you've merely captured
 * a signal from.
 */
object KeeLoqLearning {

    private const val AERF_DECRYPT_ROUNDS = 0x40

    private fun Int.toU32(): Long = this.toLong() and 0xFFFFFFFFL

    private fun bswap32(x: Int): Int =
        ((x and 0xFF) shl 24) or
            (((x ushr 8) and 0xFF) shl 16) or
            (((x ushr 16) and 0xFF) shl 8) or
            ((x ushr 24) and 0xFF)

    private fun wordRotate16(v: Int): Int = (v ushr 16) or (v shl 16)

    /** `b[0]` is the key's least-significant byte, `b[7]` the most significant. */
    private fun bytesToKey(b: IntArray): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[i].toLong() and 0xFFL)
        }
        return v
    }

    /**
     * Normal learning: the device key is the 28-bit [serial] decrypted
     * twice under the manufacturer [key], once with a `0x2` prefix and once
     * with a `0x6` prefix, concatenated.
     */
    fun normalLearning(serial: Int, key: Long): Long {
        val base = serial and 0x0FFFFFFF
        val k1 = KeeLoqCipher.decrypt(base or 0x20000000, key)
        val k2 = KeeLoqCipher.decrypt(base or 0x60000000, key)
        return (k2.toU32() shl 32) or k1.toU32()
    }

    /**
     * Secure learning: like [normalLearning] but the second half comes from
     * decrypting a device-supplied [seed] instead of a fixed-prefix serial.
     */
    fun secureLearning(serial: Int, seed: Int, key: Long): Long {
        val k1 = KeeLoqCipher.decrypt(serial and 0x0FFFFFFF, key)
        val k2 = KeeLoqCipher.decrypt(seed, key)
        return (k1.toU32() shl 32) or k2.toU32()
    }

    /**
     * XOR-type-1 learning: the device key is the 28-bit [serial] duplicated
     * into both halves of a 64-bit word, XORed with a magic constant
     * ([magicXor]) that stands in for a manufacturer key for this scheme.
     */
    fun magicXorType1Learning(serial: Int, magicXor: Long): Long {
        val d = (serial and 0x0FFFFFFF).toU32()
        return ((d shl 32) or d) xor magicXor
    }

    /** FAAC SLH (Spa) learning: encrypts the [seed] and a fixed-ending word derived from it. */
    fun faacLearning(seed: Int, key: Long): Long {
        val hs = (seed ushr 16) and 0xFFFF
        val ending = 0x544D
        val lsb = (hs shl 16) or ending
        val hi = KeeLoqCipher.encrypt(seed, key).toU32()
        val lo = KeeLoqCipher.encrypt(lsb, key).toU32()
        return (hi shl 32) or lo
    }

    /**
     * Magic-serial-type-1 learning: the low 32 bits of [magicMan] are kept
     * as-is; the high 32 bits are built from the raw [serial] and a
     * byte-sum checksum of its low two bytes.
     */
    fun magicSerialType1Learning(serial: Int, magicMan: Long): Long {
        val checksum = (((serial and 0xFF) + ((serial ushr 8) and 0xFF)) and 0xFF).toLong()
        return (magicMan and 0xFFFFFFFFL) or (serial.toU32() shl 40) or (checksum shl 32)
    }

    /**
     * Magic-serial-type-2 learning: the low 32 bits of [magicMan] are kept
     * as-is; the high 32 bits become [data] (button + serial) with its
     * bytes reversed.
     */
    fun magicSerialType2Learning(data: Int, magicMan: Long): Long {
        val hi = bswap32(data).toU32()
        return (hi shl 32) or (magicMan and 0xFFFFFFFFL)
    }

    /**
     * Magic-serial-type-3 learning: the high 40 bits of [magicMan] are kept
     * as-is; the low 24 bits become the low 24 bits of [serial].
     */
    fun magicSerialType3Learning(serial: Int, magicMan: Long): Long {
        return (magicMan and 0xFFFFFFL.inv()) or (serial.toLong() and 0xFFFFFFL)
    }

    /**
     * AERF learning: like [normalLearning], but each half is decrypted with
     * only [AERF_DECRYPT_ROUNDS] rounds instead of the full 528, and the
     * halves are *not* swapped (the `0x2`-prefixed result is the high word).
     */
    fun learningAerf(serial: Int, key: Long): Long {
        val d = serial and 0x0FFFFFFF
        val k1 = KeeLoqCipher.decryptRounds(d or 0x20000000, key, AERF_DECRYPT_ROUNDS)
        val k2 = KeeLoqCipher.decryptRounds(d or 0x60000000, key, AERF_DECRYPT_ROUNDS)
        return (k1.toU32() shl 32) or k2.toU32()
    }

    /**
     * Erreka learning: the low word is a plain [normalLearning]-style
     * decrypt of the serial; the high word decrypts a `0x6`-prefixed word
     * built by interleaving nibbles/bytes of a device-supplied [mix] value.
     */
    fun learningErreka(serial: Int, mix: Int, key: Long): Long {
        val d = serial and 0x0FFFFFFF
        val k1 = KeeLoqCipher.decrypt(d or 0x20000000, key)

        val mixL = mix.toLong() and 0xFFFFFFFFL
        val r1 = (mixL shl 4) and 0xF000F000L
        val r4 = ((mixL ushr 4) and 0x0F000F00L) or r1
        val r5 = mixL and 0x00FF00FFL
        val x = ((r4 or r5) and 0xFFFFFFFFL) or 0x60000000L
        val k2 = KeeLoqCipher.decrypt(x.toInt(), key)

        return (k2.toU32() shl 32) or k1.toU32()
    }

    /** Pujol learning: like [normalLearning], but each decrypted half is then 16-bit-rotated. */
    fun learningPujol(serial: Int, key: Long): Long {
        val d = serial and 0x0FFFFFFF
        val w1 = KeeLoqCipher.decrypt(d or 0x20000000, key)
        val w2 = KeeLoqCipher.decrypt(d or 0x60000000, key)
        return (wordRotate16(w2).toU32() shl 32) or wordRotate16(w1).toU32()
    }

    /**
     * JCM GEN2 learning: rebuilds an intermediate key out of the
     * manufacturer [key], [data] (button + serial), and [btn], runs one
     * KeeLoq decryption with it, then shuffles the result's bytes into the
     * final device key.
     */
    fun learningJcmGen2(data: Int, btn: Int, key: Long): Long {
        val k = IntArray(8) { i -> ((key ushr (8 * i)) and 0xFFL).toInt() }

        val b0 = data and 0xFF
        val b1 = (data ushr 8) and 0xFF
        val hi = data ushr 16

        k[0] = (k[0] xor b0) and 0xFF
        k[6] = (k[6] xor ((((data shl 4) and 0xFF0) or (b0 ushr 4)) and 0xFF)) and 0xFF
        val mix = ((((btn shl 4) or (btn ushr 4)) and 0xFF) + k[2]) and 0xFF
        k[2] = mix
        k[5] = (k[5] - 1 - btn) and 0xFF
        k[4] = (k[4] xor b1) and 0xFF

        val k7 = k[7]
        val k4 = k[4]

        val seedByte0 = (btn xor ((((hi shl 4) and 0xFF0) or ((hi ushr 4) and 0x0F)) and 0xFF)) and 0xFF
        val seedByte1 = b0.inv() and 0xFF
        val seedByte2 = (b1 xor ((data ushr 24) and 0x0F)) and 0xFF
        val seedByte3 = ((((data ushr 4) and 0xFF0) or (b1 ushr 4))) and 0xFF
        val seed = seedByte0 or (seedByte1 shl 8) or (seedByte2 shl 16) or (seedByte3 shl 24)

        val d = KeeLoqCipher.decrypt(seed, bytesToKey(k))

        val o = IntArray(8)
        o[0] = (d ushr 24) and 0xFF
        o[1] = (((d and 0x0F) shl 4) or ((d ushr 4) and 0x0F)) and 0xFF
        o[2] = (mix + ((((d ushr 4) and 0xFF0) or ((d ushr 12) and 0x0F)) and 0xFF)) and 0xFF
        o[3] = (d ushr 8) and 0xFF
        o[4] = (((d ushr 24) and 0xFF) + k4 + 1) and 0xFF
        o[5] = (d ushr 16) and 0xFF
        o[6] = d.inv() and 0xFF
        o[7] = (((d ushr 16) and 0xFF) + k7) and 0xFF

        return bytesToKey(o)
    }
}
