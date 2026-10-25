package com.eelab.rflab.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * [RfLabTransport] over a claimed [UsbSerialPort] (USB CDC-ACM).
 *
 * Wraps one port plus the USB-specific transfer timeouts and line
 * parameters, so [RfLabConnection] stays transport-agnostic. Instances are
 * created by [open], which applies the lab board's serial configuration.
 *
 * @property port an already-opened and configured port; see [open].
 */
class UsbSerialTransport(
    private val port: UsbSerialPort,
) : RfLabTransport {

    /**
     * Reads whatever the port has, waiting at most [READ_TIMEOUT_MS].
     *
     * The timeout is short on purpose: a finite wait lets the connection's
     * read loop notice cancellation between transfers instead of parking in
     * a blocking bulk read forever. A timeout surfaces as `0` bytes, which
     * the loop treats as "keep going".
     */
    override fun read(buffer: ByteArray): Int = port.read(buffer, READ_TIMEOUT_MS)

    /** Writes [bytes], waiting at most [WRITE_TIMEOUT_MS] for the transfer. */
    override fun write(bytes: ByteArray) {
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    /** Releases the USB interface; see [UsbSerialPort.close]. */
    override fun close() {
        port.close()
    }

    companion object {
        /** Required by the API; irrelevant for a native USB CDC-ACM link. */
        const val BAUD_RATE = 115200

        private const val DATA_BITS = UsbSerialPort.DATABITS_8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE

        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 2_000

        /**
         * Opens [port] on [usbConnection] and applies the board's serial
         * parameters.
         *
         * Baud/format are set only because the CDC-ACM API expects them; the
         * link itself is a native USB bulk pipe. This does not catch
         * failures: [RfLabConnection.open] owns the cleanup of both the port
         * and the device connection on the way out.
         *
         * @throws java.io.IOException if the port cannot be opened or
         *   configured.
         */
        fun open(port: UsbSerialPort, usbConnection: UsbDeviceConnection): UsbSerialTransport {
            port.open(usbConnection)
            port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            return UsbSerialTransport(port)
        }
    }
}
