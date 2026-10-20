package com.eelab.rflab.usb

import com.eelab.rflab.protocol.Packet
import com.eelab.rflab.protocol.PacketDecodeResult

/**
 * Something that happened on the USB transport, emitted by
 * [PacketReassembler] and [RfLabConnection].
 *
 * Decode failures are first-class events rather than being logged and
 * dropped: the device stream is binary and a desynchronised or noisy link
 * must be visible to the UI (and to later sessions' integration checks).
 */
sealed interface TransportEvent {

    /** A complete, CRC-valid packet was received and decoded. */
    data class PacketReceived(val packet: Packet) : TransportEvent

    /**
     * A delimited frame arrived but could not be turned into a [Packet].
     *
     * @property failure which stage rejected the frame.
     * @property wireBody the COBS-encoded body exactly as received, with the
     *   trailing `0x00` delimiter stripped. Running [com.eelab.rflab.protocol.Cobs.decode]
     *   on it reproduces [decodedBody] (or the COBS failure).
     * @property decodedBody the post-COBS packet bytes, when COBS decoding
     *   succeeded. Only filled for [DecodeFailure.PacketRejected], so callers
     *   can inspect the bad CRC/type/version bytes. `null` otherwise.
     */
    class DecodeError(
        val failure: DecodeFailure,
        val wireBody: ByteArray,
        val decodedBody: ByteArray? = null,
    ) : TransportEvent {

        // ByteArray properties use identity equality in a data class, which
        // would make two identical errors compare unequal. Compare contents
        // instead so tests and event deduplication behave predictably.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodeError) return false
            return failure == other.failure &&
                wireBody.contentEquals(other.wireBody) &&
                (decodedBody?.contentEquals(other.decodedBody) ?: (other.decodedBody == null))
        }

        override fun hashCode(): Int {
            var result = failure.hashCode()
            result = 31 * result + wireBody.contentHashCode()
            result = 31 * result + (decodedBody?.contentHashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "DecodeError(failure=$failure, wireBody=${wireBody.toHex()}, " +
                "decodedBody=${decodedBody?.toHex()})"
    }

    /**
     * The background read loop stopped because of an I/O error (device
     * unplugged, connection reset). Not emitted for a deliberate [java.io.Closeable.close].
     */
    data class ConnectionLost(val cause: Throwable?) : TransportEvent
}

/** Why a delimited frame was not delivered as a [Packet]. */
sealed interface DecodeFailure {

    /**
     * The body was not valid COBS: a code byte overran the body or a group
     * was truncated. The wire stream is desynchronised or corrupted.
     */
    data class InvalidCobsBody(
        val reason: String,
        val cause: IllegalArgumentException,
    ) : DecodeFailure

    /**
     * COBS decoding succeeded, but [com.eelab.rflab.protocol.PacketCodec]
     * rejected the packet (bad CRC, unknown type, wrong length, ...).
     */
    data class PacketRejected(val result: PacketDecodeResult.Failure) : DecodeFailure

    /**
     * No `0x00` delimiter appeared within [limitBytes], so the bytes were
     * discarded up to that point instead of growing the buffer without
     * bound. The largest legal encoded packet from the firmware is ~536
     * bytes; see [PacketReassembler.DEFAULT_MAX_FRAME_BYTES].
     */
    data class FrameTooLong(
        val limitBytes: Int,
        val actualBytes: Int,
    ) : DecodeFailure
}

private fun ByteArray.toHex(): String {
    if (isEmpty()) return ""
    val out = StringBuilder(size * 2)
    for (byte in this) {
        out.append(HEX[(byte.toInt() ushr 4) and 0xF])
        out.append(HEX[byte.toInt() and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
