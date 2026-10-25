package com.eelab.rflab.espflash

import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * [RomByteStream] over an opened USB CDC-ACM port.
 *
 * The ROM loader is driven synchronously on an IO thread, so these calls are
 * plain blocking transfers, not coroutines:
 *
 * - [read] uses the caller's timeout directly. `UsbSerialPort.read` returns
 *   `0` when the timeout expires, which is exactly the "no data yet" contract
 *   of [RomByteStream.read].
 * - [write] waits up to [WRITE_TIMEOUT_MS] for the bulk transfer. A 0x400-byte
 *   `FLASH_DATA` packet is small, so the generous timeout only matters when
 *   the device has disappeared.
 * - [flushInput] drops anything already queued (the reset banner a chip emits
 *   after DTR/RTS toggling) before the first SYNC.
 * - [close] releases the claimed interface; an in-flight read is aborted by
 *   the USB stack, which is how cancellation unblocks the protocol layer.
 *
 * The same class exposes the control lines used by [UsbJtagReset], so the
 * reset sequence and the protocol share one opened port and the port is not
 * reopened between them.
 */
class UsbSerialRomByteStream(
    private val port: UsbSerialPort,
) : RomByteStream, SerialControlLines {

    override fun write(bytes: ByteArray) {
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int =
        port.read(buffer, timeoutMs.coerceAtLeast(1))

    override fun flushInput() {
        runCatching { port.purgeHwBuffers(true, false) }
    }

    override fun close() {
        runCatching { port.close() }
    }

    override fun setDtr(asserted: Boolean) {
        port.setDTR(asserted)
    }

    override fun setRts(asserted: Boolean) {
        port.setRTS(asserted)
    }

    companion object {
        /** Required by the USB-CDC API; irrelevant for a native USB link. */
        const val BAUD_RATE: Int = 115_200

        private const val WRITE_TIMEOUT_MS = 5_000

        /** Applies the standard 8N1 parameters the CDC-ACM API expects. */
        fun configure(port: UsbSerialPort) {
            port.setParameters(
                BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
        }
    }
}
