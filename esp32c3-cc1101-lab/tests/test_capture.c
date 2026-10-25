// Host-side test for the portable capture framer: it must reproduce
// firmware/src/hw_capture.c's 8 ms idle delimiter, 16-pulse minimum, first
// edge timestamp and initial level.
#include "src/lab_capture.h"
#include "src/lab_config.h"

#include "test_util.h"

static void feed_edges(lab_capture_t *cap, uint32_t *timestamp, uint32_t step,
                       size_t edges) {
    for (size_t i = 0; i < edges; ++i) {
        lab_capture_edge(cap, *timestamp);
        *timestamp += step;
    }
}

int main(void) {
    // ---- a valid 16-interval frame ----
    {
        lab_capture_t cap;
        lab_capture_start(&cap, 0U, 0U); // pre-edge level low: first edge rises

        uint32_t timestamp = 1000U;
        feed_edges(&cap, &timestamp, 500U, 17U); // 17 edges -> 16 intervals

        capture_frame_t frame;
        expect("no frame before the idle gap",
               lab_capture_poll(&cap, timestamp - 500U, &frame) == 0);

        int got = lab_capture_poll(&cap, timestamp + 8000U, &frame);
        expect("16-pulse frame is delimited by an 8 ms gap", got == 1);
        expect("frame timestamp is the first edge",
               got && frame.timestamp_us == 1000U);
        expect("frame starts on the rising edge", got &&
                                                      frame.initial_level == 1U);
        expect("frame has 16 intervals", got && frame.pulse_count == 16U);
        expect("durations are preserved",
               got && frame.pulses[0] == 500U && frame.pulses[15] == 500U);
        expect("16 captured edges are counted", lab_capture_total_edges(&cap) == 17U);
    }

    // ---- fewer than 16 pulses is dropped ----
    {
        lab_capture_t cap;
        lab_capture_start(&cap, 0U, 0U);
        uint32_t timestamp = 100U;
        feed_edges(&cap, &timestamp, 500U, 11U); // 10 intervals

        capture_frame_t frame;
        expect("10-pulse frame is not reported",
               lab_capture_poll(&cap, timestamp + 8000U, &frame) == 0);
        expect("10-pulse frame increments the dropped counter",
               lab_capture_dropped_frames(&cap) == 1U);
    }

    // ---- an arriving edge that closes the gap must still yield the frame ----
    {
        lab_capture_t cap;
        lab_capture_start(&cap, 0U, 0U);
        uint32_t timestamp = 1000U;
        feed_edges(&cap, &timestamp, 500U, 17U); // 16 intervals, last edge at 9000

        // Next frame begins 9 ms after the last edge of the first.
        lab_capture_edge(&cap, 18000U);
        lab_capture_edge(&cap, 18500U);

        capture_frame_t frame;
        int got = lab_capture_poll(&cap, 19000U, &frame);
        expect("edge-closed frame is queued for the next poll", got == 1);
        expect("queued frame still has its 16 intervals",
               got && frame.pulse_count == 16U);
        expect("next frame starts at the gap-closing edge",
               lab_capture_total_edges(&cap) == 19U);
    }

    // ---- truncation flag beyond LAB_CAPTURE_MAX_PULSES ----
    {
        lab_capture_t cap;
        lab_capture_start(&cap, 0U, 0U);
        uint32_t timestamp = 0U;
        feed_edges(&cap, &timestamp, 100U,
                   LAB_CAPTURE_MAX_PULSES + 20U);

        capture_frame_t frame;
        int got = lab_capture_poll(&cap, timestamp + 8000U, &frame);
        expect("oversized frame is still reported", got == 1);
        expect("pulse array is clamped to the maximum",
               got && frame.pulse_count == LAB_CAPTURE_MAX_PULSES);
        expect("truncation flag is set",
               got && (frame.flags & LAB_CAPTURE_FLAG_TRUNCATED) != 0U);
    }

    // ---- RMT interval path: 77 nominal HCS301 intervals + idle gap ----
    {
        // 11 preamble (760) + header (4170) + 64 data (1138) + final (1482)
        // = 77 intervals.  Levels alternate starting high because the
        // transmitter toggles once per interval.  RMT reports the idle gap as
        // one more interval, so 78 entries reach lab_capture_finalize().
        lab_capture_interval_t intervals[78];
        size_t n = 0;
        for (size_t i = 0; i < 77U; ++i) {
            uint32_t duration = 1138U;
            if (i < 11U) {
                duration = 760U;
            } else if (i == 11U) {
                duration = 4170U;
            } else if (i == 76U) {
                duration = 1482U;
            }
            intervals[n].duration_us = duration;
            intervals[n++].level = (i % 2U) == 0U ? 1U : 0U;
        }
        intervals[n].duration_us = 8000U; // trailing idle interval
        intervals[n++].level = 0U;

        uint32_t now = 200000U;
        capture_frame_t frame;
        int got = lab_capture_finalize(intervals, n, now, &frame);
        uint32_t total = 0U;
        for (size_t i = 0; i < n; ++i) {
            total += intervals[i].duration_us;
        }

        expect("RMT 77-interval frame survives finalize", got == 1);
        expect("RMT frame keeps all 77 pulses",
               got && frame.pulse_count == 77U);
        expect("RMT frame strips the trailing idle interval",
               got && frame.pulses[76] == 1482U);
        expect("RMT frame starts high",
               got && frame.initial_level == 1U);
        expect("RMT frame timestamp includes the idle gap",
               got && frame.timestamp_us == now - total);
        expect("RMT frame does not set truncation",
               got && frame.flags == 0U);
    }

    // ---- RMT interval path: sub-16-pulse rejection and truncation ----
    {
        lab_capture_interval_t intervals[15];
        for (size_t i = 0; i < 15U; ++i) {
            intervals[i].duration_us = 700U;
            intervals[i].level = (i & 1U) ? 0U : 1U;
        }
        capture_frame_t frame = {0};
        expect("RMT burst under 16 pulses is dropped",
               lab_capture_finalize(intervals, 15U, 50000U, &frame) == 0);
        expect("short RMT burst leaves the output untouched",
               frame.pulse_count == 0U);
    }
    {
        lab_capture_interval_t intervals[LAB_CAPTURE_MAX_PULSES + 2U];
        size_t n = 0;
        for (size_t i = 0; i < LAB_CAPTURE_MAX_PULSES + 1U; ++i) {
            intervals[n].duration_us = 100U;
            intervals[n++].level = (i & 1U) ? 0U : 1U;
        }
        intervals[n].duration_us = 8000U;
        intervals[n++].level = 0U;

        capture_frame_t frame;
        int got = lab_capture_finalize(intervals, n, 100000U, &frame);
        expect("oversized RMT frame is reported", got == 1);
        expect("oversized RMT frame is clamped",
               got && frame.pulse_count == LAB_CAPTURE_MAX_PULSES);
        expect("oversized RMT frame sets the truncation flag",
               got && (frame.flags & LAB_CAPTURE_FLAG_TRUNCATED) != 0U);
    }

    // ---- RMT hardware filter limit & software glitch filter ----
    {
        // Regression: 20000 ns was passed as signal_range_min_ns, above the
        // 3187 ns hardware cap, so rmt_receive() failed on every call.
        expect("RMT hardware filter is below the 3187 ns cap",
               LAB_RMT_FILTER_NS < 3187U && LAB_RMT_FILTER_NS > 0U);
        expect("software chatter threshold is still 20 us",
               LAB_CAPTURE_MIN_PULSE_NS == 20000U);

        // levels 1,0,1,0,1(glitch 5us),0,1,0(gap 9000)
        lab_capture_interval_t iv[] = {
            {800, 1}, {800, 0}, {800, 1}, {5, 0}, {800, 1}, {800, 0},
            {9000, 1}};
        // a glitch (5, level 0) between two level-1 intervals: merge them
        size_t n = lab_capture_filter_glitches(iv, 7U, 20U);
        expect("glitch removal drops 2 intervals", n == 5U);
        expect("neighbours merged with glitch time (800+5+800)",
               iv[2].duration_us == 1605U && iv[2].level == 1U);
        expect("levels still alternate after filtering",
               iv[0].level != iv[1].level && iv[1].level != iv[2].level &&
                   iv[2].level != iv[3].level && iv[3].level != iv[4].level);
        expect("idle gap interval untouched",
               iv[4].duration_us == 9000U);

        lab_capture_interval_t lead[] = {{4, 1}, {800, 0}, {800, 1}, {9000, 0}};
        n = lab_capture_filter_glitches(lead, 4U, 20U);
        expect("leading glitch is dropped", n == 3U && lead[0].duration_us == 800U);

        lab_capture_interval_t gap[] = {{800, 1}, {800, 0}, {10, 1}, {9000, 0}};
        n = lab_capture_filter_glitches(gap, 4U, 20U);
        expect("short interval just before the idle gap is kept",
               n == 4U && gap[3].duration_us == 9000U);
    }

    return test_result();
}
