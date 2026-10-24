#include "usb_tx_policy.h"

usb_tx_action_t usb_tx_decide(int connected, size_t free_space, size_t length) {
    if (!connected) {
        return USB_TX_BOOTSTRAP;
    }
    return free_space >= length ? USB_TX_SEND : USB_TX_DROP;
}
