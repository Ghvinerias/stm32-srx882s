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
// On-air waveform (verified against a real remote, see tests/data and
// BENCH.md): HCS301 PWM, carrier ON = line high, TE = LAB_TE_US:
//   preamble  12 x (1 TE high, 1 TE low), the 12th low being the header
//   header    10 TE low
//   bit 0     2 TE high, 1 TE low        bit 1     1 TE high, 2 TE low
//   guard     the last low is stretched so the line idles low for
//             LAB_FRAME_IDLE_LOW_US before the next frame
// A receiver that measures periods between consecutive falling edges (the STM32
// reference capture, tools/decode_hcs301.py, the Android decoder) therefore
// sees 77 periods per frame: 11 preamble (~2 TE), 1 header (11-12 TE), 65 data
// cells of 2/3/4 TE.  The pulse lists handed to the emit callback alternate
// high/low starting with a HIGH pulse, 156 pulses per frame.

#define LAB_TE_US 395U
#define LAB_PREAMBLE_HIGHS 12U
#define LAB_HEADER_LOW_TE 10U
#define LAB_FRAME_IDLE_LOW_US 15500U
#define LAB_CODEWORD_BITS 66U
#define LAB_FRAME_PULSES 156U // alternating H/L intervals per frame
#define LAB_FRAME_PERIODS 77U // falling-edge periods of one frame
#define LAB_TX_MAX_FRAMES 10U
#define LAB_TX_MAX_PULSES (LAB_TX_MAX_FRAMES * LAB_FRAME_PULSES)

// Reference timings of the reduced (falling-edge period) representation,
// used only to decode captured/stored 77-period frames.
#define LAB_HEADER_BIT_ZERO_US 4512U
#define LAB_HEADER_BIT_ONE_US 4170U
#define LAB_DATA_SHORT_US 795U
#define LAB_DATA_MID_US 1138U
#define LAB_DATA_LONG_US 1482U

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

// Fills pulses with the PWM waveform (alternating high/low, starting high) for
// frame_count frames; every frame ends with its long idle-low guard.  Returns
// the number of pulses (frame_count * LAB_FRAME_PULSES), or 0 if capacity is
// too small / frame_count is 0.
size_t lab_tx_build_waveform(uint32_t hopping, uint32_t serial, uint8_t button,
                             uint8_t frame_count, uint16_t *pulses,
                             size_t capacity);

// Expands one 66-bit code word into LAB_FRAME_PULSES alternating H/L pulses.
size_t lab_tx_pwm_frame(const uint8_t bits[LAB_CODEWORD_BITS], uint16_t *pulses,
                        size_t capacity);

// Reduces one PWM frame back to LAB_FRAME_PERIODS falling-edge periods (what
// the STM32 capture reports).  Returns the number of periods or 0.
size_t lab_tx_frame_periods(const uint16_t *pulses, size_t count,
                            uint16_t *periods, size_t capacity);

// Decodes a 77-period frame (STM32 representation, e.g. a stored capture)
// into the 66 code word bits, auto-calibrating the time scale.  Returns 1 on
// success.  Used to replay captured frames as real PWM.
int lab_tx_bits_from_periods(const uint16_t *periods, size_t count,
                             uint8_t bits[LAB_CODEWORD_BITS]);

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
