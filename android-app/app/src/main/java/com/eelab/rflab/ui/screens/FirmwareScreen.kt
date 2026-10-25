package com.eelab.rflab.ui.screens

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.ota.OtaEngine
import com.eelab.rflab.ota.OtaPhase
import com.eelab.rflab.ota.OtaProgress
import com.eelab.rflab.ota.OtaSecretStore
import com.eelab.rflab.ota.update.FirmwareServerRules
import com.eelab.rflab.ota.update.FirmwareUnit
import com.eelab.rflab.ota.update.TofuPins
import com.eelab.rflab.ota.update.UpdateCheckState
import com.eelab.rflab.usb.TransportEvent
import com.eelab.rflab.ui.firmware.FirmwareUiState
import com.eelab.rflab.ui.firmware.FirmwareViewModel
import com.eelab.rflab.ui.firmware.OtaImageInfo
import com.eelab.rflab.ui.firmware.UsbFlashSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * "Firmware update" screen: type the OTA secret, then either pull the latest
 * app image from the firmware web server ("Update to latest" — the check
 * and download run trust-on-first-use TLS and SHA-256 verification) or, as a
 * fallback ("Advanced: pick a file"), choose a .bin manually; either way
 * the image is flashed stop-and-wait over the current link (BLE NUS or
 * USB-CDC), or the device is rebooted into the ROM boot loader for USB
 * flashing.
 *
 * The app composition root passes the shared [OtaEngine] (see
 * [com.eelab.rflab.ota.OtaGraph]), the SharedPreferences-backed
 * [OtaSecretStore], the firmware server settings/pin/last-flash stores and the
 * server repository; the ViewModel owns the image bytes, this file only
 * bridges the document picker and shows the state.
 */
@Composable
fun FirmwareScreen(
    secretStore: OtaSecretStore,
    engine: OtaEngine,
    connectionStatus: Flow<Boolean>,
    settingsStore: com.eelab.rflab.ota.update.FirmwareServerSettingsStore,
    pinStore: com.eelab.rflab.ota.update.TofuPinStore,
    lastFlashStore: com.eelab.rflab.ota.update.LastFlashStore,
    repository: com.eelab.rflab.ota.update.FirmwareRemoteRepository,
    transportEvents: Flow<TransportEvent>,
    modifier: Modifier = Modifier,
) {
    val viewModel: FirmwareViewModel = viewModel(
        factory = FirmwareViewModel.factory(
            secretStore = secretStore,
            engine = engine,
            connectionStatus = connectionStatus,
            settingsStore = settingsStore,
            pinStore = pinStore,
            lastFlashStore = lastFlashStore,
            repository = repository,
            transportEvents = transportEvents,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    FirmwareScreen(
        state = state,
        onSecretChange = viewModel::onSecretChange,
        onImageRead = viewModel::onImageRead,
        onImageReadFailed = viewModel::onImageReadFailed,
        onStart = viewModel::start,
        onCancel = viewModel::cancel,
        onRebootToBootloader = viewModel::rebootToBootloader,
        onServerUrlChange = viewModel::onServerUrlChange,
        onServerUsernameChange = viewModel::onServerUsernameChange,
        onServerPasswordChange = viewModel::onServerPasswordChange,
        onToggleServerPasswordVisible = viewModel::onToggleServerPasswordVisible,
        onSelectUnit = viewModel::onSelectUnit,
        onCheckForUpdate = viewModel::checkForUpdate,
        onTrustFingerprint = viewModel::onTrustFingerprint,
        onDismissFingerprint = viewModel::onDismissFingerprint,
        onResetPin = viewModel::resetPin,
        onUpdateToLatest = viewModel::updateToLatest,
        onConfirmFlash = viewModel::onConfirmFlash,
        onDismissFlashConfirm = viewModel::onDismissFlashConfirm,
        modifier = modifier,
    )
}

/**
 * Stateless body, kept separate so the form can be previewed without a
 * shared engine. The document picking (an Activity result) lives here because
 * it needs a [Context] to read through the content resolver.
 */
@Composable
internal fun FirmwareScreen(
    state: FirmwareUiState,
    onSecretChange: (String) -> Unit,
    onImageRead: (name: String, bytes: ByteArray) -> Unit,
    onImageReadFailed: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRebootToBootloader: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onServerUsernameChange: (String) -> Unit,
    onServerPasswordChange: (String) -> Unit,
    onToggleServerPasswordVisible: () -> Unit,
    onSelectUnit: (FirmwareUnit?) -> Unit,
    onCheckForUpdate: () -> Unit,
    onTrustFingerprint: () -> Unit,
    onDismissFingerprint: () -> Unit,
    onResetPin: () -> Unit,
    onUpdateToLatest: () -> Unit,
    onConfirmFlash: () -> Unit,
    onDismissFlashConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes()
                        } ?: ByteArray(0)
                    }
                    val name = withContext(Dispatchers.IO) {
                        queryDisplayName(context, uri)
                            ?: uri.lastPathSegment
                            ?: "image.bin"
                    }
                    onImageRead(name, bytes)
                } catch (error: Exception) {
                    onImageReadFailed("Could not read the picked file: ${error.message}")
                }
            }
        }
    }

    var confirmBootloader by remember { mutableStateOf(false) }
    if (confirmBootloader) {
        BootloaderConfirmDialog(
            onConfirm = {
                confirmBootloader = false
                onRebootToBootloader()
            },
            onDismiss = { confirmBootloader = false },
        )
    }

    state.pendingFingerprint?.let { prompt ->
        FingerprintConfirmDialog(
            prompt = prompt,
            onConfirm = onTrustFingerprint,
            onDismiss = onDismissFingerprint,
        )
    }

    state.pendingFlashConfirm?.let { confirmedImage ->
        FlashConfirmDialog(
            image = confirmedImage,
            onConfirm = onConfirmFlash,
            onDismiss = onDismissFlashConfirm,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Firmware update",
            style = MaterialTheme.typography.titleLarge,
        )

        if (!state.connected) {
            Text(
                text = "Not connected — connect the board (BLE or USB) on the " +
                    "Connect tab to update firmware.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SecretCard(
            secret = state.secret,
            error = state.secretError,
            onSecretChange = onSecretChange,
        )

        ServerUpdateCard(
            state = state,
            enabled = !state.busy,
            onServerUrlChange = onServerUrlChange,
            onServerUsernameChange = onServerUsernameChange,
            onServerPasswordChange = onServerPasswordChange,
            onToggleServerPasswordVisible = onToggleServerPasswordVisible,
            onSelectUnit = onSelectUnit,
            onCheck = onCheckForUpdate,
            onResetPin = onResetPin,
            onUpdateToLatest = onUpdateToLatest,
            onCancel = onCancel,
            onPickManually = { picker.launch(arrayOf("*/*")) },
        )

        WarningCard()

        state.actionMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        ProgressCard(
            state = state,
            onStart = onStart,
            onCancel = onCancel,
        )

        // Recovery path that does not need the OTA secret or working app
        // firmware: the ROM loader over USB-C OTG. Disabled while an OTA
        // transfer owns the device.
        UsbFlashSection(enabled = !state.busy)

        OutlinedButton(
            onClick = { confirmBootloader = true },
            enabled = state.connected && !state.busy && state.secretError == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reboot device into bootloader (USB flashing)")
        }
    }
}

@Composable
private fun SecretCard(
    secret: String,
    error: String?,
    onSecretChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "OTA secret",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = secret,
                onValueChange = onSecretChange,
                label = { Text("Secret (64 hex characters)") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Must match the LAB_OTA_SECRET_HEX the device firmware " +
                    "was built with — otherwise every authenticated operation " +
                    "is rejected (wrong secret). It is stored only in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The "latest from the web server" card: server URL, Basic credentials (with
 * a show/hide password toggle), the unit chips (auto-detected from FRAME
 * packets' `receiver_id`, overridable A/B), the "Check for update" action and
 * the "Update to latest" flow. Downloading shows a progress bar; flashing the
 * downloaded image still waits for the user's confirm dialog.
 */
@Composable
private fun ServerUpdateCard(
    state: FirmwareUiState,
    enabled: Boolean,
    onServerUrlChange: (String) -> Unit,
    onServerUsernameChange: (String) -> Unit,
    onServerPasswordChange: (String) -> Unit,
    onToggleServerPasswordVisible: () -> Unit,
    onSelectUnit: (FirmwareUnit?) -> Unit,
    onCheck: () -> Unit,
    onResetPin: () -> Unit,
    onUpdateToLatest: () -> Unit,
    onCancel: () -> Unit,
    onPickManually: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Update from server",
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server (https)") },
                placeholder = { Text(FirmwareServerRules.URL_PLACEHOLDER) },
                singleLine = true,
                isError = state.serverUrlError != null,
                supportingText = state.serverUrlError?.let { message ->
                    { Text(message) }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.serverUsername,
                onValueChange = onServerUsernameChange,
                label = { Text("Server user") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.serverPassword,
                onValueChange = onServerPasswordChange,
                label = { Text("Server password") },
                singleLine = true,
                visualTransformation = if (state.serverPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = onToggleServerPasswordVisible) {
                        Text(if (state.serverPasswordVisible) "Hide" else "Show")
                    }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Stored app-private, like the OTA secret. The password " +
                    "is the flash server's (~/.keeloq-flash-pass), not the OTA " +
                    "secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UnitChips(
                manualUnit = state.manualUnit,
                detectedUnit = state.detectedUnit,
                enabled = enabled,
                onSelect = onSelectUnit,
            )

            Button(
                onClick = onCheck,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.check is UpdateCheckState.Checking) "Checking…" else "Check for update")
            }

            CheckResult(state = state.check)

            DownloadRow(state = state.download, onCancel = onCancel)

            when (state.check) {
                is UpdateCheckState.UpdateAvailable,
                is UpdateCheckState.UnknownBaseline,
                -> Button(
                    onClick = onUpdateToLatest,
                    enabled = state.canUpdateToLatest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Update to latest")
                }

                else -> Unit
            }

            TextButton(onClick = onResetPin, enabled = enabled) {
                Text("Reset server trust")
            }

            OutlinedButton(
                onClick = onPickManually,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Advanced: pick a file")
            }
        }
    }
}

/** Unit selector: auto-detect from FRAME `receiver_id`, user-overridable. */
@Composable
private fun UnitChips(
    manualUnit: FirmwareUnit?,
    detectedUnit: FirmwareUnit?,
    enabled: Boolean,
    onSelect: (FirmwareUnit?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Unit",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = manualUnit == null,
                onClick = { onSelect(null) },
                label = { Text("Auto") },
                enabled = enabled,
            )
            for (unit in FirmwareUnit.entries) {
                FilterChip(
                    selected = manualUnit == unit,
                    onClick = { onSelect(unit) },
                    label = { Text(unit.label) },
                    enabled = enabled,
                )
            }
        }
        Text(
            text = when {
                manualUnit != null -> "Manual pick: ${manualUnit.label}"
                detectedUnit != null ->
                    "Detected: ${detectedUnit.label} (receiver_id " +
                        "0x${"%02X".format(Locale.US, detectedUnit.receiverId)})"

                else -> "No frame from a C3 board yet — a check needs a pick"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The check's outcome line: up to date / update available / failure. */
@Composable
private fun CheckResult(state: UpdateCheckState) {
    when (state) {
        is UpdateCheckState.UpToDate -> Text(
            text = "Up to date — the server's image (SHA-256 " +
                TofuPins.shortHash(state.sha256Hex) + "…) matches what was " +
                "last flashed here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is UpdateCheckState.UpdateAvailable -> Text(
            text = "Update available: ${state.image.fileName} " +
                "(SHA-256 ${TofuPins.shortHash(state.image.sha256Hex)}…, " +
                describeSize(state.image.sizeBytes) + ") — differs from the " +
                "image last flashed here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is UpdateCheckState.UnknownBaseline -> Text(
            text = "Image available: ${state.image.fileName} " +
                "(SHA-256 ${TofuPins.shortHash(state.image.sha256Hex)}…, " +
                describeSize(state.image.sizeBytes) + ") — no record of what " +
                "this unit runs (flash or pick a file here once to establish " +
                "a baseline)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is UpdateCheckState.Failed -> Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Unit
    }
}

/** The image download's progress line, with cancel. */
@Composable
private fun DownloadRow(
    state: com.eelab.rflab.ui.firmware.DownloadProgress?,
    onCancel: () -> Unit,
) {
    if (state == null) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp))
            Text(
                text = "Downloading image… ${formatBytes(state.bytesRead)}" +
                    (state.totalBytes?.let { total -> " / ${formatBytes(total)} bytes" }
                        ?: " bytes"),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        state.totalBytes?.let { total ->
            if (total > 0) {
                val fraction = (state.bytesRead.toFloat() / total.toFloat())
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "This flashes the device!",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "\"Update to latest\" downloads the app image for the " +
                    "selected unit, checks its SHA-256 against the server's " +
                    "SHA256SUMS, and asks before flashing. The fallback " +
                    "picker takes a local .bin instead — never a " +
                    "*-merged.bin (rejected). The old image is overwritten " +
                    "and the device reboots into the new one; if the new " +
                    "firmware does not run for ~10 s, the bootloader rolls " +
                    "back automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProgressCard(
    state: FirmwareUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = state.progress
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress.phase != OtaPhase.IDLE) {
                PhaseHeader(progress = progress)
            }

            state.image?.let { image ->
                Text(
                    text = "Ready: ${image.name} " +
                        "(${formatBytes(image.sizeBytes.toLong())} bytes, " +
                        "SHA-256 ${image.sha256Hex})",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.running) "Updating…" else "Start firmware update")
            }
            if (state.running) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun PhaseHeader(progress: OtaProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = when (progress.phase) {
                OtaPhase.IDLE -> ""
                OtaPhase.HELLO -> "Talking to the device…"
                OtaPhase.BEGIN -> "Authenticating the image…"
                OtaPhase.TRANSFER -> "Transferring…"
                OtaPhase.END -> "Finishing (the device reboots after END)…"
                OtaPhase.BOOTLOADER -> "Requesting bootloader reboot…"
                OtaPhase.DONE -> "Done"
                OtaPhase.FAILED -> "Failed"
            },
            style = MaterialTheme.typography.titleSmall,
        )

        if (progress.phase == OtaPhase.TRANSFER && progress.totalBytes > 0) {
            val fraction =
                (progress.bytesSent.toFloat() / progress.totalBytes.toFloat())
                    .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${formatBytes(progress.bytesSent)} / " +
                    "${formatBytes(progress.totalBytes)} bytes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (progress.phase in OtaPhase.HELLO..OtaPhase.BOOTLOADER) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        progress.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        progress.note?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Trust-on-first-use: the user must explicitly confirm the fingerprint. */
@Composable
private fun FingerprintConfirmDialog(
    prompt: com.eelab.rflab.ui.firmware.FingerprintPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "First connection to ${prompt.baseUrl}. Verify the " +
                        "server certificate's SHA-256:",
                )
                Text(
                    text = TofuPins.formatFingerprint(prompt.fingerprint),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Compare it with the fingerprint the server shows " +
                        "on the machine that hosts it (or remember what the " +
                        "certificate looked like at first connect). Tapping " +
                        "Trust pins it; any later change is rejected until " +
                        "you tap Reset server trust.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Trust") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** The required confirm step before the downloaded image reaches the device. */
@Composable
private fun FlashConfirmDialog(
    image: OtaImageInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flash the downloaded image?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${image.name} · ${formatBytes(image.sizeBytes.toLong())} bytes",
                )
                Text(
                    text = "SHA-256 ${image.sha256Hex}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "The device reboots into the new firmware; the link " +
                        "drops and the app reconnects. If the new image does " +
                        "not run for ~10 s, the bootloader rolls back.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Flash now") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun BootloaderConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reboot into bootloader?") },
        text = {
            Text(
                "The device reboots into ROM download mode (~200 ms after " +
                    "accepting the request) and the BLE/USB link drops. Flash " +
                    "it over USB with esptool/idf.py afterwards.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reboot") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay") }
        },
    )
}

/** Reads the display name of a picked document URI, or returns null. */
private suspend fun queryDisplayName(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
    }

/** "1,234,567 bytes", or "size unknown" when the server gave no length. */
private fun describeSize(sizeBytes: Long?): String = when (sizeBytes) {
    null -> "size unknown"
    else -> formatBytes(sizeBytes) + " bytes"
}

/** Display formatting matching the rest of the app's byte counts. */
private fun formatBytes(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)
