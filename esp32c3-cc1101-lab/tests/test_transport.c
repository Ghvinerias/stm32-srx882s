// Host-side golden tests for the COBS/CRC16 transport clone.  The expected
// raw bytes are built here by hand, in the documented header order, so a
// mistake in proto_transport.c cannot silently agree with itself.
#include "src/proto_transport.h"

#include "test_util.h"

static void put_u16_le(uint8_t *out, uint16_t value) {
    out[0] = (uint8_t)(value & 0xFFU);
    out[1] = (uint8_t)(value >> 8U);
}

static void put_u32_le(uint8_t *out, uint32_t value) {
    out[0] = (uint8_t)(value & 0xFFU);
    out[1] = (uint8_t)((value >> 8U) & 0xFFU);
    out[2] = (uint8_t)((value >> 16U) & 0xFFU);
    out[3] = (uint8_t)(value >> 24U);
}

static int same_bytes(const uint8_t *a, const uint8_t *b, size_t length) {
    return memcmp(a, b, length) == 0;
}

int main(void) {
    link_ctx_t links;
    memset(&links, 0, sizeof(links));
    links.deliver_mask = 0x03U;
    proto_sink_t sink = {link_write, &links};

    // ---- CRC16-CCITT check value ----
    const uint8_t check[] = "123456789";
    expect("CRC16-CCITT check value is 0x29B1",
           proto_crc16(check, 9) == 0x29B1U);

    // ---- COBS ----
    {
        uint8_t encoded[32];
        uint8_t decoded[32];
        const uint8_t one_zero[] = {0x00};
        size_t n = proto_cobs_encode(one_zero, 1, encoded, sizeof(encoded));
        expect("COBS single zero is 01 01 00",
               n == 3U && encoded[0] == 0x01U && encoded[1] == 0x01U &&
                   encoded[2] == 0x00U);

        const uint8_t sample[] = {0x11, 0x22, 0x00, 0x33};
        n = proto_cobs_encode(sample, sizeof(sample), encoded,
                              sizeof(encoded));
        expect("COBS sample matches 03 11 22 02 33 00",
               n == 6U && encoded[0] == 0x03U && encoded[1] == 0x11U &&
                   encoded[2] == 0x22U && encoded[3] == 0x02U &&
                   encoded[4] == 0x33U && encoded[5] == 0x00U);

        size_t d = proto_cobs_decode(encoded, n - 1U, decoded,
                                     sizeof(decoded));
        expect("COBS round-trip without trailing delimiter",
               d == sizeof(sample) && same_bytes(decoded, sample, d));

        uint8_t zeros[600];
        uint8_t long_encoded[640];
        uint8_t long_decoded[600];
        for (size_t i = 0; i < sizeof(zeros); ++i) {
            zeros[i] = (uint8_t)((i % 7U) == 0U ? 0x00U : (uint8_t)i);
        }
        size_t e = proto_cobs_encode(zeros, sizeof(zeros), long_encoded,
                                     sizeof(long_encoded));
        size_t r = proto_cobs_decode(long_encoded, e - 1U, long_decoded,
                                     sizeof(long_decoded));
        expect("COBS round-trip 600 bytes with zeros",
               e != 0U && r == sizeof(zeros) &&
                   same_bytes(zeros, long_decoded, r));
    }

    // ---- FRAME packet golden bytes ----
    {
        capture_frame_t frame;
        memset(&frame, 0, sizeof(frame));
        frame.timestamp_us = 0x12345678U;
        frame.initial_level = 1U;
        frame.pulse_count = 3U;
        frame.pulses[0] = 795U;
        frame.pulses[1] = 1138U;
        frame.pulses[2] = 1482U;

        uint8_t raw[64];
        size_t raw_length = 18U + 3U * 2U + 2U;
        memset(raw, 0, sizeof(raw));
        raw[0] = 1U;
        raw[1] = PROTO_TYPE_FRAME;
        put_u16_le(&raw[2], 0U);
        put_u16_le(&raw[4], 0U); // first packet sequence
        put_u16_le(&raw[6], (uint16_t)raw_length);
        put_u32_le(&raw[8], frame.timestamp_us);
        put_u16_le(&raw[12], 7U); // capture drops
        put_u16_le(&raw[14], 3U);
        raw[16] = 1U;
        raw[17] = PROTO_RECEIVER_ID;
        put_u16_le(&raw[18], 795U);
        put_u16_le(&raw[20], 1138U);
        put_u16_le(&raw[22], 1482U);
        put_u16_le(&raw[24], proto_crc16(raw, 24U));

        uint8_t golden[64];
        size_t golden_length =
            proto_cobs_encode(raw, raw_length, golden, sizeof(golden));

        rec_reset(&links.usb);
        rec_reset(&links.ble);
        proto_init(&sink, 0U);
        int sent = proto_send_frame(&frame, 7U, PROTO_LINK_ALL);
        expect("frame sent to both links", sent == 1);
        expect("frame raw bytes are unchanged from the STM32 layout",
               links.usb.length == golden_length &&
                   same_bytes(links.usb.data, golden, golden_length));
        expect("frame is broadcast identically to BLE",
               links.ble.length == golden_length &&
                   same_bytes(links.ble.data, golden, golden_length));
        expect("receiver id is not the STM32's 1",
               raw[17] != 1U && raw[17] == PROTO_RECEIVER_ID);
    }

    // ---- flags are OR-ed with boot flags ----
    {
        capture_frame_t frame;
        memset(&frame, 0, sizeof(frame));
        frame.pulse_count = 0U;
        frame.timestamp_us = 1U;
        frame.initial_level = 0U;
        frame.flags = LAB_CAPTURE_FLAG_TRUNCATED;

        rec_reset(&links.usb);
        proto_init(&sink, PROTO_FLAG_CAPTURE_INIT_FAILED);
        proto_send_frame(&frame, 0U, PROTO_LINK_USB);
        uint8_t raw[64];
        size_t n = proto_cobs_decode(links.usb.data, links.usb.length - 1U,
                                     raw, sizeof(raw));
        uint16_t flags = (uint16_t)raw[2] | ((uint16_t)raw[3] << 8U);
        expect("frame flags include boot and per-frame bits",
               n == 20U &&
                   flags == (PROTO_FLAG_CAPTURE_INIT_FAILED |
                             PROTO_FLAG_CAPTURE_TRUNCATED));
    }

    // ---- ACK golden bytes and routing ----
    {
        uint8_t raw[6] = {1U, PROTO_TYPE_ACK, PROTO_TYPE_TX_COMMAND, 1U};
        put_u16_le(&raw[4], proto_crc16(raw, 4U));

        rec_reset(&links.usb);
        rec_reset(&links.ble);
        proto_init(&sink, 0U);
        int sent = proto_send_ack(PROTO_TYPE_TX_COMMAND, 1U, PROTO_LINK_BLE);
        expect("ACK delivered", sent == 1);
        expect("ACK routed only to the originating link",
               links.usb.length == 0U && links.ble.length != 0U);

        uint8_t decoded[16];
        size_t n = proto_cobs_decode(links.ble.data, links.ble.length - 1U,
                                     decoded, sizeof(decoded));
        expect("ACK bytes match the STM32 layout",
               n == 6U && same_bytes(decoded, raw, 6U));
    }

    // ---- STATUS golden bytes ----
    {
        rec_reset(&links.usb);
        proto_init(&sink, 0U);
        proto_send_status(1000000U, 3U, 0x11223344U, PROTO_LINK_USB);

        uint8_t raw[64];
        size_t n = proto_cobs_decode(links.usb.data, links.usb.length - 1U,
                                     raw, sizeof(raw));
        expect("STATUS raw length is 30", n == 30U);
        expect("STATUS type/sequence", raw[0] == 1U && raw[1] == 2U &&
                                           raw[4] == 0U && raw[5] == 0U);
        expect("STATUS carries clock and edge counts little-endian",
               raw[18] == 0x40U && raw[19] == 0x42U && raw[20] == 0x0FU &&
                   raw[21] == 0x00U && raw[22] == 0x44U && raw[23] == 0x33U &&
                   raw[24] == 0x22U && raw[25] == 0x11U);
        expect("STATUS carries dropped packet count", raw[26] == 0U &&
                                                          raw[27] == 0U);
        uint16_t crc = (uint16_t)raw[28] | ((uint16_t)raw[29] << 8U);
        expect("STATUS CRC", crc == proto_crc16(raw, 28U));
    }

    // ---- dropped packet accounting ----
    {
        rec_reset(&links.usb);
        rec_reset(&links.ble);
        links.deliver_mask = 0x00U;
        proto_init(&sink, 0U);
        expect("send reports failure when nothing is delivered",
               proto_send_ack(PROTO_TYPE_TX_COMMAND, 1U, PROTO_LINK_ALL) == 0);
        expect("dropped packet count increments once",
               proto_dropped_packets() == 1U);
        links.deliver_mask = 0x03U;
    }

    return test_result();
}
