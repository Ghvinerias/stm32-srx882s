#include "lab_rx.h"

#include <stddef.h>

#define LAB_RX_FUTURE_ALL 0xFFFFFFFFU

static lab_rx_config_t rx_config;
static uint32_t rx_serial;
static uint8_t rx_button;
static uint32_t rx_count;
static int rx_paired;
static int rx_pending_valid;
static uint32_t rx_pending_count;

void lab_rx_init(void) {
    rx_config.window_next = 16U;
    rx_config.window_future = 32768U;
    rx_config.window_gap = 2U;
    rx_config.allow_replay = 0;
    rx_serial = 0U;
    rx_button = 0U;
    rx_count = 0U;
    rx_paired = 0;
    rx_pending_valid = 0;
    rx_pending_count = 0U;
}

void lab_rx_configure(const lab_rx_config_t *config) {
    if (config == NULL) {
        return;
    }
    rx_config = *config;
}

void lab_rx_pair(uint32_t serial, uint8_t button, uint32_t starting_count) {
    rx_serial = serial;
    rx_button = button;
    rx_count = starting_count;
    rx_paired = 1;
    rx_pending_valid = 0;
    rx_pending_count = 0U;
}

uint32_t lab_rx_current_count(void) { return rx_count; }
int lab_rx_is_paired(void) { return rx_paired; }

lab_rx_result_t lab_rx_feed(uint32_t hopping_count, uint32_t serial,
                            uint8_t button) {
    if (!rx_paired || serial != rx_serial || button != rx_button) {
        return LAB_RX_RESULT_NO_FIX_MATCH;
    }

    // Unsigned wraparound distance from the stored count to the incoming one.
    // A genuinely "past" count wraps all the way around to a huge delta, same
    // as it would in a real 16-bit (or wider) counter space.
    uint32_t delta = hopping_count - rx_count;

    if (delta == 0U) {
        // Exact same count as last accepted: a textbook replay.
        if (rx_config.allow_replay) {
            rx_pending_valid = 0;
            return LAB_RX_RESULT_OPENED_REPLAY;
        }
        return LAB_RX_RESULT_PAST;
    }

    if (delta <= rx_config.window_next) {
        rx_count = hopping_count;
        rx_pending_valid = 0;
        return LAB_RX_RESULT_OPENED_NEXT;
    }

    int within_future = (rx_config.window_future == LAB_RX_FUTURE_ALL) ||
                        (delta <= rx_config.window_future);
    if (within_future) {
        if (rx_pending_valid) {
            uint32_t gap = hopping_count - rx_pending_count;
            if (gap <= rx_config.window_gap) {
                rx_count = hopping_count;
                rx_pending_valid = 0;
                return LAB_RX_RESULT_OPENED_GAP;
            }
        }
        // Not confirmed (yet): remember it as the pending candidate and wait
        // for a second, nearby count to confirm the resync.
        rx_pending_valid = 1;
        rx_pending_count = hopping_count;
        return LAB_RX_RESULT_FUTURE;
    }

    return LAB_RX_RESULT_PAST;
}
