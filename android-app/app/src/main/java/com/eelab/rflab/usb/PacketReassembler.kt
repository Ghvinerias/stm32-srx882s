package com.eelab.rflab.usb

import com.eelab.rflab.protocol.Cobs
import com.eelab.rflab.protocol.PacketCodec
import com.eelab.rflab.protocol.PacketDecodeResult

/**
 * Reassembles the device's byte stream into decoded packets.
 *
 * On the wire, every packet is a COBS-encoded body followed by a single
 * `0x00` delimiter (see `transport_cobs_encode` in
 * `firmware/src/transport.c`, whose encoder writes that trailing `0x00`).
 * A USB bulk read can split a packet at any byte, combine several packets,
 * or deliver only part of one, so this class buffers bytes until delimiters
 * arrive and then runs each body through [Cobs.decode] and
 * [PacketCodec.decode] -- those layers are reused unchanged, not
 * reimplemented here.
 *
 * This class is deliberately plain Kotlin: it has no Android or USB
 * dependencies, so it is fully unit-testable on the JVM. [RfLabConnection]
 * feeds it whatever each bulk read returns.
 *
 * ### Behaviour details
 *
 * - Empty bodies (a `0x00` immediately following another `0x00`) are
 *   ignored. A COBS body is never empty on the wire; those delimiters are
 *   just frame boundaries with nothing between them and carry no packet.
 * - An unterminated frame longer than [maxFrameBytes] is dropped and
 *   reported as [DecodeFailure.FrameTooLong]. This bounds the buffer when
 *   the stream is noise instead of packets. The reassembler resynchronises
 *   at the next delimiter.
 * - A body that fails COBS or packet decoding is reported as a
 *   [TransportEvent.DecodeError], and processing continues with the next
 *   body. Nothing is silently dropped.
 * - Bytes after the last delimiter stay buffered until the next [feed].
 *
 * Instances are not thread-safe; [RfLabConnection] calls [feed] from its
 * single read loop.
 */
class PacketReassembler(
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {

    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive: $maxFrameBytes" }
    }

    private var buffer = ByteArray(INITIAL_BUFFER_BYTES)

    /** Number of bytes held back because no delimiter has arrived yet. */
    var bufferedByteCount: Int = 0
        private set

    /** Drops any partially buffered frame. */
    fun reset() {
        bufferedByteCount = 0
        if (buffer.size > MAX_RETAINED_BUFFER_BYTES) {
            buffer = ByteArray(INITIAL_BUFFER_BYTES)
        }
    }

    /**
     * Appends [length] bytes from [chunk] (starting at [offset]) and returns
     * every event those bytes completed, in stream order.
     *
     * The returned list is usually empty (a packet is still incomplete),
     * has one element, or has several when a chunk contains multiple
     * packets.
     */
    fun feed(
        chunk: ByteArray,
        offset: Int = 0,
        length: Int = chunk.size - offset,
    ): List<TransportEvent> {
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(length >= 0) { "length must not be negative: $length" }
        require(offset + length <= chunk.size) {
            "offset $offset + length $length exceeds chunk size ${chunk.size}"
        }
        if (length == 0) return emptyList()

        append(chunk, offset, length)

        val events = ArrayList<TransportEvent>(1)
        var frameStart = 0
        var index = 0
        while (index < bufferedByteCount) {
            if (buffer[index] == DELIMITER) {
                val bodyLength = index - frameStart
                if (bodyLength > 0) {
                    if (bodyLength > maxFrameBytes) {
                        events += tooLongEvent(frameStart, bodyLength)
                    } else {
                        decodeBody(frameStart, bodyLength, events)
                    }
                }
                frameStart = index + 1
            }
            index++
        }

        // Keep an incomplete frame buffered (it may finish in the next bulk
        // read), but never let a delimiter-less stream grow without bound.
        if (bufferedByteCount - frameStart > maxFrameBytes) {
            events += tooLongEvent(frameStart, bufferedByteCount - frameStart)
            frameStart = bufferedByteCount
        }

        compact(frameStart)
        return events
    }

    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        val required = bufferedByteCount + length
        if (required > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, required))
        }
        System.arraycopy(chunk, offset, buffer, bufferedByteCount, length)
        bufferedByteCount = required
    }

    private fun decodeBody(start: Int, length: Int, out: MutableList<TransportEvent>) {
        // Copy before compacting, since the decode result outlives this call.
        val wireBody = buffer.copyOfRange(start, start + length)

        val raw = try {
            Cobs.decode(wireBody)
        } catch (e: IllegalArgumentException) {
            out += TransportEvent.DecodeError(
                DecodeFailure.InvalidCobsBody(
                    e.message ?: "invalid COBS body",
                    e,
                ),
                wireBody,
            )
            return
        }

        when (val result = PacketCodec.decode(raw)) {
            is PacketDecodeResult.Success ->
                out += TransportEvent.PacketReceived(result.packet)

            is PacketDecodeResult.Failure ->
                out += TransportEvent.DecodeError(
                    DecodeFailure.PacketRejected(result),
                    wireBody,
                    raw,
                )
        }
    }

    private fun tooLongEvent(start: Int, actualLength: Int): TransportEvent.DecodeError {
        // Keep the event payload bounded: enough to diagnose the stream
        // without duplicating an arbitrarily large junk buffer.
        val prefixLength = minOf(actualLength, maxFrameBytes)
        return TransportEvent.DecodeError(
            DecodeFailure.FrameTooLong(maxFrameBytes, actualLength),
            buffer.copyOfRange(start, start + prefixLength),
        )
    }

    private fun compact(from: Int) {
        if (from == 0) return
        val remaining = bufferedByteCount - from
        if (remaining > 0) {
            System.arraycopy(buffer, from, buffer, 0, remaining)
        }
        bufferedByteCount = remaining
    }

    companion object {
        /** COBS wire delimiter appended after every encoded body. */
        const val DELIMITER: Byte = 0x00

        /**
         * Upper bound for one encoded body. The firmware's largest possible
         * raw packet is `18 + 256*2 + 2 = 532` bytes (FRAME with
         * `HW_CAPTURE_MAX_PULSES`), which encodes to at most ~536; 1024
         * leaves room without permitting unbounded growth.
         */
        const val DEFAULT_MAX_FRAME_BYTES: Int = 1024

        private const val INITIAL_BUFFER_BYTES = 256

        /** Free oversized backing arrays when a junk stream grew the buffer. */
        private const val MAX_RETAINED_BUFFER_BYTES = 8 * 1024
    }
}
