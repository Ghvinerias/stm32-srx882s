#include "lab_capture.h"

#include <stdbool.h>

#include <string.h>

// Same constants as firmware/src/hw_capture.c.  These are kept as macros in
// lab_config.h so the RMT driver can share them with the idle threshold.
#define FRAME_GAP_US LAB_CAPTURE_IDLE_MAX_US
#define FRAME_MIN_PULSES LAB_CAPTURE_MIN_PULSES

static void reset_partial_frame(lab_capture_t *capture) {
    memset(&capture->current, 0, sizeof(capture->current));
    capture->frame_active = 0;
}

static void reset_all(lab_capture_t *capture) {
    memset(capture, 0, sizeof(*capture));
}

static void close_frame(lab_capture_t *capture) {
    if (capture->current.pulse_count < FRAME_MIN_PULSES) {
        if (capture->dropped_frames != UINT16_MAX) {
            capture->dropped_frames++;
        }
    } else {
        if (capture->pending_valid && capture->dropped_frames != UINT16_MAX) {
            // The caller did not collect the previous frame in time.
            capture->dropped_frames++;
        }
        capture->pending = capture->current;
        capture->pending_valid = 1;
    }
    reset_partial_frame(capture);
}

void lab_capture_init(lab_capture_t *capture) {
    if (!capture) {
        return;
    }
    reset_all(capture);
}

void lab_capture_start(lab_capture_t *capture, uint32_t now_us, uint8_t level) {
    if (!capture) {
        return;
    }
    reset_all(capture);
    capture->line_level = level ? 1U : 0U;
    capture->last_edge_us = now_us;
}

void lab_capture_edge(lab_capture_t *capture, uint32_t timestamp_us) {
    if (!capture) {
        return;
    }

    // The STM32 code counts every captured edge, valid frame or not.
    capture->total_edges++;
    capture->line_level ^= 1U;
    uint8_t post_edge_level = capture->line_level;

    if (!capture->frame_active) {
        capture->frame_active = 1;
        capture->current.timestamp_us = timestamp_us;
        capture->current.initial_level = post_edge_level;
        capture->last_edge_us = timestamp_us;
        return;
    }

    uint32_t duration = timestamp_us - capture->last_edge_us;
    capture->last_edge_us = timestamp_us;

    if (duration >= FRAME_GAP_US) {
        // This edge terminates the previous frame.  Close it, then start a
        // new frame at this edge, exactly like hw_capture_poll() does.
        capture_frame_t completed = capture->current;

        if (completed.pulse_count >= FRAME_MIN_PULSES) {
            if (capture->pending_valid && capture->dropped_frames != UINT16_MAX) {
                capture->dropped_frames++;
            }
            capture->pending = completed;
            capture->pending_valid = 1;
        } else if (capture->dropped_frames != UINT16_MAX) {
            capture->dropped_frames++;
        }

        reset_partial_frame(capture);
        capture->frame_active = 1;
        capture->current.timestamp_us = timestamp_us;
        capture->current.initial_level = post_edge_level;
        return;
    }

    if (capture->current.pulse_count < LAB_CAPTURE_MAX_PULSES) {
        capture->current.pulses[capture->current.pulse_count++] =
            (uint16_t)duration;
    } else {
        capture->current.flags |= LAB_CAPTURE_FLAG_TRUNCATED;
    }
}

int lab_capture_poll(lab_capture_t *capture, uint32_t now_us,
                     capture_frame_t *out_frame) {
    if (!capture || !out_frame) {
        return 0;
    }

    if (capture->pending_valid) {
        *out_frame = capture->pending;
        capture->pending_valid = 0;
        return 1;
    }

    if (capture->frame_active &&
        (uint32_t)(now_us - capture->last_edge_us) >= FRAME_GAP_US) {
        close_frame(capture);
        if (capture->pending_valid) {
            *out_frame = capture->pending;
            capture->pending_valid = 0;
            return 1;
        }
    }

    return 0;
}

uint16_t lab_capture_dropped_frames(const lab_capture_t *capture) {
    return capture ? capture->dropped_frames : 0U;
}

uint32_t lab_capture_total_edges(const lab_capture_t *capture) {
    return capture ? capture->total_edges : 0U;
}

int lab_capture_finalize(const lab_capture_interval_t *intervals,
                         size_t interval_count, uint32_t now_us,
                         capture_frame_t *out_frame) {
    if (!intervals || interval_count == 0U || !out_frame) {
        return 0;
    }

    // The reception ends because the line stayed idle for at least the gap
    // threshold, so the final interval is that idle time, not part of the
    // frame.  RMT may also report a trailing zero-length half-symbol, which
    // never reaches this function.
    uint32_t total_us = 0;
    for (size_t i = 0; i < interval_count; ++i) {
        total_us += intervals[i].duration_us;
    }

    size_t count = interval_count;
    while (count > 0U &&
           intervals[count - 1U].duration_us >= FRAME_GAP_US) {
        count--;
    }

    if (count < FRAME_MIN_PULSES) {
        return 0;
    }

    memset(out_frame, 0, sizeof(*out_frame));
    out_frame->timestamp_us = now_us - total_us;
    out_frame->initial_level = intervals[0].level ? 1U : 0U;

    size_t pulses = count;
    if (pulses > LAB_CAPTURE_MAX_PULSES) {
        pulses = LAB_CAPTURE_MAX_PULSES;
        out_frame->flags |= LAB_CAPTURE_FLAG_TRUNCATED;
    }
    out_frame->pulse_count = (uint16_t)pulses;
    for (size_t i = 0; i < pulses; ++i) {
        uint32_t duration = intervals[i].duration_us;
        out_frame->pulses[i] =
            duration > UINT16_MAX ? UINT16_MAX : (uint16_t)duration;
    }
    return 1;
}

size_t lab_capture_filter_glitches(lab_capture_interval_t *intervals,
                                   size_t count, uint32_t min_us) {
    if (!intervals || count < 3U) {
        return count;
    }
    size_t out = 0;
    for (size_t i = 0; i < count; ++i) {
        bool last = i + 1U == count;
        bool next_is_last = i + 2U == count;
        if (!last && !next_is_last && intervals[i].duration_us < min_us) {
            if (out == 0U) {
                continue; // leading glitch
            }
            // Absorb the glitch and the following opposite-level interval
            // into the previous interval.
            intervals[out - 1U].duration_us +=
                intervals[i].duration_us + intervals[i + 1U].duration_us;
            ++i;
            continue;
        }
        intervals[out++] = intervals[i];
    }
    return out;
}

#define HCS_PREAMBLE_PERIODS 11U
#define HCS_DATA_CELLS 65U
#define HCS_FRAME_PERIODS (HCS_PREAMBLE_PERIODS + 1U + HCS_DATA_CELLS)

static uint32_t median_u16(const uint16_t *values, size_t count) {
    uint16_t sorted[HCS_DATA_CELLS];
    if (count > HCS_DATA_CELLS) {
        count = HCS_DATA_CELLS;
    }
    for (size_t i = 0; i < count; ++i) {
        size_t j = i;
        while (j > 0U && sorted[j - 1U] > values[i]) {
            sorted[j] = sorted[j - 1U];
            --j;
        }
        sorted[j] = values[i];
    }
    return count ? sorted[count / 2U] : 0U;
}

int lab_capture_reduce_hcs301(capture_frame_t *frame) {
    if (!frame || frame->pulse_count < 2U * (8U + 1U + 64U)) {
        return 0;
    }
    for (size_t phase = 0; phase < 2U; ++phase) {
        size_t pairs = ((size_t)frame->pulse_count - phase) / 2U;
        for (size_t h = 8U; h + 64U < pairs; ++h) {
            uint32_t header = (uint32_t)frame->pulses[phase + 2U * h] +
                              frame->pulses[phase + 2U * h + 1U];
            if (header < 3300U || header > 6500U) {
                continue;
            }
            int preamble_ok = 1;
            for (size_t k = h - 8U; k < h; ++k) {
                uint32_t period = (uint32_t)frame->pulses[phase + 2U * k] +
                                  frame->pulses[phase + 2U * k + 1U];
                if (period < 600U || period > 1000U) {
                    preamble_ok = 0;
                    break;
                }
            }
            if (!preamble_ok) {
                continue;
            }
            uint16_t cells[HCS_DATA_CELLS];
            size_t have = pairs - h - 1U;
            if (have > HCS_DATA_CELLS) {
                have = HCS_DATA_CELLS;
            }
            size_t valid = 0;
            for (; valid < have; ++valid) {
                size_t index = phase + 2U * (h + 1U + valid);
                uint32_t period = (uint32_t)frame->pulses[index] +
                                  frame->pulses[index + 1U];
                if (period < 500U || period > 1900U) {
                    break;
                }
                cells[valid] = (uint16_t)period;
            }
            // The very last pair of a capture ends in the idle gap that closed
            // the reception, so its period is meaningless: never use it.
            if (valid > 0U && h + 1U + valid == pairs) {
                --valid;
            }
            // The 65th cell is normally unusable (cut by the idle gap), so
            // accept 64 valid cells; fewer means a split frame.
            if (valid < 64U) {
                continue;
            }
            uint32_t mid = median_u16(cells, valid);
            if (mid < 1000U || mid > 1350U) {
                continue; // data cells are not the MID class: not HCS301
            }
            // Real data has bit transitions (short/long cells); a run of
            // identical cells is receiver noise, not a code word.
            size_t transitions = 0;
            for (size_t k = 0; k < valid; ++k) {
                uint32_t diff = cells[k] > mid ? cells[k] - mid : mid - cells[k];
                if (diff * 8U > mid) {
                    ++transitions;
                }
            }
            if (transitions < 8U) {
                continue;
            }
            if (valid == 64U) {
                cells[64] = (uint16_t)mid; // estimated: repeat bit = vlow bit
            }
            uint32_t preamble_sum = 0;
            for (size_t k = h - 8U; k < h; ++k) {
                preamble_sum += (uint32_t)frame->pulses[phase + 2U * k] +
                                frame->pulses[phase + 2U * k + 1U];
            }
            uint16_t preamble = (uint16_t)(preamble_sum / 8U);
            uint16_t out[HCS_FRAME_PERIODS];
            size_t n = 0;
            for (size_t k = 0; k < HCS_PREAMBLE_PERIODS; ++k) {
                out[n++] = preamble;
            }
            out[n++] = (uint16_t)header;
            for (size_t k = 0; k < HCS_DATA_CELLS; ++k) {
                out[n++] = cells[k];
            }
            for (size_t k = 0; k < HCS_FRAME_PERIODS; ++k) {
                frame->pulses[k] = out[k];
            }
            frame->pulse_count = (uint16_t)HCS_FRAME_PERIODS;
            frame->initial_level = 1U; // same convention as the STM32 capture
            return 1;
        }
    }
    return 0;
}
