package com.eelab.rflab.ui.receive

import com.eelab.rflab.decode.Ev1527Protocol
import com.eelab.rflab.decode.Ht12Frame
import com.eelab.rflab.decode.PrincetonFrame

/** Which static-code decoder produced a [StaticCodeRow]. */
enum class StaticCodeProtocol {
    /** 24-bit PT2262/EV1527 PWM — one signal, shown with both field readings. */
    PRINCETON_EV1527,

    /** 12-bit Holtek HT12E/HT12A. */
    HT12,
}

/**
 * One row in the static-code list: a decoded static code and any repeats
 * collapsed into it.
 *
 * The meaningful fields depend on [protocol]:
 * - [PRINCETON_EV1527][StaticCodeProtocol.PRINCETON_EV1527]: a 24-bit code.
 *   EV1527 and PT2262/Princeton are the same signal on the air (see
 *   [com.eelab.rflab.decode.Ev1527Protocol]), so both readings are carried:
 *   [serial]/[button] are the PT2262 split, [address]/[dataNibble] the EV1527
 *   split of the identical [data].
 * - [HT12][StaticCodeProtocol.HT12]: a 12-bit code; [address] is the 8-bit
 *   DIP/address field and [dataNibble] the 4-bit data field. [serial]/[button]
 *   are not used for HT12.
 *
 * There is no counter, no key, and no selection/send-queue field here, unlike
 * [ReceivedSignalRow] — a static code has no rolling state to track, and this
 * app has no supported way to transmit one (see
 * [com.eelab.rflab.decode.PrincetonProtocol]'s doc comment).
 */
data class StaticCodeRow(
    val id: Long,
    val firstSeenMs: Long,
    val protocol: StaticCodeProtocol,
    val data: Long,
    val bitCount: Int,
    val serial: Long,
    val button: Int,
    val address: Long,
    val dataNibble: Int,
    val repeatCount: Int = 1,
)

/**
 * Collapses repeated identical static-code frames (a held button
 * re-transmits the same code many times) into one row, mirroring
 * [ReceivedSignalAccumulator]'s repeat-grouping idea in spirit but simpler:
 * grouping is only against the single most-recently-seen row, not any row in
 * the list, since there is no selection state to keep stable while scrolling.
 *
 * Grouping keys on ([StaticCodeProtocol], [StaticCodeRow.data]) so a 12-bit
 * HT12 code and a 24-bit code that happen to share a numeric value never
 * merge.
 */
class StaticCodeAccumulator(private val groupWindowMs: Long = DEFAULT_GROUP_WINDOW_MS) {

    private data class Entry(val row: StaticCodeRow, val lastSeenMs: Long)

    private val entries = mutableListOf<Entry>()
    private var nextId = 1L

    /** Newest first. */
    fun rows(): List<StaticCodeRow> = entries.map { it.row }

    /** A 24-bit PT2262/EV1527 frame — carries both field readings of the same code. */
    fun accept(frame: PrincetonFrame, atMs: Long) {
        val (address, dataNibble) = Ev1527Protocol.parseFields(frame.data)
        acceptRow(
            protocol = StaticCodeProtocol.PRINCETON_EV1527,
            data = frame.data,
            bitCount = frame.bitCount,
            serial = frame.serial,
            button = frame.button,
            address = address,
            dataNibble = dataNibble,
            atMs = atMs,
        )
    }

    /** A 12-bit Holtek HT12 frame. */
    fun accept(frame: Ht12Frame, atMs: Long) {
        acceptRow(
            protocol = StaticCodeProtocol.HT12,
            data = frame.data,
            bitCount = frame.bitCount,
            serial = 0L,
            button = 0,
            address = frame.address,
            dataNibble = frame.button,
            atMs = atMs,
        )
    }

    private fun acceptRow(
        protocol: StaticCodeProtocol,
        data: Long,
        bitCount: Int,
        serial: Long,
        button: Int,
        address: Long,
        dataNibble: Int,
        atMs: Long,
    ) {
        val head = entries.firstOrNull()
        if (head != null &&
            head.row.protocol == protocol &&
            head.row.data == data &&
            atMs - head.lastSeenMs <= groupWindowMs
        ) {
            entries[0] = Entry(head.row.copy(repeatCount = head.row.repeatCount + 1), atMs)
            return
        }
        entries.add(
            0,
            Entry(
                StaticCodeRow(
                    id = nextId++,
                    firstSeenMs = atMs,
                    protocol = protocol,
                    data = data,
                    bitCount = bitCount,
                    serial = serial,
                    button = button,
                    address = address,
                    dataNibble = dataNibble,
                ),
                atMs,
            ),
        )
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_GROUP_WINDOW_MS = ReceivedSignalAccumulator.DEFAULT_GROUP_WINDOW_MS
    }
}
