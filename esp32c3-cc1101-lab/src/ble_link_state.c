#include "ble_link_state.h"

#define BLE_DEFAULT_MTU 23U

void ble_link_init(ble_link_state_t *state) {
    state->connected = 0U;
    state->subscribed = 0U;
    state->mtu = BLE_DEFAULT_MTU;
}

void ble_link_on_connect(ble_link_state_t *state) {
    // A new connection always starts unsubscribed with the default MTU; the
    // central must write the CCCD again.
    state->connected = 1U;
    state->subscribed = 0U;
    state->mtu = BLE_DEFAULT_MTU;
}

void ble_link_on_disconnect(ble_link_state_t *state) {
    state->connected = 0U;
    state->subscribed = 0U;
    state->mtu = BLE_DEFAULT_MTU;
}

void ble_link_on_subscribe(ble_link_state_t *state, uint16_t sub_value) {
    state->subscribed = (sub_value & 0x0001U) != 0U ? 1U : 0U;
}

void ble_link_on_mtu(ble_link_state_t *state, uint16_t mtu) {
    state->mtu = mtu;
}

int ble_link_can_notify(const ble_link_state_t *state) {
    return state->connected && state->subscribed;
}

uint16_t ble_link_chunk(const ble_link_state_t *state, uint16_t max_payload) {
    uint16_t mtu = state->mtu < BLE_DEFAULT_MTU ? BLE_DEFAULT_MTU : state->mtu;
    uint16_t chunk = (uint16_t)(mtu - 3U);
    return chunk > max_payload ? max_payload : chunk;
}
