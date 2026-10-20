package com.eelab.rflab.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.eelab.rflab.protocol.Cobs
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One open USB serial connection to the STM32 RF lab board.
 *
 * Owns a [UsbSerialPort] claimed via usb-serial-for-android's
 * `CdcAcmSerialDriver` plus a background read coroutine that lives as long
 * as the connection. Raw device bytes go through [PacketReassembler], which
 * splits on the `0x00` delimiter and decodes packets; results are published
 * on [events].
 *
 * This class is the thin Android-facing shell around the protocol layer:
 * everything that can be tested without a device lives in
 * [PacketReassembler], [com.eelab.rflab.protocol.Cobs] and
 * [com.eelab.rflab.protocol.PacketCodec]. Opening, claiming, and reading
 * require a real USB host and are not unit-tested (see the session 4 notes
 * in `README.md`).
 *
 * @see UsbDeviceFinder for locating the board.
 * @see UsbPermission for the permission flow that must succeed first.
 */
class RfLabConnection private constructor(
    private val port: UsbSerialPort,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Serialises writes: USB transfers must not overlap between coroutines. */
    private val writeMutex = Mutex()

    /** False once the link has failed or [close] has started. */
    private val open = AtomicBoolean(true)

    /** Set once shutdown has run, so [close] and the read loop agree on who cleans up. */
    private val closed = AtomicBoolean(false)

    // No replay: a new collector sees packets from the moment it subscribes.
    // The buffer applies backpressure to the read loop instead of silently
    // dropping packets under a slow consumer.
    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )

    /**
     * Decoded packets and transport failures from the background reader.
     *
     * Subscribe before opening the connection if every packet matters;
     * values emitted while nobody is collecting are not retained.
     */
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    /** True while the port is open and the reader has not failed. */
    val isOpen: Boolean
        get() = open.get() && !closed.get()

    init {
        scope.launch { readLoop() }
    }

    /**
     * Writes [bytes] to the device unchanged.
     *
     * Later sessions use this for already COBS-encoded, delimiter-terminated
     * command frames; [sendPacket] is the convenience wrapper that adds that
     * framing. Writes are serialised so concurrent callers cannot interleave
     * partial USB transfers.
     *
     * @throws IOException if the connection is closed or the transfer fails.
     */
    suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        writeMutex.withLock {
            ensureOpen()
            withContext(ioDispatcher) {
                port.write(bytes, WRITE_TIMEOUT_MS)
            }
        }
    }

    /**
     * COBS-encodes [rawPacket] (a packet from
     * [com.eelab.rflab.protocol.PacketCodec.encodeTxCommand] or
     * [com.eelab.rflab.protocol.PacketCodec.encodeScenarioCommand]),
     * appends the `0x00` delimiter, and writes it.
     */
    suspend fun sendPacket(rawPacket: ByteArray) {
        require(rawPacket.isNotEmpty()) { "rawPacket must not be empty" }
        write(Cobs.encode(rawPacket) + PacketReassembler.DELIMITER)
    }

    /**
     * Stops the read loop and releases the USB interface and device
     * connection. Idempotent and safe to call from any thread.
     *
     * A deliberate close does not emit [TransportEvent.ConnectionLost];
     * only an unexpected I/O failure does.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        open.set(false)
        scope.cancel()
        runCatching { port.close() }
    }

    private fun ensureOpen() {
        if (!isOpen) throw IOException("USB connection is closed")
    }

    /**
     * Blocking bulk reads on the IO dispatcher for the lifetime of the
     * connection. A finite read timeout keeps coroutine cancellation
     * responsive: [close] also closes the port, which aborts an in-flight
     * transfer immediately.
     */
    private suspend fun readLoop() {
        val reassembler = PacketReassembler()
        val buffer = ByteArray(READ_BUFFER_BYTES)

        try {
            while (open.get() && currentCoroutineContext().isActive) {
                val bytesRead = try {
                    port.read(buffer, READ_TIMEOUT_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!closed.get()) {
                        open.set(false)
                        _events.emit(TransportEvent.ConnectionLost(e))
                    }
                    return
                }

                if (bytesRead <= 0) continue

                for (event in reassembler.feed(buffer, 0, bytesRead)) {
                    _events.emit(event)
                }
            }
        } finally {
            open.set(false)
            if (closed.compareAndSet(false, true)) {
                runCatching { port.close() }
            }
            // Also stops the scope when the reader ended on its own (I/O
            // failure); idempotent after an explicit close().
            scope.cancel()
        }
    }

    companion object {
        /** Required by the API; irrelevant for a native USB CDC-ACM link. */
        const val BAUD_RATE = 115200

        private const val DATA_BITS = UsbSerialPort.DATABITS_8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE

        private const val READ_BUFFER_BYTES = 4096
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val EVENT_BUFFER_CAPACITY = 64

        /**
         * Opens and claims [device], which must already have USB permission
         * (see [UsbPermission]).
         *
         * The driver is constructed directly rather than through
         * `UsbSerialProber`, because the board's VID/PID is not in that
         * library's detection table. Baud/format parameters are set only
         * because the CDC-ACM API expects them.
         *
         * @throws SecurityException if permission has not been granted.
         * @throws IOException if the device cannot be opened or configured.
         */
        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): RfLabConnection {
            if (!usbManager.hasPermission(device)) {
                throw SecurityException(
                    "USB permission not granted for ${device.deviceName}",
                )
            }

            val driver = UsbDeviceFinder.createDriver(device)
            val port = driver.ports.firstOrNull()
                ?: throw IOException("no serial port on device ${device.deviceName}")

            val usbConnection = usbManager.openDevice(device)
                ?: throw IOException("could not open USB device ${device.deviceName}")

            try {
                port.open(usbConnection)
                port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            } catch (e: Exception) {
                runCatching { port.close() }
                runCatching { usbConnection.close() }
                if (e is IOException) throw e
                throw IOException("could not configure ${device.deviceName}", e)
            }

            return RfLabConnection(port, dispatcher)
        }
    }
}
