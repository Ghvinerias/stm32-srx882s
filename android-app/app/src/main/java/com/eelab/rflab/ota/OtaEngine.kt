package com.eelab.rflab.ota

import com.eelab.rflab.protocol.OtaAuth
import com.eelab.rflab.protocol.OtaCommand
import com.eelab.rflab.protocol.OtaOps
import com.eelab.rflab.protocol.OtaResponse
import com.eelab.rflab.protocol.OtaStatus
import com.eelab.rflab.protocol.PacketCodec
import com.eelab.rflab.usb.TransportEvent
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sequential stop-and-wait firmware update engine for the OTA protocol
 * (types 6/7, `esp32c3-cc1101-lab/ota/OTA_PROTOCOL.md`).
 *
 * Update sequence: `HELLO` (fresh nonce, device `max_chunk`, slot size, local
 * too-large rejection) → `BEGIN` with the image SHA-256 and its session MAC →
 * `CHUNK` data one packet at a time → `END`. Every packet is written and its
 * response awaited before the next one (the same discipline
 * [com.eelab.rflab.send.BatchSendEngine] uses for ACKs). A CHUNK response that
 * times out is retried with the *same* packet a few times, and a BAD_OFFSET
 * response resyncs to the device's `next_offset`.
 *
 * Finishing `END` reboots the device, so the link is expected to drop right
 * afterwards: a lost link around END — instead of — or before the END/OK
 * response — is a reboot marker, not a failure (the firmware only reboots
 * after accepting END, and its own SHA-256 check already ran). The same holds
 * for an accepted BOOTLOADER op. Mid-transfer a lost link is a plain failure;
 * cancellation is reported as cancelled *after* a best-effort ABORT so the
 * device discards the partial write.
 *
 * The nonce is single-use (consumed by the first BEGIN/BOOTLOADER attempt,
 * good or bad), so BEGIN is sent once: a retry could only fail the MAC.
 * HELLO is idempotent and may be retried with the same bytes.
 *
 * ### Sharing the event stream
 *
 * [events] is the hot broadcast stream ([com.eelab.rflab.usb.RfLabSession.events]).
 * Collecting it does not consume it: the Receive screen and the send engine
 * keep their own collectors. Only `OTA_RESP` packets answering the awaited
 * `op` and `ConnectionLost` resolve an in-flight wait; FRAME/STATUS/ACK
 * packets and decode errors are ignored.
 *
 * @param events decoded transport events from the current connection.
 * @param sendPacket writes one raw packet (COBS + `0x00` delimiter are the
 *   transport's job, as in [com.eelab.rflab.send.SendGraph.engine]).
 * @param responseTimeoutMs how long one packet may take to be answered; the
 *   protocol prescribes 2 s and up to 3 sends of the same packet.
 * @param attemptsPerPacket how many times a packet may be sent when its
 *   response keeps timing out (3 = one send plus two retries).
 * @param maxChunkCap upper limit for CHUNK data; the engine sends payloads of
 *   at most `min(maxChunkCap, device-advertised max_chunk)`.
 */
class OtaEngine(
    private val events: Flow<TransportEvent>,
    private val sendPacket: suspend (rawPacket: ByteArray) -> Unit,
    private val responseTimeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    private val attemptsPerPacket: Int = DEFAULT_ATTEMPTS_PER_PACKET,
    private val maxChunkCap: Int = OtaCommand.MAX_CHUNK,
) {

    init {
        require(responseTimeoutMs > 0) {
            "responseTimeoutMs must be positive: $responseTimeoutMs"
        }
        require(attemptsPerPacket > 0) {
            "attemptsPerPacket must be positive: $attemptsPerPacket"
        }
        require(maxChunkCap in 1..OtaCommand.MAX_CHUNK) { "maxChunkCap must be 1..192" }
    }

    private val _progress = MutableStateFlow(OtaProgress())

    /** Live progress: phase, bytes sent vs image size, and the error/note. */
    val progress: StateFlow<OtaProgress> = _progress.asStateFlow()

    /** One OTA operation at a time; overlapping callers are told to wait. */
    private val busy = AtomicBoolean(false)

    /**
     * Flashes [image] to the device using [secret] (the 32 bytes of the
     * 64-hex-character OTA secret built into the firmware).
     *
     * Every terminal outcome (DONE, FAILED, cancelled) is published through
     * [progress]; this function returns normally in those cases. Cancellation
     * is the one exception: the cancellation state is published first, then
     * the exception is rethrown so the caller's coroutine finishes cleanly.
     */
    suspend fun flash(image: ByteArray, secret: ByteArray) {
        runGuarded(image.size.toLong()) {
            runFlash(image, secret)
        }
    }

    /**
     * Reboots the device into the ROM boot loader (`BOOTLOADER` op) so it can
     * be flashed over USB with esptool/`idf.py`. Needs the same [secret].
     */
    suspend fun rebootToBootloader(secret: ByteArray) {
        runGuarded(totalBytes = 0) {
            runBootloaderReboot(secret)
        }
    }

    // --- Update sequence ----------------------------------------------------

    private suspend fun runFlash(image: ByteArray, secret: ByteArray) {
        val total = image.size.toLong()

        setProgress(OtaPhase.HELLO, bytesSent = 0, totalBytes = total)
        val hello = resolved(
            requestUntilAnswer(OtaCommand.hello(), expectedOp = OtaOps.HELLO),
            requestName = "HELLO",
        )
        if (!hello.isOk) fail(OtaStatusText.forStatus(hello.status))

        val deviceInfo = hello.helloInfo()
            ?: fail("The device sent a malformed HELLO response")
        // The device must offer at least one data byte per CHUNK. Values above
        // the protocol's 192-byte maximum are fine: the engine caps the chunk
        // at min(192, advertised), just like ota_ref.py's MAX_CHUNK.
        if (deviceInfo.maxChunk < 1) {
            fail("The device advertises an unusable max_chunk (${deviceInfo.maxChunk})")
        }
        val chunkSize = minOf(maxChunkCap, deviceInfo.maxChunk, OtaCommand.MAX_CHUNK)

        // Reject locally before spending the session nonce on BEGIN.
        if (total > deviceInfo.slotSize) {
            fail(
                "The image is ${formatBytes(total)} but the OTA slot is only " +
                    "${formatBytes(deviceInfo.slotSize)} — too large",
            )
        }

        setProgress(OtaPhase.BEGIN, bytesSent = 0, totalBytes = total)
        val sha256 = OtaAuth.sha256(image)
        val mac = OtaAuth.beginMac(secret, deviceInfo.nonce, total, sha256)
        val begin = resolved(
            // Single attempt: a repeat would fail the MAC (nonce consumed).
            request(OtaCommand.begin(total, sha256, mac), expectedOp = OtaOps.BEGIN),
            requestName = "BEGIN",
        )
        if (!begin.isOk) fail(OtaStatusText.forStatus(begin.status))

        setProgress(OtaPhase.TRANSFER, bytesSent = 0, totalBytes = total)
        var offset = 0L
        var resyncs = 0
        val resyncBudget = (total / chunkSize).toInt() + RESYNC_BUDGET_MARGIN

        while (offset < total) {
            val length = minOf(chunkSize.toLong(), total - offset).toInt()
            val chunk = resolved(
                requestUntilAnswer(
                    OtaCommand.chunk(
                        offset = offset,
                        data = image.copyOfRange(offset.toInt(), offset.toInt() + length),
                    ),
                    expectedOp = OtaOps.CHUNK,
                ),
                requestName = "CHUNK at offset $offset",
            )

            if (chunk.isOk) {
                // Trust the device's next_offset: it also authorises the
                // re-ACK of a repeat after a lost response.
                offset = chunk.chunkNextOffset() ?: (offset + length)
                setProgress(OtaPhase.TRANSFER, bytesSent = offset, totalBytes = total)
                continue
            }

            when (chunk.status) {
                OtaStatus.BAD_OFFSET -> {
                    val next = chunk.chunkNextOffset()
                        ?: fail("The device is out of sync and gave no next_offset")
                    resyncs += 1
                    if (resyncs > resyncBudget) {
                        fail("Could not resync with the device after $resyncs resyncs")
                    }
                    offset = next
                    setProgress(OtaPhase.TRANSFER, bytesSent = offset, totalBytes = total)
                }

                else -> fail(OtaStatusText.forStatus(chunk.status))
            }
        }

        setProgress(OtaPhase.END, bytesSent = total, totalBytes = total)
        when (val end = requestUntilAnswer(OtaCommand.end(), expectedOp = OtaOps.END)) {
            is OtaResult.Response ->
                if (end.packet.isOk) {
                    complete(END_SUCCESS_NOTE)
                } else {
                    fail(OtaStatusText.forStatus(end.packet.status))
                }

            OtaResult.TimedOut -> fail(
                "The device did not answer END; it may not have rebooted — " +
                    "reconnect and verify which image is running",
            )

            // The device reboots ~300 ms after accepting END, so losing the
            // link while waiting for its OK is the expected reboot marker.
            // A failed write is different: END may never have reached the
            // device, so it must not be reported as success.
            OtaResult.LinkLost -> complete(END_SUCCESS_NOTE)

            is OtaResult.WriteFailed -> fail(
                "Could not write END to the device: " +
                    "${end.cause.message ?: end.cause}",
            )
        }
    }

    private suspend fun runBootloaderReboot(secret: ByteArray) {
        setProgress(OtaPhase.HELLO, bytesSent = 0, totalBytes = 0)
        val hello = resolved(
            requestUntilAnswer(OtaCommand.hello(), expectedOp = OtaOps.HELLO),
            requestName = "HELLO",
        )
        if (!hello.isOk) fail(OtaStatusText.forStatus(hello.status))
        val deviceInfo = hello.helloInfo()
            ?: fail("The device sent a malformed HELLO response")

        setProgress(OtaPhase.BOOTLOADER, bytesSent = 0, totalBytes = 0)
        val command = OtaCommand.bootloader(OtaAuth.bootloaderMac(secret, deviceInfo.nonce))
        when (val response = requestUntilAnswer(command, expectedOp = OtaOps.BOOTLOADER)) {
            is OtaResult.Response ->
                if (response.packet.isOk) {
                    complete(BOOTLOADER_SUCCESS_NOTE)
                } else {
                    fail(OtaStatusText.forStatus(response.packet.status))
                }

            OtaResult.TimedOut -> fail(
                "The device did not answer the bootloader request; it may have " +
                    "already rebooted — re-enumerate USB and flash there",
            )

            // Same reasoning as END: only a lost link is a reboot marker.
            OtaResult.LinkLost -> complete(BOOTLOADER_SUCCESS_NOTE)

            is OtaResult.WriteFailed -> fail(
                "Could not write the bootloader request to the device: " +
                    "${response.cause.message ?: response.cause}",
            )
        }
    }

    // --- Guarding, progress and failure plumbing ----------------------------

    /**
     * Runs one OTA operation exclusively: an overlapping call is ignored with
     * a note (the running operation keeps the device session), and a
     * cancellation first sends ABORT and publishes its terminal state.
     */
    private suspend fun runGuarded(totalBytes: Long, block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            _progress.update { it.copy(note = "An OTA operation is already running") }
            return
        }
        try {
            try {
                block()
            } catch (terminal: TerminalState) {
                // A failure leaves the device in its session state; reset it
                // to IDLE so the partial write is discarded right away. A
                // success terminal (END/BOOTLOADER accepted) must NOT abort —
                // the device is already rebooting.
                if (terminal.progress.error != null) {
                    abortSessionBestEffort()
                }
                _progress.value = terminal.progress
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                abortSessionBestEffort()
                _progress.value = OtaProgress(
                    phase = OtaPhase.FAILED,
                    bytesSent = _progress.value.bytesSent,
                    totalBytes = totalBytes,
                    error = "Update cancelled — the device discards any partial write",
                )
            }
            throw cancelled
        } finally {
            busy.set(false)
        }
    }

    private fun setProgress(phase: OtaPhase, bytesSent: Long, totalBytes: Long) {
        _progress.value = OtaProgress(
            phase = phase,
            bytesSent = bytesSent,
            totalBytes = totalBytes,
        )
    }

    /** Publishes the success path and returns to [runGuarded]. */
    private fun complete(note: String): Nothing = throw TerminalState(
        OtaProgress(
            phase = OtaPhase.DONE,
            bytesSent = _progress.value.bytesSent,
            totalBytes = _progress.value.totalBytes,
            note = note,
        ),
    )

    /** Exits a sequence step with a user-readable failure message. */
    private fun fail(message: String): Nothing = throw TerminalState(
        OtaProgress(
            phase = OtaPhase.FAILED,
            bytesSent = _progress.value.bytesSent,
            totalBytes = _progress.value.totalBytes,
            error = message,
        ),
    )

    /** Maps a timed-out/lost/failed request to a readable failure. */
    private fun resolved(result: OtaResult, requestName: String): OtaResponse =
        when (result) {
            is OtaResult.Response -> result.packet

            OtaResult.TimedOut -> fail(
                "The device did not answer $requestName after " +
                    "$attemptsPerPacket attempts",
            )

            OtaResult.LinkLost -> fail(LINK_LOST)
            is OtaResult.WriteFailed ->
                fail("Could not write to the device: ${result.cause.message ?: result.cause}")
        }

    /**
     * Sends ABORT without waiting for its response: it resets the device to
     * IDLE in any state and must work even from failure paths, so the partial
     * write is discarded instead of waiting out the 30 s session timeout.
     */
    private suspend fun abortSessionBestEffort() {
        try {
            sendPacket(PacketCodec.encodeOtaCommand(OtaCommand.abort()))
        } catch (_: Exception) {
            // The link can already be gone; the device clears itself quickly
            // (bad BEGIN / 30 s inactivity), so swallowing this is safe.
        }
    }

    // --- Stop-and-wait plumbing --------------------------------------------

    /**
     * Sends [command] and waits for the matching `OTA_RESP` with the same
     * [expectedOp], re-sending the *same* packet while its response keeps
     * timing out ([attemptsPerPacket] transmissions total).
     */
    private suspend fun requestUntilAnswer(command: OtaCommand, expectedOp: Int): OtaResult {
        var result = request(command, expectedOp)
        var attempt = 1
        while (result is OtaResult.TimedOut && attempt < attemptsPerPacket) {
            attempt += 1
            result = request(command, expectedOp)
        }
        return result
    }

    /**
     * One send-and-await round. The collector starts *before* the write
     * (UNDISPATCHED, as in [com.eelab.rflab.send.BatchSendEngine]) so a fast
     * device cannot beat it, and stops as soon as this packet resolves so a
     * late response belongs to the next attempt/command.
     */
    private suspend fun request(command: OtaCommand, expectedOp: Int): OtaResult =
        coroutineScope {
            val signal = CompletableDeferred<OtaSignal>()

            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { event ->
                    if (signal.isCompleted) return@collect
                    when (event) {
                        is TransportEvent.PacketReceived -> {
                            val packet = event.packet
                            if (packet is OtaResponse && packet.op == expectedOp) {
                                signal.complete(OtaSignal.Response(packet))
                            }
                        }

                        // No response can arrive once the read loop is dead;
                        // resolve now instead of burning the full timeout.
                        is TransportEvent.ConnectionLost ->
                            signal.complete(OtaSignal.LinkLost)

                        is TransportEvent.DecodeError -> Unit
                    }
                }
            }

            try {
                try {
                    sendPacket(PacketCodec.encodeOtaCommand(command))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    return@coroutineScope OtaResult.WriteFailed(error)
                }

                val received = withTimeoutOrNull(responseTimeoutMs) { signal.await() }
                    ?: return@coroutineScope OtaResult.TimedOut
                when (received) {
                    is OtaSignal.Response -> OtaResult.Response(received.packet)
                    OtaSignal.LinkLost -> OtaResult.LinkLost
                }
            } finally {
                collector.cancel()
            }
        }

    private sealed interface OtaSignal {
        data class Response(val packet: OtaResponse) : OtaSignal

        data object LinkLost : OtaSignal
    }

    private sealed interface OtaResult {
        data class Response(val packet: OtaResponse) : OtaResult

        data object TimedOut : OtaResult

        /** The transport reported the connection dead while waiting. */
        data object LinkLost : OtaResult

        data class WriteFailed(val cause: Throwable) : OtaResult
    }

    /**
     * Internal early-exit carrying the sequence's terminal progress state;
     * [runGuarded] publishes it and never lets it escape.
     */
    private class TerminalState(val progress: OtaProgress) :
        Throwable(null, null, false, false)

    companion object {
        /** Protocol stop-and-wait timeout: 2 s per response. */
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 2_000L

        /**
         * "retry same packet up to 3x" implemented as at most three
         * transmissions of the packet (one send, two retries).
         */
        const val DEFAULT_ATTEMPTS_PER_PACKET = 3

        /** Resync cushion beyond one re-write per chunk. */
        private const val RESYNC_BUDGET_MARGIN = 16

        /** Generic link-lost failure (mid-transfer). */
        internal const val LINK_LOST =
            "The connection was lost — reconnect the board and retry"

        private val END_SUCCESS_NOTE =
            "Firmware update complete — the device is rebooting with the new " +
                "image. Reconnect to the board (BLE or USB); give it about " +
                "10 s so the new firmware can mark itself valid."

        private val BOOTLOADER_SUCCESS_NOTE =
            "The device accepted the bootloader request and is rebooting into " +
                "ROM download mode. The serial link will drop — flash it over " +
                "USB with esptool/idf.py."

        private fun formatBytes(value: Long): String =
            NumberFormat.getIntegerInstance(Locale.US).format(value)
    }
}

/** Readable failure/status mapping shared with the UI (pure Kotlin). */
object OtaStatusText {

    /**
     * Maps a device status byte to a user-facing message; [OtaStatus.BAD_MAC]
     * and [OtaStatus.DISABLED] are phrased exactly as the brief requires.
     */
    fun forStatus(status: Int): String = when (status) {
        OtaStatus.BAD_STATE ->
            "The device is not in the right state for that step — reconnect and retry"

        OtaStatus.BAD_LENGTH ->
            "The device rejected the command length (protocol/size mismatch)"

        OtaStatus.DISABLED ->
            "The firmware has no OTA secret built in"

        OtaStatus.BAD_MAC ->
            "Wrong secret — the device did not accept the OTA secret"

        OtaStatus.TOO_BIG ->
            "The image is too large for the OTA slot"

        OtaStatus.BAD_HASH ->
            "The device's SHA-256 check failed at END — the transfer was corrupted"

        OtaStatus.BAD_OFFSET ->
            "The device is out of sync with the transfer offsets"

        OtaStatus.FLASH_ERROR ->
            "The device failed writing to flash"

        else -> "The device replied with error status $status"
    }
}
