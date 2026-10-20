package com.eelab.rflab.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver

/**
 * Locates the STM32 RF lab board among the attached USB devices.
 *
 * The board is a standard CDC-ACM device with a vendor-specific VID/PID
 * (`0xCAFE` / `0x4001`). That pair is deliberately *not* in
 * usb-serial-for-android's `UsbSerialProber` auto-detection table, so this
 * finder matches it explicitly and constructs [CdcAcmSerialDriver] directly
 * for the matched device, per `android-app/AGENT_BRIEF.md`
 * ("Connecting to the device").
 */
object UsbDeviceFinder {

    /** Lab board vendor ID. */
    const val VENDOR_ID = 0xCAFE

    /** Lab board product ID. */
    const val PRODUCT_ID = 0x4001

    /**
     * Returns the first attached device matching [VENDOR_ID]/[PRODUCT_ID],
     * or `null` when the board is not plugged in.
     */
    fun findDevice(usbManager: UsbManager): UsbDevice? =
        usbManager.deviceList.values.firstOrNull(::matches)

    /** True when [device] is the lab board. */
    fun matches(device: UsbDevice): Boolean =
        device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID

    /**
     * Builds the CDC-ACM driver for a device already matched by
     * [findDevice]. Constructing the driver does not open or claim
     * anything; [RfLabConnection.open] does that once permission is granted.
     */
    fun createDriver(device: UsbDevice): CdcAcmSerialDriver = CdcAcmSerialDriver(device)
}
