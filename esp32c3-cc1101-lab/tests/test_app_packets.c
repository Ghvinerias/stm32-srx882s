// Regression test: feeds the exact byte streams the Android app emits
// (PacketCodec.encode*Command -> Cobs.encode -> 0x00 delimiter) through
// command_parse_feed, in the chunkings USB-CDC and BLE actually deliver, and
// requires an ACK with the right for_type for every one of them.
#include "src/command_parse.h"
#include "src/lab_tx.h"
#include "src/proto_transport.h"

#include "test_util.h"

#include "fake_radio.h"

static fake_radio_t radio;

static void put_u32_le(uint8_t *out, uint32_t value) {
    for (int i = 0; i < 4; ++i) {
        out[i] = (uint8_t)(value >> (8 * i));
    }
}

static size_t app_tx_packet(uint8_t *wire, uint32_t hopping, uint32_t serial,
                            uint8_t button, uint8_t frames) {
    uint8_t raw[14];
    raw[0] = 1U;
    raw[1] = PROTO_TYPE_TX_COMMAND;
    put_u32_le(&raw[2], hopping);
    put_u32_le(&raw[6], serial & 0x0FFFFFFFU);
    raw[10] = button;
    raw[11] = frames;
    uint16_t crc = proto_crc16(raw, 12U);
    raw[12] = (uint8_t)crc;
    raw[13] = (uint8_t)(crc >> 8U);
    return proto_cobs_encode(raw, sizeof(raw), wire, 64U); // appends 0x00
}

static size_t app_scenario_packet(uint8_t *wire, uint8_t command) {
    uint8_t raw[5] = {1U, PROTO_TYPE_SCENARIO_COMMAND, command, 0, 0};
    uint16_t crc = proto_crc16(raw, 3U);
    raw[3] = (uint8_t)crc;
    raw[4] = (uint8_t)(crc >> 8U);
    return proto_cobs_encode(raw, sizeof(raw), wire, 64U);
}

static int ack_is(const rec_t *rec, uint8_t for_type) {
    uint8_t raw[16];
    if (rec->length < 3U || rec->data[rec->length - 1U] != 0U) {
        return 0;
    }
    size_t n = proto_cobs_decode(rec->data, rec->length - 1U, raw, sizeof(raw));
    return n == 6U && raw[1] == PROTO_TYPE_ACK && raw[2] == for_type &&
           raw[3] == 1U &&
           proto_crc16(raw, 4U) ==
               (uint16_t)(raw[4] | ((uint16_t)raw[5] << 8U));
}

static link_ctx_t links;

// Returns 1 if feeding `wire` in `chunk`-byte pieces yields exactly one ACK.
static int send_chunked(uint8_t link, const uint8_t *wire, size_t length,
                        size_t chunk, uint8_t for_type) {
    rec_reset(&links.usb);
    rec_reset(&links.ble);
    radio.bursts = 0U;
    radio.frames_queued = 0U;
    for (size_t off = 0; off < length; off += chunk) {
        size_t n = length - off < chunk ? length - off : chunk;
        command_parse_feed(link, wire + off, n);
    }
    fake_pump(&radio);
    const rec_t *rec = link == PROTO_LINK_USB ? &links.usb : &links.ble;
    return radio.bursts == 1U && ack_is(rec, for_type);
}

int main(void) {
    memset(&links, 0, sizeof(links));
    links.deliver_mask = 0x03U;
    proto_sink_t sink = {link_write, &links};
    proto_init(&sink, 0U);
    command_parse_init(&sink, NULL);
    fake_radio_init(&radio);

    uint8_t wire[64];
    size_t n = app_tx_packet(wire, 0x114766B9U, 0x0FB58C0CU, 2U, 3U);
    expect("USB: whole app TX packet is ACKed",
           send_chunked(PROTO_LINK_USB, wire, n, n, PROTO_TYPE_TX_COMMAND));
    expect("BLE: whole app TX packet is ACKed",
           send_chunked(PROTO_LINK_BLE, wire, n, n, PROTO_TYPE_TX_COMMAND));

    const uint8_t scenarios[] = {'s', 'z', 'p', 'x', 'd', 'r'};
    for (size_t i = 0; i < sizeof(scenarios); ++i) {
        n = app_scenario_packet(wire, scenarios[i]);
        expect("scenario ACKed over USB",
               send_chunked(PROTO_LINK_USB, wire, n, n,
                            PROTO_TYPE_SCENARIO_COMMAND));
        expect("scenario ACKed over BLE, byte at a time",
               send_chunked(PROTO_LINK_BLE, wire, n, 1U,
                            PROTO_TYPE_SCENARIO_COMMAND));
    }

    // Sweep: every serial byte-3 pattern and button/frame combination, plus
    // hopping codes that put zero bytes (and so small COBS codes, including
    // 0x0A and 0x0D) at every position.
    unsigned bad = 0, total = 0;
    uint32_t seed = 0x12345678U;
    const size_t chunks[] = {64U, 20U, 7U, 1U};
    for (unsigned iter = 0; iter < 20000U; ++iter) {
        seed = seed * 1664525U + 1013904223U;
        uint32_t hopping = seed;
        seed = seed * 1664525U + 1013904223U;
        uint32_t serial = seed;
        // Force zero bytes into assorted positions.
        for (int b = 0; b < 4; ++b) {
            if ((iter >> b) & 1U && (iter % 3U) == 0U) {
                hopping &= ~(0xFFU << (8 * b));
            }
        }
        if ((iter % 5U) == 0U) {
            serial &= 0x00FFFFFFU; // byte 3 == 0 -> COBS code 0x0A
        }
        uint8_t button = (uint8_t)(iter & 0xFU);
        uint8_t frames = (uint8_t)(1U + iter % 10U);
        n = app_tx_packet(wire, hopping, serial, button, frames);
        for (size_t c = 0; c < 4U; ++c) {
            ++total;
            if (!send_chunked(iter & 1U ? PROTO_LINK_BLE : PROTO_LINK_USB, wire,
                              n, chunks[c], PROTO_TYPE_TX_COMMAND)) {
                if (bad++ < 5U) {
                    printf("  miss: hop=%08x ser=%08x btn=%u fr=%u chunk=%zu"
                           " first=0x%02x\n",
                           (unsigned)hopping, (unsigned)serial, button, frames,
                           chunks[c], wire[0]);
                }
            }
        }
    }
    printf("sweep: %u/%u packets missed\n", bad, total);
    expect("every app-encoded TX packet is ACKed", bad == 0U);
    return test_result();
}
