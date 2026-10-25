package com.eelab.rflab.ota

/** What [OtaEngine] is currently doing during a firmware update. */
enum class OtaPhase {
    /** No update is running (or nothing has happened yet). */
    IDLE,

    /** HELLO exchange: getting a nonce and the device's capabilities. */
    HELLO,

    /** Authenticating the image to the device. */
    BEGIN,

    /** Streaming CHUNK packets. */
    TRANSFER,

    /** Waiting for the END response that triggers the reboot. */
    END,

    /** Rebooting the device into the ROM boot loader (BOOTLOADER op). */
    BOOTLOADER,

    /** The update (or reboot request) completed. Terminal. */
    DONE,

    /** The update failed, was cancelled or lost the link. Terminal. */
    FAILED,
}

/**
 * Progress snapshot published by [OtaEngine.progress].
 *
 * @property phase what the engine is doing; see [OtaPhase].
 * @property bytesSent bytes the device has acknowledged so far.
 * @property totalBytes the full image size being flashed (0 when unknown).
 * @property error human-readable failure reason; non-null only when
 *   [phase] is [OtaPhase.FAILED].
 * @property note human-readable, non-error status text (success hints such as
 *   "the device is rebooting — reconnect"); null while a phase is running.
 */
data class OtaProgress(
    val phase: OtaPhase = OtaPhase.IDLE,
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val note: String? = null,
) {

    /** True while the phases HELLO..BOOTLOADER are in progress. */
    val isActive: Boolean
        get() = phase in OtaPhase.HELLO..OtaPhase.BOOTLOADER
}
