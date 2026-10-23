package com.eelab.rflab.ota

import com.eelab.rflab.protocol.Cobs
import com.eelab.rflab.protocol.Crc16
import com.eelab.rflab.protocol.OtaAuth
import com.eelab.rflab.protocol.OtaCommand
import com.eelab.rflab.protocol.OtaOps
import com.eelab.rflab.protocol.OtaResponse
import com.eelab.rflab.protocol.OtaStatus
import com.eelab.rflab.protocol.PROTOCOL_VERSION
import com.eelab.rflab.protocol.PacketCodec
import com.eelab.rflab.protocol.PacketDecodeResult
import com.eelab.rflab.protocol.PacketType
import com.eelab.rflab.usb.TransportEvent
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory device tests for [OtaEngine]: a [FakeOtaDevice] drives the same
 * protocol state machine the firmware implements and answers with packets
 * built by the same framing the vectors use, while the engine under test is
 * wired through a production-shaped write path (COBS + `0x00` framing, then
 * the decoded packet is delivered on [TransportEvent.PacketReceived]).
 *
 * Runs entirely on the test scheduler's virtual clock: every fake response
 * arrives after a virtual delay, so the 2 s stop-and-wait timeouts resolve
 * instantly when exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtaEngineTest {

    private val vec = OtaGolden.parsed
    private val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)

    // --- happy path -----------------------------------------------------------

    @Test
    fun happyPathFlashesTheGoldenImageByteExactly() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        val (writes, engine) = buildEngine(this, device)

        engine.flash(vec.image, vec.secret)

        // Every command on the wire reproduces the golden vectors exactly:
        // hello, begin, the six chunks (in order), end.
        val expectedWires = listOf(vec.hello.wireHex, vec.begin.wireHex) +
            vec.chunks.map { it.wireHex } +
            listOf(vec.end.wireHex)
        assertEquals(expectedWires, writes)

        // The device stored the image and finished its sequence.
        assertTrue(device.storedBytes().contentEquals(vec.image))
        assertEquals(FakeState.REBOOTING, device.state)

        // Progress reached DONE with the full byte count.
        val progress = engine.progress.value
        assertEquals(OtaPhase.DONE, progress.phase)
        assertEquals(1000L, progress.bytesSent)
        assertEquals(1000L, progress.totalBytes)
        assertEquals(null, progress.error)

        // The responses served were the golden response packets.
        assertEquals(vec.helloRespOk.rawHex, device.responseRawHex(0))
        assertEquals(vec.beginRespOk.rawHex, device.responseRawHex(1))
        assertEquals(vec.chunkRespFirst.rawHex, device.responseRawHex(2))
        assertEquals(vec.endRespOk.rawHex, device.responseRawHex(8))
    }

    @Test
    fun advertisedChunkLargerThanTheProtocolMaximumIsCappedAt192() = runTest {
        // A future device may advertise more than the protocol's 192-byte
        // maximum; the engine must still send min(192, advertised) bytes.
        val device = FakeOtaDevice(
            secret = vec.secret,
            nonce = vec.nonce,
            maxChunk = 256,
        )
        val (writes, engine) = buildEngine(this, device)

        engine.flash(vec.image, vec.secret)

        val expectedWires = listOf(vec.hello.wireHex, vec.begin.wireHex) +
            vec.chunks.map { it.wireHex } +
            listOf(vec.end.wireHex)
        assertEquals(expectedWires, writes)
        assertEquals(OtaPhase.DONE, engine.progress.value.phase)
        assertTrue(device.storedBytes().contentEquals(vec.image))
    }

    @Test
    fun bootloaderRebootSendsHelloThenTheGoldenBootloaderPacket() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        val (writes, engine) = buildEngine(this, device)

        engine.rebootToBootloader(vec.secret)

        assertEquals(listOf(vec.hello.wireHex, vec.bootloader.wireHex), writes)
        assertEquals(OtaPhase.DONE, engine.progress.value.phase)
        assertEquals(FakeState.ROM_MODE, device.state)
        assertTrue(engine.progress.value.note?.contains("bootloader") == true)
    }

    // --- retry / resync ---------------------------------------------------------

    @Test
    fun lostChunkResponseRetriesTheSamePacket() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        var dropped = false
        val (writes, engine) = buildEngine(this, device) { command ->
            if (!dropped && command.op == OtaOps.CHUNK &&
                chunkOffsetOf(command) == 192L
            ) {
                // The device processes the chunk but its response is lost;
                // the retry is a repeat of the immediately-previous chunk.
                dropped = true
                device.dropNextResponse()
            }
        }

        engine.flash(vec.image, vec.secret)

        assertEquals(OtaPhase.DONE, engine.progress.value.phase)
        // The chunk was sent twice (once for the timeout retry); everything
        // else exactly once: hello, begin, 6 chunks + 1 repeat, end.
        assertEquals(2, writes.count { it == vec.chunks[1].wireHex })
        assertEquals(10, writes.size)
        // No data was written twice by the device.
        assertTrue(device.storedBytes().contentEquals(vec.image))
    }

    @Test
    fun badOffsetResponseResyncsToTheDeviceOffset() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        var armed = false
        val (writes, engine) = buildEngine(this, device) { command ->
            // Arm the fault once: the engine is supposed to retry the
            // rolled-back chunk and then move forward again. Re-arming on
            // every CHUNK@192 would model a device that never makes progress.
            if (!armed && command.op == OtaOps.CHUNK && chunkOffsetOf(command) == 192L) {
                armed = true
                device.armBadOffset(offset = 192L, next = 0L)
            }
        }

        engine.flash(vec.image, vec.secret)

        assertEquals(OtaPhase.DONE, engine.progress.value.phase)
        assertEquals(1000L, engine.progress.value.bytesSent)
        // The engine rewrote chunk@0 after rolling the device back to it.
        assertEquals(2, writes.count { it == vec.chunks[0].wireHex })
        assertTrue(device.storedBytes().contentEquals(vec.image))
        assertEquals(FakeState.REBOOTING, device.state)
    }

    @Test
    fun exhaustedResponseAttemptsFailWithTheAttemptCount() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        device.dropNextResponse(99)
        val (writes, engine) = buildEngine(this, device)

        engine.flash(vec.image, vec.secret)

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("did not answer HELLO after 3 attempts"))
        // Three transmissions of the same HELLO packet, then a session ABORT
        // once the engine gave up.
        assertEquals(
            List(3) { vec.hello.wireHex } + vec.abort.wireHex,
            writes,
        )
    }

    // --- device rejections ------------------------------------------------------

    @Test
    fun badMacFailsWithTheWrongSecretMessage() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        val (writes, engine) = buildEngine(this, device)
        val wrongSecret = ByteArray(32)

        engine.flash(vec.image, wrongSecret)

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("wrong secret", ignoreCase = true))
        // The nonce was consumed by the rejected BEGIN, and the device
        // returned to idle; no chunks ever flew.
        assertEquals(
            listOf(vec.hello.wireHex, vec.badMacBegin.wireHex, vec.abort.wireHex),
            writes,
        )
        assertEquals(FakeState.IDLE, device.state)
        assertEquals(0, device.storedBytes().size)
    }

    @Test
    fun disabledFirmwareFailsWithTheDisabledMessage() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce, disabled = true)
        val writes = mutableListOf<String>()
        val engine = OtaEngine(events = events, sendPacket = { raw ->
            writes += wireOf(raw)
            val command = decodedCommand(raw)
            launch {
                delay(RESPONSE_DELAY_MS)
                device.consume(raw)?.let {
                    events.emit(TransportEvent.PacketReceived(it))
                }
            }
        })

        engine.flash(vec.image, vec.secret)

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("no OTA secret built in"))
        // HELLO was refused with DISABLED; the engine then reset the device.
        assertEquals(listOf(vec.hello.wireHex, vec.abort.wireHex), writes)
    }

    @Test
    fun imageLargerThanTheAdvertisedSlotIsRejectedLocally() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce, slotSize = 0x10000L)
        val image = ByteArray(0x10001) { (it % 251).toByte() }
        val (writes, engine) = buildEngine(this, device)

        engine.flash(image, vec.secret)
        // The engine sends the best-effort ABORT without waiting for its
        // answer; let the fake device consume it before checking its state.
        advanceUntilIdle()

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("too large"))
        // Refused after HELLO, before any nonce spend: only hello (+ the
        // session ABORT) hit the wire.
        assertEquals(listOf(vec.hello.wireHex, vec.abort.wireHex), writes)
        assertEquals(FakeState.IDLE, device.state)
    }

    @Test
    fun connectionLostWhileWaitingForEndIsTreatedAsSuccess() = runTest {
        // The device accepted END and rebooted, but the OK response and the
        // link were lost. This is the documented success marker.
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        val writes = mutableListOf<String>()
        val engine = OtaEngine(events = events, sendPacket = { raw ->
            writes += wireOf(raw)
            val command = decodedCommand(raw)
            launch {
                delay(RESPONSE_DELAY_MS)
                if (command.op == OtaOps.END) {
                    device.consume(raw) // accepted; state -> REBOOTING
                    events.emit(TransportEvent.ConnectionLost(null))
                } else {
                    device.consume(raw)?.let {
                        events.emit(TransportEvent.PacketReceived(it))
                    }
                }
            }
        })

        engine.flash(vec.image, vec.secret)

        val progress = engine.progress.value
        assertEquals(OtaPhase.DONE, progress.phase)
        assertEquals(1000L, progress.bytesSent)
        assertTrue(progress.note?.contains("rebooting") == true)
        assertEquals(FakeState.REBOOTING, device.state)
        assertEquals(vec.end.wireHex, writes.last())
    }

    @Test
    fun endWriteFailureIsReportedAsFailure() = runTest {
        // A failed END write is not evidence that the device rebooted, so it
        // must not be reported as a completed update.
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        val writes = mutableListOf<String>()
        val engine = OtaEngine(events = events, sendPacket = { raw ->
            val command = decodedCommand(raw)
            if (command.op == OtaOps.END) {
                throw IOException("link down before END")
            }
            writes += wireOf(raw)
            launch {
                delay(RESPONSE_DELAY_MS)
                device.consume(raw)?.let {
                    events.emit(TransportEvent.PacketReceived(it))
                }
            }
        })

        engine.flash(vec.image, vec.secret)

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("Could not write END"))
        advanceUntilIdle()
        assertEquals(FakeState.IDLE, device.state)
        assertEquals(0, device.storedBytes().size)
    }

    // --- cancellation & link loss ------------------------------------------------

    @Test
    fun cancelSendsAbortAndReportsCancelled() = runTest {
        val device = FakeOtaDevice(
            secret = vec.secret,
            nonce = vec.nonce,
            slowResponseOffset = 192L, // keep the engine parked on CHUNK@192
        )
        // Use the test scope, not backgroundScope: runTest's backgroundScope
        // tasks are not run by advanceUntilIdle, so the fake device would
        // never consume the ABORT written from the cancellation handler.
        val (writes, engine) = buildEngine(this, device)

        val job = launch { engine.flash(vec.image, vec.secret) }
        // Wait until the first chunk has been ACKed (progress ≥ 192 bytes).
        engine.progress.first { it.phase == OtaPhase.TRANSFER && it.bytesSent >= 192L }
        job.cancelAndJoin()
        advanceUntilIdle()

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("cancelled"))
        // ABORT (wire-identical to the vector) is the last packet written,
        // and nothing was written after it.
        assertEquals(vec.abort.wireHex, writes.last())
        assertEquals(FakeState.IDLE, device.state)
        assertEquals(0, device.storedBytes().size)
    }

    @Test
    fun connectionLostMidTransferFailsWithoutCrashing() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        var linkAlive = true
        val writes = mutableListOf<String>()
        val engine = OtaEngine(events = events, sendPacket = { raw ->
            if (!linkAlive) {
                // The link is gone: even the engine's own ABORT write fails.
                throw IOException("port open failed: link is gone")
            }
            writes += wireOf(raw)
            val command = decodedCommand(raw)
            if (command.op == OtaOps.CHUNK && chunkOffsetOf(command) == 192L) {
                // This write still made it out (a BLE write can "succeed"
                // just before a disconnect is noticed), but the link died
                // before its response could arrive.
                launch {
                    delay(2 * RESPONSE_DELAY_MS)
                    linkAlive = false
                    events.emit(TransportEvent.ConnectionLost(null))
                }
            } else {
                launch {
                    delay(RESPONSE_DELAY_MS)
                    device.consume(raw)?.let {
                        events.emit(TransportEvent.PacketReceived(it))
                    }
                }
            }
        })

        engine.flash(vec.image, vec.secret)

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("connection was lost"))
        // hello, begin, chunk@0, chunk@192 — then the engine gave up and its
        // best-effort ABORT could not leave the dead link.
        assertEquals(
            listOf(
                vec.hello.wireHex,
                vec.begin.wireHex,
                vec.chunks[0].wireHex,
                vec.chunks[1].wireHex,
            ),
            writes,
        )
        assertEquals(FakeState.RECEIVING, device.state)
    }

    @Test
    fun writeFailureFailsTheUpdateWithTheWriteError() = runTest {
        val device = FakeOtaDevice(secret = vec.secret, nonce = vec.nonce)
        // The write path fails from the very first packet.
        val engine = OtaEngine(events = events, sendPacket = { _ ->
            throw IOException("USB device is not connected")
        })

        engine.flash(vec.image, vec.secret)

        val progress = engine.progress.value
        assertEquals(OtaPhase.FAILED, progress.phase)
        assertTrue(progress.error!!.contains("Could not write to the device"))
        assertTrue(progress.error!!.contains("USB device is not connected"))
        // Nothing ever left the app, and the best-effort ABORT after the
        // failure could not be written either.
        assertTrue(device.storedBytes().isEmpty())
        assertEquals(FakeState.IDLE, device.state)
    }

    // --- harness -----------------------------------------------------------------

    /**
     * Builds the engine with a production-shaped write path: the raw packet
     * is COBS-framed and recorded, and the fake device answers after a
     * (virtual) delay by emitting the decoded response packet onto [events].
     * The device lives in [scope] rather than the caller's coroutine so its
     * answers keep coming even after the tested operation is cancelled —
     * pass [kotlinx.coroutines.test.TestScope.backgroundScope] for tests
     * that cancel [OtaEngine.flash] mid-transfer.
     *
     * @param intercept runs in the sender before the response is scheduled,
     *   so tests can arm per-packet faults just in time.
     */
    private fun buildEngine(
        scope: CoroutineScope,
        device: FakeOtaDevice,
        intercept: suspend (OtaCommand) -> Unit = {},
    ): Pair<MutableList<String>, OtaEngine> {
        val writes = mutableListOf<String>()
        val engine = OtaEngine(events = events, sendPacket = { raw ->
            writes += wireOf(raw)
            val command = decodedCommand(raw)
            intercept(command)
            val responseDelay = if (command.op == OtaOps.CHUNK &&
                chunkOffsetOf(command) == device.slowResponseOffset
            ) {
                LONG_RESPONSE_DELAY_MS
            } else {
                RESPONSE_DELAY_MS
            }
            scope.launch {
                delay(responseDelay)
                device.consume(raw)?.let {
                    events.emit(TransportEvent.PacketReceived(it))
                }
            }
        })
        return writes to engine
    }

    /** Applies the production transport framing: COBS body + `0x00`. */
    private fun wireOf(raw: ByteArray): String =
        (Cobs.encode(raw) + byteArrayOf(0)).toHex()

    private fun decodedCommand(raw: ByteArray): OtaCommand {
        val result = PacketCodec.decode(raw)
        if (result !is PacketDecodeResult.Success ||
            result.packet !is OtaCommand
        ) {
            throw AssertionError("engine wrote a non-command packet: $result")
        }
        return result.packet
    }

    private fun chunkOffsetOf(command: OtaCommand): Long =
        readU32Le(command.payload, 0)

    private companion object {
        const val RESPONSE_DELAY_MS = 10L
        const val LONG_RESPONSE_DELAY_MS = 100_000L
    }
}

/** States of the fake device's mini state machine (logging only). */
internal enum class FakeState { IDLE, NONCE, RECEIVING, REBOOTING, ROM_MODE }

/**
 * The fake device: implements the firmware's OTA state machine from
 * `OTA_PROTOCOL.md` (IDLE → NONCE → RECEIVING → reboot) with the same MACs,
 * including the repeat-chunk re-ACK, plus small fault-injection knobs the
 * tests arm per packet.
 */
private class FakeOtaDevice(
    val secret: ByteArray,
    private val nonce: ByteArray = ByteArray(16) { (0x40 + it).toByte() },
    private val slotSize: Long = 0x1F0000,
    private val maxChunk: Int = 192,
    private val disabled: Boolean = false,
    /** CHUNK offsets whose response should be withheld far past the timeout. */
    val slowResponseOffset: Long? = null,
) {

    val state: FakeState
        get() = currentState

    private var currentState: FakeState = FakeState.IDLE
    private var sessionNonce: ByteArray? = null
    private var nextOffset = 0L
    private var imageSize = 0
    private var expectedSha: ByteArray? = null
    private var storedBuffer = ByteArray(0)

    /** Raw bytes of every response the device produced, in order. */
    private val producedResponses = ArrayList<ByteArray>()

    /** The next N responses are computed but "lost on the wire". */
    private var dropsRemaining = 0

    private var badOffsetTargetOffset: Long? = null
    private var badOffsetNextValue = 0L
    private var badOffsetConsumed = true

    fun armBadOffset(offset: Long, next: Long) {
        badOffsetTargetOffset = offset
        badOffsetNextValue = next
        badOffsetConsumed = false
    }

    fun dropNextResponse(times: Int = 1) {
        dropsRemaining += times
    }

    fun storedBytes(): ByteArray = storedBuffer.copyOf()

    fun responseRawHex(index: Int): String = producedResponses[index].toHex()

    /**
     * Consumes one raw command packet exactly as the firmware's parser would,
     * returning the response packet (already decoded through
     * [PacketCodec.decode]) or null when the response is lost.
     */
    fun consume(raw: ByteArray): OtaResponse? {
        val result = PacketCodec.decode(raw)
        val command = (result as PacketDecodeResult.Success).packet as OtaCommand
        val response = when (command.op) {
            OtaOps.HELLO -> hello()
            OtaOps.BEGIN -> begin(command)
            OtaOps.CHUNK -> chunk(command)
            OtaOps.END -> end()
            OtaOps.ABORT -> abort()
            OtaOps.BOOTLOADER -> bootloader(command)
            else -> throw AssertionError("fake device got op ${command.op}")
        }

        producedResponses += responseRaw(response)
        if (dropsRemaining > 0) {
            dropsRemaining--
            return null
        }
        return response
    }

    private fun hello(): OtaResponse {
        if (disabled) return response(OtaOps.HELLO, OtaStatus.DISABLED)
        currentState = FakeState.NONCE
        sessionNonce = nonce.copyOf()
        val payload = ByteArray(23)
        payload[0] = 0x01 // caps: the protocol is supported
        payload[1] = (maxChunk and 0xFF).toByte()
        payload[2] = ((maxChunk ushr 8) and 0xFF).toByte()
        putU32(payload, 3, slotSize)
        nonce.copyInto(payload, 7)
        return response(OtaOps.HELLO, OtaStatus.OK, payload)
    }

    private fun begin(command: OtaCommand): OtaResponse {
        if (disabled) return response(OtaOps.BEGIN, OtaStatus.DISABLED)
        if (currentState != FakeState.NONCE) {
            currentState = FakeState.IDLE
            return response(OtaOps.BEGIN, OtaStatus.BAD_STATE)
        }
        if (command.payload.size != OtaCommand.BEGIN_PAYLOAD_SIZE) {
            currentState = FakeState.IDLE
            return response(OtaOps.BEGIN, OtaStatus.BAD_LENGTH)
        }
        val payload = command.payload
        val size = readU32Le(payload, 0)
        val sha = payload.copyOfRange(4, 36)
        val mac = payload.copyOfRange(36, 68)
        val wantedMac = OtaAuth.beginMac(secret, sessionNonce!!, size, sha)
        if (!mac.contentEquals(wantedMac)) {
            // A bad attempt consumes the nonce and goes back to idle.
            currentState = FakeState.IDLE
            return response(OtaOps.BEGIN, OtaStatus.BAD_MAC)
        }
        if (size > slotSize) {
            currentState = FakeState.IDLE
            return response(OtaOps.BEGIN, OtaStatus.TOO_BIG)
        }
        imageSize = size.toInt()
        expectedSha = sha
        storedBuffer = ByteArray(imageSize)
        nextOffset = 0
        currentState = FakeState.RECEIVING
        return response(OtaOps.BEGIN, OtaStatus.OK)
    }

    private fun chunk(command: OtaCommand): OtaResponse {
        if (disabled) return response(OtaOps.CHUNK, OtaStatus.DISABLED)
        if (currentState != FakeState.RECEIVING) {
            currentState = FakeState.IDLE
            return response(OtaOps.CHUNK, OtaStatus.BAD_STATE)
        }
        val offset = readU32Le(command.payload, 0)
        val data = command.payload.copyOfRange(4, command.payload.size)

        // Fault hook: the pointed-at chunk is answered out of step.
        val target = badOffsetTargetOffset
        if (!badOffsetConsumed && target != null && target == offset) {
            badOffsetConsumed = true
            // The device "already has" everything before `next`, so roll the
            // write position back and discard whatever it had stored.
            nextOffset = badOffsetNextValue
            storedBuffer = ByteArray(imageSize)
            return response(
                OtaOps.CHUNK,
                OtaStatus.BAD_OFFSET,
                putU32(ByteArray(4), 0, nextOffset),
            )
        }

        return when {
            // Repeat of the immediately previous chunk: re-ACK, no rewrite.
            offset < nextOffset && offset + data.size == nextOffset ->
                response(OtaOps.CHUNK, OtaStatus.OK, putU32(ByteArray(4), 0, nextOffset))

            offset == nextOffset -> {
                data.copyInto(storedBuffer, offset.toInt())
                nextOffset = offset + data.size
                response(OtaOps.CHUNK, OtaStatus.OK, putU32(ByteArray(4), 0, nextOffset))
            }

            else ->
                response(
                    OtaOps.CHUNK,
                    OtaStatus.BAD_OFFSET,
                    putU32(ByteArray(4), 0, nextOffset),
                )
        }
    }

    private fun end(): OtaResponse {
        if (disabled) return response(OtaOps.END, OtaStatus.DISABLED)
        if (currentState != FakeState.RECEIVING) {
            currentState = FakeState.IDLE
            return response(OtaOps.END, OtaStatus.BAD_STATE)
        }
        val digest = OtaAuth.sha256(storedBuffer)
        if (nextOffset != imageSize.toLong() ||
            expectedSha == null ||
            !digest.contentEquals(expectedSha)
        ) {
            currentState = FakeState.IDLE
            return response(OtaOps.END, OtaStatus.BAD_HASH)
        }
        currentState = FakeState.REBOOTING
        return response(OtaOps.END, OtaStatus.OK)
    }

    private fun abort(): OtaResponse {
        // ABORT works in any state and always returns the device to idle,
        // discarding the partial write.
        currentState = FakeState.IDLE
        storedBuffer = ByteArray(0)
        nextOffset = 0
        expectedSha = null
        sessionNonce = null
        return response(OtaOps.ABORT, OtaStatus.OK)
    }

    private fun bootloader(command: OtaCommand): OtaResponse {
        if (disabled) return response(OtaOps.BOOTLOADER, OtaStatus.DISABLED)
        if (currentState != FakeState.NONCE) {
            currentState = FakeState.IDLE
            return response(OtaOps.BOOTLOADER, OtaStatus.BAD_STATE)
        }
        val wanted = OtaAuth.bootloaderMac(secret, sessionNonce!!)
        if (!command.payload.contentEquals(wanted)) {
            currentState = FakeState.IDLE
            return response(OtaOps.BOOTLOADER, OtaStatus.BAD_MAC)
        }
        currentState = FakeState.ROM_MODE
        return response(OtaOps.BOOTLOADER, OtaStatus.OK)
    }

    /** Builds the response per `ota_ref.py`: `[1][7][op][status][payload][crc]`. */
    private fun response(op: Int, status: Int, payload: ByteArray = ByteArray(0)): OtaResponse {
        val raw = ByteArray(4 + payload.size + 2)
        raw[0] = PROTOCOL_VERSION.toByte()
        raw[1] = PacketType.OTA_RESP.toByte()
        raw[2] = op.toByte()
        raw[3] = status.toByte()
        payload.copyInto(raw, 4)
        val crc = Crc16.compute(raw.copyOfRange(0, raw.size - 2))
        raw[raw.size - 2] = (crc and 0xFF).toByte()
        raw[raw.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        // Decode through the real codec so the emitted event is exactly what
        // RfLabConnection would deliver.
        return PacketCodec.decode(raw).let {
            (it as PacketDecodeResult.Success).packet as OtaResponse
        }
    }

    fun responseRaw(op: Int, status: Int, payload: ByteArray): ByteArray {
        val raw = ByteArray(4 + payload.size + 2)
        raw[0] = PROTOCOL_VERSION.toByte()
        raw[1] = PacketType.OTA_RESP.toByte()
        raw[2] = op.toByte()
        raw[3] = status.toByte()
        payload.copyInto(raw, 4)
        val crc = Crc16.compute(raw.copyOfRange(0, raw.size - 2))
        raw[raw.size - 2] = (crc and 0xFF).toByte()
        raw[raw.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        return raw
    }

    private fun responseRaw(response: OtaResponse): ByteArray =
        responseRaw(response.op, response.status, response.payload)

    private fun putU32(target: ByteArray, offset: Int, value: Long): ByteArray {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        return target
    }
}
