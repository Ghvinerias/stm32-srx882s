package com.eelab.rflab.ui.receive

import java.util.Locale

/**
 * One row in the receive list: a decoded signal and any repeats that were
 * collapsed into it.
 *
 * @property id unique within this ViewModel, used as the list key and for
 *   selection.
 * @property firstSeenMs host arrival time of the first decode in the group,
 *   in milliseconds since the Unix epoch. Repeats do not move it.
 * @property hopping 32-bit hopping word as captured.
 * @property serial serial number as captured.
 * @property button button nibble, `0x0..0xF`.
 * @property repeatCount number of decodes collapsed into this row (at least 1).
 * @property selectionOrder 1-based position in the ordered selection while
 *   the row is selected for "add to send queue", or null when it is not
 *   selected. Deselecting a row renumbers the rows selected after it, so the
 *   numbers always stay contiguous.
 * @property captureTimestampUs device capture timestamp of the first decode,
 *   kept for diagnostics; host device timestamps are a 32-bit microsecond
 *   uptime counter, so they are not used for display or grouping.
 */
data class ReceivedSignalRow(
    val id: Long,
    val firstSeenMs: Long,
    val hopping: Long,
    val serial: Long,
    val button: Int,
    val repeatCount: Int = 1,
    val selectionOrder: Int? = null,
    val captureTimestampUs: Long = 0L,
    /**
     * Sync counter decrypted from [hopping] with the selected key, shown only
     * when that key matches this remote ([keyMatches]); null when no key is
     * selected or the key does not match. Display-only: it does not affect
     * grouping or selection.
     */
    val decryptedCounter: Int? = null,
    /** True when the selected key's decrypt matches this frame's button. */
    val keyMatches: Boolean = false,
) {
    /** Whether the row is checked for "add to send queue". */
    val selected: Boolean get() = selectionOrder != null
}

/**
 * Display formatting for the receive rows.
 *
 * Widths match the wire fields: 32-bit hopping, 28-bit serial, 4-bit button.
 */
internal object SignalFormat {

    fun hopping(value: Long): String = "0x%08X".format(Locale.US, value)

    fun serial(value: Long): String = "0x%07X".format(Locale.US, value)

    fun button(value: Int): String = "0x%X".format(Locale.US, value)
}
