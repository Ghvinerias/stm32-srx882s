#pragma once

#include <stddef.h>
#include <stdint.h>
#include "hw_capture.h"

#define TRANSPORT_VERSION 1U
#define TRANSPORT_RECEIVER_SRX882S 1U

// All packet types share one COBS+CRC16-framed binary protocol over the
// native USB CDC port -- both directions. Device -> host: FRAME/STATUS
// (unchanged). Host -> device: TX_COMMAND/SCENARIO_COMMAND. Device -> host,
// in reply to either command: ACK. Framing being self-delimited and binary
// both ways is what lets capture streaming and command input coexist on
// the same endpoint without a mode switch: there is no free-text console
// protocol left to collide with the packet stream.
enum {
    TRANSPORT_TYPE_FRAME = 1U,
    TRANSPORT_TYPE_STATUS = 2U,
    TRANSPORT_TYPE_TX_COMMAND = 3U,       // host -> device
    TRANSPORT_TYPE_SCENARIO_COMMAND = 4U, // host -> device
    TRANSPORT_TYPE_ACK = 5U,              // device -> host
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

// Reply to a received TX_COMMAND/SCENARIO_COMMAND packet: echoes the type
// it answers, plus whether it was accepted and executed.
int transport_send_ack(uint8_t for_type, uint8_t accepted);

uint16_t transport_dropped_packets(void);

uint16_t transport_crc16(const uint8_t *data, size_t length);
size_t transport_cobs_encode(const uint8_t *input, size_t input_length,
                             uint8_t *output, size_t output_capacity);
// Decodes a COBS-encoded body (the bytes *before* the trailing 0x00 packet
// delimiter -- that delimiter is not part of the COBS body itself, see
// transport_cobs_encode, and should be stripped by the caller first).
size_t transport_cobs_decode(const uint8_t *input, size_t input_length,
                             uint8_t *output, size_t output_capacity);
