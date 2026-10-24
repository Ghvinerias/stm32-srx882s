#pragma once

#include <stddef.h>
#include <stdint.h>

#include "lab_config.h"

// Portable re-implementation of the byte-level framing half of
// firmware/src/hw_capture.c: edge durations are turned into idle-gap-delimited
// capture frames with the same 8 ms gap and 16-pulse minimum, so the ESP32
// RMT path produces exactly the frames the STM32 DMA path produced.
//
// The RMT glue feeds reconstructed edge timestamps into this module; host
// tests drive it directly.  No hardware calls live here.

enum {
    LAB_CAPTURE_FLAG_TRUNCATED = 1U << 0,
    LAB_CAPTURE_FLAG_DMA_OVERRUN = 1U << 1,
};

typedef struct {
    uint32_t timestamp_us;
    uint16_t pulses[LAB_CAPTURE_MAX_PULSES];
    uint16_t pulse_count;
    uint16_t flags;
    uint8_t initial_level;
} capture_frame_t;

typedef struct {
    capture_frame_t current;
    capture_frame_t pending; // closed by an arriving edge, not yet polled
    uint32_t last_edge_us;
    uint16_t dropped_frames;
    uint32_t total_edges;
    uint8_t line_level; // level after the most recent edge
    uint8_t frame_active;
    uint8_t pending_valid;
} lab_capture_t;

// One level+duration interval as produced by the RMT symbol decoder: the line
// held `level` for `duration_us` after an edge.  Adjacent entries always have
// different levels (the caller merges pieces the hardware split).
typedef struct {
    uint32_t duration_us;
    uint8_t level;
} lab_capture_interval_t;

#ifdef __cplusplus
extern "C" {
#endif

// Clears all state, including counters.  level is the current (pre-edge)
// line level, matching hw_capture_start().
void lab_capture_init(lab_capture_t *capture);
void lab_capture_start(lab_capture_t *capture, uint32_t now_us, uint8_t level);

// One captured edge, timestamped in microseconds (monotonic, 32-bit wrap is
// handled by unsigned subtraction).
void lab_capture_edge(lab_capture_t *capture, uint32_t timestamp_us);

// Call from the main loop.  Returns 1 and fills out_frame when an idle gap has
// closed a frame with at least LAB_CAPTURE_MIN_PULSES pulses.
int lab_capture_poll(lab_capture_t *capture, uint32_t now_us,
                     capture_frame_t *out_frame);

uint16_t lab_capture_dropped_frames(const lab_capture_t *capture);
uint32_t lab_capture_total_edges(const lab_capture_t *capture);

// Finalize one already idle-delimited RMT reception into a capture frame.
//
// `now_us` is the timestamp at which the idle timeout fired, i.e. the end of
// the reception.  The trailing interval is normally the idle gap itself
// (>= LAB_CAPTURE_IDLE_MAX_US) and is removed from the pulse list, but its time
// still counts when back-calculating the frame start.  Returns 1 when at least
// LAB_CAPTURE_MIN_PULSES remain, 0 otherwise (no frame).  The output follows
// exactly the same conventions as lab_capture_poll(): timestamp_us is the
// first edge, initial_level is the level after that edge, and pulse_count is
// capped at LAB_CAPTURE_MAX_PULSES with LAB_CAPTURE_FLAG_TRUNCATED set.
int lab_capture_finalize(const lab_capture_interval_t *intervals,
                         size_t interval_count, uint32_t now_us,
                         capture_frame_t *out_frame);

// Removes sub-`min_us` glitch intervals in place and returns the new count.
// A glitch between two intervals of the opposite level is dropped and its
// neighbours merged into one (level alternation and total time are kept); a
// leading glitch is dropped; the final interval (the idle gap) is never
// touched or merged into.
size_t lab_capture_filter_glitches(lab_capture_interval_t *intervals,
                                   size_t count, uint32_t min_us);

#ifdef __cplusplus
}
#endif
