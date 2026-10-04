#pragma once

#include <stddef.h>
#include <stdint.h>
#include "hw_capture.h"

#define TRANSPORT_VERSION 1U
#define TRANSPORT_RECEIVER_SRX882S 1U

enum {
    TRANSPORT_TYPE_FRAME = 1U,
    TRANSPORT_TYPE_STATUS = 2U,
};

enum {
    TRANSPORT_FLAG_CAPTURE_TRUNCATED = 1U << 0,
    TRANSPORT_FLAG_DMA_OVERRUN = 1U << 1,
    TRANSPORT_FLAG_CLOCK_FALLBACK = 1U << 8,
    TRANSPORT_FLAG_CAPTURE_INIT_FAILED = 1U << 9,
};

void transport_init(uint16_t boot_flags);
int transport_send_status(uint32_t clock_hz, uint16_t dropped_frames,
                          uint32_t total_edges);
int transport_send_frame(const capture_frame_t *frame, uint16_t dropped_frames);
uint16_t transport_dropped_packets(void);

uint16_t transport_crc16(const uint8_t *data, size_t length);
size_t transport_cobs_encode(const uint8_t *input, size_t input_length,
                             uint8_t *output, size_t output_capacity);
