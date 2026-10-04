#pragma once

#include <stdint.h>

// Configure STX882 DATA on PB6 and hold it inactive (low).
int lab_tx_init(void);

// Reproduce one previously captured, already-used RF frame. This is a
// replay-validation experiment: a conforming rolling-code receiver should
// reject it as stale. Returns 1 after the waveform has been emitted.
int lab_tx_send_stale_replay(void);

// Emit an HCS301-shaped 66-bit code word using the timing measured from the
// two lab remotes. The 32-bit hopping value is supplied explicitly; this
// function does not claim to perform KeeLoq encryption.
int lab_tx_send_codeword(uint32_t hopping, uint32_t serial, uint8_t button,
                         uint8_t frame_count);

// Run one command-selected black-box receiver experiment.
// r: measured stale replay
// s: synthesize the same stale code word (waveform-generator control)
// z: original identity with an all-zero hopping field
// p: original identity with an incrementing *plaintext* counter layout
// x: original identity with a changing pseudorandom hopping field
// d: different identity with an all-zero hopping field
int lab_tx_run_scenario(uint8_t command);
