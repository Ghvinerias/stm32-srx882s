package com.eelab.rflab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import com.eelab.rflab.usb.RfLabTransport
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared state between the GATT callback (in [AndroidBleLink]) and the
 * [BleUartTransport] that wraps it.
 *
 * BLE is callback-driven, but [RfLabTransport] is a blocking byte stream, so
 * this holder is the bridge: the callback pushes received notifications into
 * [incoming] and resolves the in-flight write, while the transport blocks on
 * them. Everything here is thread-safe because the GATT callback runs on a
 * binder thread while [BleUartTransport.read]/`write` run on the connection's
 * IO dispatcher.
 */
class BleGattChannel {

    /** Notification payloads from the TX characteristic, in arrival order. */
    val incoming: BlockingQueue<ByteArray> = LinkedBlockingQueue()

    /** Flipped once the link drops (callback) or [BleUartTransport.close]. */
    val disconnected = AtomicBoolean(false)

    /** The write awaiting its `onCharacteristicWrite`, or null between writes. */
    val pendingWrite = AtomicReference<WriteSlot?>(null)

    /** One outstanding write: [RfLabConnection] serialises writes, so ≤1 exists. */
    class WriteSlot(val latch: CountDownLatch, val success: AtomicBoolean)

    /** Resolves the in-flight write, if any, with [success]. */
    fun completeWrite(success: Boolean) {
        pendingWrite.getAndSet(null)?.let {
            it.success.set(success)
            it.latch.countDown()
        }
    }

    /**
     * Marks the link dead and unblocks a parked reader/writer.
     *
     * Offering an empty array wakes a blocked [BlockingQueue.poll] at once so
     * [BleUartTransport.read] returns promptly on close instead of waiting a
     * full poll timeout; the reader then sees [disconnected] on the next loop.
     */
    fun markDisconnected() {
        disconnected.set(true)
        incoming.offer(ByteArray(0))
        completeWrite(false)
    }
}

/**
 * [RfLabTransport] over a connected BLE GATT link using the Nordic UART
 * Service.
 *
 * The ESP32-C3 build pushes the exact same COBS + CRC-16 packet stream over
 * BLE notifications that the STM32 sends over USB, so only the byte transport
 * differs: notifications on the TX characteristic become [read] bytes, and
 * [write] fans a command frame out across the negotiated-MTU-sized chunks the
 * RX characteristic accepts. Everything above — framing, reassembly, write
 * serialisation, liveness — is the unchanged
 * [com.eelab.rflab.usb.RfLabConnection].
 *
 * The GATT client, its callback and the [channel] are created and wired by
 * [AndroidBleLink.openTransport]; this transport only drives an *already
 * connected and subscribed* link, mirroring how
 * [com.eelab.rflab.bluetooth.BluetoothSerialTransport] wraps an already-open
 * socket. It is only exercised on device.
 *
 * @property gatt the connected GATT client; closed by [close].
 * @property rxCharacteristic the NUS RX characteristic (host → device write).
 * @property channel shared queue/state fed by the GATT callback.
 * @property maxPayload bytes per write chunk (negotiated ATT MTU − 3).
 */
@SuppressLint("MissingPermission") // The controller checks BLUETOOTH_CONNECT first.
class BleUartTransport(
    private val gatt: BluetoothGatt,
    private val rxCharacteristic: BluetoothGattCharacteristic,
    private val channel: BleGattChannel,
    private val maxPayload: Int,
) : RfLabTransport {

    // A notification can be longer than the caller's buffer only in theory
    // (the read buffer is 4 KB, notifications ≤244 B), but carrying any
    // remainder keeps the transport correct regardless of buffer sizing.
    private var leftover: ByteArray = EMPTY

    /**
     * Blocking read into [buffer].
     *
     * Parks up to [READ_TIMEOUT_MS] for the next notification. A timeout
     * returns `0` ("nothing this round"), which keeps the connection's read
     * loop responsive to cancellation, exactly as the contract wants; a
     * dropped link is reported as an [IOException] so
     * [com.eelab.rflab.usb.RfLabConnection] emits `ConnectionLost` like a
     * reset USB/RFCOMM link.
     */
    override fun read(buffer: ByteArray): Int {
        if (leftover.isEmpty()) {
            val chunk = channel.incoming.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: return idleOrFail()
            if (chunk.isEmpty()) return idleOrFail()
            leftover = chunk
        }
        val count = minOf(leftover.size, buffer.size)
        System.arraycopy(leftover, 0, buffer, 0, count)
        leftover = if (count == leftover.size) EMPTY else leftover.copyOfRange(count, leftover.size)
        return count
    }

    private fun idleOrFail(): Int {
        if (channel.disconnected.get()) throw IOException("BLE link lost")
        return 0
    }

    /**
     * Writes [bytes] by splitting into ≤[maxPayload] chunks and waiting for
     * each chunk's `onCharacteristicWrite` before sending the next.
     *
     * Write-with-response gives per-chunk flow control (the firmware's RX
     * characteristic advertises both WRITE and WRITE_NR); blocking the IO
     * thread on a latch here is the same shape as a blocking socket write, and
     * the connection serialises callers so only one write is ever in flight.
     *
     * @throws IOException if the link is down, the stack rejects the write, or
     *   the completion callback does not arrive within [WRITE_TIMEOUT_MS].
     */
    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (channel.disconnected.get()) throw IOException("BLE link lost")
            val end = minOf(offset + maxPayload, bytes.size)
            val piece = bytes.copyOfRange(offset, end)

            val slot = BleGattChannel.WriteSlot(CountDownLatch(1), AtomicBoolean(false))
            channel.pendingWrite.set(slot)

            if (!sendChunk(piece)) {
                channel.pendingWrite.compareAndSet(slot, null)
                throw IOException("BLE writeCharacteristic was rejected by the stack")
            }
            if (!slot.latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                channel.pendingWrite.compareAndSet(slot, null)
                throw IOException("BLE write timed out")
            }
            if (!slot.success.get()) throw IOException("BLE write failed")
            offset = end
        }
    }

    /**
     * Issues the platform write, handling the API-33 signature change.
     *
     * API 33 added `writeCharacteristic(characteristic, value, writeType)`
     * returning a status code and deprecated the setter-plus-write form; both
     * are kept so minSdk 23 and the current target compile and run alike.
     */
    @Suppress("DEPRECATION")
    private fun sendChunk(piece: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                rxCharacteristic,
                piece,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            rxCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rxCharacteristic.value = piece
            gatt.writeCharacteristic(rxCharacteristic)
        }
    }

    /**
     * Tears the GATT link down and unblocks an in-flight [read]/`write`.
     *
     * Idempotent and safe from any thread. A deliberate close marks the
     * channel disconnected *before* closing GATT, so the resulting read
     * failure is suppressed by [com.eelab.rflab.usb.RfLabConnection] (which
     * sets its `closed` flag first) rather than surfacing as `ConnectionLost`.
     */
    override fun close() {
        channel.markDisconnected()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** Max read park before returning 0 so cancellation stays responsive. */
        const val READ_TIMEOUT_MS: Long = 250L

        /** Max wait for a chunk's write-completion callback. */
        const val WRITE_TIMEOUT_MS: Long = 5_000L
    }
}
