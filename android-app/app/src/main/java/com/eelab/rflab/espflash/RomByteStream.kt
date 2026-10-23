package com.eelab.rflab.espflash

import java.io.Closeable

/**
 * The byte pipe between the ROM loader protocol and a transport.
 *
 * This is deliberately tiny and synchronous so the entire ROM protocol can
 * be exercised on the JVM against a simulated bootloader (see the tests'
 * `FakeEspRom`). The production implementation wraps a
 * `UsbSerialPort` (`UsbSerialRomByteStream`); the writes carry a complete
 * SLIP frame produced by [Slip.encode].
 */
interface RomByteStream : Closeable {

    /** Writes all of [bytes]; throws [java.io.IOException] on failure. */
    fun write(bytes: ByteArray)

    /**
     * Blocking read into [buffer], waiting at most [timeoutMs].
     *
     * Returns the number of bytes read, or `0` when the timeout expired
     * without data. `0` is not an error: the protocol layer polls so it can
     * enforce its own deadlines and notice cancellation.
     */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /**
     * Discards anything already queued by the transport.
     *
     * Used once before the first SYNC, so reset-banner bytes cannot be
     * mistaken for a response. Implementations without a purge operation may
     * no-op.
     */
    fun flushInput()

    /** Releases the transport. Must unblock an in-flight [read]. */
    override fun close()
}
