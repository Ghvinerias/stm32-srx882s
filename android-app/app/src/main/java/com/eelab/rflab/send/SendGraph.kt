package com.eelab.rflab.send

import com.eelab.rflab.usb.RfLabSession
import java.io.IOException

/**
 * App-level composition root for the send path.
 *
 * Builds the one [BatchSendEngine] the process uses and points it at the
 * current USB connection. Keeping a single instance matters: the engine's
 * internal mutex is what prevents a preset-scenario press from overlapping a
 * running batch, and prevents two "Send All" presses from pipelining.
 *
 * The event source is [RfLabSession.events], which switches with the
 * connection, and the write lambda resolves the *current* connection at send
 * time. With no connection attached — or one whose read loop has already
 * failed — every write fails fast with an [IOException], so the engine
 * reports the item as [com.eelab.rflab.queue.SendItemStatus.FAILED] instead of
 * crashing the UI or burning an ACK timeout.
 */
object SendGraph {

    /** The process-wide sequential sender. */
    val engine: BatchSendEngine by lazy {
        BatchSendEngine(
            events = RfLabSession.events,
            sendPacket = { rawPacket ->
                val connection = RfLabSession.connection.value
                    ?: throw IOException("USB device is not connected")
                if (!connection.isOpen) {
                    throw IOException("USB connection is closed")
                }
                // COBS encoding plus the trailing 0x00 delimiter.
                connection.sendPacket(rawPacket)
            },
        )
    }
}
