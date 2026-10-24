#pragma once

#include <stddef.h>
#include <stdint.h>

// Port of firmware/src/lab_tx.c's waveform generation and lab scenarios.
//
// The bit layout and timings are measurements from the project's own HCS301
// remote and are deliberately identical to the STM32 implementation:
//
//   bits[0..31]  hopping value, LSB first
//   bits[32..59] serial number, 28 bits, LSB first
//   bits[60..63] button, LSB first
//   bit[64]      VLOW (always 0)
//   bit[65]      repeat (0 for frame 0, 1 for every following frame)
//
// A frame is 77 edge-to-edge intervals: 11 preamble, 1 header, 65 data.
// The line starts high and ends low, so initial_level is always 1 and the
// RMT eot_level must be 0.

#define LAB_PREAMBLE_EDGE_US 760U
#define LAB_HEADER_BIT_ZERO_US 4512U
#define LAB_HEADER_BIT_ONE_US 4170U
#define LAB_DATA_SHORT_US 795U
#define LAB_DATA_MID_US 1138U
#define LAB_DATA_LONG_US 1482U
#define LAB_INTER_FRAME_GAP_US 15500U

#define LAB_PREAMBLE_INTERVALS 11U
#define LAB_CODEWORD_BITS 66U
#define LAB_FRAME_PULSES 77U
#define LAB_TX_MAX_FRAMES 10U
#define LAB_TX_MAX_PULSES \
    (LAB_TX_MAX_FRAMES * LAB_FRAME_PULSES + (LAB_TX_MAX_FRAMES - 1U))

// Emits one complete burst (all frames including inter-frame gaps).  Must
// block until the burst has left the radio and return 1 on success.
typedef int (*lab_tx_emit_fn)(void *context, const uint16_t *pulses,
                              size_t pulse_count);

#ifdef __cplusplus
extern "C" {
#endif

void lab_tx_init(lab_tx_emit_fn emit, void *context);

// Layout helpers, exposed for host tests and reuse by the RMT layer.
void lab_tx_build_bits(uint8_t bits[LAB_CODEWORD_BITS], uint32_t hopping,
                       uint32_t serial, uint8_t button, uint8_t frame_index);

// Fills pulses with the complete interval sequence for frame_count frames,
// including LAB_INTER_FRAME_GAP_US between frames.  Returns the number of
// intervals, or 0 if capacity is too small / frame_count is 0.
size_t lab_tx_build_waveform(uint32_t hopping, uint32_t serial, uint8_t button,
                             uint8_t frame_count, uint16_t *pulses,
                             size_t capacity);

int lab_tx_send_stale_replay(void);
int lab_tx_send_codeword(uint32_t hopping, uint32_t serial, uint8_t button,
                         uint8_t frame_count);

// r: measured stale replay
// s: synthesize the same stale code word
// z: original identity, all-zero hopping
// p: original identity, incrementing plaintext counter layout
// x: original identity, changing pseudorandom hopping field
// d: different identity, all-zero hopping
int lab_tx_run_scenario(uint8_t command);

// Exposed for tests: the measured stale frame from
// tools/captures/keeloq-button1.ndjson (77 intervals).
size_t lab_tx_stale_frame(const uint16_t **pulses);

#ifdef __cplusplus
}
#endif
