package com.eelab.rflab.protocol

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors cross-checked against the real `transport_crc16` in
 * `firmware/src/transport.c` (compiled into a host harness), plus the
 * standard CRC-16/CCITT-FALSE catalogue check value for "123456789".
 */
class Crc16Test {

    @Test
    fun emptyBufferIsInitialValue() {
        assertEquals(0xFFFF, Crc16.compute(ByteArray(0)))
    }

    @Test
    fun standardCcittFalseCheckValue() {
        // CRC-16/CCITT-FALSE ("123456789") = 0x29B1, the canonical check
        // value for init 0xFFFF / poly 0x1021 / no reflection / no final XOR.
        assertEquals(0x29B1, Crc16.compute("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun matchesFirmwareVectors() {
        assertEquals(0xE1F0, Crc16.compute(hex("00")))
        assertEquals(0xE3E0, Crc16.compute(hex("11")))
        assertEquals(0xCC9C, Crc16.compute(hex("000000")))
        assertEquals(0x0745, Crc16.compute(hex("11220033")))
        assertEquals(0xD592, Crc16.compute(hex("00010200")))
        assertEquals(0xC4F8, Crc16.compute(ByteArray(254) { 0xAA.toByte() }))
        assertEquals(0x682F, Crc16.compute(ByteArray(300)))
    }

    @Test
    fun resultAlwaysFitsIn16Bits() {
        val random = Random(0x1021)
        repeat(100) {
            val data = ByteArray(random.nextInt(64)) { random.nextInt(256).toByte() }
            val crc = Crc16.compute(data)
            assertTrue("crc $crc out of range", crc in 0..0xFFFF)
        }
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
