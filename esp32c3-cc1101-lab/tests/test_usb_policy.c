#include "src/usb_tx_policy.h"

#include "test_util.h"

int main(void) {
    expect("not connected -> bootstrap write (no isConnected deadlock)",
           usb_tx_decide(0, 2048U, 30U) == USB_TX_BOOTSTRAP);
    expect("not connected with a full queue still bootstraps",
           usb_tx_decide(0, 0U, 30U) == USB_TX_BOOTSTRAP);
    expect("connected with room sends",
           usb_tx_decide(1, 2048U, 537U) == USB_TX_SEND);
    expect("connected, exact fit sends",
           usb_tx_decide(1, 30U, 30U) == USB_TX_SEND);
    expect("connected but host not draining drops instead of blocking",
           usb_tx_decide(1, 29U, 30U) == USB_TX_DROP);
    return test_result();
}
