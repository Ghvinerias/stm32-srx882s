package com.eelab.rflab.ui.firmware

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.espflash.EspFlashPhase
import com.eelab.rflab.espflash.EspFlashProgress
import com.eelab.rflab.espflash.EspFlashResult
import com.eelab.rflab.espflash.FlashImage
import com.eelab.rflab.espflash.FlashImageClassification
import com.eelab.rflab.espflash.FlashImageSource
import com.eelab.rflab.espflash.FlashImages
import com.eelab.rflab.espflash.UsbEspFlashController
import com.eelab.rflab.espflash.UsbFlashRunner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and actions for the "Flash over USB (no OTA)" card.
 *
 * Deliberately independent of [FirmwareViewModel] and of the OTA engine: it
 * owns the picked bytes, validates them with [FlashImages], and drives a
 * [UsbFlashRunner] (the Android implementation is
 * [UsbEspFlashController]). The screen composes the card next to the OTA
 * card, with both disabled while the other is busy, but a failure in one path
 * never blocks the other.
 *
 * The image bytes are private so a `ByteArray` is never part of Compose state
 * (which would make recomposition jump). Only [UsbImageInfo] reaches the UI.
 *
 * @param runner the USB flash implementation; tests inject a fake.
 */
class UsbFlashViewModel(
    private val runner: UsbFlashRunner,
) : ViewModel() {

    private var image: FlashImage? = null

    private var flashJob: Job? = null

    private val cancelRequested = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(UsbFlashUiState())

    /** The card's state snapshot. */
    val uiState: StateFlow<UsbFlashUiState> = _uiState.asStateFlow()

    /** Marks the picker's read as started (shows a spinner, disables Flash). */
    fun onImageReadStarted() {
        _uiState.update { it.copy(reading = true, imageError = null) }
    }

    /**
     * Accepts bytes read from the document picker (or handed over by another
     * flow) and validates them.
     */
    fun onImagePicked(name: String, bytes: ByteArray) {
        when (val classification = FlashImages.classify(name, bytes)) {
            is FlashImageClassification.Accepted -> {
                image = classification.image
                _uiState.update {
                    it.copy(
                        image = UsbFlashUiState.infoFor(classification.image),
                        imageError = null,
                        reading = false,
                        progress = EspFlashProgress(),
                        summary = null,
                        error = null,
                        recoveryHint = null,
                    )
                }
            }

            is FlashImageClassification.Rejected -> {
                image = null
                _uiState.update {
                    it.copy(
                        image = null,
                        imageError = classification.message,
                        reading = false,
                        summary = null,
                        error = null,
                        recoveryHint = null,
                    )
                }
            }
        }
    }

    /**
     * Hand-off point for the concurrent download path: the other flow passes
     * the verified bytes and name without going through SAF. The same
     * classification runs, so an app image is never mistaken for a merged one.
     */
    fun offerImage(source: FlashImageSource) {
        onImagePicked(source.name, source.bytes)
    }

    /** The picker failed before bytes existed. */
    fun onImageReadFailed(message: String) {
        image = null
        _uiState.update {
            it.copy(
                image = null,
                imageError = message,
                reading = false,
                summary = null,
                error = null,
                recoveryHint = null,
            )
        }
    }

    /** Forgets the picked image (does nothing while a flash owns the card). */
    fun clearImage() {
        if (_uiState.value.busy) return
        image = null
        _uiState.update {
            it.copy(
                image = null,
                imageError = null,
                progress = EspFlashProgress(),
                summary = null,
                error = null,
                recoveryHint = null,
            )
        }
    }

    /** Opens the explicit "flash this image?" dialog. */
    fun requestFlash() {
        if (!_uiState.value.canFlash) return
        _uiState.update { it.copy(confirmVisible = true) }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirmVisible = false) }
    }

    /** Starts the flash after the user confirmed. */
    fun confirmFlash() {
        val current = image ?: return
        if (_uiState.value.busy || flashJob?.isActive == true) {
            _uiState.update { it.copy(confirmVisible = false) }
            return
        }
        cancelRequested.set(false)
        _uiState.update {
            it.copy(
                confirmVisible = false,
                error = null,
                recoveryHint = null,
                summary = null,
                progress = EspFlashProgress(
                    phase = EspFlashPhase.ENTERING_BOOTLOADER,
                    totalBytes = current.bytes.size.toLong(),
                    note = "Preparing the USB link",
                ),
            )
        }
        flashJob = viewModelScope.launch {
            try {
                when (
                    val result = runner.flash(
                        image = current,
                        onProgress = ::onProgress,
                        isCancelled = cancelRequested::get,
                    )
                ) {
                    is EspFlashResult.Success -> _uiState.update {
                        it.copy(
                            summary = result.summary,
                            progress = it.progress.copy(
                                phase = EspFlashPhase.DONE,
                                bytesWritten = result.summary.bytesWritten,
                                totalBytes = result.summary.bytesWritten,
                                note = "Flashed and verified (MD5 " +
                                    result.summary.md5Hex + ")",
                            ),
                        )
                    }

                    is EspFlashResult.Failure -> _uiState.update {
                        it.copy(
                            error = result.message,
                            recoveryHint = result.recoveryHint,
                            progress = it.progress.copy(
                                phase = EspFlashPhase.FAILED,
                                note = result.message,
                            ),
                        )
                    }

                    EspFlashResult.Cancelled -> _uiState.update {
                        it.copy(
                            error = null,
                            recoveryHint = null,
                            progress = it.progress.copy(
                                phase = EspFlashPhase.CANCELLED,
                                note = "Cancelled — the image may be " +
                                    "incomplete; flash it again to finish",
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        error = error.message ?: "USB flash failed",
                        recoveryHint = "Reconnect the board and try again.",
                        progress = it.progress.copy(
                            phase = EspFlashPhase.FAILED,
                            note = error.message ?: "USB flash failed",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Asks the flash to stop. The blocking USB session notices between blocks
     * and response polls; the coroutine itself is not cancelled so the port
     * is closed cleanly by [UsbEspFlashController]'s `finally` blocks.
     */
    fun cancel() {
        cancelRequested.set(true)
    }

    private fun onProgress(progress: EspFlashProgress) {
        _uiState.update { it.copy(progress = progress) }
    }

    override fun onCleared() {
        cancelRequested.set(true)
        super.onCleared()
    }

    companion object {

        /** Factory used by the screen; builds the Android USB runner. */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UsbFlashViewModel(
                    runner = UsbEspFlashController(context.applicationContext),
                )
            }
        }

        /** Factory for tests and previews with a substitute runner. */
        fun factory(runner: UsbFlashRunner): ViewModelProvider.Factory = viewModelFactory {
            initializer { UsbFlashViewModel(runner) }
        }
    }
}
