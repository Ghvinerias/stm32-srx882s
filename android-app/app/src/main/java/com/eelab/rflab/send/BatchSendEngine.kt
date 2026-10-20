package com.eelab.rflab.send

import com.eelab.rflab.protocol.AckPacket
import com.eelab.rflab.protocol.PacketCodec
import com.eelab.rflab.protocol.PacketType
import com.eelab.rflab.protocol.ScenarioCommand
import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Strictly sequential send-then-await-ACK engine for the binary protocol.
 *
 * ### Why this is sequential (correctness, not polish)
 *
 * The ACK packet has no per-request id: it only echoes `for_type` (3 for
 * `TX_COMMAND`, 4 for `SCENARIO_COMMAND`). The only thing that makes "the
 * next ACK of this type belongs to the command I just sent" unambiguous is
 * never having more than one command in flight. This engine therefore:
 *
 * 1. marks one queue item [SendItemStatus.SENDING],
 * 2. writes exactly one encoded command (COBS + `0x00` delimiter is the
 *    transport's job, see [sendPacket]),
 * 3. waits for the next matching-for-type ACK, or [ackTimeoutMs],
 * 4. reports the terminal status, and only then
 * 5. starts the next item.
 *
 * It never pipelines. The brief's "Batch-sending timing constraint" also
 * explains the hardware reason: the device's 256-byte USB receive ring
 * overflows if a burst arrives while it is busy transmitting a high
 * `frame_count` command, corrupting framing. A generous timeout (the
 * default 2 s) covers worst-case `frame_count = 10`.
 *
 * ### Sharing the event stream
 *
 * [events] is a hot broadcast stream (`RfLabConnection.events` /
 * `RfLabSession.events`). Collecting it here does not consume it: the
 * receive screen's independent collector continues to see every packet.
 * FRAME, STATUS and decode-error events arriving while a command is in
 * flight are ignored, as are ACKs for the other command type; only the
 * first matching ACK after the write resolves the wait.
 *
 * @param events decoded transport events from the current connection.
 * @param sendPacket writes one raw packet to the device, applying the COBS
 *   encoding and trailing `0x00` delimiter. [com.eelab.rflab.usb.RfLabConnection.sendPacket]
 *   is the production implementation; tests capture the bytes instead.
 * @param ackTimeoutMs how long one command may take to be acknowledged
 *   before it is reported as [SendItemStatus.TIMEOUT]. Injectable so JVM
 *   tests do not have to wait the production 2 s.
 */
class BatchSendEngine(
    private val events: Flow<TransportEvent>,
    private val sendPacket: suspend (rawPacket: ByteArray) -> Unit,
    private val ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
) {

    init {
        require(ackTimeoutMs > 0) { "ackTimeoutMs must be positive: $ackTimeoutMs" }
    }

    /**
     * Serialises every send path through one engine instance, including
     * preset scenarios pressed while a batch is running. This is the
     * coroutine-level enforcement of "one command in flight", complementing
     * the sequential loop below.
     */
    private val sendMutex = Mutex()

    /**
     * Sends [items] one at a time, in list order.
     *
     * For each item: [onStatusChange] is called with [SendItemStatus.SENDING]
     * before the write, then once more with the terminal status
     * ([SendItemStatus.ACKED_OK], [SendItemStatus.ACKED_FAILED] or
     * [SendItemStatus.TIMEOUT]). The next item is not written until that
     * terminal callback has run.
     *
     * A timeout does not abort the batch: the device may simply have dropped
     * one command, and the brief describes timeout-then-continue. A *write*
     * failure (for example the device was unplugged) does abort the
     * remaining items, because sending into a closed link would only burn one
     * timeout per item; those items stay [SendItemStatus.PENDING]. The
     * failed write is reported as [SendItemStatus.TIMEOUT] because the
     * protocol model has no separate transport-error state (session 10 owns
     * richer disconnect handling).
     *
     * @param onStatusChange invoked on the caller's coroutine context, in
     *   order. It should be cheap (updating a queue/repository), not block.
     */
    suspend fun sendBatch(
        items: List<SendQueueItem>,
        onStatusChange: (itemId: Long, status: SendItemStatus) -> Unit,
    ) {
        if (items.isEmpty()) return

        sendMutex.withLock {
            for (item in items) {
                onStatusChange(item.id, SendItemStatus.SENDING)

                val result = sendAndAwait(
                    rawPacket = PacketCodec.encodeTxCommand(item.toTxCommand()),
                    expectedForType = PacketType.TX_COMMAND,
                )

                when (result) {
                    is AwaitResult.Acknowledged -> onStatusChange(
                        item.id,
                        if (result.accepted) {
                            SendItemStatus.ACKED_OK
                        } else {
                            SendItemStatus.ACKED_FAILED
                        },
                    )

                    AwaitResult.TimedOut -> onStatusChange(item.id, SendItemStatus.TIMEOUT)

                    is AwaitResult.WriteFailed -> {
                        onStatusChange(item.id, SendItemStatus.TIMEOUT)
                        // Link is gone: do not spend a timeout on every
                        // remaining item. They keep their prior status.
                        return
                    }
                }
            }
        }
    }

    /**
     * Sends one preset [cmd] and waits for its type-4 ACK.
     *
     * Shares [sendMutex] with [sendBatch], so a preset press cannot inject a
     * second in-flight command into a running batch (or vice versa).
     *
     * @return [SendItemStatus.ACKED_OK] / [SendItemStatus.ACKED_FAILED] from
     *   the ACK's `accepted` byte, or [SendItemStatus.TIMEOUT] when no ACK
     *   arrived in time or the write failed.
     */
    suspend fun sendScenario(cmd: ScenarioCommand): SendItemStatus {
        return sendMutex.withLock {
            when (
                val result = sendAndAwait(
                    rawPacket = PacketCodec.encodeScenarioCommand(cmd),
                    expectedForType = PacketType.SCENARIO_COMMAND,
                )
            ) {
                is AwaitResult.Acknowledged ->
                    if (result.accepted) SendItemStatus.ACKED_OK else SendItemStatus.ACKED_FAILED

                AwaitResult.TimedOut,
                is AwaitResult.WriteFailed,
                -> SendItemStatus.TIMEOUT
            }
        }
    }

    /**
     * Subscribes to [events], writes one command, then waits for the first
     * ACK whose `for_type` matches [expectedForType].
     *
     * The subscription is started *before* [sendPacket] with
     * [CoroutineStart.UNDISPATCHED] so a device that ACKs very quickly cannot
     * beat the collector. It is cancelled as soon as this command resolves,
     * so ACKs arriving later belong to the next command (if any) and are not
     * consumed here. Cancelling this collector does not affect other
     * collectors of the shared flow.
     */
    private suspend fun sendAndAwait(
        rawPacket: ByteArray,
        expectedForType: Int,
    ): AwaitResult = coroutineScope {
        val ack = CompletableDeferred<AckPacket>()

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { event ->
                if (event !is TransportEvent.PacketReceived) return@collect
                val packet = event.packet
                if (packet is AckPacket &&
                    packet.forType == expectedForType &&
                    !ack.isCompleted
                ) {
                    ack.complete(packet)
                }
            }
        }

        try {
            try {
                sendPacket(rawPacket)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return@coroutineScope AwaitResult.WriteFailed(error)
            }

            val received = withTimeoutOrNull(ackTimeoutMs) { ack.await() }
            if (received == null) {
                AwaitResult.TimedOut
            } else {
                AwaitResult.Acknowledged(received.accepted)
            }
        } finally {
            // Never keep collecting between commands: the next command owns
            // the next matching ACK.
            collector.cancel()
        }
    }

    private sealed interface AwaitResult {
        data class Acknowledged(val accepted: Boolean) : AwaitResult

        data object TimedOut : AwaitResult

        data class WriteFailed(val cause: Throwable) : AwaitResult
    }

    companion object {
        /**
         * Generous per-command budget: worst-case `frame_count = 10` blocks
         * the device for only a few hundred milliseconds, so 2 s leaves a
         * wide margin without stalling a disconnected UI for long.
         */
        const val DEFAULT_ACK_TIMEOUT_MS = 2_000L
    }
}
