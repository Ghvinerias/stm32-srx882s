#include "radio_mode.h"

static radio_mode_ops_t ops;
static uint8_t base_mode;
static uint8_t tx_active;

void radio_mode_init(const radio_mode_ops_t *new_ops) {
    if (new_ops) {
        ops = *new_ops;
    } else {
        ops = (radio_mode_ops_t){0};
    }
    base_mode = RADIO_MODE_IDLE;
    tx_active = 0U;
}

uint8_t radio_mode_current(void) {
    return tx_active ? RADIO_MODE_TX : base_mode;
}

uint8_t radio_mode_base(void) { return base_mode; }

static void notify(void) {
    if (ops.changed) {
        ops.changed(ops.context, radio_mode_current());
    }
}

int radio_mode_request(uint8_t mode) {
    if (mode != RADIO_MODE_IDLE && mode != RADIO_MODE_RX) {
        return 0;
    }
    base_mode = mode;
    if (tx_active) {
        // Applied by radio_mode_tx_end().  IDLE stops the burst early.
        if (mode == RADIO_MODE_IDLE && ops.abort_tx) {
            ops.abort_tx(ops.context);
        }
        return 1;
    }
    if (ops.apply) {
        ops.apply(ops.context, base_mode);
    }
    notify();
    return 1;
}

void radio_mode_tx_begin(void) {
    tx_active = 1U;
    notify();
}

void radio_mode_tx_end(void) {
    tx_active = 0U;
    if (ops.apply) {
        ops.apply(ops.context, base_mode);
    }
    notify();
}

const char *radio_mode_name(uint8_t mode) {
    switch (mode) {
    case RADIO_MODE_IDLE:
        return "idle";
    case RADIO_MODE_RX:
        return "rx";
    case RADIO_MODE_TX:
        return "tx";
    default:
        return "?";
    }
}
