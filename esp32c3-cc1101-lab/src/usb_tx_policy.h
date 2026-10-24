#pragma once

#include <stddef.h>

// Decides how to write one packet to the HWCDC USB port.
//
// HWCDC only flips `connected` to true once the host has clocked data out of
// us (IN_EMPTY) or sent us data (OUT_RECV_PKT).  A passive host (the Android
// app only reads until it sees STATUS) therefore never makes the port
// "connected" unless we queue something first.  Gating output on
// Serial.isConnected() deadlocks; the policy below never does.
typedef enum {
    USB_TX_SEND,           // connected and room for the whole packet
    USB_TX_BOOTSTRAP,      // not connected: drop stale queue, write anyway so
                           // the next IN token can flip `connected`
    USB_TX_DROP            // connected but the host is not draining: skip the
                           // packet instead of blocking the main loop
} usb_tx_action_t;

#ifdef __cplusplus
extern "C" {
#endif

usb_tx_action_t usb_tx_decide(int connected, size_t free_space, size_t length);

#ifdef __cplusplus
}
#endif
