// Host-side tests for the dual-format command parser, mirroring
// firmware/test_command_rx.c: packets are built like a host would build them,
// COBS encoded, fed byte-by-byte, and the emitted waveform/ACK/reply is
// checked.
#include "src/command_parse.h"
#include "src/lab_tx.h"
#include "src/proto_transport.h"
#include "src/radio_mode.h"

#include "test_util.h"
#include "fake_radio.h"

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

// Decode the 66 code-word bits out of the first frame of an emitted burst:
// reduce the PWM waveform to the STM32's falling-edge periods, then decode.
static int decode_bits(const uint16_t *pulses, size_t count,
                       uint8_t bits[LAB_CODEWORD_BITS]) {
    uint16_t periods[LAB_FRAME_PERIODS];
    if (lab_tx_frame_periods(pulses, count, periods, LAB_FRAME_PERIODS) !=
        LAB_FRAME_PERIODS) {
        return 0;
    }
    return lab_tx_bits_from_periods(periods, LAB_FRAME_PERIODS, bits);
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
    fake_radio_t radio;
    fake_radio_init(&radio);

    proto_init(&sink, 0U);
    command_parse_init(&sink, NULL);

    // ---- binary TX_COMMAND ----
    {
        uint8_t raw[16];
        build_tx_command(raw, 0x114766B9U, 0x0FB58C0CU, 2U, 5U);
        rec_reset(&links.usb);
        rec_reset(&links.ble);
        radio.bursts = radio.frames_queued = 0U;
        feed_binary(PROTO_LINK_USB, raw, 14U);
        expect("TX_COMMAND starts a burst without ACKing yet",
               radio.bursts == 1U && lab_tx_busy() && links.usb.length == 0U);
        fake_pump(&radio);

        expect("five frames are streamed one by one", radio.frames_queued == 5U);

        uint8_t bits[LAB_CODEWORD_BITS];
        uint8_t expected[LAB_CODEWORD_BITS];
        lab_tx_build_bits(expected, 0x114766B9U, 0x0FB58C0CU, 2U, 0U);
        expect("the first frame carries the codeword",
               decode_bits(radio.first_frame, LAB_FRAME_PULSES, bits) &&
                   memcmp(bits, expected, sizeof(bits)) == 0);

        uint8_t type = 0, accepted = 0;
        expect("ACK(accepted=1) arrives when the burst ends, on the same link",
               decode_ack(&links.usb, &type, &accepted) &&
                   type == PROTO_TYPE_TX_COMMAND && accepted == 1U);
        expect("binary TX_COMMAND does not leak to the other link",
               links.ble.length == 0U);
    }

    // ---- frame counts: 0 -> 1, up to 255 streamed ----
    {
        uint8_t raw[16];
        rec_reset(&links.usb);
        radio.bursts = radio.frames_queued = 0U;
        build_tx_command(raw, 0U, 0U, 0U, 0U);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        fake_pump(&radio);
        expect("frame_count 0 is treated as 1", radio.frames_queued == 1U);

        uint8_t type = 0, accepted = 0;
        rec_reset(&links.usb);
        radio.bursts = radio.frames_queued = 0U;
        build_tx_command(raw, 0U, 0U, 0U, 200U);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        fake_pump(&radio);
        expect("frame_count 200 is no longer capped", radio.frames_queued == 200U);

        rec_reset(&links.usb);
        radio.bursts = radio.frames_queued = 0U;
        build_tx_command(raw, 0x114766B9U, 0x0FB58C0CU, 2U, 255U);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        fake_pump(&radio);
        expect("frame_count 255 streams all 255 frames",
               radio.frames_queued == 255U && !lab_tx_busy());
        expect("the 255-frame burst ACKs accepted=1",
               decode_ack(&links.usb, &type, &accepted) && accepted == 1U);
    }

    // ---- binary SCENARIO_COMMAND ----
    {
        uint8_t raw[8];
        build_scenario_command(raw, 's');
        rec_reset(&links.usb);
        radio.bursts = radio.frames_queued = 0U;
        feed_binary(PROTO_LINK_USB, raw, 5U);
        fake_pump(&radio);
        expect("scenario 's' streams three frames", radio.frames_queued == 3U);
        uint8_t type = 0, accepted = 0;
        expect("scenario is ACKed with its own packet type",
               decode_ack(&links.usb, &type, &accepted) &&
                   type == PROTO_TYPE_SCENARIO_COMMAND && accepted == 1U);

        build_scenario_command(raw, 'q');
        rec_reset(&links.usb);
        feed_binary(PROTO_LINK_USB, raw, 5U);
        expect("an unknown scenario is ACKed with accepted=0 at once",
               decode_ack(&links.usb, &type, &accepted) && accepted == 0U &&
                   !lab_tx_busy());
    }

    // ---- bad CRC is silently dropped ----
    {
        uint8_t raw[16];
        build_tx_command(raw, 1U, 2U, 3U, 4U);
        raw[13] ^= 0xFFU;
        rec_reset(&links.usb);
        radio.bursts = 0U;
        feed_binary(PROTO_LINK_USB, raw, 14U);
        expect("bad CRC produces no TX and no ACK",
               radio.bursts == 0U && links.usb.length == 0U);
    }

    // ---- radio refuses to enter TX ----
    {
        uint8_t raw[16], type = 0, accepted = 9;
        radio.begin_ok = 0;
        rec_reset(&links.usb);
        build_tx_command(raw, 1U, 2U, 3U, 4U);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        expect("a radio that cannot enter TX gives ACK accepted=0",
               decode_ack(&links.usb, &type, &accepted) && accepted == 0U);
        radio.begin_ok = 1;
    }

    // ---- ASCII tx path ----
    {
        const char *line = "tx 2 FB58C0C 2 1\r\n";
        rec_reset(&links.usb);
        radio.bursts = radio.frames_queued = 0U;
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)line,
                           strlen(line));
        expect("ASCII tx reply announces the burst",
               rec_is_text(&links.usb,
                           "TX hopping=0x00000002 serial=0xfb58c0c "
                           "button=0x2 frames=01 START\r\n"));
        fake_pump(&radio);
        expect("ASCII tx streams one frame", radio.frames_queued == 1U);

        uint8_t bits[LAB_CODEWORD_BITS];
        uint8_t expected[LAB_CODEWORD_BITS];
        lab_tx_build_bits(expected, 2U, 0x0FB58C0CU, 2U, 0U);
        expect("ASCII tx waveform carries the codeword",
               decode_bits(radio.first_frame, LAB_FRAME_PULSES, bits) &&
                   memcmp(bits, expected, sizeof(bits)) == 0);
        expect("ASCII tx reports DONE when the burst ends",
               rec_is_text(&links.usb, "TX DONE\r\n"));
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
        radio.bursts = 0U;

        command_parse_feed(PROTO_LINK_USB, encoded, n / 2U);
        command_parse_feed(PROTO_LINK_BLE, encoded + n / 2U,
                           n - n / 2U);
        expect("halves on different links do not form a packet",
               radio.bursts == 0U && links.usb.length == 0U);

        command_parse_feed(PROTO_LINK_USB, encoded + n / 2U,
                           n - n / 2U);
        expect("completing the packet on its own link dispatches it",
               radio.bursts == 1U);
        fake_pump(&radio);
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
        radio.bursts = 0U;
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)word,
                           strlen(word));
        expect("scenario word runs and reports DONE",
               radio.bursts == 1U && rec_is_text(&links.usb, "RUN START"));
        fake_pump(&radio);
        expect("scenario letters report DONE when the burst ends",
               rec_is_text(&links.usb, "RUN DONE"));
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

    // ---- radio modes: MODE_COMMAND, text `mode`, busy, abort ----
    {
        uint8_t raw[16], type = 0, accepted = 9;
        // helper to send a MODE_COMMAND
        #define SEND_MODE(m) do { \
            raw[0] = 1U; raw[1] = PROTO_TYPE_MODE_COMMAND; raw[2] = (m); \
            uint16_t c_ = proto_crc16(raw, 3U); \
            raw[3] = (uint8_t)c_; raw[4] = (uint8_t)(c_ >> 8U); \
            rec_reset(&links.usb); feed_binary(PROTO_LINK_USB, raw, 5U); } while (0)

        radio_mode_init(&(radio_mode_ops_t){fake_apply, fake_changed, fake_abort, &radio});
        radio.applies = radio.changes = 0U;
        SEND_MODE(1U);
        expect("MODE rx -> ACK(for_type 8, accepted 1)",
               decode_ack(&links.usb, &type, &accepted) && type == PROTO_TYPE_MODE_COMMAND && accepted == 1U);
        expect("MODE rx applies RX and reports it",
               radio.last_base == RADIO_MODE_RX && radio.last_changed == RADIO_MODE_RX &&
                   radio_mode_current() == RADIO_MODE_RX);
        SEND_MODE(7U);
        expect("unknown mode -> accepted 0 and the mode is unchanged",
               decode_ack(&links.usb, &type, &accepted) && type == PROTO_TYPE_MODE_COMMAND &&
                   accepted == 0U && radio_mode_current() == RADIO_MODE_RX);
        SEND_MODE(0U);
        expect("MODE idle -> accepted 1, base idle",
               decode_ack(&links.usb, &type, &accepted) && accepted == 1U &&
                   radio_mode_current() == RADIO_MODE_IDLE && radio.last_base == RADIO_MODE_IDLE);

        const char *t1 = "mode rx\r\n";
        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)t1, strlen(t1));
        expect("text `mode rx` switches and replies", rec_is_text(&links.usb, "MODE rx") &&
                                                       radio_mode_current() == RADIO_MODE_RX);
        const char *t2 = "mode\r\n";
        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)t2, strlen(t2));
        expect("bare `mode` prints the current mode", rec_is_text(&links.usb, "MODE rx"));
        const char *t3 = "mode idle\r\n";
        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)t3, strlen(t3));
        expect("text `mode idle` switches", rec_is_text(&links.usb, "MODE idle") &&
                                             radio_mode_current() == RADIO_MODE_IDLE);
        const char *t4 = "mode bogus\r\n";
        rec_reset(&links.usb);
        command_parse_feed(PROTO_LINK_USB, (const uint8_t *)t4, strlen(t4));
        expect("`mode bogus` prints usage", rec_is_text(&links.usb, "Usage: mode"));

        // --- TX is a transient mode that returns to the previous one ---
        radio_mode_init(&(radio_mode_ops_t){fake_apply, fake_changed, fake_abort, &radio});
        SEND_MODE(1U); // base RX
        radio.history_len = 0U;
        radio.auto_complete = 0;
        radio.inflight = 0U;
        radio.bursts = radio.frames_queued = 0U;
        build_tx_command(raw, 0x114766B9U, 0x0FB58C0CU, 2U, 50U);
        rec_reset(&links.usb);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        expect("a TX burst reports mode TX", radio_mode_current() == RADIO_MODE_TX &&
                                               radio.last_changed == RADIO_MODE_TX);

        // a second TX_COMMAND mid-burst is rejected at once, not queued
        uint8_t raw2[16];
        rec_reset(&links.usb);
        build_tx_command(raw2, 1U, 2U, 3U, 4U);
        feed_binary(PROTO_LINK_USB, raw2, 14U);
        expect("a second TX_COMMAND during a burst gets ACK accepted=0 (busy)",
               decode_ack(&links.usb, &type, &accepted) && type == PROTO_TYPE_TX_COMMAND &&
                   accepted == 0U && radio.bursts == 1U);

        // MODE idle mid-burst: acknowledged now, burst aborted after its frame
        unsigned ends_before = radio.ends;
        SEND_MODE(0U);
        expect("MODE idle mid-burst is ACKed immediately",
               decode_ack(&links.usb, &type, &accepted) && type == PROTO_TYPE_MODE_COMMAND &&
                   accepted == 1U);
        expect("the abort waits for the frame on the air", lab_tx_busy() && radio.ends == ends_before);
        unsigned queued_before = radio.frames_queued;
        radio.now += 120U;
        lab_tx_poll(); // frame still in flight (inflight=1)
        expect("no new frame is queued after the abort", radio.frames_queued == queued_before);
        rec_reset(&links.usb);
        radio.inflight = 0U;
        lab_tx_poll();
        expect("burst ends: ACK accepted=2 (aborted)",
               decode_ack(&links.usb, &type, &accepted) && type == PROTO_TYPE_TX_COMMAND &&
                   accepted == 2U && radio.last_end_aborted == 1 && !lab_tx_busy());
        expect("after the abort the device is IDLE and reported it",
               radio_mode_current() == RADIO_MODE_IDLE && radio.last_changed == RADIO_MODE_IDLE &&
                   radio.last_base == RADIO_MODE_IDLE && radio.frames_queued < 50U);

        // a burst started in RX returns to RX; MODE rx during TX is deferred
        SEND_MODE(1U);
        radio.history_len = 0U;
        radio.bursts = radio.frames_queued = 0U;
        build_tx_command(raw, 1U, 2U, 3U, 3U);
        feed_binary(PROTO_LINK_USB, raw, 14U);
        radio.applies = 0U;
        SEND_MODE(1U);
        expect("MODE rx during TX is accepted but not applied until the burst ends",
               radio.applies == 0U && radio_mode_current() == RADIO_MODE_TX);
        radio.auto_complete = 1;
        radio.inflight = 0U;
        fake_pump(&radio);
        expect("TX returns to the previous mode (RX)",
               radio_mode_current() == RADIO_MODE_RX && radio.last_base == RADIO_MODE_RX &&
                   radio.mode_history[0] == RADIO_MODE_TX &&
                   radio.mode_history[radio.history_len - 1U] == RADIO_MODE_RX);
        radio.auto_complete = 1;
        #undef SEND_MODE
    }

    return test_result();
}
