package com.eelab.rflab.espflash

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SLIP framing tests for the ROM loader transport.
 *
 * The encoder must match esptool's `ESPLoader.write` byte for byte and the
 * decoder must survive the arbitrary chunk boundaries a USB bulk pipe
 * produces.
 */
class SlipTest {

    private val c0 = 0xC0.toByte()
    private val db = 0xDB.toByte()

    // ---- Encoding ---------------------------------------------------------

    @Test
    fun `wraps a plain packet in delimiters without escaping`() {
        val encoded = Slip.encode(byteArrayOf(0x00, 0x01, 0x02, 0x7F))

        assertArrayEquals(
            byteArrayOf(c0, 0x00, 0x01, 0x02, 0x7F, c0),
            encoded,
        )
    }

    @Test
    fun `escapes a literal end byte`() {
        assertArrayEquals(
            byteArrayOf(c0, db, 0xDC.toByte(), c0),
            Slip.encode(byteArrayOf(0xC0.toByte())),
        )
    }

    @Test
    fun `escapes a literal escape byte`() {
        assertArrayEquals(
            byteArrayOf(c0, db, 0xDD.toByte(), c0),
            Slip.encode(byteArrayOf(0xDB.toByte())),
        )
    }

    @Test
    fun `escapes runs of both special bytes independently`() {
        val encoded = Slip.encode(byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xC0.toByte()))

        assertArrayEquals(
            byteArrayOf(c0, db, 0xDC.toByte(), db, 0xDD.toByte(), db, 0xDC.toByte(), c0),
            encoded,
        )
    }

    @Test
    fun `encodes an empty packet as two delimiters`() {
        assertArrayEquals(byteArrayOf(c0, c0), Slip.encode(ByteArray(0)))
    }

    // ---- Decoding ---------------------------------------------------------

    @Test
    fun `decodes a single frame`() {
        val decoder = SlipDecoder()

        val frames = decoder.feed(Slip.encode(byteArrayOf(0x11, 0x22, 0x33)))

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), frames[0])
    }

    @Test
    fun `decodes two frames delivered in one chunk`() {
        val decoder = SlipDecoder()
        val wire = Slip.encode(byteArrayOf(0x01)) + Slip.encode(byteArrayOf(0x02))

        val frames = decoder.feed(wire)

        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(0x01), frames[0])
        assertArrayEquals(byteArrayOf(0x02), frames[1])
    }

    @Test
    fun `decodes a frame split one byte at a time`() {
        val decoder = SlipDecoder()
        val payload = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0x02, 0x03)
        val wire = Slip.encode(payload)

        val frames = mutableListOf<ByteArray>()
        for (byte in wire) {
            frames += decoder.feed(byteArrayOf(byte))
        }

        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test
    fun `round trips all 256 byte values across random chunk boundaries`() {
        val payload = ByteArray(512) { (it % 256).toByte() }
        val wire = Slip.encode(payload)
        val decoder = SlipDecoder()
        val random = Random(0x5259)

        val frames = mutableListOf<ByteArray>()
        var index = 0
        while (index < wire.size) {
            val chunk = 1 + random.nextInt(17)
            val end = minOf(index + chunk, wire.size)
            frames += decoder.feed(wire.copyOfRange(index, end))
            index = end
        }

        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test
    fun `drops noise before the first delimiter`() {
        val decoder = SlipDecoder()
        val wire = byteArrayOf(0x00, 0x7F, 0x55.toByte()) +
            Slip.encode(byteArrayOf(0x42))

        val frames = decoder.feed(wire)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x42), frames[0])
    }

    @Test
    fun `ignores empty packets between delimiters`() {
        val decoder = SlipDecoder()
        val wire = byteArrayOf(c0, c0, c0) + Slip.encode(byteArrayOf(0x42))

        val frames = decoder.feed(wire)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x42), frames[0])
    }

    @Test
    fun `throws on an invalid escape`() {
        val decoder = SlipDecoder()
        val wire = byteArrayOf(c0, 0x01, db, 0x00)

        val error = runCatching { decoder.feed(wire) }.exceptionOrNull()

        assertTrue("expected EspSlipException, got $error", error is EspSlipException)
    }

    @Test
    fun `reset clears a partially received frame`() {
        val decoder = SlipDecoder()
        val wire = Slip.encode(byteArrayOf(0x01, 0x02, 0x03))

        assertTrue(decoder.feed(wire.copyOfRange(0, 3)).isEmpty())
        decoder.reset()
        val frames = decoder.feed(Slip.encode(byteArrayOf(0x09)))

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x09), frames[0])
    }

    @Test
    fun `decodes an escape pair split across feeds`() {
        val decoder = SlipDecoder()

        assertTrue(decoder.feed(byteArrayOf(c0, db)).isEmpty())
        val frames = decoder.feed(byteArrayOf(0xDC.toByte(), c0))

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0xC0.toByte()), frames[0])
    }
}
