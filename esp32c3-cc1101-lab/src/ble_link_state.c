#include "ble_link_state.h"

#define BLE_DEFAULT_MTU 23U

void ble_link_init(ble_link_state_t *state) {
    state->connected = 0U;
    state->subscribed = 0U;
    state->mtu = BLE_DEFAULT_MTU;
    state->conn_handle = 0xFFFFU;
}

void ble_link_on_connect(ble_link_state_t *state, uint16_t handle) {
    // The newest connection becomes current: it starts unsubscribed with the
    // default MTU and must write the CCCD itself.
    state->conn_handle = handle;
    state->connected = 1U;
    state->subscribed = 0U;
    state->mtu = BLE_DEFAULT_MTU;
}

void ble_link_on_disconnect(ble_link_state_t *state, uint16_t handle) {
    if (state->connected && handle != state->conn_handle) {
        return; // a stale/older connection going away
    }
    state->connected = 0U;
    state->subscribed = 0U;
    state->mtu = BLE_DEFAULT_MTU;
}

void ble_link_on_subscribe(ble_link_state_t *state, uint16_t handle,
                           uint16_t sub_value) {
    int enable = (sub_value & 0x0001U) != 0U;
    if (enable) {
        // A CCCD write proves a live connection even if the connect callback
        // was never delivered (observed on rapid reconnects): adopt it.
        state->conn_handle = handle;
        state->connected = 1U;
        state->subscribed = 1U;
        return;
    }
    if (state->connected && handle != state->conn_handle) {
        return;
    }
    state->subscribed = 0U;
}

void ble_link_on_mtu(ble_link_state_t *state, uint16_t handle, uint16_t mtu) {
    if (state->connected && handle != state->conn_handle) {
        return;
    }
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
