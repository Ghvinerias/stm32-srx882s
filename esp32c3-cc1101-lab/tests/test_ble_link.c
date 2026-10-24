#include "src/ble_link_state.h"

#include "test_util.h"

int main(void) {
    ble_link_state_t s;
    ble_link_init(&s);
    expect("idle: cannot notify", !ble_link_can_notify(&s));
    ble_link_on_connect(&s);
    expect("connected but not subscribed: cannot notify",
           !ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 0x0001U);
    expect("subscribe (notify bit) enables notifications",
           ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 0x0000U);
    expect("unsubscribe disables them", !ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 0x0002U);
    expect("indicate-only does not count as notify", !ble_link_can_notify(&s));
    ble_link_on_subscribe(&s, 0x0001U);
    ble_link_on_disconnect(&s);
    expect("disconnect clears subscription", !ble_link_can_notify(&s));
    ble_link_on_connect(&s);
    expect("reconnect needs a fresh subscribe", !ble_link_can_notify(&s));
    expect("default chunk is 20", ble_link_chunk(&s, 244U) == 20U);
    ble_link_on_mtu(&s, 247U);
    expect("MTU 247 gives 244", ble_link_chunk(&s, 244U) == 244U);
    ble_link_on_mtu(&s, 517U);
    expect("chunk capped at max payload", ble_link_chunk(&s, 244U) == 244U);
    ble_link_on_mtu(&s, 5U);
    expect("bogus tiny MTU still gives 20", ble_link_chunk(&s, 244U) == 20U);
    return test_result();
}
