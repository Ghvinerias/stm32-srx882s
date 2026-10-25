#pragma once

// ---------------------------------------------------------------------------
// Board / wiring / identity configuration for the ESP32-C3 + CC1101 lab node.
//
// Target: ESP32-C3 SuperMini (native USB Serial/JTAG, BLE only -- no Classic
// Bluetooth) + a CC1101 module in asynchronous OOK mode at 433.92 MHz.
//
// Pin choices are all on the SuperMini's broken-out header and avoid the
// flash pins (GPIO11..17) and the USB pins (GPIO18/19):
//
//   CC1101 SCK   -> GPIO4
//   CC1101 MISO  -> GPIO5
//   CC1101 MOSI  -> GPIO6
//   CC1101 CSN   -> GPIO7
//   CC1101 GDO0  -> GPIO3   (async serial data INPUT to the CC1101, RMT TX)
//   CC1101 GDO2  -> GPIO10  (async serial data OUTPUT from the CC1101, RMT RX)
//   status LED   -> GPIO8   (active low on the SuperMini)
//
// GDO0 is the CC1101's asynchronous-TX data input.  During RX the CC1101
// also drives GDO0 with the sliced data (IOCFG0 = 0x0D), so the ESP32 must
// NOT hold that line driven outside a transmission: radio_rmt.cpp leaves the
// TX GPIO high-impedance except while a burst is in flight.  A 1k series
// resistor between GDO0 and GPIO3 is recommended as belt-and-braces.
//
// Receiver-delimiter constants deliberately mirror firmware/src/hw_capture.c:
// 8 ms idle gap and at least 16 pulses for a frame to be worth reporting.
// ---------------------------------------------------------------------------

// ---- pin map ----
#define LAB_PIN_SPI_SCK 4
#define LAB_PIN_SPI_MISO 5
#define LAB_PIN_SPI_MOSI 6
#define LAB_PIN_CC1101_CS 7
#define LAB_PIN_GDO0_TX 3
#define LAB_PIN_GDO2_RX 10
#define LAB_PIN_STATUS_LED 8

// ---- unit identity ----
// Two identical units are distinguished by a build-time unit id.  It selects
// both the ATTR/receiver byte in every FRAME/STATUS packet and the BLE name
// suffix.  The STM32 build uses receiver id 1, so 0xE1/0xE2 cannot collide.
#ifndef LAB_UNIT_ID
#define LAB_UNIT_ID 1
#endif

#if LAB_UNIT_ID == 1
#define LAB_RECEIVER_ID 0xE1U
#define LAB_UNIT_SUFFIX "A"
#elif LAB_UNIT_ID == 2
#define LAB_RECEIVER_ID 0xE2U
#define LAB_UNIT_SUFFIX "B"
#else
#error "LAB_UNIT_ID must be 1 or 2"
#endif

// USB CDC and BLE expose the same COBS byte stream.  BLE is a Nordic-UART-
// Service-style GATT profile; these UUIDs are the public NUS values, and are
// documented in README.md for the future Android BLE transport.
#define LAB_BLE_NAME "KeeLoqLab-C3-" LAB_UNIT_SUFFIX
#define LAB_BLE_SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define LAB_BLE_RX_CHAR_UUID "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // host -> device (write)
#define LAB_BLE_TX_CHAR_UUID "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // device -> host (notify)

// Requested ATT MTU.  NimBLE in this core is built with an ATT preferred MTU
// of 256; 247 is the value every current phone/BlueZ accepts and leaves 244
// bytes of notification payload.  The transport is a byte stream, so a long
// FRAME may span several notifications and a host must reassemble on 0x00.
#define LAB_BLE_PREFERRED_MTU 247
#define LAB_BLE_MAX_PAYLOAD 244

// ---- capture framing (same values as firmware/src/hw_capture.c) ----
#define LAB_CAPTURE_MAX_PULSES 256
#define LAB_CAPTURE_IDLE_MAX_US 8000U // > 4512us header cell, < 15500us inter-frame gap
#define LAB_CAPTURE_IDLE_MAX_NS (LAB_CAPTURE_IDLE_MAX_US * 1000U)
// The RMT hardware glitch filter (signal_range_min_ns) saturates at 3187 ns;
// anything larger makes rmt_receive() fail every time.  The 20 us chatter
// rejection is therefore done in software (lab_capture_filter_glitches).
#define LAB_RMT_FILTER_NS 3000U
#define LAB_CAPTURE_MIN_PULSE_NS 20000U // reject sub-20us RF slicer chatter (software)
#define LAB_CAPTURE_MIN_PULSES 16U

// RMT RX symbol buffer.  A 77-pulse HCS301 frame is 39 RMT symbols; 128
// symbols (256 pulses) also covers two or three merged repeats before the
// 8 ms idle delimiter separates them.  The ESP32-C3 RMT block is 48 symbols,
// and the IDF driver ping-pongs a larger user buffer for us.
#define LAB_RMT_RX_SYMBOLS 128

// ---- CC1101 ----
// PATABLE[0] = off, PATABLE[1] = on.  0xC0 is the Flipper OOK preset's 12 dBm
// setting; see README.md for the lower-power alternatives if two nodes sit
// within a few centimetres of each other and the receiver front end saturates.
#define LAB_CC1101_PATABLE_HIGH 0xC0U
#define LAB_CC1101_SPI_HZ 4000000U
