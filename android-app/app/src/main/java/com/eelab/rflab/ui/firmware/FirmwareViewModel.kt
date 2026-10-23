package com.eelab.rflab.ui.firmware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.ota.OtaEngine
import com.eelab.rflab.ota.OtaPhase
import com.eelab.rflab.ota.OtaProgress
import com.eelab.rflab.ota.OtaSecrets
import com.eelab.rflab.ota.OtaSecretStore
import com.eelab.rflab.protocol.OtaAuth
import com.eelab.rflab.ota.update.FirmwareErrorText
import com.eelab.rflab.ota.update.FirmwareRemoteRepository
import com.eelab.rflab.ota.update.FirmwareServerException
import com.eelab.rflab.ota.update.FirmwareServerRules
import com.eelab.rflab.ota.update.FirmwareServerSettings
import com.eelab.rflab.ota.update.FirmwareServerSettingsStore
import com.eelab.rflab.ota.update.FirmwareUnit
import com.eelab.rflab.ota.update.LastFlashStore
import com.eelab.rflab.ota.update.TofuPinStore
import com.eelab.rflab.ota.update.UpdateCheckState
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.usb.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and actions for the firmware-update screen.
 *
 * Two paths feed the same [OtaEngine] with the same confirm-before-flash
 * rule (nothing ever reboots the device without a user tap):
 *
 * - **Update to latest** — the settings card stores the firmware web server
 *   (URL/user/password), [checkForUpdate] pulls `SHA256SUMS`, picks the unit
 *   → image entry via [FirmwareUnit], and compares against the hash this app
 *   persisted after its last successful flash ([LastFlashStore]). A first-use
 *   server certificate opens a fingerprint-confirmation dialog instead of
 *   being trusted blindly; a changed certificate fails loudly until the user
 *   resets trust. [updateToLatest] downloads the image into the cache dir,
 *   verifies its SHA-256 *before* flashing, and then waits for the flash
 *   confirmation dialog before calling [start] — the file-picker path's
 *   exact engine hand-off, so "Update to latest" is nothing but a cleaner way
 *   into the same [start].
 * - **Advanced: pick a file** — the document picker, unchanged.
 *
 * One launch at a time per aspect: the engine refuses a second operation
 * while one runs and the ViewModel disables the controls through
 * [FirmwareUiState.busy]. The unit is auto-detected from FRAME packets'
 * `receiver_id` (0xE1 → unit A, 0xE2 → unit B, see [FirmwareUnit]); the user
 * can override it with the A/B chips, and the manual choice wins.
 *
 * @param secretStore the typed OTA secret's storage.
 * @param engine the shared stop-and-wait OTA engine.
 * @param connectionStatus live attached-and-healthy state of the transport.
 * @param settingsStore persisted firmware server settings.
 * @param pinStore persisted TLS trust-on-first-use pins, per base URL.
 * @param lastFlashStore persisted last-flashed image hashes, per unit.
 * @param repository the SHA256SUMS/network side, pre-bound to a base URL set
 *   of stores through `FirmwareServerClient`.
 * @param transportEvents the shared transport event stream, used only to
 *   auto-detect the connected unit from FRAME packets.
 */
class FirmwareViewModel(
    private val secretStore: OtaSecretStore,
    private val engine: OtaEngine,
    connectionStatus: Flow<Boolean> = flowOf(true),
    private val settingsStore: FirmwareServerSettingsStore,
    private val pinStore: TofuPinStore,
    private val lastFlashStore: LastFlashStore,
    private val repository: FirmwareRemoteRepository,
    transportEvents: Flow<TransportEvent> = emptyFlow(),
) : ViewModel() {

    private var flashJob: Job? = null

    private var checkJob: Job? = null

    private var downloadJob: Job? = null

    /** The picked/downloaded image's bytes; never exposed to the UI state. */
    private var imageBytes: ByteArray? = null

    /** The hash recorded for the unit when the current flash completes. */
    private var pendingFlashRecord: PendingFlashRecord? = null

    private val initialSettings = settingsStore.load()

    private val _uiState = MutableStateFlow(
        FirmwareUiState(
            secret = secretStore.load(),
            secretError = OtaSecrets.validationError(secretStore.load()),
            serverUrl = initialSettings.baseUrl,
            serverUsername = initialSettings.username,
            serverPassword = initialSettings.password,
        ),
    )

    /** The screen's state snapshot. */
    val uiState: StateFlow<FirmwareUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            connectionStatus.collect { connected ->
                _uiState.update { it.copy(connected = connected) }
            }
        }
        viewModelScope.launch {
            engine.progress.collect { progress ->
                _uiState.update { it.copy(progress = progress) }
                onEngineProgress(progress)
            }
        }
        viewModelScope.launch {
            transportEvents.collect { event -> onTransportEvent(event) }
        }
    }

    // --- Secret -------------------------------------------------------------

    /**
     * Applies [text] to the field, validating as hex. A well-formed value is
     * persisted immediately so a process death never loses it; a valid edit
     * to the same value as already stored is not written again.
     */
    fun onSecretChange(text: String) {
        val error = OtaSecrets.validationError(text)
        _uiState.update { it.copy(secret = text, secretError = error, actionMessage = null) }
        if (error == null && text != secretStore.load()) {
            secretStore.save(text)
        }
    }

    // --- Server settings ----------------------------------------------------

    /** Applies and persists a syntactically valid base URL edit. */
    fun onServerUrlChange(text: String) {
        _uiState.update {
            // Blank is allowed while typing (no error bugging the user);
            // anything non-blank must parse or the field says why it won't.
            it.copy(
                serverUrl = text,
                serverUrlError = if (text.isBlank()) null else
                    FirmwareServerRules.baseUrlError(text),
                actionMessage = null,
            )
        }
        if (text.isNotBlank() && FirmwareServerRules.baseUrlError(text) == null) {
            persistServerSettings()
        }
    }

    /** Applies and persists a username edit. */
    fun onServerUsernameChange(text: String) {
        _uiState.update {
            it.copy(serverUsername = text, actionMessage = null)
        }
        if (!text.isBlank()) {
            persistServerSettings()
        }
    }

    /** Applies and persists a password edit (only the field layout changes). */
    fun onServerPasswordChange(text: String) {
        _uiState.update { it.copy(serverPassword = text, actionMessage = null) }
        persistServerSettings()
    }

    /** Show/hide the password field. */
    fun onToggleServerPasswordVisible() {
        _uiState.update { it.copy(serverPasswordVisible = !it.serverPasswordVisible) }
    }

    // --- Unit ---------------------------------------------------------------

    /**
     * Picks the unit for update checks and flashes, or (null) follows the
     * automatic detection from FRAME packets' `receiver_id`.
     */
    fun onSelectUnit(unit: FirmwareUnit?) {
        _uiState.update {
            it.copy(manualUnit = unit, actionMessage = null)
        }
    }

    // --- Update check -------------------------------------------------------

    /**
     * Checks the server for a newer image for the effective unit.
     *
     * Needs valid server credentials and a known unit (manual pick or FRAME
     * detection). A first-use certificate pauses the check with the
     * [FirmwareUiState.pendingFingerprint] dialog; confirming pins the
     * fingerprint and re-runs the check, dismissing fails the check.
     */
    fun checkForUpdate() {
        if (_uiState.value.busy) {
            _uiState.update { it.copy(actionMessage = "An update action is already running") }
            return
        }
        val run = prepareCheckRun() ?: return

        checkJob = viewModelScope.launch {
            runCheck(run)
        }
    }

    /** User confirmed the presented fingerprint: pin it and re-check. */
    fun onTrustFingerprint() {
        val prompt = _uiState.value.pendingFingerprint ?: return
        pinStore.pin(prompt.baseUrl, prompt.fingerprint)
        _uiState.update { it.copy(pendingFingerprint = null) }
        checkForUpdate()
    }

    /** User rejected the presented fingerprint: the check fails. */
    fun onDismissFingerprint() {
        _uiState.update {
            it.copy(
                pendingFingerprint = null,
                check = UpdateCheckState.Failed(TRUST_REFUSED_MESSAGE),
            )
        }
    }

    /** Clears the stored pin; the next connection is a first use again. */
    fun resetPin() {
        val baseUrl = currentSettings().normalizedBaseUrl
        if (baseUrl.isEmpty()) {
            _uiState.update { it.copy(actionMessage = "Enter the server address first") }
            return
        }
        pinStore.clear(baseUrl)
        _uiState.update {
            it.copy(
                actionMessage = "Trust cleared — the next check asks to confirm " +
                    "the server certificate again",
                check = UpdateCheckState.Idle,
            )
        }
    }

    // --- Update to latest (download + flash) --------------------------------

    /**
     * Downloads the checked image (progress via [FirmwareUiState.download]),
     * verifies its SHA-256 against the manifest, and opens the flash
     * confirmation dialog. Nothing is sent to the device until the user
     * confirms; the actual flashing is the same [start] the picker uses.
     */
    fun updateToLatest() {
        if (_uiState.value.busy) {
            _uiState.update { it.copy(actionMessage = "An update action is already running") }
            return
        }
        val state = _uiState.value
        if (!state.connected) {
            _uiState.update { it.copy(actionMessage = NOT_CONNECTED_MESSAGE) }
            return
        }
        val secret = state.secret
        if (!OtaSecrets.isWellFormed(secret)) {
            _uiState.update { it.copy(actionMessage = "Enter the 64-hex OTA secret first") }
            return
        }
        val remote = when (val check = state.check) {
            is UpdateCheckState.UpdateAvailable -> check.image
            is UpdateCheckState.UnknownBaseline -> check.image
            else -> {
                _uiState.update { it.copy(actionMessage = CHECK_FIRST_MESSAGE) }
                return
            }
        }

        val unit = state.effectiveUnit ?: run {
            _uiState.update { it.copy(actionMessage = MANUAL_UNIT_MESSAGE) }
            return
        }
        val settings = currentSettings()
        downloadJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(download = DownloadProgress(0, null)) }
                val bytes = repository.fetchImage(settings, remote) { read, total ->
                    // Flow-emitting on the IO thread is safe for StateFlow.
                    _uiState.update {
                        it.copy(download = DownloadProgress(read, total))
                    }
                }
                acceptImageBytes(name = remote.fileName, bytes = bytes)
                _uiState.update {
                    it.copy(
                        download = null,
                        pendingFlashConfirm = OtaImageInfo(
                            name = remote.fileName,
                            sizeBytes = bytes.size,
                            sha256Hex = remote.sha256Hex,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(download = null) }
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        download = null,
                        // A FirstUse prompt appearing mid-download (pin cleared
                        // between check and download) is a trust, not error,
                        // surface.
                        pendingFingerprint =
                            (error as? FirmwareServerException.FirstUseCertificate)
                                ?.let { fp -> FingerprintPrompt(settings.normalizedBaseUrl, fp.fingerprint) }
                                ?: it.pendingFingerprint,
                        actionMessage = FirmwareErrorText.describe(error),
                    )
                }
            }
        }
    }

    /** Confirms flashing the downloaded image (the required user tap). */
    fun onConfirmFlash() {
        val confirmed = _uiState.value.pendingFlashConfirm ?: return
        _uiState.update { it.copy(pendingFlashConfirm = null) }
        start()
    }

    /** Cancels the flash-confirmation dialog; the verified image stays ready. */
    fun onDismissFlashConfirm() {
        _uiState.update { it.copy(pendingFlashConfirm = null) }
    }

    // --- Image (fallback picker path) ---------------------------------------

    /**
     * Accepts the picked image after the safety checks, or records why it was
     * rejected: a merged flash file (exactly 4 MiB) and any file without the
     * ESP image magic `0xE9` as its first byte are refused here.
     */
    fun onImageRead(name: String, bytes: ByteArray) {
        acceptImageBytes(name, bytes)
    }

    /** Records a read failure from the document picker flow. */
    fun onImageReadFailed(message: String) {
        _uiState.update { it.copy(imageError = message) }
    }

    // --- Flash --------------------------------------------------------------

    /** Starts the update with the current field values (shared by both paths). */
    fun start() {
        if (_uiState.value.busy) {
            _uiState.update { it.copy(actionMessage = "An update is already running") }
            return
        }
        if (!uiState.value.connected) {
            _uiState.update { it.copy(actionMessage = NOT_CONNECTED_MESSAGE) }
            return
        }
        val secret = _uiState.value.secret
        if (!OtaSecrets.isWellFormed(secret)) {
            _uiState.update { it.copy(actionMessage = "Enter the 64-hex OTA secret first") }
            return
        }
        val bytes = imageBytes
        if (bytes == null || _uiState.value.image == null) {
            _uiState.update { it.copy(actionMessage = "Choose the .bin to flash first") }
            return
        }
        val unit = _uiState.value.effectiveUnit ?: run {
            _uiState.update { it.copy(actionMessage = MANUAL_UNIT_MESSAGE) }
            return
        }

        // Remember what is being flashed so the persisted last-flashed hash
        // (the update check's baseline) is written on DONE.
        pendingFlashRecord = PendingFlashRecord(
            unit = unit,
            sha256Hex = _uiState.value.image?.sha256Hex ?: "",
        )

        flashJob = viewModelScope.launch {
            engine.flash(bytes, OtaSecrets.decode(secret))
        }
    }

    /** Cancels the running download and/or update. */
    fun cancel() {
        downloadJob?.cancel()
        flashJob?.cancel()
    }

    /** Reboots the device into the ROM boot loader for USB flashing. */
    fun rebootToBootloader() {
        if (_uiState.value.busy) {
            _uiState.update { it.copy(actionMessage = "An update is already running") }
            return
        }
        if (!_uiState.value.connected) {
            _uiState.update { it.copy(actionMessage = NOT_CONNECTED_MESSAGE) }
            return
        }
        val secret = _uiState.value.secret
        if (!OtaSecrets.isWellFormed(secret)) {
            _uiState.update { it.copy(actionMessage = "Enter the 64-hex OTA secret first") }
            return
        }
        val unit = _uiState.value.effectiveUnit ?: run {
            _uiState.update { it.copy(actionMessage = MANUAL_UNIT_MESSAGE) }
            return
        }

        flashJob = viewModelScope.launch {
            engine.rebootToBootloader(OtaSecrets.decode(secret))
        }
    }

    // --- Internals ----------------------------------------------------------

    private data class CheckRun(
        val settings: FirmwareServerSettings,
        val unit: FirmwareUnit,
    )

    /**
     * Validates the current settings/unit for a check run, persisting
     * well-formed values and reporting back the reason nothing runs, or null
     * when a run may proceed.
     */
    private fun prepareCheckRun(): CheckRun? {
        val settings = currentSettings()
        val urlError = FirmwareServerRules.baseUrlError(settings.baseUrl)
        val userError = FirmwareServerRules.usernameError(settings.username)
        val passwordError = FirmwareServerRules.passwordError(settings.password)
        if (urlError != null || userError != null || passwordError != null) {
            _uiState.update { current ->
                current.copy(
                    actionMessage = urlError ?: userError ?: passwordError,
                    serverUrlError = urlError,
                )
            }
            return null
        }
        val unit = _uiState.value.effectiveUnit ?: run {
            _uiState.update { it.copy(actionMessage = MANUAL_UNIT_MESSAGE) }
            return null
        }
        persistServerSettings()
        return CheckRun(settings = settings, unit = unit)
    }

    /** Executes one check run, mapping outcomes into the check card state. */
    private suspend fun runCheck(run: CheckRun) {
        _uiState.update { it.copy(check = UpdateCheckState.Checking, actionMessage = null) }

        try {
            val state = repository.checkForUpdate(
                settings = run.settings,
                unit = run.unit,
                lastFlashedSha256 = lastFlashStore.load(run.unit),
            )
            _uiState.update { it.copy(check = state) }
        } catch (cancelled: CancellationException) {
            _uiState.update { it.copy(check = UpdateCheckState.Idle) }
            throw cancelled
        } catch (error: Exception) {
            _uiState.update { current ->
                current.copy(
                    check = when (error) {
                        is FirmwareServerException.FirstUseCertificate ->
                            UpdateCheckState.Idle

                        else -> UpdateCheckState.Failed(FirmwareErrorText.describe(error))
                    },
                    // The certificate dialog replaces "checking" whichever
                    // direction the handshake went.
                    pendingFingerprint =
                        (error as? FirmwareServerException.FirstUseCertificate)?.let { fp ->
                            FingerprintPrompt(run.settings.normalizedBaseUrl, fp.fingerprint)
                        } ?: current.pendingFingerprint,
                )
            }
        }
    }

    /** Current settings as (possibly invalid) typed in — never persisted here. */
    private fun currentSettings(): FirmwareServerSettings {
        val state = _uiState.value
        return FirmwareServerSettings(
            baseUrl = state.serverUrl,
            username = state.serverUsername,
            password = state.serverPassword,
        )
    }

    /** Writes the well-formed subset of the current settings to storage. */
    private fun persistServerSettings() {
        val state = _uiState.value
        val valid = FirmwareServerRules.baseUrlError(state.serverUrl) == null &&
            FirmwareServerRules.usernameError(state.serverUsername) == null
        // The password may be empty while the user types; an empty password is
        // still saved (a 401 will ask for the real one at check time).
        if (valid) {
            settingsStore.save(currentSettings())
        }
    }

    /**
     * The shared image-acceptance path (picker and verified download): bytes
     * in memory plus [OtaImageInfo] in the state, guarded by the magic and
     * merged-image checks that keep -merged.bin out of the update flow.
     */
    private fun acceptImageBytes(name: String, bytes: ByteArray) {
        when {
            bytes.isEmpty() -> refuseImage("$name is empty")

            bytes.size == MERGED_IMAGE_SIZE -> refuseImage(
                "$name is exactly 4,194,304 bytes — that is the merged flash " +
                    "image (-merged.bin). Pick the app image " +
                    "(esp32c3-cc1101-lab.ino.bin) instead.",
            )

            bytes[0] != ESP_IMAGE_MAGIC -> refuseImage(
                "$name does not start with 0xE9, so it is not an ESP32 app " +
                    "image. Pick esp32c3-cc1101-lab.ino.bin (not -merged.bin).",
            )

            else -> {
                imageBytes = bytes
                _uiState.update {
                    it.copy(
                        image = OtaImageInfo(
                            name = name,
                            sizeBytes = bytes.size,
                            sha256Hex = binToHex(OtaAuth.sha256(bytes)),
                        ),
                        imageError = null,
                        actionMessage = null,
                    )
                }
            }
        }
    }

    private fun refuseImage(message: String) {
        imageBytes = null
        _uiState.update {
            it.copy(image = null, imageError = message, actionMessage = null)
        }
    }

    /** Tracks engine completion to persist the last-flashed hash per unit. */
    private fun onEngineProgress(progress: OtaProgress) {
        when (progress.phase) {
            OtaPhase.DONE -> {
                pendingFlashRecord?.let { record ->
                    if (record.sha256Hex.isNotEmpty()) {
                        lastFlashStore.save(record.unit, record.sha256Hex)
                    }
                    pendingFlashRecord = null
                }
            }

            OtaPhase.FAILED -> pendingFlashRecord = null

            else -> Unit
        }
    }

    /** FRAME packets carry the unit's `receiver_id`; use it for auto-detect. */
    private fun onTransportEvent(event: TransportEvent) {
        if (event !is TransportEvent.PacketReceived) return
        val packet = event.packet
        if (packet !is FramePacket) return
        val unit = FirmwareUnit.fromReceiverId(packet.receiverId) ?: return
        if (_uiState.value.detectedUnit != unit) {
            _uiState.update { it.copy(detectedUnit = unit) }
        }
    }

    /** What a finished flash should record: which unit got which image. */
    private data class PendingFlashRecord(
        val unit: FirmwareUnit,
        val sha256Hex: String,
    )

    companion object {
        /** Shown when an action is attempted with no live connection. */
        const val NOT_CONNECTED_MESSAGE: String =
            "Not connected — connect the board on the Connect tab first"

        /** Merged full-flash images are exactly this many bytes (2 x 2 MiB slots). */
        const val MERGED_IMAGE_SIZE = 4_194_304

        /** ESP32 app image magic byte. */
        const val ESP_IMAGE_MAGIC: Byte = 0xE9.toByte()

        const val CHECK_FIRST_MESSAGE: String =
            "Check for update first — the image list comes from the server"

        const val MANUAL_UNIT_MESSAGE: String =
            "Pick the unit (A or B) — no frame has identified the board yet"

        const val TRUST_REFUSED_MESSAGE: String =
            "The server was not trusted, so the check stopped — trust the " +
                "certificate (after comparing the fingerprint) and retry"

        /** ViewModel factory wired with the shared engine and stores. */
        fun factory(
            secretStore: OtaSecretStore,
            engine: OtaEngine,
            connectionStatus: Flow<Boolean>,
            settingsStore: FirmwareServerSettingsStore,
            pinStore: TofuPinStore,
            lastFlashStore: LastFlashStore,
            repository: FirmwareRemoteRepository,
            transportEvents: Flow<TransportEvent>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FirmwareViewModel(
                    secretStore = secretStore,
                    engine = engine,
                    connectionStatus = connectionStatus,
                    settingsStore = settingsStore,
                    pinStore = pinStore,
                    lastFlashStore = lastFlashStore,
                    repository = repository,
                    transportEvents = transportEvents,
                )
            }
        }
    }
}

private fun binToHex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        out.append(HEX[(byte.toInt() ushr 4) and 0xF])
        out.append(HEX[byte.toInt() and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
