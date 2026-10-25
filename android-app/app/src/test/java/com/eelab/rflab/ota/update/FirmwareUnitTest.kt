package com.eelab.rflab.ota.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit→`receiver_id`→file selection rules: the two lab units' header bytes
 * and the app image file names `serve_flash.py` offers under `/fw/`.
 */
class FirmwareUnitTest {

    @Test
    fun receiverIdsMatchTheFirmwareBuild() {
        assertEquals(0xE1, FirmwareUnit.UNIT_A.receiverId)
        assertEquals(0xE2, FirmwareUnit.UNIT_B.receiverId)
    }

    @Test
    fun receiverIdSelectsTheUnit() {
        assertEquals(FirmwareUnit.UNIT_A, FirmwareUnit.fromReceiverId(0xE1))
        assertEquals(FirmwareUnit.UNIT_B, FirmwareUnit.fromReceiverId(0xE2))
    }

    @Test
    fun otherReceiverIdsAreUnknown() {
        // The STM32 board always sends 1; that is not a lab unit.
        assertNull(FirmwareUnit.fromReceiverId(1))
        assertNull(FirmwareUnit.fromReceiverId(0))
    }

    @Test
    fun appImageFileNameMatchesTheServerLayout() {
        assertEquals(
            "esp32c3-cc1101-lab-unit1-app.bin",
            FirmwareUnit.UNIT_A.appImageFileName,
        )
        assertEquals(
            "esp32c3-cc1101-lab-unit2-app.bin",
            FirmwareUnit.UNIT_B.appImageFileName,
        )
    }

    @Test
    fun imagePathUsesTheFwDirectory() {
        assertEquals(
            "/fw/esp32c3-cc1101-lab-unit1-app.bin",
            FirmwareServerClient.imagePath(FirmwareUnit.UNIT_A.appImageFileName),
        )
    }
}
