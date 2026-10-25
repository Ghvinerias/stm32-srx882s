// Host-side test for the simulated rolling-code receiver (src/lab_rx.c).
// Pure logic, no hardware/transport involved -- exercises the windowing
// state machine directly against the named scenarios it's modeled on
// (see src/lab_rx.h).
#include <stdio.h>
#include "src/lab_rx.h"

static int failures;

static void expect(const char *label, int condition) {
    if (!condition) {
        printf("FAIL %s\n", label);
        ++failures;
    } else {
        printf("ok   %s\n", label);
    }
}

#define SERIAL 0x084EE9D5U
#define BUTTON 0x2U

int main(void) {
    lab_rx_init();

    // --- default config: window_next=16, window_future=32768, gap=2, no replay ---
    lab_rx_pair(SERIAL, BUTTON, 0U);

    // Wrong fix: ignored regardless of count.
    expect("wrong serial is NO_FIX_MATCH",
           lab_rx_feed(1U, SERIAL ^ 1U, BUTTON) == LAB_RX_RESULT_NO_FIX_MATCH);
    expect("wrong button is NO_FIX_MATCH",
           lab_rx_feed(1U, SERIAL, BUTTON ^ 1U) == LAB_RX_RESULT_NO_FIX_MATCH);

    // Exact replay of the current count with replay flaw disabled: rejected.
    expect("replay disabled rejects same count as PAST",
           lab_rx_feed(0U, SERIAL, BUTTON) == LAB_RX_RESULT_PAST);
    expect("count unchanged after rejected replay", lab_rx_current_count() == 0U);

    // Inside window-next: accepted immediately, count advances.
    expect("count 1 is OPENED_NEXT",
           lab_rx_feed(1U, SERIAL, BUTTON) == LAB_RX_RESULT_OPENED_NEXT);
    expect("count advanced to 1", lab_rx_current_count() == 1U);

    // Now replaying that same accepted count again: PAST.
    expect("replaying just-accepted count is PAST",
           lab_rx_feed(1U, SERIAL, BUTTON) == LAB_RX_RESULT_PAST);

    // Skip ahead within window-next (count=1, +16 -> 0x11).
    expect("count 0x11 (delta 16) is OPENED_NEXT",
           lab_rx_feed(0x11U, SERIAL, BUTTON) == LAB_RX_RESULT_OPENED_NEXT);
    expect("count advanced to 0x11", lab_rx_current_count() == 0x11U);

    // --- future/gap resync ---
    lab_rx_pair(SERIAL, BUTTON, 0U);
    expect("count 0x3E91 (far ahead) is FUTURE, not yet opened",
           lab_rx_feed(0x3E91U, SERIAL, BUTTON) == LAB_RX_RESULT_FUTURE);
    expect("count unchanged while pending", lab_rx_current_count() == 0U);
    expect("count 0x3E92 within gap confirms OPENED_GAP",
           lab_rx_feed(0x3E92U, SERIAL, BUTTON) == LAB_RX_RESULT_OPENED_GAP);
    expect("count advanced to 0x3E92 after gap resync", lab_rx_current_count() == 0x3E92U);

    // A future count beyond window_future entirely: still just PAST/rejected.
    lab_rx_pair(SERIAL, BUTTON, 0U);
    expect("count beyond window_future is PAST",
           lab_rx_feed(0x10000U, SERIAL, BUTTON) == LAB_RX_RESULT_PAST);

    // Second future candidate too far from the first: no resync, becomes
    // the new pending candidate instead.
    lab_rx_pair(SERIAL, BUTTON, 0U);
    lab_rx_feed(0x3E91U, SERIAL, BUTTON);
    expect("second candidate outside gap stays FUTURE, not opened",
           lab_rx_feed(0x3E91U + 100U, SERIAL, BUTTON) == LAB_RX_RESULT_FUTURE);

    // --- replay-attack flaw enabled ---
    lab_rx_config_t flawed = {16U, 32768U, 2U, 1};
    lab_rx_configure(&flawed);
    lab_rx_pair(SERIAL, BUTTON, 5U);
    expect("replay flaw: exact same count reopens as OPENED_REPLAY",
           lab_rx_feed(5U, SERIAL, BUTTON) == LAB_RX_RESULT_OPENED_REPLAY);

    // --- rollback attack: window_future = ALL treats past as future too ---
    lab_rx_config_t rollback = {16U, 0xFFFFFFFFU, 2U, 0};
    lab_rx_configure(&rollback);
    lab_rx_pair(SERIAL, BUTTON, 1000U);
    expect("with window_future=ALL, a 'past' count is treated as FUTURE",
           lab_rx_feed(10U, SERIAL, BUTTON) == LAB_RX_RESULT_FUTURE);
    expect("a second nearby 'past' count then resyncs via OPENED_GAP",
           lab_rx_feed(11U, SERIAL, BUTTON) == LAB_RX_RESULT_OPENED_GAP);
    expect("count rolled back to 11", lab_rx_current_count() == 11U);

    if (failures == 0) {
        printf("ALL PASS\n");
        return 0;
    }
    printf("%d FAILURE(S)\n", failures);
    return 1;
}
