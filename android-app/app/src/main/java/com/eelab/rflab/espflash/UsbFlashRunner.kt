package com.eelab.rflab.espflash

/**
 * Outcome of one complete USB flash, in the form the UI needs.
 *
 * The Android implementation ([UsbEspFlashController]) converts every
 * exception into one of these three cases so the ViewModel never has to know
 * about USB exceptions, and the whole state machine stays JVM-testable with a
 * fake runner.
 */
sealed interface EspFlashResult {

    /** The image was written and the ROM's MD5 matched. */
    data class Success(val summary: EspFlashSummary) : EspFlashResult

    /**
     * The flash failed.
     *
     * @property message what went wrong, shown as the error.
     * @property recoveryHint a concrete next step, e.g. the manual
     *   BOOT+RESET sequence when the chip never answered SYNC.
     */
    data class Failure(
        val message: String,
        val recoveryHint: String,
    ) : EspFlashResult

    /** The user asked to stop; the port was closed and no error is reported. */
    data object Cancelled : EspFlashResult
}

/**
 * Seam between [com.eelab.rflab.ui.firmware.UsbFlashViewModel] and the
 * Android USB stack.
 *
 * Production uses [UsbEspFlashController]; tests inject a fake that reports
 * progress and returns whatever [EspFlashResult] a case needs. The runner is
 * responsible for mapping [EspCancelledException] to
 * [EspFlashResult.Cancelled] and every other failure to
 * [EspFlashResult.Failure], so the ViewModel only branches on the result.
 */
interface UsbFlashRunner {

    /**
     * Flashes [image] and reports progress.
     *
     * @param onProgress called from a background thread with each phase and
     *   byte count.
     * @param isCancelled polled between blocks and response reads; returning
     *   `true` aborts and produces [EspFlashResult.Cancelled].
     */
    suspend fun flash(
        image: FlashImage,
        onProgress: (EspFlashProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): EspFlashResult
}
