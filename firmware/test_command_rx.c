// Host-side test for the unified binary command protocol: builds a
// TX_COMMAND / SCENARIO_COMMAND packet the same way an Android app (or any
// host) would -- COBS-encode a raw packet with transport_cobs_encode(),
// feed it byte-by-byte into command_rx_poll() via a stubbed usb_cdc, and
// confirm it dispatches to lab_tx and sends back a correct ACK.
//
// Links the REAL src/transport.c and src/command_rx.c; only usb_cdc and
// lab_tx are stubbed.
#include <stdio.h>
#include <string.h>
#include "src/command_rx.h"
#include "src/lab_tx.h"
#include "src/transport.h"
#include "src/usb_cdc.h"

// ---- usb_cdc stub: byte queue in, captured ACK bytes out ----

static const uint8_t *rx_data;
static size_t rx_length;
static size_t rx_pos;

static uint8_t tx_buffer[256];
static size_t tx_length;

int usb_cdc_init(void) { return 1; }
void usb_cdc_poll(void) {}

int usb_cdc_read_byte(uint8_t *value) {
    if (rx_pos >= rx_length) {
        return 0;
    }
    *value = rx_data[rx_pos++];
    return 1;
}

int usb_cdc_write(const uint8_t *data, size_t length) {
    if (tx_length + length <= sizeof(tx_buffer)) {
        memcpy(tx_buffer + tx_length, data, length);
        tx_length += length;
    }
    return (int)length;
}

// ---- lab_tx stub ----

static int tx_calls;
static uint32_t last_hopping, last_serial;
static uint8_t last_button, last_frames;
static int scenario_calls;
static uint8_t last_scenario;
static int next_result = 1;

int lab_tx_init(void) { return 1; }
int lab_tx_send_stale_replay(void) { return 1; }

int lab_tx_send_codeword(uint32_t hopping, uint32_t serial, uint8_t button,
                          uint8_t frame_count) {
    ++tx_calls;
    last_hopping = hopping;
    last_serial = serial;
    last_button = button;
    last_frames = frame_count;
    return next_result;
}

int lab_tx_run_scenario(uint8_t command) {
    ++scenario_calls;
    last_scenario = command;
    return next_result;
}

// ---- harness ----

static int failures;

static void feed_encoded_packet(const uint8_t *raw, size_t raw_length) {
    uint8_t encoded[64];
    size_t encoded_length = transport_cobs_encode(raw, raw_length, encoded,
                                                   sizeof(encoded));
    // The trailing 0x00 transport_cobs_encode appends is the wire
    // delimiter command_rx_poll looks for -- feed it as-is.
    rx_data = encoded;
    rx_length = encoded_length;
    rx_pos = 0;
    tx_length = 0;
    tx_calls = 0;
    scenario_calls = 0;
    command_rx_poll();
}

static void put_u32_le(uint8_t *buf, uint32_t value) {
    buf[0] = (uint8_t)(value & 0xFFU);
    buf[1] = (uint8_t)((value >> 8U) & 0xFFU);
    buf[2] = (uint8_t)((value >> 16U) & 0xFFU);
    buf[3] = (uint8_t)(value >> 24U);
}

static void build_tx_command(uint8_t *raw, size_t *raw_length, uint32_t hopping,
                             uint32_t serial, uint8_t button, uint8_t frames) {
    raw[0] = 1; // TRANSPORT_VERSION
    raw[1] = TRANSPORT_TYPE_TX_COMMAND;
    put_u32_le(&raw[2], hopping);
    put_u32_le(&raw[6], serial);
    raw[10] = button;
    raw[11] = frames;
    uint16_t crc = transport_crc16(raw, 12);
    raw[12] = (uint8_t)(crc & 0xFFU);
    raw[13] = (uint8_t)(crc >> 8U);
    *raw_length = 14;
}

static void build_scenario_command(uint8_t *raw, size_t *raw_length, uint8_t cmd) {
    raw[0] = 1;
    raw[1] = TRANSPORT_TYPE_SCENARIO_COMMAND;
    raw[2] = cmd;
    uint16_t crc = transport_crc16(raw, 3);
    raw[3] = (uint8_t)(crc & 0xFFU);
    raw[4] = (uint8_t)(crc >> 8U);
    *raw_length = 5;
}

static void expect(const char *label, int condition) {
    if (!condition) {
        printf("FAIL %s\n", label);
        ++failures;
    } else {
        printf("ok   %s\n", label);
    }
}

int main(void) {
    command_rx_init();

    // --- valid TX_COMMAND ---
    uint8_t raw[32];
    size_t raw_length;
    build_tx_command(raw, &raw_length, 0xCF3EED68U, 0x0FB3EC77U, 0x2U, 5U);
    feed_encoded_packet(raw, raw_length);
    expect("tx_command dispatches to lab_tx_send_codeword", tx_calls == 1);
    expect("tx_command hopping decoded correctly", last_hopping == 0xCF3EED68U);
    expect("tx_command serial decoded correctly", last_serial == 0x0FB3EC77U);
    expect("tx_command button decoded correctly", last_button == 0x2U);
    expect("tx_command frames decoded correctly", last_frames == 5U);
    expect("ack sent for tx_command", tx_length > 0);
    // Decode the ack ourselves to check its fields.
    {
        uint8_t decoded[16];
        size_t n = tx_length > 0 ? tx_length - 1 : 0; // strip trailing 0x00
        size_t decoded_len = transport_cobs_decode(tx_buffer, n, decoded, sizeof(decoded));
        expect("ack decodes", decoded_len == 6);
        expect("ack echoes TX_COMMAND type", decoded[2] == TRANSPORT_TYPE_TX_COMMAND);
        expect("ack reports accepted=1", decoded[3] == 1);
    }

    // --- valid SCENARIO_COMMAND ---
    build_scenario_command(raw, &raw_length, 'z');
    feed_encoded_packet(raw, raw_length);
    expect("scenario_command dispatches to lab_tx_run_scenario", scenario_calls == 1);
    expect("scenario_command char decoded correctly", last_scenario == 'z');

    // --- corrupted CRC: must not dispatch, must not ack ---
    build_tx_command(raw, &raw_length, 0x11111111U, 0x22222222U, 0x3U, 2U);
    raw[12] ^= 0xFFU; // corrupt the CRC
    feed_encoded_packet(raw, raw_length);
    expect("corrupted crc does not dispatch", tx_calls == 0);
    expect("corrupted crc sends no ack", tx_length == 0);

    // --- lab_tx reports failure: ack should say accepted=0 ---
    next_result = 0;
    build_tx_command(raw, &raw_length, 0x1U, 0x2U, 0x3U, 0x1U);
    feed_encoded_packet(raw, raw_length);
    expect("failed tx still acks", tx_length > 0);
    {
        uint8_t decoded[16];
        size_t n = tx_length > 0 ? tx_length - 1 : 0;
        size_t decoded_len = transport_cobs_decode(tx_buffer, n, decoded, sizeof(decoded));
        expect("ack reports accepted=0 on failure", decoded_len == 6 && decoded[3] == 0);
    }
    next_result = 1;

    // --- ASCII text command path ---
    const char *text_cmd = "tx 114766B9 FB58C0C 2 5\r\n";
    rx_data = (const uint8_t *)text_cmd;
    rx_length = strlen(text_cmd);
    rx_pos = 0;
    tx_length = 0;
    tx_calls = 0;
    command_rx_poll();
    expect("text tx command dispatches", tx_calls == 1);
    expect("text tx hopping decoded", last_hopping == 0x114766B9U);
    expect("text tx serial decoded", last_serial == 0xFB58C0CU);
    expect("text tx button decoded", last_button == 0x2U);
    expect("text tx frames decoded", last_frames == 5U);
    tx_buffer[tx_length] = '\0';
    expect("text reply is human-readable",
           strstr((const char *)tx_buffer, "hopping=0x114766b9") != NULL);
    expect("text reply contains DONE",
           strstr((const char *)tx_buffer, "DONE") != NULL);

    // Text scenario word.
    const char *scenario_text = "zero-hop\r\n";
    rx_data = (const uint8_t *)scenario_text;
    rx_length = strlen(scenario_text);
    rx_pos = 0;
    scenario_calls = 0;
    command_rx_poll();
    expect("text scenario word dispatches", scenario_calls == 1);
    expect("text scenario char correct", last_scenario == 'z');

    // Malformed text should not dispatch anything.
    const char *bad_text = "tx 1 2\r\n"; // missing button
    rx_data = (const uint8_t *)bad_text;
    rx_length = strlen(bad_text);
    rx_pos = 0;
    tx_calls = 0;
    command_rx_poll();
    expect("malformed text does not dispatch", tx_calls == 0);

    // --- disambiguation: a real binary packet must never get misparsed
    // as text, even though it may contain bytes that look like '\r'/'\n'. ---
    build_tx_command(raw, &raw_length, 0xAAAAAAAAU, 0x0D0D0D0DU, 0x5U, 0x3U);
    feed_encoded_packet(raw, raw_length);
    expect("binary packet containing CR-like bytes still dispatches as binary",
           tx_calls == 1 && last_hopping == 0xAAAAAAAAU && last_serial == 0x0D0D0D0DU);

    // --- rx-simulator text commands (src/lab_rx.c via src/command_rx.c) ---
    {
        const char *pair_cmd = "rxpair 84EE9D5 2 0\r\n";
        rx_data = (const uint8_t *)pair_cmd;
        rx_length = strlen(pair_cmd);
        rx_pos = 0;
        tx_length = 0;
        command_rx_poll();
        tx_buffer[tx_length] = '\0';
        expect("rxpair acks", strstr((const char *)tx_buffer, "RXPAIR DONE") != NULL);

        const char *sim_next_cmd = "rxsim 1 84EE9D5 2\r\n";
        rx_data = (const uint8_t *)sim_next_cmd;
        rx_length = strlen(sim_next_cmd);
        rx_pos = 0;
        tx_length = 0;
        command_rx_poll();
        tx_buffer[tx_length] = '\0';
        expect("rxsim within window-next reports OPENED_NEXT",
               strstr((const char *)tx_buffer, "RX OPENED_NEXT count=0x00000001") != NULL);

        const char *sim_replay_cmd = "rxsim 1 84EE9D5 2\r\n";
        rx_data = (const uint8_t *)sim_replay_cmd;
        rx_length = strlen(sim_replay_cmd);
        rx_pos = 0;
        tx_length = 0;
        command_rx_poll();
        tx_buffer[tx_length] = '\0';
        expect("rxsim replaying the just-accepted count reports PAST",
               strstr((const char *)tx_buffer, "RX PAST") != NULL);

        const char *config_cmd = "rxconfig 10 all 2 1\r\n";
        rx_data = (const uint8_t *)config_cmd;
        rx_length = strlen(config_cmd);
        rx_pos = 0;
        tx_length = 0;
        command_rx_poll();
        tx_buffer[tx_length] = '\0';
        expect("rxconfig acks", strstr((const char *)tx_buffer, "RXCONFIG DONE") != NULL);

        const char *sim_replay_flaw_cmd = "rxsim 1 84EE9D5 2\r\n";
        rx_data = (const uint8_t *)sim_replay_flaw_cmd;
        rx_length = strlen(sim_replay_flaw_cmd);
        rx_pos = 0;
        tx_length = 0;
        command_rx_poll();
        tx_buffer[tx_length] = '\0';
        expect("rxsim replay with replay flaw enabled reports OPENED_REPLAY",
               strstr((const char *)tx_buffer, "RX OPENED_REPLAY") != NULL);
    }

    if (failures == 0) {
        printf("ALL PASS\n");
        return 0;
    }
    printf("%d FAILURE(S)\n", failures);
    return 1;
}
