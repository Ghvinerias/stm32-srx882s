#pragma once

#include <stddef.h>
#include <stdint.h>

#include "lab_capture.h"

// Byte-for-byte clone of firmware/src/transport.h + transport.c, with the
// single USB write replaced by a sink callback so the same framed stream can
// be fanned out to the native USB-CDC port and the BLE NUS characteristic.
//
// Packet layouts (all little-endian, all COBS encoded, 0x00 delimited):
//
//   [version:1][type:1][flags:u16][seq:u16][length:u16]
//   [timestamp:u32][capture_drops:u16][pulse_count:u16]
//   [initial_level:1][receiver_id:1]
//   [pulses: u16 * pulse_count][crc16:u16]
//
// CRC16-CCITT, poly 0x1021, init 0xFFFF, over every byte before the CRC.
// The header is always 18 bytes, so the raw packet length is
// 18 + pulse_count*2 + 2.

#define PROTO_VERSION 1U
#define PROTO_RECEIVER_ID LAB_RECEIVER_ID

enum {
    PROTO_TYPE_FRAME = 1U,
    PROTO_TYPE_STATUS = 2U,
    PROTO_TYPE_TX_COMMAND = 3U,       // host -> device
    PROTO_TYPE_SCENARIO_COMMAND = 4U, // host -> device
    PROTO_TYPE_ACK = 5U,              // device -> host
    PROTO_TYPE_OTA_CMD = 6U,          // host -> device, see ota/OTA_PROTOCOL.md
    PROTO_TYPE_OTA_RESP = 7U,         // device -> host
};

enum {
    PROTO_FLAG_CAPTURE_TRUNCATED = 1U << 0,
    PROTO_FLAG_DMA_OVERRUN = 1U << 1,
    PROTO_FLAG_CLOCK_FALLBACK = 1U << 8,
    PROTO_FLAG_CAPTURE_INIT_FAILED = 1U << 9,
};

// Link bit mask.  FRAME/STATUS go to both links, ACKs and text replies go
// back only to the link the command arrived on.
#define PROTO_LINK_USB 0x01U
#define PROTO_LINK_BLE 0x02U
#define PROTO_LINK_ALL 0x03U

// Writes length bytes to every link in targets.  Returns the mask of links
// that actually accepted the bytes (0 means nothing was delivered, which the
// transport counts as a dropped packet).
typedef uint8_t (*proto_write_fn)(void *context, uint8_t targets,
                                  const uint8_t *data, size_t length);

typedef struct {
    proto_write_fn write;
    void *context;
} proto_sink_t;

#ifdef __cplusplus
extern "C" {
#endif

void proto_init(const proto_sink_t *sink, uint16_t boot_flags);

int proto_send_status(uint32_t clock_hz, uint16_t capture_drops,
                      uint32_t total_edges, uint8_t targets);
int proto_send_frame(const capture_frame_t *frame, uint16_t capture_drops,
                     uint8_t targets);
int proto_send_ack(uint8_t for_type, uint8_t accepted, uint8_t targets);

// OTA_RESP: [version][7][op][status][payload][crc16 LE].  Sent to `targets`
// only (the caller passes the single link the OTA_CMD arrived on).
int proto_send_ota_resp(uint8_t op, uint8_t status, const uint8_t *payload,
                        size_t payload_length, uint8_t targets);

uint16_t proto_dropped_packets(void);

uint16_t proto_crc16(const uint8_t *data, size_t length);
size_t proto_cobs_encode(const uint8_t *input, size_t input_length,
                         uint8_t *output, size_t output_capacity);
size_t proto_cobs_decode(const uint8_t *input, size_t input_length,
                         uint8_t *output, size_t output_capacity);

#ifdef __cplusplus
}
#endif
