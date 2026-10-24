// Host-side tests for the dual-format command parser, mirroring
// firmware/test_command_rx.c: packets are built like a host would build them,
// COBS encoded, fed byte-by-byte, and the emitted waveform/ACK/reply is
// checked.
#include "src/command_parse.h"
#include "src/lab_tx.h"
#include "src/proto_transport.h"

#include "test_util.h"

typedef struct {
    uint16_t pulses[LAB_TX_MAX_PULSES];
    size_t count;
    int calls;
    int result;
} tx_rec_t;

static int rec_emit(void *context, const uint16_t *pulses, size_t count) {
    tx_rec_t *rec = (tx_rec_t *)context;
    ++rec->calls;
    rec->count = count;
    if (count <= LAB_TX_MAX_PULSES) {
        memcpy(rec->pulses, pulses, count * sizeof(pulses[0]));
    }
    return rec->result;
}

static void put_u32_le(uint8_t *out, uint32_t value) {
    out[0] = (uint8_t)(value & 0xFFU);
    out[1] = (uint8_t)((value >> 8U) & 0xFFU);
    out[2] = (uint8_t)((value >> 16U) & 0xFFU);
    out[3] = (uint8_t)(value >> 24U);
}

static void feed_binary(uint8_t link, const uint8_t *raw, size_t raw_length) {
    uint8_t encoded[64];
    size_t n = proto_cobs_encode(raw, raw_length, encoded, sizeof(encoded));
    command_parse_feed(link, encoded, n);
}

static void build_tx_command(uint8_t *raw, uint32_t hopping, uint32_t serial,
                             uint8_t button, uint8_t frames) {
    raw[0] = 1U;
    raw[1] = PROTO_TYPE_TX_COMMAND;
    put_u32_le(&raw[2], hopping);
    put_u32_le(&raw[6], serial);
    raw[10] = button;
    raw[11] = frames;
    uint16_t crc = proto_crc16(raw, 12U);
    raw[12] = (uint8_t)(crc & 0xFFU);
    raw[13] = (uint8_t)(crc >> 8U);
}

static void build_scenario_command(uint8_t *raw, uint8_t command) {
    raw[0] = 1U;
    raw[1] = PROTO_TYPE_SCENARIO_COMMAND;
    raw[2] = command;
    uint16_t crc = proto_crc16(raw, 3U);
    raw[3] = (uint8_t)(crc & 0xFFU);
    raw[4] = (uint8_t)(crc >> 8U);
}

// Decode the 66 code-word bits out of the first frame of an emitted burst.
static int decode_bits(const uint16_t *pulses, size_t count,
                       uint8_t bits[LAB_CODEWORD_BITS]) {
    if (count < LAB_FRAME_PULSES) {
        return 0;
    }
    for (size_t i = 0; i < LAB_PREAMBLE_INTERVALS; ++i) {
        if (pulses[i] != LAB_PREAMBLE_EDGE_US) {
            return 0;
        }
    }
    if (pulses[11] == LAB_HEADER_BIT_ONE_US) {
        bits[0] = 1U;
    } else if (pulses[11] == LAB_HEADER_BIT_ZERO_US) {
        bits[0] = 0U;
    } else {
        return 0;
    }
    for (size_t i = 0; i < 65U; ++i) {
        uint16_t d = pulses[12U + i];
        if (d == LAB_DATA_MID_US) {
            bits[i + 1U] = bits[i];
        } else if (d == LAB_DATA_SHORT_US && bits[i] == 0U) {
            bits[i + 1U] = 1U;
        } else if (d == LAB_DATA_LONG_US && bits[i] == 1U) {
            bits[i + 1U] = 0U;
        } else {
            return 0;
        }
    }
    return 1;
}

static int decode_ack(const rec_t *rec, uint8_t *type, uint8_t *accepted) {
    uint8_t raw[32];
    if (rec->length < 3U || rec->data[rec->length - 1U] != 0U) {
        return 0;
    }
    size_t n = proto_cobs_decode(rec->data, rec->length - 1U, raw,
                                 sizeof(raw));
    if (n != 6U || raw[0] != 1U || raw[1] != PROTO_TYPE_ACK) {
        return 0;
    }
    uint16_t crc = (uint16_t)raw[4] | ((uint16_t)raw[5] << 8U);
    if (crc != proto_crc16(raw, 4U)) {
        return 0;
    }
    *type = raw[2];
    *accepted = raw[3];
    return 1;
}

static int rec_is_text(const rec_t *rec, const char *text) {
    if (rec->length == 0U) {
        return 0;
    }
    rec_t copy = *rec;
    if (copy.length < REC_MAX) {
        copy.data[copy.length] = '\0';
    }
    return strstr((const char *)copy.data, text) != NULL;
}

static int test_txtest_radio = -1;
static int test_last_mode = -1;
static char test_diag_name[16];
static void test_diag(const char *name, char *out, size_t capacity) {
    snprintf(test_diag_name, sizeof(test_diag_name), "%s", name);
    snprintf(out, capacity, "DIAG %s\r\n", name);
}
static void test_regs_dump(char *out, size_t capacity) {
    snprintf(out, capacity, "REGS test\r\n");
}
static void test_tx_selftest(int with_radio, char *out, size_t capacity) {
    test_txtest_radio = with_radio;
    test_last_mode = with_radio;
    snprintf(out, capacity, "TXTEST ok=1\r\n");
}

static unsigned test_requested_khz;

static void test_radio_report(char *out, size_t capacity) {
    snprintf(out, capacity, "RADIO test\r\n");
}

static int test_set_ook_preset(unsigned khz) {
    test_requested_khz = khz;
    return 1;
}

int main(void) {
    link_ctx_t links;
    memset(&links, 0, sizeof(links));
    links.deliver_mask = 0x03U;
    proto_sink_t sink = {link_write, &links};
    tx_rec_t tx;
    memset(&tx, 0, sizeof(tx));
    tx.result = 1;

    proto_init(&sink, 0U);
    command_parse_init(&sink, NULL);
    lab_tx_init(rec_emit, &tx);

    // ---- binary TX_COMMAND ----
    {
        uint8_t raw[16];
        build_tx_command(raw, 0x114766B9U, 0x0FB58C0CU, 2U, 5U);
        rec_reset(&links.usb);
        rec_reset(&links.ble);
        tx.calls = 0;
        feed_binary(PROTO_LINK_USB, raw, 14U);

        expect("binary TX_COMMAND emits one burst", tx.calls == 1);
        expect("five frames means 5*77 + 4 gaps",
               tx.count == 5U * LAB_FRAME_PULSES + 4U);

        uint8_t bits[LAB_CODEWORD_BITS];
        uint8_t expected[LAB_CODEWORD_BITS];
        lab_tx_build_bits(expected, 0x114766B9U, 0x0FB58C0CU, 2U, 0U);
        expect("binary TX_COMMAND waveform carries the codeword",
               decode_bits(tx.pulses, tx.count, bits) &&
                   memcmp(bits, expected, sizeof(bits)) == 0);

        uint8_t type = 0, accepted = 0;
        expect("binary TX_COMMAND is ACKed on the originating link",
               decode_ack(&links.usb, &type, &accepted) &&
                   type == PROTO_TYPE_TX_COMMAND && accepted == 1U);
        expect("binary TX_COMMAND does not leak to the other link",
               links.ble.length == 0U);
    }

    // ---- frame-count clamping ----
    {
        uint8_t raw[16];
        rec_reset(&links.usb);
        tx.calls = 0;
        build_tx_command(raw, 0U, 0U, 0U, 0U);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        expect("frame_count 0 is clamped to 1",
               tx.calls == 1 && tx.count == LAB_FRAME_PULSES);

        rec_reset(&links.usb);
        tx.calls = 0;
        build_tx_command(raw, 0U, 0U, 0U, 200U);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        expect("frame_count 200 is clamped to 10", tx.calls == 1 &&
                                                       tx.count ==
                                                           LAB_TX_MAX_PULSES);
    }

    // ---- binary SCENARIO_COMMAND ----
    {
        uint8_t raw[8];
        build_scenario_command(raw, 's');
        rec_reset(&links.usb);
        tx.calls = 0;
        feed_binary(PROTO_LINK_USB, raw, 5U);
        expect("scenario 's' emits three frames", tx.calls == 1 &&
                                                      tx.count ==
                                                          3U * LAB_FRAME_PULSES +
                                                              2U);
        uint8_t type = 0, accepted = 0;
        expect("scenario is ACKed with its own packet type",
               decode_ack(&links.usb, &type, &accepted) &&
                   type == PROTO_TYPE_SCENARIO_COMMAND && accepted == 1U);
    }

    // ---- bad CRC is silently dropped ----
    {
        uint8_t raw[16];
        build_tx_command(raw, 1U, 2U, 3U, 4U);
        raw[13] ^= 0xFFU;
        rec_reset(&links.usb);
        tx.calls = 0;
        feed_binary(PROTO_LINK_USB, raw, 14U);
        expect("bad CRC produces no TX and no ACK",
               tx.calls == 0 && links.usb.length == 0U);
    }

    // ---- ASCII tx path ----
    {
        const char *line = "tx 2 FB58C0C 2 1\r\n";
        rec_reset(&links.usb);
        tx.calls = 0;
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)line,
                           strlen(line));
        expect("ASCII tx emits one frame", tx.calls == 1 &&
                                               tx.count == LAB_FRAME_PULSES);

        uint8_t bits[LAB_CODEWORD_BITS];
        uint8_t expected[LAB_CODEWORD_BITS];
        lab_tx_build_bits(expected, 2U, 0x0FB58C0CU, 2U, 0U);
        expect("ASCII tx waveform carries the codeword",
               decode_bits(tx.pulses, tx.count, bits) &&
                   memcmp(bits, expected, sizeof(bits)) == 0);
        expect("ASCII tx reply matches the STM32 wording",
               rec_is_text(&links.usb,
                           "TX hopping=0x00000002 serial=0xfb58c0c "
                           "button=0x2 frames=1 DONE\r\n"));
    }

    // ---- ASCII replies follow the originating link ----
    {
        const char *line = "help\r\n";
        rec_reset(&links.usb);
        rec_reset(&links.ble);
        command_parse_feed(PROTO_LINK_BLE, (const uint8_t *)line,
                           strlen(line));
        expect("help goes only to the BLE link",
               links.usb.length == 0U && rec_is_text(&links.ble, "rxconfig"));
    }

    // ---- per-link reassembly ----
    {
        uint8_t raw[16];
        uint8_t encoded[32];
        build_tx_command(raw, 0xAAU, 0xBBU, 0xCU, 1U);
        size_t n = proto_cobs_encode(raw, 14U, encoded, sizeof(encoded));
        rec_reset(&links.usb);
        rec_reset(&links.ble);
        tx.calls = 0;

        command_parse_feed(PROTO_LINK_USB, encoded, n / 2U);
        command_parse_feed(PROTO_LINK_BLE, encoded + n / 2U,
                           n - n / 2U);
        expect("halves on different links do not form a packet",
               tx.calls == 0 && links.usb.length == 0U);

        command_parse_feed(PROTO_LINK_USB, encoded + n / 2U,
                           n - n / 2U);
        expect("completing the packet on its own link dispatches it",
               tx.calls == 1);
        command_parse_reset_link(PROTO_LINK_BLE);
    }

    // ---- rx simulator text commands ----
    {
        const char *pair = "rxpair 84EE9D5 2 0\r\n";
        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)pair,
                           strlen(pair));
        expect("rxpair acks", rec_is_text(&links.usb, "RXPAIR DONE"));

        const char *next = "rxsim 1 84EE9D5 2\r\n";
        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)next,
                           strlen(next));
        expect("rxsim within window-next reports OPENED_NEXT",
               rec_is_text(&links.usb,
                           "RX OPENED_NEXT count=0x00000001"));

        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)next,
                           strlen(next));
        expect("replaying the accepted count reports PAST",
               rec_is_text(&links.usb, "RX PAST"));

        const char *config = "rxconfig 10 all 2 1\r\n";
        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)config,
                           strlen(config));
        expect("rxconfig acks", rec_is_text(&links.usb, "RXCONFIG DONE"));

        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)next,
                           strlen(next));
        expect("rxconfig all + replay flaw re-opens a past count",
               rec_is_text(&links.usb, "RX OPENED_REPLAY"));
    }

    // ---- scenario words ----
    {
        const char *word = "synthetic-stale\r\n";
        rec_reset(&links.usb);
        tx.calls = 0;
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)word,
                           strlen(word));
        expect("scenario word runs and reports DONE",
               tx.calls == 1 && rec_is_text(&links.usb, "RUN DONE"));
    }

    // ---- ESP32-only diagnostics ----
    {
        command_ops_t ops;
        ops.radio_report = test_radio_report;
        ops.set_ook_preset = test_set_ook_preset;
        ops.tx_selftest = test_tx_selftest;
        ops.regs_dump = test_regs_dump;
        ops.diag = test_diag;
        command_parse_init(&sink, &ops);

        rec_reset(&links.usb);
        const char *radio = "radio\r\n";
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)radio,
                           strlen(radio));
        expect("radio reports through the ops hook",
               rec_is_text(&links.usb, "RADIO test"));

        test_requested_khz = 0U;
        rec_reset(&links.usb);
        const char *ook = "ook 650\r\n";
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)ook, strlen(ook));
        expect("ook 650 calls the preset hook and acks",
               test_requested_khz == 650U &&
                   rec_is_text(&links.usb, "OOK DONE"));

        rec_reset(&links.usb);
        const char *bad = "ook 123\r\n";
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)bad, strlen(bad));
        expect("ook rejects unknown bandwidths",
               rec_is_text(&links.usb, "Usage: ook <270|650>"));

        rec_reset(&links.usb);
        const char *tt = "txtest\r\ntxtest pad\r\n";
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)tt, strlen(tt));
        expect("txtest pad reaches the hook without the radio",
               test_txtest_radio == 0 && rec_is_text(&links.usb, "TXTEST ok=1"));
        rec_reset(&links.usb);
        const char *cr = "carrier\r\nregs\r\n";
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)cr, strlen(cr));
        expect("carrier dispatches CARRIER mode and regs dumps",
               test_last_mode == COMMAND_TEST_CARRIER &&
                   rec_is_text(&links.usb, "REGS test"));
        rec_reset(&links.usb);
        const char *dg = "gpio\r\nrssi\r\nrxedges\r\nreinit\r\ntxtest gpio\r\n";
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)dg, strlen(dg));
        expect("gpio/rssi/rxedges/reinit reach the diag hook",
               strcmp(test_diag_name, "reinit") == 0 &&
                   rec_is_text(&links.usb, "DIAG gpio") &&
                   rec_is_text(&links.usb, "DIAG rxedges"));
        expect("txtest gpio selects the bit-bang mode",
               test_last_mode == COMMAND_TEST_GPIO);
        rec_reset(&links.usb);
        const char *tb = "txtest bogus\r\n";
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)tb, strlen(tb));
        expect("txtest rejects unknown arguments",
               rec_is_text(&links.usb, "Usage: txtest"));
    }

    return test_result();
}
