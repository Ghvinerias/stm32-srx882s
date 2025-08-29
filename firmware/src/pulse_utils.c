#include "pulse_utils.h"
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// Helper: compute median of array of uint32_t (small arrays so simple sort is fine)
static uint32_t median_u32(uint32_t *arr, size_t n) {
    if (n == 0) return 0;
    // copy
    uint32_t *tmp = (uint32_t*)malloc(n * sizeof(uint32_t));
    if (!tmp) return 0;
    memcpy(tmp, arr, n * sizeof(uint32_t));
    // simple insertion sort
    for (size_t i = 1; i < n; ++i) {
        uint32_t v = tmp[i];
        size_t j = i;
        while (j > 0 && tmp[j-1] > v) { tmp[j] = tmp[j-1]; --j; }
        tmp[j] = v;
    }
    uint32_t m = tmp[n/2];
    free(tmp);
    return m;
}

size_t pulses_to_bits(const uint32_t *pulses, size_t npulses, uint8_t *out_bits, uint32_t threshold_us, pulse_mode_t *out_mode) {
    if (out_mode) *out_mode = PULSE_MODE_UNKNOWN;
    if (!pulses || !out_bits || npulses < 2) return 0;
    size_t pairs = npulses / 2;

    // compute arrays of highs, lows, sums
    uint32_t *highs = (uint32_t*)malloc(pairs * sizeof(uint32_t));
    uint32_t *lows = (uint32_t*)malloc(pairs * sizeof(uint32_t));
    uint32_t *sums = (uint32_t*)malloc(pairs * sizeof(uint32_t));
    if (!highs || !lows || !sums) { free(highs); free(lows); free(sums); return 0; }
    for (size_t i=0;i<pairs;i++) {
        highs[i] = pulses[2*i];
        lows[i] = pulses[2*i+1];
        sums[i] = highs[i] + lows[i];
    }

    uint32_t med_high = median_u32(highs, pairs);
    uint32_t med_low = median_u32(lows, pairs);
    uint32_t med_sum = median_u32(sums, pairs);
    // compute min/max of lows to detect pulse-distance
    uint32_t min_low = lows[0], max_low = lows[0];
    for (size_t i=1;i<pairs;i++) {
        if (lows[i] < min_low) min_low = lows[i];
        if (lows[i] > max_low) max_low = lows[i];
    }

    // Detect Manchester: highs and lows similar
    if (med_high > 0 && med_low > 0) {
        uint32_t diff = (med_high > med_low) ? (med_high - med_low) : (med_low - med_high);
        if (diff * 100 / ((med_high + med_low)/2 + 1) < 30) {
            // Manchester: decode by comparing high vs low times inside symbol
            if (out_mode) *out_mode = PULSE_MODE_MANCHESTER;
            size_t written = 0;
            for (size_t i=0;i<pairs;i++) {
                // For Manchester: a '10' or '01' constitutes a bit; take high duration relative
                out_bits[written++] = (highs[i] > lows[i]) ? 1 : 0;
            }
            free(highs); free(lows); free(sums);
            return written;
        }
    }

    // If threshold not provided, use median of sums as threshold between short/long symbols
    uint32_t thr = threshold_us;
    if (thr == 0) thr = med_sum;

    // Detect pulse-distance: lows show two clusters (short/long)
    if (min_low > 0 && max_low >= 2 * min_low) {
        if (out_mode) *out_mode = PULSE_MODE_PULSE_DISTANCE;
        uint32_t low_thr = (min_low + max_low) / 2;
        size_t written = 0;
        for (size_t i=0;i<pairs;i++) {
            out_bits[written++] = (lows[i] > low_thr) ? 1 : 0;
        }
        free(highs); free(lows); free(sums);
        return written;
    }

    // Fallback: PWM-like: treat high duration as symbol
    if (out_mode) *out_mode = PULSE_MODE_PWM;
    size_t written = 0;
    for (size_t i=0;i<pairs;i++) {
        out_bits[written++] = (highs[i] > med_high) ? 1 : 0;
    }

    free(highs); free(lows); free(sums);
    return written;
}
