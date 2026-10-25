package com.eelab.rflab.protocol

/**
 * Consistent Overhead Byte Stuffing (COBS), ported from
 * `transport_cobs_encode` / `transport_cobs_decode` in
 * `firmware/src/transport.c`.
 *
 * The encoded *body* produced by [encode] and consumed by [decode] does not
 * include the trailing `0x00` byte delimiter used on the wire. That delimiter
 * is a transport-layer concern and is appended/stripped by the packet
 * reassembler, not by this codec. Likewise, [encode] never emits a `0x00`
 * byte -- that is the whole point of COBS.
 *
 * Both functions implement the general algorithm: runs of 254 non-zero bytes
 * are split with a `0xFF` code byte, and embedded zero bytes become group
 * boundaries. There is no small-packet shortcut or maximum input length.
 */
object Cobs {

    /** A code byte of 0xFF marks a full group of 254 data bytes. */
    private const val MAX_CODE = 0xFF

    /** Maximum number of non-zero data bytes in one group. */
    private const val MAX_GROUP = MAX_CODE - 1

    /**
     * COBS-encodes [input], returning the encoded body without a trailing
     * `0x00` delimiter.
     *
     * An empty input encodes to a single `0x01` code byte, matching the
     * firmware encoder (whose caller then appends the delimiter).
     */
    fun encode(input: ByteArray): ByteArray {
        // Worst case per standard COBS: one code byte per group plus one for
        // the final (possibly empty) group.
        val output = ByteArray(input.size + input.size / MAX_GROUP + 1)

        var readIndex = 0
        var writeIndex = 1
        var codeIndex = 0
        var code = 1

        while (readIndex < input.size) {
            if (input[readIndex] == 0.toByte()) {
                // Close the current group at the embedded zero.
                output[codeIndex] = code.toByte()
                code = 1
                codeIndex = writeIndex
                writeIndex++
                readIndex++
            } else {
                output[writeIndex++] = input[readIndex++]
                code++
                if (code == MAX_CODE) {
                    // Full group: emit the 0xFF code and start a new group
                    // immediately, without an implicit zero byte.
                    output[codeIndex] = code.toByte()
                    code = 1
                    codeIndex = writeIndex
                    writeIndex++
                }
            }
        }

        output[codeIndex] = code.toByte()
        return output.copyOf(writeIndex)
    }

    /**
     * Decodes a COBS body produced by [encode].
     *
     * @throws IllegalArgumentException if [input] is malformed: an embedded
     *   `0x00` byte (which a COBS body can never contain, including the
     *   transport delimiter), a code byte that overruns the input, or a
     *   truncated group.
     *
     * The firmware decoder signals these cases by returning 0. Kotlin cannot
     * distinguish that from a legitimately empty decode, so this port throws
     * instead; the accepted/rejected decisions are otherwise identical.
     */
    fun decode(input: ByteArray): ByteArray {
        // A valid COBS body decodes to strictly fewer bytes than it occupies.
        val output = ByteArray(input.size)

        var readIndex = 0
        var writeIndex = 0

        while (readIndex < input.size) {
            val code = input[readIndex].toInt() and 0xFF
            if (code == 0 || readIndex + code > input.size + 1) {
                throw IllegalArgumentException(
                    "Invalid COBS code byte $code at index $readIndex",
                )
            }
            readIndex++

            for (i in 1 until code) {
                if (readIndex >= input.size) {
                    throw IllegalArgumentException(
                        "Truncated COBS group at index ${input.size}",
                    )
                }
                output[writeIndex++] = input[readIndex++]
            }

            // A 0xFF group is followed immediately by the next code byte; any
            // other group that is not the final one represents a zero byte.
            if (code != MAX_CODE && readIndex < input.size) {
                output[writeIndex++] = 0.toByte()
            }
        }

        return output.copyOf(writeIndex)
    }
}
