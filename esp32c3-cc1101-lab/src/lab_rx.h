#pragma once

#include <stdint.h>

// Simulates a simplified KeeLoq-style rolling-code *receiver*, purely so this
// lab's own transmitter (lab_tx.c) can be tested against configurable
// resynchronization policies without touching real hardware.
//
// This is NOT a KeeLoq decryption engine and never will be: the "hopping"
// value passed in is treated as an already-known plaintext counter, by design.
// This lab only ever exercises its own synthetic codewords (the same ones
// lab_tx.c generates) against a receiver model it controls -- never a third
// party's encrypted traffic, and no key material is involved anywhere in this
// file.
//
// The windowing behavior modeled here (next/single window, future/double
// window, gap-confirmed resync, past rejection, and an optional replay-attack
// flaw toggle) mirrors the resynchronization mechanism described in the
// HCS300/301 datasheets and demonstrated interactively by the "Rolling Flaws"
// Flipper Zero app
// (github.com/jamisonderek/flipper-zero-tutorials/tree/main/subghz/apps/rolling-flaws),
// so results from this simulator map directly onto those named scenarios.

typedef enum {
    LAB_RX_RESULT_NO_FIX_MATCH = 0, // serial/button didn't match the paired fix
    LAB_RX_RESULT_PAST,             // count was at/behind the stored count
    LAB_RX_RESULT_FUTURE,           // inside window-future but not confirmed
    LAB_RX_RESULT_OPENED_NEXT,      // inside window-next: accepted immediately
    LAB_RX_RESULT_OPENED_GAP,       // second future count within gap: resynced
    LAB_RX_RESULT_OPENED_REPLAY,    // replay flaw: re-accepted an exact past count
} lab_rx_result_t;

typedef struct {
    uint32_t window_next;   // normal/single sync window width
    uint32_t window_future; // resync/double window width; 0xFFFFFFFFU means all
    uint32_t window_gap;    // max forward distance between two future counts
    int allow_replay;       // simulate a receiver with the replay flaw
} lab_rx_config_t;

#ifdef __cplusplus
extern "C" {
#endif

void lab_rx_init(void);
void lab_rx_configure(const lab_rx_config_t *config);
void lab_rx_pair(uint32_t serial, uint8_t button, uint32_t starting_count);
lab_rx_result_t lab_rx_feed(uint32_t hopping_count, uint32_t serial,
                            uint8_t button);
uint32_t lab_rx_current_count(void);
int lab_rx_is_paired(void);

#ifdef __cplusplus
}
#endif
