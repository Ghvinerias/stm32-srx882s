package com.eelab.rflab.ota

import com.eelab.rflab.usb.RfLabConnection
import com.eelab.rflab.usb.RfLabSession
import java.io.IOException

/**
 * App-level composition root for the firmware-update path, mirroring
 * [com.eelab.rflab.send.SendGraph].
 *
 * One [OtaEngine] per process, pointed at the current connection (BLE NUS or
 * USB-CDC — everything above the byte stream is transport-agnostic). The
 * write lambda resolves the current connection at send time and applies the
 * same COBS + `0x00` framing [RfLabConnection.sendPacket] gives every other
 * command type; with no attached healthy connection it fails fast so the
 * engine reports a readable error instead of crashing.
 */
object OtaGraph {

    /** The process-wide OTA engine. */
    val engine: OtaEngine by lazy {
        OtaEngine(
            events = RfLabSession.events,
            sendPacket = { rawPacket ->
                val connection = RfLabSession.connection.value
                    ?: throw IOException("Device is not connected")
                if (!connection.isOpen) {
                    throw IOException("Device connection is closed")
                }
                connection.sendPacket(rawPacket)
            },
        )
    }
}
