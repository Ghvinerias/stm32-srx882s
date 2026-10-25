#include "transport.h"
#include "usb_cdc.h"

#define HEADER_SIZE 18U
#define CRC_SIZE 2U
#define RAW_PACKET_MAX (HEADER_SIZE + HW_CAPTURE_MAX_PULSES * 2U + CRC_SIZE)
#define ENCODED_PACKET_MAX (RAW_PACKET_MAX + RAW_PACKET_MAX / 254U + 2U)

static uint16_t next_sequence;
static uint16_t dropped_packets;
static uint16_t persistent_flags;
static uint8_t raw_packet[RAW_PACKET_MAX];
static uint8_t encoded_packet[ENCODED_PACKET_MAX];

static void put_u16(uint8_t *buffer, size_t offset, uint16_t value) {
    buffer[offset] = (uint8_t)(value & 0xFFU);
    buffer[offset + 1U] = (uint8_t)(value >> 8U);
}

static void put_u32(uint8_t *buffer, size_t offset, uint32_t value) {
    buffer[offset] = (uint8_t)(value & 0xFFU);
    buffer[offset + 1U] = (uint8_t)((value >> 8U) & 0xFFU);
    buffer[offset + 2U] = (uint8_t)((value >> 16U) & 0xFFU);
    buffer[offset + 3U] = (uint8_t)(value >> 24U);
}

uint16_t transport_crc16(const uint8_t *data, size_t length) {
    uint16_t crc = 0xFFFFU;
    for (size_t i = 0; i < length; ++i) {
        crc ^= (uint16_t)data[i] << 8U;
        for (uint8_t bit = 0; bit < 8U; ++bit) {
            crc = (crc & 0x8000U) ? (uint16_t)((crc << 1U) ^ 0x1021U)
                                  : (uint16_t)(crc << 1U);
        }
    }
    return crc;
}

size_t transport_cobs_encode(const uint8_t *input, size_t input_length,
                             uint8_t *output, size_t output_capacity) {
    if (!input || !output || output_capacity < 2U) {
        return 0;
    }

    size_t read_index = 0;
    size_t write_index = 1;
    size_t code_index = 0;
    uint8_t code = 1;

    while (read_index < input_length) {
        if (input[read_index] == 0) {
            if (code_index >= output_capacity) {
                return 0;
            }
            output[code_index] = code;
            code = 1;
            code_index = write_index++;
            if (write_index > output_capacity) {
                return 0;
            }
            read_index++;
        } else {
            if (write_index >= output_capacity) {
                return 0;
            }
            output[write_index++] = input[read_index++];
            code++;
            if (code == 0xFFU) {
                output[code_index] = code;
                code = 1;
                code_index = write_index++;
                if (write_index > output_capacity) {
                    return 0;
                }
            }
        }
    }

    if (code_index >= output_capacity || write_index >= output_capacity) {
        return 0;
    }
    output[code_index] = code;
    output[write_index++] = 0;
    return write_index;
}

size_t transport_cobs_decode(const uint8_t *input, size_t input_length,
                             uint8_t *output, size_t output_capacity) {
    if (!input || !output) {
        return 0;
    }
    size_t read_index = 0;
    size_t write_index = 0;
    while (read_index < input_length) {
        uint8_t code = input[read_index];
        if (code == 0U || read_index + code > input_length + 1U) {
            return 0;
        }
        read_index++;
        for (uint8_t i = 1U; i < code; ++i) {
            if (read_index >= input_length || write_index >= output_capacity) {
                return 0;
            }
            output[write_index++] = input[read_index++];
        }
        if (code != 0xFFU && read_index < input_length) {
            if (write_index >= output_capacity) {
                return 0;
            }
            output[write_index++] = 0;
        }
    }
    return write_index;
}

static int send_raw(const uint8_t *raw, size_t raw_length) {
    size_t encoded_length = transport_cobs_encode(raw, raw_length,
                                                  encoded_packet,
                                                  sizeof(encoded_packet));
    int sent = encoded_length != 0 &&
               usb_cdc_write(encoded_packet, encoded_length);
    if (!sent) {
        if (dropped_packets != UINT16_MAX) {
            dropped_packets++;
        }
        return 0;
    }
    return 1;
}

static int send_packet(uint8_t type, uint16_t flags, uint32_t timestamp,
                       uint16_t capture_drops, uint8_t initial_level,
                       const uint16_t *pulses, uint16_t pulse_count) {
    size_t raw_length = HEADER_SIZE + (size_t)pulse_count * 2U + CRC_SIZE;
    if (raw_length > sizeof(raw_packet)) {
        return 0;
    }

    raw_packet[0] = TRANSPORT_VERSION;
    raw_packet[1] = type;
    put_u16(raw_packet, 2, (uint16_t)(persistent_flags | flags));
    put_u16(raw_packet, 4, next_sequence++);
    put_u16(raw_packet, 6, (uint16_t)raw_length);
    put_u32(raw_packet, 8, timestamp);
    put_u16(raw_packet, 12, capture_drops);
    put_u16(raw_packet, 14, pulse_count);
    raw_packet[16] = initial_level;
    raw_packet[17] = TRANSPORT_RECEIVER_SRX882S;

    for (uint16_t i = 0; i < pulse_count; ++i) {
        put_u16(raw_packet, HEADER_SIZE + (size_t)i * 2U, pulses[i]);
    }
    uint16_t crc = transport_crc16(raw_packet, raw_length - CRC_SIZE);
    put_u16(raw_packet, raw_length - CRC_SIZE, crc);

    return send_raw(raw_packet, raw_length);
}

void transport_init(uint16_t boot_flags) {
    next_sequence = 0;
    dropped_packets = 0;
    persistent_flags = boot_flags;
}

int transport_send_ack(uint8_t for_type, uint8_t accepted) {
    uint8_t ack[6];
    ack[0] = TRANSPORT_VERSION;
    ack[1] = TRANSPORT_TYPE_ACK;
    ack[2] = for_type;
    ack[3] = accepted;
    uint16_t crc = transport_crc16(ack, 4U);
    ack[4] = (uint8_t)(crc & 0xFFU);
    ack[5] = (uint8_t)(crc >> 8U);
    return send_raw(ack, sizeof(ack));
}

int transport_send_status(uint32_t clock_hz, uint16_t capture_drops,
                          uint32_t total_edges) {
    uint16_t status[5] = {
        (uint16_t)(clock_hz & 0xFFFFU),
        (uint16_t)(clock_hz >> 16U),
        (uint16_t)(total_edges & 0xFFFFU),
        (uint16_t)(total_edges >> 16U),
        dropped_packets,
    };
    return send_packet(TRANSPORT_TYPE_STATUS, 0, 0,
                       capture_drops, 0, status, 5);
}

int transport_send_frame(const capture_frame_t *frame, uint16_t capture_drops) {
    if (!frame) {
        return 0;
    }
    uint16_t flags = 0;
    if (frame->flags & HW_CAPTURE_FLAG_TRUNCATED) {
        flags |= TRANSPORT_FLAG_CAPTURE_TRUNCATED;
    }
    if (frame->flags & HW_CAPTURE_FLAG_DMA_OVERRUN) {
        flags |= TRANSPORT_FLAG_DMA_OVERRUN;
    }
    return send_packet(TRANSPORT_TYPE_FRAME, flags, frame->timestamp_us,
                       capture_drops, frame->initial_level,
                       frame->pulses, frame->pulse_count);
}

uint16_t transport_dropped_packets(void) {
    return dropped_packets;
}
