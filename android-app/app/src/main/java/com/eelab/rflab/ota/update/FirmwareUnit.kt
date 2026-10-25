package com.eelab.rflab.ota.update

/**
 * One of the two ESP32-C3 + CC1101 lab units, named by the build-time unit id
 * (`esp32c3-cc1101-lab/src/lab_config.h`: `LAB_RECEIVER_ID 0xE1`/`0xE2`, BLE
 * names `KeeLoqLab-C3-A`/-B).
 *
 * The firmware-update flow needs the unit to pick the right app image file on
 * the web server. Protocol-wise the unit is the `receiver_id` byte the device
 * puts at header offset 17 of every packet; the app decodes it on FRAME
 * packets ([com.eelab.rflab.protocol.FramePacket.receiverId]), so when frames
 * have been seen the unit is known automatically — otherwise the user picks
 * A/B on the Firmware screen and the manual choice wins.
 */
enum class FirmwareUnit(
    /** The `receiver_id` header byte this unit always sends. */
    val receiverId: Int,
    /** Short user-facing label ("A" on the screen's unit chips). */
    val label: String,
) {
    UNIT_A(0xE1, "A"),
    UNIT_B(0xE2, "B"),

    ;

    /**
     * The OTA app image's file name on the firmware web server
     * (`esp32c3-cc1101-lab-unit1-app.bin` for [UNIT_A]) — never a
     * `*-merged.bin`, which the update path must not flash.
     */
    val appImageFileName: String
        get() = "esp32c3-cc1101-lab-unit${ordinal + 1}-app.bin"

    companion object {

        /**
         * The unit with the given `receiver_id`, or null when the value is not
         * one the lab firmware sends (e.g. the STM32 board's `1`).
         */
        fun fromReceiverId(receiverId: Int): FirmwareUnit? =
            entries.firstOrNull { it.receiverId == receiverId }
    }
}
