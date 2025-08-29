#pragma once
#include <stdint.h>
#include <stddef.h>

// Result mode for pulse decoding
typedef enum {
	PULSE_MODE_UNKNOWN = 0,
	PULSE_MODE_PULSE_DISTANCE,
	PULSE_MODE_PWM,
	PULSE_MODE_MANCHESTER,
} pulse_mode_t;

// Convert pulse widths (in microseconds) to bits using an adaptive algorithm.
// pulses: array of pulse durations alternating high/low starting with high.
// npulses: number of pulses in array (must be even for complete symbols).
// out_bits: output buffer for bits (0/1). Caller must allocate at least npulses/2 bytes.
// threshold_us: optional hint threshold; if 0 the function will auto-calc.
// Returns number of bits written and sets *out_mode if provided.
size_t pulses_to_bits(const uint32_t *pulses, size_t npulses, uint8_t *out_bits, uint32_t threshold_us, pulse_mode_t *out_mode);

// Backwards-compatible thin wrapper: uses threshold and ignores mode
static inline size_t pulses_to_bits_simple(const uint32_t *pulses, size_t npulses, uint8_t *out_bits, uint32_t threshold_us) {
	return pulses_to_bits(pulses, npulses, out_bits, threshold_us, NULL);
}
