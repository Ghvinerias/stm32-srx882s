#pragma once

// Host-side stand-in for the radio hardware used by the streaming TX engine
// and the mode manager: records queued frames and lets a test decide when the
// "air time" of a queued frame ends.
#include "src/lab_tx.h"
#include "src/radio_mode.h"

typedef struct {
    uint16_t first_frame[LAB_FRAME_PULSES];
    unsigned bursts;          // begin() calls that succeeded
    unsigned frames_queued;   // total frames handed to queue()
    unsigned inflight;        // frames "on the air"
    unsigned max_inflight;
    unsigned ends;
    int last_end_aborted;
    int begin_ok;
    int auto_complete;        // frames finish the instant they are queued
    int queue_result;         // 1 normally; 0 = full; -1 = error
    uint32_t now;
    uint32_t queue_times[300];
    uint8_t last_base;        // last base mode passed to apply()
    unsigned applies;
    unsigned changes;
    uint8_t last_changed;
    uint8_t mode_history[64];
    unsigned history_len;
} fake_radio_t;

static fake_radio_t *g_fake;

static int fake_begin(void *context) {
    fake_radio_t *f = (fake_radio_t *)context;
    if (!f->begin_ok) {
        return 0;
    }
    ++f->bursts;
    f->frames_queued = f->bursts == 1U ? 0U : f->frames_queued;
    radio_mode_tx_begin();
    return 1;
}

static int fake_queue(void *context, const uint16_t *pulses, size_t count) {
    fake_radio_t *f = (fake_radio_t *)context;
    if (f->queue_result <= 0) {
        return f->queue_result;
    }
    if (f->frames_queued == 0U || f->inflight == 0U) {
        // keep the first frame of the most recent burst for waveform checks
    }
    if (count == LAB_FRAME_PULSES && f->frames_queued == 0U) {
        memcpy(f->first_frame, pulses, sizeof(f->first_frame));
    }
    if (f->frames_queued < 300U) {
        f->queue_times[f->frames_queued] = f->now;
    }
    ++f->frames_queued;
    if (!f->auto_complete) {
        ++f->inflight;
        if (f->inflight > f->max_inflight) {
            f->max_inflight = f->inflight;
        }
    }
    return 1;
}

static unsigned fake_inflight(void *context) {
    return ((fake_radio_t *)context)->inflight;
}

static void fake_end(void *context, int aborted) {
    fake_radio_t *f = (fake_radio_t *)context;
    ++f->ends;
    f->last_end_aborted = aborted;
    radio_mode_tx_end();
}

static uint32_t fake_now(void *context) {
    return ((fake_radio_t *)context)->now;
}

static void fake_apply(void *context, uint8_t base) {
    fake_radio_t *f = (fake_radio_t *)context;
    f->last_base = base;
    ++f->applies;
}

static void fake_changed(void *context, uint8_t mode) {
    fake_radio_t *f = (fake_radio_t *)context;
    ++f->changes;
    f->last_changed = mode;
    if (f->history_len < sizeof(f->mode_history)) {
        f->mode_history[f->history_len++] = mode;
    }
}

static void fake_abort(void *context) {
    (void)context;
    lab_tx_abort();
}

static void fake_radio_init(fake_radio_t *f) {
    memset(f, 0, sizeof(*f));
    f->begin_ok = 1;
    f->auto_complete = 1;
    f->queue_result = 1;
    g_fake = f;
    lab_tx_ops_t tx = {fake_begin, fake_queue, fake_inflight, fake_end,
                       fake_now, f};
    lab_tx_init(&tx);
    radio_mode_ops_t mode = {fake_apply, fake_changed, fake_abort, f};
    radio_mode_init(&mode);
}

// Run the TX engine to completion with frames finishing at once (each poll
// advances the clock by one frame time).  Bounded so a bug cannot hang.
static void fake_pump(fake_radio_t *f) {
    for (unsigned i = 0; i < 2000U && lab_tx_busy(); ++i) {
        f->now += 107U;
        lab_tx_poll();
    }
}
