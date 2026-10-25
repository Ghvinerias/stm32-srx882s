#pragma once

#include <stdint.h>

// Explicit radio modes.
//   IDLE  boot default: CC1101 in SIDLE, RMT RX disabled, no FRAME packets.
//   RX    listening (the previous always-on behaviour).
//   TX    transient: entered by any TX_COMMAND / scenario, then the mode that
//         was active before (the "base" mode, IDLE or RX) is restored.
// The current mode is reported in STATUS flags bits 2..3 (see
// proto_transport.h) and an immediate STATUS is sent on every change.
enum {
    RADIO_MODE_IDLE = 0,
    RADIO_MODE_RX = 1,
    RADIO_MODE_TX = 2,
};

typedef struct {
    // Put the hardware into IDLE or RX (never TX).
    void (*apply)(void *context, uint8_t base_mode);
    // The reported mode changed or was re-requested: send STATUS now.
    void (*changed)(void *context, uint8_t mode);
    // MODE IDLE arrived while a TX burst runs: stop it after the current frame.
    void (*abort_tx)(void *context);
    void *context;
} radio_mode_ops_t;

#ifdef __cplusplus
extern "C" {
#endif

// Resets to base IDLE / not transmitting.  Does not call any callback; the
// device layer puts the hardware in IDLE itself at boot.
void radio_mode_init(const radio_mode_ops_t *ops);

uint8_t radio_mode_current(void); // IDLE, RX or TX
uint8_t radio_mode_base(void);    // IDLE or RX (what TX returns to)

// MODE_COMMAND handler.  Returns 1 when accepted, 0 for an unknown mode.
// While a TX burst runs the new base mode is remembered and applied when the
// burst ends; requesting IDLE additionally aborts the burst.
int radio_mode_request(uint8_t mode);

// Called by the TX path around a burst.
void radio_mode_tx_begin(void);
void radio_mode_tx_end(void);

const char *radio_mode_name(uint8_t mode);

#ifdef __cplusplus
}
#endif
