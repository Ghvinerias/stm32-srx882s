package com.eelab.rflab.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver

/**
 * Locates the STM32 or ESP32-C3 RF lab board among the attached USB devices.
 *
 * Both boards are standard CDC-ACM devices: the STM32 uses a vendor-specific
 * VID/PID (`0xCAFE` / `0x4001`), while the ESP32-C3 build enumerates under
 * Espressif's USB VID with its CDC-ACM PID (`0x303A` / `0x1001`). Neither
 * pair is in usb-serial-for-android's `UsbSerialProber` auto-detection table,
 * so this finder matches both explicitly and constructs [CdcAcmSerialDriver]
 * directly for the matched device, per `android-app/AGENT_BRIEF.md`
 * ("Connecting to the device").
 */
object UsbDeviceFinder {

    /** STM32 lab board vendor ID. */
    const val VENDOR_ID = 0xCAFE

    /** STM32 lab board product ID. */
    const val PRODUCT_ID = 0x4001

    /** ESP32-C3 lab board vendor ID (Espressif). */
    const val C3_VENDOR_ID = 0x303A

    /** ESP32-C3 lab board product ID (USB-CDC). */
    const val C3_PRODUCT_ID = 0x1001

    /**
     * Returns the first attached device matching a known lab board VID/PID,
     * or `null` when neither board is plugged in.
     */
    fun findDevice(usbManager: UsbManager): UsbDevice? =
        usbManager.deviceList.values.firstOrNull(::matches)

    /** True when [device] is a lab board (STM32 or ESP32-C3). */
    fun matches(device: UsbDevice): Boolean =
        (device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID) ||
            (device.vendorId == C3_VENDOR_ID && device.productId == C3_PRODUCT_ID)

    /**
     * Builds the CDC-ACM driver for a device already matched by
     * [findDevice]. Constructing the driver does not open or claim
     * anything; [RfLabConnection.open] does that once permission is granted.
     */
    fun createDriver(device: UsbDevice): CdcAcmSerialDriver = CdcAcmSerialDriver(device)
}
