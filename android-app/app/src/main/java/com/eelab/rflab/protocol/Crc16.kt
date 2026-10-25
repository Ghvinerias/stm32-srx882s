package com.eelab.rflab.protocol

/**
 * CRC-16/CCITT-FALSE, ported from `transport_crc16` in
 * `firmware/src/transport.c`.
 *
 * Parameters: initial value `0xFFFF`, polynomial `0x1021`, MSB-first, no
 * input/output reflection and no final XOR. Every packet in the wire
 * protocol carries this CRC (little-endian) over all preceding bytes.
 */
object Crc16 {

    private const val INITIAL = 0xFFFF
    private const val POLYNOMIAL = 0x1021
    private const val TOP_BIT = 0x8000
    private const val MASK = 0xFFFF

    /**
     * Computes the CRC-16 over [data], returning a value in `0x0000..0xFFFF`.
     */
    fun compute(data: ByteArray): Int {
        var crc = INITIAL
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and TOP_BIT != 0) {
                    (crc shl 1) xor POLYNOMIAL
                } else {
                    crc shl 1
                }
                // The C implementation is uint16_t, so each shift truncates.
                crc = crc and MASK
            }
        }
        return crc
    }
}
