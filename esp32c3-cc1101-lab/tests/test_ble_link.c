#include "src/ble_link_state.h"

#include "test_util.h"

int main(void) {
    ble_link_state_t s;
    ble_link_init(&s);
    expect("idle: cannot notify", !ble_link_can_notify(&s));
    ble_link_on_connect(&s, 1U);
    expect("connected but not subscribed: cannot notify",
           !ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 1U, 0x0001U);
    expect("subscribe (notify bit) enables notifications",
           ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 1U, 0x0000U);
    expect("unsubscribe disables them", !ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 1U, 0x0002U);
    expect("indicate-only does not count as notify", !ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 1U, 0x0001U);
    ble_link_on_disconnect(&s, 1U);
    expect("disconnect clears subscription", !ble_link_can_notify(&s));
    ble_link_on_connect(&s, 1U);
    expect("reconnect needs a fresh subscribe", !ble_link_can_notify(&s));
    expect("default chunk is 20", ble_link_chunk(&s, 244U) == 20U);
    ble_link_on_mtu(&s, 1U, 247U);
    expect("MTU 247 gives 244", ble_link_chunk(&s, 244U) == 244U);
    ble_link_on_mtu(&s, 1U, 517U);
    expect("chunk capped at max payload", ble_link_chunk(&s, 244U) == 244U);
    ble_link_on_mtu(&s, 1U, 5U);
    expect("bogus tiny MTU still gives 20", ble_link_chunk(&s, 244U) == 20U);
    // Regression for the intermittent BLE flake: an old connection reporting its
    // disconnect (or subscribe/MTU) after a new client connected and subscribed
    // must not clear the new client's state.
    ble_link_init(&s);
    ble_link_on_connect(&s, 7U);
    ble_link_on_subscribe(&s, 7U, 1U);
    ble_link_on_connect(&s, 8U); // new client
    ble_link_on_subscribe(&s, 8U, 1U);
    ble_link_on_disconnect(&s, 7U); // late disconnect of the stale one
    expect("stale disconnect does not clear the new client", ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 7U, 0U);
    expect("stale unsubscribe is ignored", ble_link_can_notify(&s));
    ble_link_on_mtu(&s, 7U, 100U);
    expect("stale MTU is ignored", ble_link_chunk(&s, 244U) == 20U);
    ble_link_on_disconnect(&s, 8U);
    expect("current disconnect clears", !ble_link_can_notify(&s));
    // Missed onConnect (seen on rapid reconnects): a subscribe alone is enough.
    ble_link_init(&s);
    ble_link_on_subscribe(&s, 5U, 1U);
    expect("subscribe without a connect event still enables notifications",
           ble_link_can_notify(&s));
    ble_link_on_disconnect(&s, 5U);
    expect("and its disconnect clears them", !ble_link_can_notify(&s));
    return test_result();
}
