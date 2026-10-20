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
 * @property selected whether the row is checked for "add to send queue".
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
    val selected: Boolean = false,
    val captureTimestampUs: Long = 0L,
)

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
