package com.eelab.rflab.decode

/**
 * The clear-text fields of one HCS301-shaped frame, as transmitted.
 *
 * This is deliberately *not* a decryption result: [hopping] is the 32-bit
 * hopping word exactly as received (still ciphertext for a rolling code),
 * matching what `tools/decode_hcs301.py` prints.
 *
 * Bit layout is LSB-first within every field:
 * - bits 0..31  -> [hopping] (32 bits)
 * - bits 32..59 -> [serial] (28 bits)
 * - bits 60..63 -> [button] (4 bits)
 * - bit 64      -> [vlow]
 * - bit 65      -> [repeat]
 *
 * @property hopping 32-bit hopping word as transmitted, `0x00000000..0xFFFFFFFF`.
 * @property serial 28-bit serial number, `0x0000000..0x0FFFFFFF`.
 * @property button button nibble, `0x0..0xF`.
 * @property vlow VLOW status bit (bit 64).
 * @property repeat repeat status bit (bit 65).
 * @property timestampUs device capture timestamp passed through from the
 *   source `FramePacket`, or 0 when decoding a bare pulse array.
 * @property sequence packet sequence number passed through from the source
 *   `FramePacket`, or null when decoding a bare pulse array.
 * @property scale timing scale used for the decode (1.0 when uncalibrated).
 */
data class DecodedSignal(
    val hopping: Long,
    val serial: Long,
    val button: Int,
    val vlow: Boolean,
    val repeat: Boolean,
    val timestampUs: Long = 0L,
    val sequence: Int? = null,
    val scale: Double = 1.0,
)
