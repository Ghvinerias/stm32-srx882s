// Timing model of the streaming TX engine: frames are queued one ahead so the
// radio never starves (inter-frame timing stays exact), never more than two
// are in flight, and an abort stops after the frame(s) already on the air.
#include "src/lab_tx.h"

#include "test_util.h"

#define MAXF 300

typedef struct {
    uint32_t now;
    uint32_t start[MAXF], end[MAXF];
    unsigned queued;
    unsigned max_inflight;
    unsigned ended, aborted_flag;
    uint8_t result;
    int done;
} sim_t;

static sim_t sim;

static int s_begin(void *c) { (void)c; return 1; }
static unsigned live(void) {
    unsigned n = 0;
    for (unsigned i = 0; i < sim.queued; ++i) n += sim.end[i] > sim.now;
    return n;
}
static int s_queue(void *c, const uint16_t *p, size_t n) {
    (void)c;
    uint32_t dur = 0;
    for (size_t i = 0; i < n; ++i) dur += p[i];
    dur = (dur + 500U) / 1000U;
    if (live() >= 2U) return 0;
    uint32_t start = sim.now;
    if (sim.queued && sim.end[sim.queued - 1U] > start) start = sim.end[sim.queued - 1U];
    sim.start[sim.queued] = start;
    sim.end[sim.queued] = start + dur;
    ++sim.queued;
    if (live() > sim.max_inflight) sim.max_inflight = live();
    return 1;
}
static unsigned s_inflight(void *c) { (void)c; return live(); }
static void s_end(void *c, int a) { (void)c; ++sim.ended; sim.aborted_flag = (unsigned)a; }
static uint32_t s_now(void *c) { (void)c; return sim.now; }
static void s_done(void *c, uint8_t k, uint8_t l, uint8_t r) {
    (void)c; (void)k; (void)l; sim.result = r; sim.done = 1;
}

static void run(unsigned frames, unsigned loop_ms, unsigned abort_at_ms) {
    memset(&sim, 0, sizeof(sim));
    lab_tx_ops_t ops = {s_begin, s_queue, s_inflight, s_end, s_now, 0};
    lab_tx_init(&ops);
    lab_tx_set_done(s_done, 0);
    lab_tx_start_codeword(0x114766B9U, 0x0FB58C0CU, 2U, (uint8_t)frames, 3U, 1U);
    for (unsigned t = 0; t < 40000U && lab_tx_busy(); ++t) {
        sim.now = t;
        if (abort_at_ms && t == abort_at_ms) lab_tx_abort();
        if (t % loop_ms == 0U) lab_tx_poll(); // main loop iteration period
    }
}

int main(void) {
    run(50, 1, 0);
    expect("50 frames all queued", sim.queued == 50U);
    expect("never more than 2 frames in flight", sim.max_inflight <= 2U);
    unsigned worst = 0;
    for (unsigned i = 1; i < sim.queued; ++i) {
        unsigned gap = sim.start[i] - sim.end[i - 1U];
        if (gap > worst) worst = gap;
    }
    expect("frames are back to back (no radio gap) with a 1 ms loop", worst == 0U);
    expect("burst result COMPLETE delivered after the last frame",
           sim.done && sim.result == LAB_TX_RESULT_COMPLETE && sim.now >= sim.end[49]);
    expect("whole-burst length is ~50 x 106 ms", sim.end[49] > 5000U && sim.end[49] < 5600U);

    run(50, 20, 0); // a sluggish 20 ms main loop must still not starve the radio
    worst = 0;
    for (unsigned i = 1; i < sim.queued; ++i) {
        unsigned gap = sim.start[i] - sim.end[i - 1U];
        if (gap > worst) worst = gap;
    }
    expect("20 ms loop period keeps frames back to back (lookahead window)", worst == 0U);

    run(255, 5, 0);
    expect("255 frames complete", sim.queued == 255U && sim.result == LAB_TX_RESULT_COMPLETE);

    run(50, 1, 1500);
    expect("abort at 1.5 s stops queuing; ACK result ABORTED",
           sim.done && sim.result == LAB_TX_RESULT_ABORTED && sim.aborted_flag == 1U);
    expect("abort stops within one frame of the request",
           sim.queued < 50U && sim.end[sim.queued - 1U] <= 1500U + 2U * 107U);
    printf("  frames sent before abort: %u\n", sim.queued);

    run(2, 1, 400); // abort after everything is already queued: nothing left to abort
    expect("abort when all frames are already queued still completes",
           sim.done && sim.result == LAB_TX_RESULT_COMPLETE && sim.queued == 2U);
    return test_result();
}
