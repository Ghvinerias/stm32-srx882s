package com.eelab.rflab.espflash

/**
 * The two modem control lines the ROM-loader reset toggles.
 *
 * Kept as an interface so the exact sequence can be verified on the JVM with
 * a recording fake; the Android implementation is a thin wrapper around
 * `UsbSerialPort.setDTR` / `setRTS` (see `UsbSerialRomByteStream`).
 */
interface SerialControlLines {

    /** Drives the DTR line (USB `SET_CONTROL_LINE_STATE` bit 0). */
    fun setDtr(asserted: Boolean)

    /** Drives the RTS line (USB `SET_CONTROL_LINE_STATE` bit 1). */
    fun setRts(asserted: Boolean)
}

/**
 * Puts an ESP32-C3 behind its native USB-Serial/JTAG peripheral into the ROM
 * download loader without a BOOT button.
 *
 * This is esptool 5.3.1's `USBJTAGSerialReset` (`esptool/reset.py:145-163`)
 * copied call for call. The order matters because
 * `CdcAcmSerialPort.setDTR`/`setRTS` each send a complete
 * `SET_CONTROL_LINE_STATE` request carrying *both* current bits: a call for
 * one line also re-sends the state of the other. esptool notes the fourth
 * step ("RTS set as Windows only propagates DTR on RTS setting") and repeats
 * `setRTS(true)` for that reason.
 *
 * The physical lines are inverted by the C3's USB-JTAG bridge, so
 * `setDtr(false)`/`setRts(false)` do not literally mean "GPIO0 high / EN
 * high"; the sequence below is the host-side one esptool uses and must not be
 * "corrected".
 *
 * If this does not work (some cables/adapters do not wire the lines, or the
 * board has no USB-JTAG support), the UI tells the user to hold BOOT, tap
 * RESET, release BOOT and press Flash again — the same manual entry into
 * download mode that esptool documents for boards without an automatic reset.
 */
object UsbJtagReset {

    /** Settle time between each group of line changes (esptool: 0.1 s). */
    const val SETTLE_MS: Long = 100L

    /**
     * Runs the sequence on [lines].
     *
     * @param sleep called with [SETTLE_MS] between steps; overridable so unit
     *   tests do not wait 300 ms per case.
     */
    fun reset(
        lines: SerialControlLines,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ) {
        lines.setRts(false)
        lines.setDtr(false) // Idle
        sleep(SETTLE_MS)
        lines.setDtr(true) // Set IO0
        lines.setRts(false)
        sleep(SETTLE_MS)
        lines.setRts(true) // Reset. Calls inverted to go through (1,1) instead of (0,0)
        lines.setDtr(false)
        lines.setRts(true) // RTS set as Windows only propagates DTR on RTS setting
        sleep(SETTLE_MS)
        lines.setDtr(false)
        lines.setRts(false) // Chip out of reset
    }
}
