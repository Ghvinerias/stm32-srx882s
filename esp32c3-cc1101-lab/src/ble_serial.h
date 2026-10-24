#pragma once

#include <stddef.h>
#include <stdint.h>

// BLE GATT serial transport (Nordic UART Service style).
//
//   service  6E400001-B5A3-F393-E0A9-E50E24DCCA9E
//   RX char  6E400002-...  host -> device, Write / Write Without Response
//   TX char  6E400003-...  device -> host, Notify
//
// The characteristic payload is the same COBS+CRC16 byte stream as USB-CDC.
// Writes are queued and drained by the main loop so the NimBLE host task is
// never blocked by an RF transmission.

bool ble_serial_init(void);
bool ble_serial_connected(void);
bool ble_serial_subscribed(void);
uint16_t ble_serial_mtu(void);

// Bytes received from the central; returns 0 when the queue is empty.
size_t ble_serial_read(uint8_t *data, size_t max_length);

// Send one chunked stream.  Returns false if no central is subscribed, in
// which case the transport counts the packet as undelivered.
bool ble_serial_write(const uint8_t *data, size_t length);

void ble_serial_reset_link(void);

// Diagnostics for the `radio` line.
typedef struct {
    uint8_t connected;
    uint8_t subscribed;
    uint16_t mtu;
    uint16_t conn_handle;
    uint32_t subscribe_events;
    uint32_t connect_events;
    uint32_t disconnect_events;
    uint32_t notifies;
} ble_serial_stats_t;
ble_serial_stats_t ble_serial_stats(void);
