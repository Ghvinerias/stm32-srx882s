package com.eelab.rflab.ui.firmware

import com.eelab.rflab.ota.OtaProgress
import com.eelab.rflab.ota.update.FirmwareServerRules
import com.eelab.rflab.ota.update.FirmwareUnit
import com.eelab.rflab.ota.update.UpdateCheckState

/**
 * Download-in-flight state: raw counters the screen renders into a progress
 * bar. `totalBytes` is null while the server has not revealed a length.
 */
data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
)

/**
 * The picked (.bin picked through the fallback, or the verified download the
 * user is about to flash) image summarised for display. The image bytes stay
 * private to the [FirmwareViewModel]; only this summary reaches the UI state.
 */
data class OtaImageInfo(
    /** Display name from the content resolver (or the verified remote name). */
    val name: String,
    /** Image size in bytes. */
    val sizeBytes: Int,
    /** SHA-256 of the image, lowercase hex — the value shown to the user. */
    val sha256Hex: String,
)

/**
 * The server certificate waiting for the user's trust confirmation (trust on
 * first use): the fingerprint is shown in the dialog, and on confirmation is
 * pinned for [baseUrl].
 */
data class FingerprintPrompt(
    /** The server base URL the certificate was observed on. */
    val baseUrl: String,
    /** The observed leaf certificate's SHA-256, lowercase hex. */
    val fingerprint: String,
)

/**
 * UI state of the firmware-update screen.
 *
 * The screen has two paths into the same engine. The primary "Update to
 * latest" path pulls the image from the firmware web server (settings below,
 * trust-on-first-use TLS) and verifies it before flashing; the fallback path
 * ("Advanced: pick a file") reuses the document picker. Both feed
 * [com.eelab.rflab.ota.OtaEngine] the same way.
 *
 * @property secret the (masked) secret field text as typed.
 * @property secretError why the typed secret is not valid yet; null when it
 *   is well-formed (64 hex characters).
 * @property connected whether a transport is currently attached — flashing
 *   (not checking the server) is disabled without it.
 * @property image the image summary (picked or downloaded), or null before
 *   one is ready to flash.
 * @property imageError why the picked file was rejected (merged image /
 *   wrong magic); null when [image] is acceptable, or when no file has been
 *   picked yet.
 * @property progress the engine's live progress (phase, bytesSent/total,
 *   error/note messages).
 * @property actionMessage a one-off hint about presses (e.g. Start while an
 *   update is already running or while disconnected).
 * @property serverUrl the firmware web server base URL as typed.
 * @property serverUrlError why the typed URL is not usable; null while blank
 *   (blank is an accepted in-progress state, actions enforce presence) and
 *   while the URL parses.
 * @property serverUsername HTTP Basic user, default `flash`.
 * @property serverPassword HTTP Basic password, masked with the toggle.
 * @property serverPasswordVisible true when the password is shown in clear.
 * @property manualUnit the unit the user picked (null = follow detection).
 * @property detectedUnit the unit inferred from received FRAME packets'
 *   `receiver_id` (0xE1/0xE2); null until a frame identifies the board.
 * @property check the result of the last update check (or Checking/Idle).
 * @property download the in-flight image download progress, or null.
 * @property pendingFingerprint a server certificate awaiting the user's
 *   trust confirmation, or null.
 * @property pendingFlashConfirm the verified remote image waiting for the
 *   user's "flash now" confirmation, or null.
 */
data class FirmwareUiState(
    val secret: String = "",
    val secretError: String? = null,
    val connected: Boolean = false,
    val image: OtaImageInfo? = null,
    val imageError: String? = null,
    val progress: OtaProgress = OtaProgress(),
    val actionMessage: String? = null,
    val serverUrl: String = "",
    val serverUrlError: String? = null,
    val serverUsername: String = FirmwareServerRules.DEFAULT_USERNAME,
    val serverPassword: String = "",
    val serverPasswordVisible: Boolean = false,
    val manualUnit: FirmwareUnit? = null,
    val detectedUnit: FirmwareUnit? = null,
    val check: UpdateCheckState = UpdateCheckState.Idle,
    val download: DownloadProgress? = null,
    val pendingFingerprint: FingerprintPrompt? = null,
    val pendingFlashConfirm: OtaImageInfo? = null,
) {

    /** True while the engine is inside HELLO..BOOTLOADER phases. */
    val running: Boolean
        get() = progress.isActive

    /** True while an image is being pulled from the server. */
    val downloading: Boolean
        get() = download != null

    /** The unit that will be updated: the manual pick, else the detected one. */
    val effectiveUnit: FirmwareUnit?
        get() = manualUnit ?: detectedUnit

    /** True while any background work owns the screen (check/download/flash). */
    val busy: Boolean
        get() = running || downloading || check is UpdateCheckState.Checking

    /** True when the screen has everything it needs to start an update. */
    val canStart: Boolean
        get() = connected &&
            !running &&
            secretError == null &&
            imageError == null &&
            image != null

    /** True when "Update to latest" may run (download + confirm + flash). */
    val canUpdateToLatest: Boolean
        get() = connected &&
            !busy &&
            secretError == null &&
            pendingFingerprint == null &&
            (check is UpdateCheckState.UpdateAvailable ||
                check is UpdateCheckState.UnknownBaseline)
}
