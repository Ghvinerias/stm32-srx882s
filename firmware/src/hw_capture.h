#pragma once

#include <stdint.h>

#define HW_CAPTURE_MAX_PULSES 256U

enum {
    HW_CAPTURE_FLAG_TRUNCATED = 1U << 0,
    HW_CAPTURE_FLAG_DMA_OVERRUN = 1U << 1,
};

typedef struct {
    uint32_t timestamp_us;
    uint16_t pulses[HW_CAPTURE_MAX_PULSES];
    uint16_t pulse_count;
    uint16_t flags;
    uint8_t initial_level;
} capture_frame_t;

// SRX882R: CS on PB7 (driven high), DATA on TIM4 CH3/PB8.
// Capture both DATA edges with one-microsecond timer ticks.
int hw_capture_init(void);
int hw_capture_start(void);

// Poll from the main loop. Returns 1 only when a complete idle-gap-delimited
// frame is copied to out_frame.
int hw_capture_poll(capture_frame_t *out_frame);

uint16_t hw_capture_dropped_frames(void);
uint32_t hw_capture_total_edges(void);
