package com.eelab.rflab.bluetooth

import android.bluetooth.BluetoothSocket
import com.eelab.rflab.usb.RfLabTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [RfLabTransport] over a connected [BluetoothSocket] (Bluetooth Classic SPP).
 *
 * The ESP32 build of the lab board speaks the exact same COBS + CRC-16 packet
 * protocol as the STM32 over RFCOMM, so only the byte stream differs: this
 * class adapts a socket's streams to the contract [RfLabTransport] defines,
 * and everything above it — framing, reassembly, write serialisation,
 * liveness flows — is the unchanged [com.eelab.rflab.usb.RfLabConnection].
 *
 * Sockets are created, cancelled and connected by [AndroidBluetoothLink];
 * this transport only takes over an *already connected* socket, mirroring
 * how [com.eelab.rflab.usb.UsbSerialTransport] wraps an already-open port.
 *
 * @property socket a connected SPP socket; see
 *   [AndroidBluetoothLink.openTransport].
 */
class BluetoothSerialTransport(
    private val socket: BluetoothSocket,
) : RfLabTransport {

    // The streams are read once so every call goes through the same objects;
    // BluetoothSocket caches them internally, but keeping them here makes the
    // transport's dependency explicit and cheap to exercise in tests.
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    /**
     * Blocking read into [buffer].
     *
     * Unlike USB, RFCOMM has no read timeout: this parks until bytes arrive,
     * the peer closes the stream, or [close] closes the socket (which is what
     * makes the connection's read loop cancellation-responsive). A return of
     * `-1` is EOF — the peer went away without an error — so it is reported
     * as an [IOException], which makes [com.eelab.rflab.usb.RfLabConnection]
     * emit [com.eelab.rflab.usb.TransportEvent.ConnectionLost] exactly like a
     * reset USB link.
     *
     * @throws IOException if the socket is closed or the peer disconnects.
     */
    override fun read(buffer: ByteArray): Int {
        val bytesRead = input.read(buffer)
        if (bytesRead < 0) {
            throw IOException("Bluetooth socket closed by peer (EOF)")
        }
        return bytesRead
    }

    /** Writes [bytes] and flushes, so the board sees the command immediately. */
    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** Closes the socket, which also unblocks an in-flight [read]. */
    override fun close() {
        // Closing an already-closed socket is harmless on Android, but a
        // driver-level failure must not escape a shutdown path.
        runCatching { socket.close() }
    }
}
