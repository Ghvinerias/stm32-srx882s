package com.eelab.rflab.ui.firmware

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.espflash.EspFlashPhase
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The additive "Flash over USB (no OTA)" card.
 *
 * This is the recovery path for a board whose app-side OTA path is broken:
 * it picks a local image through the Storage Access Framework, validates it,
 * and flashes it through the ESP32-C3 ROM bootloader over USB-C OTG. It does
 * not use the OTA secret, the server, the engine, or any cooperation from the
 * installed firmware — which is exactly why it is a separate card and a
 * separate ViewModel from [FirmwareViewModel].
 *
 * The card owns its picker (Activity result) so `FirmwareScreen` only has to
 * place the composable; the existing OTA form is untouched. [enabled] is
 * false while an OTA transfer owns the device.
 */
@Composable
fun UsbFlashSection(
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: UsbFlashViewModel = viewModel(
        factory = UsbFlashViewModel.factory(context.applicationContext),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    UsbFlashSection(
        state = state,
        enabled = enabled,
        onPickStarted = viewModel::onImageReadStarted,
        onImageRead = viewModel::onImagePicked,
        onImageReadFailed = viewModel::onImageReadFailed,
        onRequestFlash = viewModel::requestFlash,
        onConfirmFlash = viewModel::confirmFlash,
        onDismissConfirm = viewModel::dismissConfirm,
        onCancel = viewModel::cancel,
        onClear = viewModel::clearImage,
        modifier = modifier,
    )
}

/** Stateless body, kept separate for previews and state tests. */
@Composable
internal fun UsbFlashSection(
    state: UsbFlashUiState,
    enabled: Boolean,
    onPickStarted: () -> Unit,
    onImageRead: (String, ByteArray) -> Unit,
    onImageReadFailed: (String) -> Unit,
    onRequestFlash: () -> Unit,
    onConfirmFlash: () -> Unit,
    onDismissConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            onPickStarted()
            scope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "image.bin"
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: ByteArray(0)
                    }
                    onImageRead(name, bytes)
                } catch (error: Exception) {
                    onImageReadFailed(
                        "Could not read the picked file: ${error.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    if (state.confirmVisible) {
        UsbFlashConfirmDialog(
            image = state.image,
            onConfirm = onConfirmFlash,
            onDismiss = onDismissConfirm,
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Flash over USB (no OTA)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Writes a local firmware file through the ESP32-C3 ROM " +
                    "loader over a USB-C OTG cable. No OTA secret, no server, " +
                    "and no help from the app firmware — this still works when " +
                    "the OTA path on the device is broken.",
                style = MaterialTheme.typography.bodySmall,
            )

            state.image?.let { image ->
                Text(
                    text = "${image.name} · ${formatBytes(image.sizeBytes.toLong())} bytes",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = image.placement,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "SHA-256 ${image.sha256Hex}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (state.reading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Text("Reading the file…", style = MaterialTheme.typography.bodySmall)
                }
            }

            state.imageError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = enabled && !state.busy && !state.reading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.image == null) "Pick firmware .bin" else "Pick another")
                }
                if (state.image != null) {
                    TextButton(
                        onClick = onClear,
                        enabled = enabled && !state.busy && !state.reading,
                    ) {
                        Text("Clear")
                    }
                }
            }

            Button(
                onClick = onRequestFlash,
                enabled = enabled && state.canFlash,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.busy) "Flashing over USB…" else "Flash over USB")
            }

            if (state.busy) {
                UsbFlashProgress(state = state)
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel flash")
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.recoveryHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.succeeded && !state.busy) {
                state.summary?.let { summary ->
                    Text(
                        text = "Flashed and verified — MD5 ${summary.md5Hex}, " +
                            "${formatBytes(summary.bytesWritten)} bytes at " +
                            "0x${summary.targetOffset.toString(16).uppercase()}. " +
                            "The board rebooted into the new firmware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "Recovery: if the board does not enter download mode by " +
                    "itself, hold BOOT (the button on GPIO9), tap RESET/EN, " +
                    "release BOOT, then tap Flash over USB again. After a " +
                    "cancelled or failed flash the image may be incomplete — " +
                    "flash it again to finish.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Progress line: phase label, bar and byte counter. */
@Composable
private fun UsbFlashProgress(state: UsbFlashUiState) {
    val progress = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = state.phaseLabel,
            style = MaterialTheme.typography.titleSmall,
        )
        if (progress.phase == EspFlashPhase.WRITING && progress.totalBytes > 0) {
            val fraction = (progress.bytesWritten.toFloat() / progress.totalBytes.toFloat())
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${formatBytes(progress.bytesWritten)} / " +
                    "${formatBytes(progress.totalBytes)} bytes" +
                    (progress.percent?.let { " ($it%)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            // Erase and verify can each take tens of seconds and have no
            // byte-level signal from the ROM, so show indeterminate motion.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        progress.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The required explicit confirmation before any bytes are written. */
@Composable
private fun UsbFlashConfirmDialog(
    image: UsbImageInfo?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flash over USB?") },
        text = {
            if (image == null) {
                Text("No image is selected.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${image.name} · ${formatBytes(image.sizeBytes.toLong())} bytes")
                    Text(
                        text = image.placement,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "SHA-256 ${image.sha256Hex}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "The board resets into its ROM bootloader, the " +
                            "whole target range is erased, the image is written " +
                            "uncompressed, and the ROM's MD5 is checked before " +
                            "rebooting. Do not unplug the cable until this " +
                            "finishes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = image != null,
            ) {
                Text("Erase and flash")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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

/** Display formatting matching the rest of the app's byte counts. */
private fun formatBytes(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)
