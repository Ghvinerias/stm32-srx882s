package com.eelab.rflab.send

import com.eelab.rflab.protocol.AckPacket
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.protocol.PacketCodec
import com.eelab.rflab.protocol.PacketDecodeResult
import com.eelab.rflab.protocol.PacketType
import com.eelab.rflab.protocol.RadioMode
import com.eelab.rflab.protocol.ScenarioCommand
import com.eelab.rflab.protocol.StatusPacket
import com.eelab.rflab.protocol.TxCommand
import com.eelab.rflab.queue.SendItemStatus
import com.eelab.rflab.queue.SendQueueItem
import com.eelab.rflab.usb.TransportEvent
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict sequential send-then-await-ACK tests for [BatchSendEngine].
 *
 * A [MutableSharedFlow] stands in for `RfLabConnection.events`, and the write
 * function captures the raw packet bytes instead of touching USB. Timeouts are
 * injectable (200 ms here) and every test runs on the virtual clock, so the
 * timeout cases finish instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchSendEngineTest {

    private val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)

    private fun item(
        id: Long,
        hopping: Long = 0x63FD859DL,
        serial: Long = 0x0FB58C0CL,
        button: Int = 0x2,
        frameCount: Int = 5,
    ): SendQueueItem = SendQueueItem(
        id = id,
        hopping = hopping,
        serial = serial,
        button = button,
        frameCount = frameCount,
    )

    private fun ack(forType: Int, accepted: Boolean = true): TransportEvent =
        TransportEvent.PacketReceived(AckPacket(forType = forType, accepted = accepted))

    private fun ackResult(forType: Int, result: Int): TransportEvent =
        TransportEvent.PacketReceived(AckPacket(forType = forType, result = result))

    private fun packetEvent(packet: com.eelab.rflab.protocol.Packet): TransportEvent =
        TransportEvent.PacketReceived(packet)

    private fun statusPacket(): StatusPacket = StatusPacket(
        version = 1,
        flags = 0,
        sequence = 7,
        droppedFrames = 0,
        clockHz = 72_000_000L,
        totalEdges = 1234L,
        droppedPackets = 0,
    )

    private fun framePacket(): FramePacket = FramePacket(
        version = 1,
        flags = 0,
        sequence = 8,
        timestampUs = 5678L,
        droppedFrames = 0,
        pulses = intArrayOf(4512, 795, 1138, 1482),
        initialLevel = 0,
        receiverId = 1,
    )

    // --- (a) prompt ACK -----------------------------------------------------

    @Test
    fun promptAckMarksTxItemAckedOkAndEncodesTheQueueFields() = runTest {
        val statuses = mutableListOf<SendItemStatus>()
        val writes = mutableListOf<ByteArray>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = { raw ->
                writes += raw
                launch {
                    delay(10)
                    events.emit(ack(PacketType.TX_COMMAND))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1))) { _, status -> statuses += status }

        assertEquals(listOf(SendItemStatus.SENDING, SendItemStatus.ACKED_OK), statuses)
        assertEquals(1, writes.size)

        val decoded = PacketCodec.decode(writes.single()) as PacketDecodeResult.Success
        assertEquals(PacketType.TX_COMMAND, writes.single()[1].toInt())
        val command = decoded.packet as TxCommand
        assertEquals(0x63FD859DL, command.hopping)
        assertEquals(0x0FB58C0CL, command.serial)
        assertEquals(0x2, command.button)
        assertEquals(5, command.frameCount)
    }

    // --- (b) rejected ACK ---------------------------------------------------

    @Test
    fun ackWithAcceptedZeroMarksItemAckedFailed() = runTest {
        val statuses = mutableListOf<SendItemStatus>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = {
                launch {
                    delay(10)
                    events.emit(ack(PacketType.TX_COMMAND, accepted = false))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1))) { _, status -> statuses += status }

        assertEquals(listOf(SendItemStatus.SENDING, SendItemStatus.ACKED_FAILED), statuses)
    }

    // --- (c) timeout --------------------------------------------------------

    @Test
    fun missingAckTimesOutAfterTheInjectedBudget() = runTest {
        val statuses = mutableListOf<SendItemStatus>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = { /* never acknowledged */ },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1))) { _, status -> statuses += status }

        assertEquals(listOf(SendItemStatus.SENDING, SendItemStatus.TIMEOUT), statuses)
        assertEquals(200L, currentTime)
    }

    // --- (d) unrelated traffic during the wait ------------------------------

    @Test
    fun unrelatedFramesStatusAndOtherTypeAcksDoNotResolveTheWait() = runTest {
        val statuses = mutableListOf<SendItemStatus>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = {
                launch {
                    // Traffic the device emits while it is busy with a command.
                    delay(20)
                    events.emit(packetEvent(statusPacket()))
                    delay(20)
                    events.emit(packetEvent(framePacket()))
                    delay(20)
                    // An ACK for the *other* command type must be ignored too.
                    events.emit(ack(PacketType.SCENARIO_COMMAND))
                    delay(20)
                    events.emit(ack(PacketType.TX_COMMAND))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        val job = launch {
            engine.sendBatch(listOf(item(1))) { _, status -> statuses += status }
        }

        // Through the unrelated packets and the wrong-type ACK, the item is
        // still in flight: nothing resolved the wait early.
        advanceTimeBy(70)
        runCurrent()
        assertEquals(listOf(SendItemStatus.SENDING), statuses)

        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(listOf(SendItemStatus.SENDING, SendItemStatus.ACKED_OK), statuses)
    }

    // --- (e) strict sequential batch ---------------------------------------

    @Test
    fun threeItemBatchWritesOnlyAfterEachPreviousAck() = runTest {
        val writeTimes = mutableListOf<Long>()
        val resolvedAtWrite = mutableListOf<Int>()
        val resolved = mutableSetOf<Long>()

        val engine = BatchSendEngine(
            events = events,
            sendPacket = {
                // Recorded at the exact virtual time the next write starts.
                writeTimes += currentTime
                resolvedAtWrite += resolved.size
                launch {
                    delay(50)
                    events.emit(ack(PacketType.TX_COMMAND))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1), item(2), item(3))) { id, status ->
            if (status != SendItemStatus.SENDING) resolved += id
        }

        // Each item's ACK takes 50 ms. If the engine pipelined, writes 2 and 3
        // would happen at t=0 (or before the previous ACK) instead.
        assertEquals(listOf(0L, 50L, 100L), writeTimes)
        // Direct no-overlap assertion: when write N starts, exactly N-1 items
        // have already reached a terminal status.
        assertEquals(listOf(0, 1, 2), resolvedAtWrite)
        assertEquals(setOf(1L, 2L, 3L), resolved)
    }

    @Test
    fun timeoutOnOneItemStillAdvancesToTheNextSequentially() = runTest {
        val writes = mutableListOf<Long>()
        var firstWrite = true
        val statuses = mutableListOf<Pair<Long, SendItemStatus>>()

        val engine = BatchSendEngine(
            events = events,
            sendPacket = {
                writes += currentTime
                if (!firstWrite) {
                    launch {
                        delay(10)
                        events.emit(ack(PacketType.TX_COMMAND))
                    }
                }
                firstWrite = false
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1), item(2))) { id, status -> statuses += id to status }

        assertEquals(listOf(0L, 200L), writes)
        assertEquals(
            listOf(
                1L to SendItemStatus.SENDING,
                1L to SendItemStatus.TIMEOUT,
                2L to SendItemStatus.SENDING,
                2L to SendItemStatus.ACKED_OK,
            ),
            statuses,
        )
    }

    // --- (f) one-shot scenario ---------------------------------------------

    @Test
    fun scenarioSendsOneShotAndWaitsForItsType4Ack() = runTest {
        val writes = mutableListOf<ByteArray>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = { raw ->
                writes += raw
                launch {
                    delay(10)
                    events.emit(ack(PacketType.SCENARIO_COMMAND, accepted = true))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        val status = engine.sendScenario(ScenarioCommand(ScenarioCommand.PLAINTEXT_COUNTER))

        assertEquals(SendItemStatus.ACKED_OK, status)
        assertEquals(1, writes.size)
        assertEquals(PacketType.SCENARIO_COMMAND, writes.single()[1].toInt())
        val decoded = PacketCodec.decode(writes.single()) as PacketDecodeResult.Success
        assertEquals('p', (decoded.packet as ScenarioCommand).commandChar)
    }

    @Test
    fun scenarioFailureAndTimeoutMapToTheirStatuses() = runTest {
        val rejected = BatchSendEngine(
            events = events,
            sendPacket = {
                launch {
                    delay(10)
                    events.emit(ack(PacketType.SCENARIO_COMMAND, accepted = false))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )
        assertEquals(
            SendItemStatus.ACKED_FAILED,
            rejected.sendScenario(ScenarioCommand(ScenarioCommand.ZERO_HOP)),
        )

        val silent = BatchSendEngine(
            events = events,
            sendPacket = { },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )
        assertEquals(
            SendItemStatus.TIMEOUT,
            silent.sendScenario(ScenarioCommand(ScenarioCommand.ZERO_HOP)),
        )
    }

    // --- (g) link failure mid-batch ----------------------------------------

    @Test
    fun connectionLostWhileWaitingForAckFailsTheItemAndAbortsImmediately() = runTest {
        val statuses = mutableListOf<Pair<Long, SendItemStatus>>()
        val writes = mutableListOf<Long>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = {
                writes += currentTime
                launch {
                    delay(10)
                    events.emit(TransportEvent.ConnectionLost(IOException("device unplugged")))
                }
            },
            // Far longer than the test: the loss must resolve the wait, not
            // the timeout.
            ackTimeoutMs = 5_000,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1), item(2), item(3))) { id, status ->
            statuses += id to status
        }

        // Only the in-flight item was written; nothing was sent after the loss.
        assertEquals(listOf(0L), writes)
        assertEquals(
            listOf(1L to SendItemStatus.SENDING, 1L to SendItemStatus.FAILED),
            statuses,
        )
        // Aborted at the loss (10 ms), not after the 5 s ACK budget.
        assertEquals(10L, currentTime)
    }

    @Test
    fun writeFailureMarksTheItemFailedAndLeavesLaterItemsPending() = runTest {
        val statuses = mutableListOf<Pair<Long, SendItemStatus>>()
        var writes = 0
        val engine = BatchSendEngine(
            events = events,
            sendPacket = {
                writes++
                if (writes == 2) throw IOException("USB connection is closed")
                launch {
                    delay(10)
                    events.emit(ack(PacketType.TX_COMMAND))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1), item(2), item(3))) { id, status ->
            statuses += id to status
        }

        assertEquals(2, writes)
        assertEquals(
            listOf(
                1L to SendItemStatus.SENDING,
                1L to SendItemStatus.ACKED_OK,
                2L to SendItemStatus.SENDING,
                2L to SendItemStatus.FAILED,
                // Item 3 was never attempted, so it gets no callback at all
                // and stays PENDING in the queue.
            ),
            statuses,
        )
    }

    @Test
    fun scenarioLinkLossAndWriteFailureMapToFailed() = runTest {
        val lost = BatchSendEngine(
            events = events,
            sendPacket = {
                launch {
                    delay(10)
                    events.emit(TransportEvent.ConnectionLost(IOException("gone")))
                }
            },
            ackTimeoutMs = 5_000,
            perFrameTimeoutMs = 0,
        )
        assertEquals(
            SendItemStatus.FAILED,
            lost.sendScenario(ScenarioCommand(ScenarioCommand.STALE_REPLAY)),
        )

        val closed = BatchSendEngine(
            events = events,
            sendPacket = { throw IOException("USB device is not connected") },
            ackTimeoutMs = 5_000,
            perFrameTimeoutMs = 0,
        )
        assertEquals(
            SendItemStatus.FAILED,
            closed.sendScenario(ScenarioCommand(ScenarioCommand.STALE_REPLAY)),
        )
    }

    @Test
    fun cancellingMidBatchLeavesNoItemStuckAsSending() = runTest {
        val statuses = mutableListOf<Pair<Long, SendItemStatus>>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = { /* never acknowledged */ },
            ackTimeoutMs = 10_000,
            perFrameTimeoutMs = 0,
        )

        val job = launch {
            engine.sendBatch(listOf(item(1), item(2))) { id, status ->
                statuses += id to status
            }
        }
        runCurrent()
        assertEquals(listOf(1L to SendItemStatus.SENDING), statuses)

        job.cancelAndJoin()

        // The cancelled in-flight item must not stay SENDING in the
        // process-scoped queue; item 2 was never attempted.
        assertEquals(1L to SendItemStatus.FAILED, statuses.last())
        assertEquals(1, statuses.count { it.second == SendItemStatus.SENDING })
    }

    // --- radio modes, long bursts, abort ------------------------------------

    @Test
    fun ackTimeoutScalesWithFrameCount() = runTest {
        val engine = BatchSendEngine(events = events, sendPacket = { })
        assertEquals(2_000L + 5 * 120L, engine.ackTimeoutFor(5))
        assertEquals(2_000L + 255 * 120L, engine.ackTimeoutFor(255))
    }

    @Test
    fun longBurstIsNotTimedOutBeforeItsScaledBudget() = runTest {
        val statuses = mutableListOf<SendItemStatus>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = {
                launch {
                    // 100 frames * ~105 ms: far beyond the old flat 2 s.
                    delay(10_500)
                    events.emit(ack(PacketType.TX_COMMAND))
                }
            },
        )
        engine.sendBatch(listOf(item(1, frameCount = 100))) { _, st -> statuses += st }
        assertEquals(listOf(SendItemStatus.SENDING, SendItemStatus.ACKED_OK), statuses)
    }

    @Test
    fun abortedAckEndsTheBatchAndLeavesTheRestPending() = runTest {
        val statuses = mutableListOf<Pair<Long, SendItemStatus>>()
        val writes = mutableListOf<ByteArray>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = { raw ->
                writes += raw
                launch {
                    delay(10)
                    events.emit(ackResult(PacketType.TX_COMMAND, AckPacket.RESULT_ABORTED))
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )

        engine.sendBatch(listOf(item(1), item(2))) { id, st -> statuses += id to st }

        assertEquals(
            listOf(1L to SendItemStatus.SENDING, 1L to SendItemStatus.ABORTED),
            statuses,
        )
        assertEquals(1, writes.size)
    }

    @Test
    fun stopTransmitSendsModeIdleWhileABatchHoldsTheSendLock() = runTest {
        val statuses = mutableListOf<Pair<Long, SendItemStatus>>()
        val writes = mutableListOf<ByteArray>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = { raw ->
                writes += raw
                if (raw[1].toInt() == PacketType.MODE_COMMAND) {
                    launch {
                        // Measured firmware order: MODE ACK first, the aborted
                        // TX ACK ~150 ms later.
                        delay(5)
                        events.emit(ack(PacketType.MODE_COMMAND))
                        delay(150)
                        events.emit(ackResult(PacketType.TX_COMMAND, AckPacket.RESULT_ABORTED))
                    }
                }
            },
        )

        val batch = launch {
            engine.sendBatch(listOf(item(1, frameCount = 200), item(2))) { id, st ->
                statuses += id to st
            }
        }
        runCurrent()
        assertEquals(1, writes.size) // TX burst on the wire, ACK pending

        assertTrue(engine.stopTransmit())
        // stopTransmit returned on the MODE ACK; the batch is still waiting
        // for the aborted TX ACK.
        assertTrue(batch.isActive)
        assertEquals(SendItemStatus.SENDING, statuses.last().second)
        batch.join()

        assertEquals(2, writes.size)
        assertEquals(
            PacketCodec.encodeModeCommand(com.eelab.rflab.protocol.ModeCommand(RadioMode.IDLE)).toList(),
            writes[1].toList(),
        )
        assertEquals(SendItemStatus.ABORTED, statuses.last().second)
        assertTrue(statuses.none { it.first == 2L })
    }

    @Test
    fun stopRequestedBeforeAnItemEndsTheBatchEvenWithoutAnAbortAck() = runTest {
        val statuses = mutableListOf<Pair<Long, SendItemStatus>>()
        val engine = BatchSendEngine(
            events = events,
            sendPacket = { raw ->
                launch {
                    delay(10)
                    events.emit(
                        if (raw[1].toInt() == PacketType.MODE_COMMAND) ack(PacketType.MODE_COMMAND)
                        else ack(PacketType.TX_COMMAND),
                    )
                }
            },
            ackTimeoutMs = 200,
            perFrameTimeoutMs = 0,
        )
        val batch = launch {
            engine.sendBatch(listOf(item(1), item(2), item(3))) { id, st -> statuses += id to st }
        }
        runCurrent()
        // Stop lands while item 1 is in flight; its ACK was still OK.
        engine.stopTransmit()
        batch.join()
        assertTrue(statuses.none { it.first != 1L })
    }

    @Test
    fun setModeReportsAcceptedRejectedAndTimeout() = runTest {
        val ok = BatchSendEngine(
            events = events,
            sendPacket = { launch { delay(5); events.emit(ack(PacketType.MODE_COMMAND)) } },
            ackTimeoutMs = 200,
        )
        assertTrue(ok.setMode(RadioMode.RX))

        val rejected = BatchSendEngine(
            events = events,
            sendPacket = { launch { delay(5); events.emit(ack(PacketType.MODE_COMMAND, accepted = false)) } },
            ackTimeoutMs = 200,
        )
        assertEquals(false, rejected.setMode(RadioMode.RX))

        val silent = BatchSendEngine(events = events, sendPacket = { }, ackTimeoutMs = 200)
        assertEquals(false, silent.setMode(RadioMode.IDLE))
    }
}
