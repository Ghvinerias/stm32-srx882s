package com.eelab.rflab.ota.update

/**
 * The image the server offers for one unit, as summarised from the
 * `SHA256SUMS` manifest (plus an optional [sizeBytes] probed from the HTTP
 * headers).
 */
data class RemoteImage(
    /** Exact file name on the server, e.g. `esp32c3-cc1101-lab-unit1-app.bin`. */
    val fileName: String,
    /** SHA-256 the server's manifest promises for [fileName], lowercase hex. */
    val sha256Hex: String,
    /**
     * Content length the server reported, in bytes, or null when it was not
     * available at check time (the size is still shown once the download
     * starts and the real length is known).
     */
    val sizeBytes: Long?,
)

/**
 * Result of one "check for update" round; shown on the Firmware screen.
 */
sealed interface UpdateCheckState {

    /** No check has run (or the last one was cleared). */
    data object Idle : UpdateCheckState

    /** The manifest download is in flight. */
    data object Checking : UpdateCheckState

    /** The image on the server equals the one last flashed to this unit. */
    data class UpToDate(val sha256Hex: String) : UpdateCheckState

    /**
     * The server has a different image than the one last flashed via this
     * app — the "Update to latest" action becomes available.
     */
    data class UpdateAvailable(val image: RemoteImage) : UpdateCheckState

    /**
     * This app has never recorded an update for this unit, so there is no
     * baseline to compare against. Presented like an update (flashing the
     * latest is safe; the device itself verifies and can roll back), with a
     * "version unknown" hint.
     */
    data class UnknownBaseline(val image: RemoteImage) : UpdateCheckState

    /** The check failed; [message] is user-facing explanatory text. */
    data class Failed(val message: String) : UpdateCheckState
}

/**
 * The up-to-date-vs-update-available decision (pure Kotlin, JVM-tested).
 *
 * The OTA protocol's HELLO/BEGIN responses carry no running-image version or
 * hash (only caps/max_chunk/slot_size/nonce), so the only baseline is the
 * hash this app persisted after its own last successful flash of the unit
 * (see [com.eelab.rflab.ota.update.LastFlashStore]). With no baseline the
 * result is [UpdateCheckState.UnknownBaseline] rather than "up to date": the
 * user must not be told "up to date" on a guess.
 */
object UpdateEvaluator {

    /**
     * @param lastFlashedSha256 hash of the image last flashed to the unit via
     *   this app, or null when never flashed / not recorded.
     * @param latest the image the server offers for the unit.
     */
    fun evaluate(lastFlashedSha256: String?, latest: RemoteImage): UpdateCheckState = when {
        lastFlashedSha256 == null -> UpdateCheckState.UnknownBaseline(latest)

        // Hashes are compared case-insensitively: the manifest is normalised
        // to lowercase at parse time, but a hand-edited SUMS file may not be.
        lastFlashedSha256.equals(latest.sha256Hex, ignoreCase = true) ->
            UpdateCheckState.UpToDate(latest.sha256Hex)

        else -> UpdateCheckState.UpdateAvailable(latest)
    }
}
