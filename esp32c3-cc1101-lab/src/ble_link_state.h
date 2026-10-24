#pragma once

#include <stdint.h>

// Portable BLE link bookkeeping (connected / subscribed / MTU) so the rules
// that gate notifications can be unit-tested without NimBLE.
//
// NimBLE delivers a CCCD write (onSubscribe) to the callbacks of the
// characteristic that OWNS the CCCD -- the notify characteristic (6E400003),
// not the write characteristic (6E400002).
typedef struct {
    volatile uint8_t connected;
    volatile uint8_t subscribed;
    volatile uint16_t mtu;
} ble_link_state_t;

#ifdef __cplusplus
extern "C" {
#endif

void ble_link_init(ble_link_state_t *state);
void ble_link_on_connect(ble_link_state_t *state);
void ble_link_on_disconnect(ble_link_state_t *state);
void ble_link_on_subscribe(ble_link_state_t *state, uint16_t sub_value);
void ble_link_on_mtu(ble_link_state_t *state, uint16_t mtu);
int ble_link_can_notify(const ble_link_state_t *state);
// Notification payload size: min(mtu - 3, max_payload), never below 20.
uint16_t ble_link_chunk(const ble_link_state_t *state, uint16_t max_payload);

#ifdef __cplusplus
}
#endif
