package com.eelab.rflab.espflash

import java.io.ByteArrayOutputStream

/**
 * SLIP framing for the ESP32-C3 ROM serial bootloader.
 *
 * The ROM loader speaks the same framing esptool uses on every UART/USB-CDC
 * transport (`ESPLoader.write` and `slip_reader` in `esptool/loader.py`):
 *
 * - every packet is wrapped in `0xC0` … `0xC0`;
 * - `0xDB` is the escape byte; `0xDB 0xDD` means a literal `0xDB` and
 *   `0xDB 0xDC` means a literal `0xC0` (all other bytes travel unchanged).
 *
 * The trailing `0xC0` is a packet delimiter, not part of the packet body.
 * This framing is unrelated to the lab's COBS transport used for packet
 * types 1–7; nothing here touches that protocol.
 */
object Slip {

    /** Frame delimiter (also the "start of packet" byte). */
    const val END: Int = 0xC0

    /** Escape marker introducing a two-byte sequence. */
    const val ESC: Int = 0xDB

    /** Second byte of `ESC END` — a literal [END] in the payload. */
    const val ESC_END: Int = 0xDC

    /** Second byte of `ESC ESC` — a literal [ESC] in the payload. */
    const val ESC_ESC: Int = 0xDD

    /**
     * Escapes and delimits [packet] exactly like esptool's `write`.
     *
     * `0xDB` is replaced first, then `0xC0`, which is why a replacement
     * cannot feed the next one (the same order as the Python code).
     */
    fun encode(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(packet.size + 2)
        out.write(END)
        for (byte in packet) {
            when (byte.toInt() and 0xFF) {
                ESC -> {
                    out.write(ESC)
                    out.write(ESC_ESC)
                }

                END -> {
                    out.write(ESC)
                    out.write(ESC_END)
                }

                else -> out.write(byte.toInt() and 0xFF)
            }
        }
        out.write(END)
        return out.toByteArray()
    }
}

/**
 * Incremental SLIP decoder.
 *
 * USB bulk reads split frames at arbitrary boundaries, so decoding has to be
 * stateful. Bytes before the first `0xC0` are discarded: the ROM may still be
 * emitting a reset banner or a stray byte when the host starts listening, and
 * esptool's reader would abort on that noise, but a flasher that has just
 * triggered a reset should resynchronise instead. Inside a frame an invalid
 * escape (`0xDB` followed by anything but `0xDD`/`0xDC`) is a hard protocol
 * error, matching the ROM's framing contract.
 *
 * Empty packets (two consecutive delimiters) are ignored: the ROM never sends
 * one, and esptool's command reader would discard them too.
 */
class SlipDecoder {

    private val packet = ByteArrayOutputStream(64)

    private var inPacket = false

    private var inEscape = false

    /**
     * Feeds [length] bytes from [bytes] starting at [offset] and returns every
     * complete packet decoded by this call, in wire order.
     *
     * @throws EspSlipException on an invalid escape sequence.
     */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "invalid slice: offset=$offset length=$length size=${bytes.size}"
        }
        val decoded = mutableListOf<ByteArray>()
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 0xFF
            when {
                // Waiting for a frame start: ignore noise until the delimiter.
                !inPacket -> if (value == Slip.END) {
                    inPacket = true
                    packet.reset()
                    inEscape = false
                }

                inEscape -> {
                    inEscape = false
                    when (value) {
                        Slip.ESC_END -> packet.write(Slip.END)
                        Slip.ESC_ESC -> packet.write(Slip.ESC)
                        else -> throw EspSlipException(
                            "invalid SLIP escape 0xDB 0x%02X".format(value),
                        )
                    }
                }

                value == Slip.ESC -> inEscape = true

                value == Slip.END -> {
                    // A delimiter both ends the current frame and starts the
                    // next one, so stay in the packet. Empty frames (two
                    // delimiters in a row) are simply not emitted.
                    if (packet.size() > 0) {
                        decoded += packet.toByteArray()
                    }
                    packet.reset()
                    inEscape = false
                    inPacket = true
                }

                else -> packet.write(value)
            }
        }
        return decoded
    }

    /** Resets all framing state, e.g. after the port was reopened. */
    fun reset() {
        packet.reset()
        inPacket = false
        inEscape = false
    }
}

/** Raised when the ROM's SLIP framing is not what the protocol expects. */
class EspSlipException(message: String) : Exception(message)
