package com.eelab.rflab.usb

import java.io.Closeable

/**
 * A bidirectional byte stream to the RF lab board.
 *
 * [RfLabConnection] owns the protocol stack — [PacketReassembler], COBS
 * framing, the write mutex, the [TransportEvent] flow — and knows nothing
 * about *how* bytes reach the board. Everything transport-specific lives
 * behind this seam, so the same connection logic drives USB CDC-ACM today
 * and RFCOMM (Bluetooth SPP) in a later session.
 *
 * Implementations must be safe to call from the connection's read loop and
 * write path concurrently: [read] runs on its own coroutine while [write]
 * and [close] may run on other threads. [close] must unblock an in-flight
 * [read] so shutdown does not wait for the next timeout.
 *
 * @see UsbSerialTransport the USB CDC-ACM implementation.
 */
interface RfLabTransport : Closeable {

    /**
     * Blocking read into [buffer].
     *
     * Returns the number of bytes written into [buffer]. `0` means "nothing
     * arrived this round" — the caller keeps looping, which is what keeps the
     * read loop responsive to cancellation; it is not an error and not EOF.
     * A dead link is reported by throwing [java.io.IOException].
     */
    fun read(buffer: ByteArray): Int

    /**
     * Blocking write of all [bytes]; throws [java.io.IOException] on failure.
     *
     * Callers serialise writes themselves (see `RfLabConnection.writeMutex`),
     * so an implementation need not guard against overlapping transfers.
     */
    fun write(bytes: ByteArray)

    /**
     * Releases the underlying link. Idempotent and safe to call from any
     * thread; must not throw for an already-closed link.
     */
    override fun close()
}
