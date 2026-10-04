#include "pulse_utils.h"

#define MAX_ANALYSIS_BITS 128U

static uint32_t median_u32(const uint32_t *values, size_t count) {
    uint32_t scratch[MAX_ANALYSIS_BITS];
    if (count == 0 || count > MAX_ANALYSIS_BITS) {
        return 0;
    }
    for (size_t i = 0; i < count; ++i) {
        scratch[i] = values[i];
    }
    for (size_t i = 1; i < count; ++i) {
        uint32_t value = scratch[i];
        size_t j = i;
        while (j > 0 && scratch[j - 1] > value) {
            scratch[j] = scratch[j - 1];
            --j;
        }
        scratch[j] = value;
    }
    return scratch[count / 2U];
}

size_t pulses_to_bits(const uint32_t *pulses, size_t npulses,
                     uint8_t *out_bits, size_t out_capacity,
                     uint32_t threshold_us, pulse_mode_t *out_mode) {
    if (out_mode) {
        *out_mode = PULSE_MODE_UNKNOWN;
    }
    if (!pulses || !out_bits || npulses < 2 || out_capacity == 0) {
        return 0;
    }

    size_t pairs = npulses / 2U;
    if (pairs > out_capacity) {
        pairs = out_capacity;
    }
    if (pairs > MAX_ANALYSIS_BITS) {
        pairs = MAX_ANALYSIS_BITS;
    }

    uint32_t highs[MAX_ANALYSIS_BITS];
    uint32_t lows[MAX_ANALYSIS_BITS];
    for (size_t i = 0; i < pairs; ++i) {
        highs[i] = pulses[2U * i];
        lows[i] = pulses[2U * i + 1U];
    }

    uint32_t med_high = median_u32(highs, pairs);
    uint32_t med_low = median_u32(lows, pairs);
    uint32_t min_low = lows[0];
    uint32_t max_low = lows[0];
    for (size_t i = 1; i < pairs; ++i) {
        if (lows[i] < min_low) min_low = lows[i];
        if (lows[i] > max_low) max_low = lows[i];
    }

    if (med_high > 0 && med_low > 0) {
        uint32_t diff = med_high > med_low ? med_high - med_low : med_low - med_high;
        if (diff * 100U / ((med_high + med_low) / 2U + 1U) < 30U) {
            if (out_mode) *out_mode = PULSE_MODE_MANCHESTER;
            for (size_t i = 0; i < pairs; ++i) {
                out_bits[i] = highs[i] > lows[i] ? 1U : 0U;
            }
            return pairs;
        }
    }

    if (min_low > 0 && max_low >= 2U * min_low) {
        if (out_mode) *out_mode = PULSE_MODE_PULSE_DISTANCE;
        uint32_t low_threshold = threshold_us ? threshold_us : (min_low + max_low) / 2U;
        for (size_t i = 0; i < pairs; ++i) {
            out_bits[i] = lows[i] > low_threshold ? 1U : 0U;
        }
        return pairs;
    }

    if (out_mode) *out_mode = PULSE_MODE_PWM;
    uint32_t high_threshold = threshold_us ? threshold_us : med_high;
    for (size_t i = 0; i < pairs; ++i) {
        out_bits[i] = highs[i] > high_threshold ? 1U : 0U;
    }
    return pairs;
}
