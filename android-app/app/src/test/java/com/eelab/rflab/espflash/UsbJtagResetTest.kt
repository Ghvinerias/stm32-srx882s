package com.eelab.rflab.espflash

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies [UsbJtagReset] against esptool 5.3.1's `USBJTAGSerialReset`
 * (`esptool/reset.py:145-163`).
 *
 * The exact call order matters because `CdcAcmSerialPort.setDTR` / `setRTS`
 * each send a full `SET_CONTROL_LINE_STATE` request carrying both bits, so a
 * call for one line also re-transmits the other. This is the host-side
 * sequence esptool uses; the physical C3 lines are inverted and must not be
 * "corrected".
 */
class UsbJtagResetTest {

    private class RecordingLines : SerialControlLines {
        val calls = mutableListOf<String>()

        override fun setDtr(asserted: Boolean) {
            calls += "dtr=$asserted"
        }

        override fun setRts(asserted: Boolean) {
            calls += "rts=$asserted"
        }
    }

    @Test
    fun `drives the lines in the esptool order`() {
        val lines = RecordingLines()

        UsbJtagReset.reset(lines) { }

        assertEquals(
            listOf(
                "rts=false",
                "dtr=false",
                "dtr=true",
                "rts=false",
                "rts=true",
                "dtr=false",
                "rts=true",
                "dtr=false",
                "rts=false",
            ),
            lines.calls,
        )
    }

    @Test
    fun `waits one settle interval between the three steps`() {
        val sleeps = mutableListOf<Long>()

        UsbJtagReset.reset(RecordingLines()) { sleeps += it }

        assertEquals(listOf(100L, 100L, 100L), sleeps)
        assertEquals(3, sleeps.size)
    }

    @Test
    fun `enters and leaves reset through the inverted line combination`() {
        val lines = RecordingLines()

        UsbJtagReset.reset(lines) { }

        // Step 2 asserts RTS while DTR is still asserted, i.e. the host sends
        // (1,1) rather than (0,0) to release the chip from reset.
        val rtsTrueIndex = lines.calls.indexOf("rts=true")
        // setRTS re-transmits DTR as well, so inspect the DTR call before the
        // preceding rts=false rather than the immediately preceding entry.
        assertEquals("dtr=true", lines.calls[rtsTrueIndex - 2])
        // The sequence ends with both lines released.
        assertEquals("dtr=false", lines.calls[lines.calls.size - 2])
        assertEquals("rts=false", lines.calls.last())
    }
}
