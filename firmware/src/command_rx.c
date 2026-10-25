#include "command_rx.h"

#include "lab_rx.h"
#include "lab_tx.h"
#include "transport.h"
#include "usb_cdc.h"
#include <string.h>

// Incoming packet layouts (raw, before COBS encoding):
//   TX_COMMAND:       [version][type][hopping LE32][serial LE32][button][frame_count][crc16 LE]  (14 bytes)
//   SCENARIO_COMMAND: [version][type][command_char][crc16 LE]                                    (5 bytes)
//
// Input also accepts a human-typed ASCII alternative, e.g.
// "tx 114766B9 FB58C0C 2 5\r\n", for manual testing from a plain terminal.
// Output always stays binary (capture frames + ACKs) regardless of which
// input format was used, so the capture stream and any host tooling never
// see free text mixed in -- only input is dual-format.
//
// Detecting which format arrived is unambiguous for this protocol's packet
// sizes: a real COBS-encoded packet's first byte is always a "code" byte
// no larger than the raw packet length (<=14 here), which is always
// outside the printable ASCII range (0x20-0x7E). So: if every byte seen
// since the last delimiter has been printable ASCII by the time a '\r' or
// '\n' arrives, it's a text command; a 0x00 byte is unambiguously a binary
// packet's terminator (COBS bodies never contain an embedded 0x00).
#define RX_BUFFER_MAX 40U
#define RX_DECODED_MAX 16U

static uint8_t rx_buffer[RX_BUFFER_MAX];
static uint8_t rx_length;
static int rx_looks_like_text;

#define DEFAULT_FRAMES 3U
#define MAX_FRAMES 10U

static uint32_t get_u32_le(const uint8_t *data) {
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8U) |
           ((uint32_t)data[2] << 16U) | ((uint32_t)data[3] << 24U);
}

static void handle_binary_packet(const uint8_t *encoded, uint8_t length) {
    uint8_t decoded[RX_DECODED_MAX];
    size_t decoded_length = transport_cobs_decode(encoded, length, decoded,
                                                   sizeof(decoded));
    if (decoded_length < 4U || decoded[0] != TRANSPORT_VERSION) {
        return;
    }
    uint16_t crc = transport_crc16(decoded, decoded_length - 2U);
    uint16_t received_crc = (uint16_t)decoded[decoded_length - 2U] |
                            ((uint16_t)decoded[decoded_length - 1U] << 8U);
    if (crc != received_crc) {
        return;
    }

    uint8_t type = decoded[1];
    int accepted = 0;
    if (type == TRANSPORT_TYPE_TX_COMMAND && decoded_length == 14U) {
        uint32_t hopping = get_u32_le(&decoded[2]);
        uint32_t serial = get_u32_le(&decoded[6]);
        uint8_t button = decoded[10];
        uint8_t frame_count = decoded[11];
        // Enforce the same 1..10 range the text path clamps to and that
        // PacketCodec validates on the host side. Without this a buggy or
        // hostile host could ask for 255 frames and block the device with
        // interrupts disabled for tens of seconds (and blow every ACK
        // timeout while it does).
        if (frame_count == 0U) {
            frame_count = 1U;
        } else if (frame_count > MAX_FRAMES) {
            frame_count = MAX_FRAMES;
        }
        accepted = lab_tx_send_codeword(hopping, serial, button, frame_count);
    } else if (type == TRANSPORT_TYPE_SCENARIO_COMMAND && decoded_length == 5U) {
        accepted = lab_tx_run_scenario(decoded[2]);
    } else {
        return; // Unrecognized/malformed: no ack, just drop.
    }
    transport_send_ack(type, accepted ? 1U : 0U);
}

// ---- ASCII text command path ----

static void send_text(const char *value) {
    usb_cdc_write((const uint8_t *)value, strlen(value));
}

static void send_hex(uint32_t value, uint8_t digits) {
    static const char alphabet[] = "0123456789abcdef";
    char text[9];
    uint8_t i = digits;
    text[digits] = '\0';
    while (i-- > 0U) {
        text[i] = alphabet[value & 0xFU];
        value >>= 4;
    }
    send_text(text);
}

static int parse_hex(const char *text, uint32_t *value) {
    if (text == NULL || *text == '\0') {
        return 0;
    }
    uint32_t accumulator = 0;
    uint8_t digits = 0;
    for (const char *p = text; *p != '\0' && *p != ' '; ++p) {
        uint32_t nibble;
        if (*p >= '0' && *p <= '9') {
            nibble = (uint32_t)(*p - '0');
        } else if (*p >= 'a' && *p <= 'f') {
            nibble = (uint32_t)(*p - 'a' + 10);
        } else if (*p >= 'A' && *p <= 'F') {
            nibble = (uint32_t)(*p - 'A' + 10);
        } else {
            return 0;
        }
        if (digits >= 8U) {
            return 0;
        }
        accumulator = (accumulator << 4) | nibble;
        ++digits;
    }
    *value = accumulator;
    return 1;
}

static char *next_token(char **cursor) {
    char *p = *cursor;
    while (*p == ' ') {
        ++p;
    }
    if (*p == '\0') {
        *cursor = p;
        return NULL;
    }
    char *start = p;
    while (*p != '\0' && *p != ' ') {
        ++p;
    }
    if (*p == ' ') {
        *p = '\0';
        ++p;
    }
    *cursor = p;
    return start;
}

struct scenario_mapping {
    const char *name;
    uint8_t command;
};
static const struct scenario_mapping scenario_mappings[] = {
    {"synthetic-stale", 's'}, {"zero-hop", 'z'},   {"plaintext-counter", 'p'},
    {"random-hop", 'x'},      {"different-serial", 'd'},
    {"s", 's'}, {"z", 'z'}, {"p", 'p'}, {"x", 'x'}, {"d", 'd'},
};

static int scenario_for_name(const char *name, uint8_t *command) {
    for (size_t i = 0; i < sizeof(scenario_mappings) / sizeof(scenario_mappings[0]); ++i) {
        if (strcmp(name, scenario_mappings[i].name) == 0) {
            *command = scenario_mappings[i].command;
            return 1;
        }
    }
    return 0;
}

static void execute_tx_text(char *cursor) {
    char *hopping_text = next_token(&cursor);
    char *serial_text = next_token(&cursor);
    char *button_text = next_token(&cursor);
    char *frames_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    uint32_t hopping = 0, serial = 0, button = 0, frames = DEFAULT_FRAMES;

    if (hopping_text == NULL || serial_text == NULL || button_text == NULL ||
        extra != NULL || !parse_hex(hopping_text, &hopping) ||
        !parse_hex(serial_text, &serial) || !parse_hex(button_text, &button) ||
        button > 0xFU ||
        (frames_text != NULL && !parse_hex(frames_text, &frames))) {
        send_text("Usage: tx <hopping> <serial> <button> [frames]\r\n"
                  "  e.g. tx 114766B9 FB58C0C 2 5\r\n");
        return;
    }
    if (frames == 0U) {
        frames = 1U;
    }
    if (frames > MAX_FRAMES) {
        frames = MAX_FRAMES;
    }

    int ok = lab_tx_send_codeword(hopping, serial, (uint8_t)button, (uint8_t)frames);
    send_text("TX hopping=0x");
    send_hex(hopping, 8);
    send_text(" serial=0x");
    send_hex(serial, 7);
    send_text(" button=0x");
    send_hex(button, 1);
    send_text(" frames=");
    send_hex(frames, 1);
    send_text(ok ? " DONE\r\n" : " ERROR\r\n");
}

// ---- rx-simulator text commands (src/lab_rx.h) ----
//
// Drives the in-firmware simulated rolling-code receiver so lab_tx.c's own
// codewords can be tested against configurable resynchronization windows
// without any real receiver hardware. See src/lab_rx.h for what this is and
// (importantly) is not.

static void execute_rxpair_text(char *cursor) {
    char *serial_text = next_token(&cursor);
    char *button_text = next_token(&cursor);
    char *count_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    uint32_t serial = 0, button = 0, count = 0;
    if (serial_text == NULL || button_text == NULL || count_text == NULL ||
        extra != NULL || !parse_hex(serial_text, &serial) ||
        !parse_hex(button_text, &button) || !parse_hex(count_text, &count)) {
        send_text("Usage: rxpair <serial> <button> <starting_count>\r\n");
        return;
    }
    lab_rx_pair(serial, (uint8_t)button, count);
    send_text("RXPAIR DONE\r\n");
}

static void execute_rxconfig_text(char *cursor) {
    char *next_text = next_token(&cursor);
    char *future_text = next_token(&cursor);
    char *gap_text = next_token(&cursor);
    char *replay_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    uint32_t window_next = 0, window_future = 0, window_gap = 0, replay = 0;
    int future_ok = future_text != NULL &&
                    (strcmp(future_text, "all") == 0 ||
                     parse_hex(future_text, &window_future));
    if (next_text == NULL || future_text == NULL || gap_text == NULL ||
        replay_text == NULL || extra != NULL || !parse_hex(next_text, &window_next) ||
        !future_ok || !parse_hex(gap_text, &window_gap) ||
        !parse_hex(replay_text, &replay) || replay > 1U) {
        send_text("Usage: rxconfig <window_next> <window_future|all> <window_gap> <replay 0|1>\r\n");
        return;
    }
    if (strcmp(future_text, "all") == 0) {
        window_future = 0xFFFFFFFFU;
    }
    lab_rx_config_t config = {window_next, window_future, window_gap, (int)replay};
    lab_rx_configure(&config);
    send_text("RXCONFIG DONE\r\n");
}

static const char *rx_result_name(lab_rx_result_t result) {
    switch (result) {
    case LAB_RX_RESULT_NO_FIX_MATCH:
        return "NO_FIX_MATCH";
    case LAB_RX_RESULT_PAST:
        return "PAST";
    case LAB_RX_RESULT_FUTURE:
        return "FUTURE";
    case LAB_RX_RESULT_OPENED_NEXT:
        return "OPENED_NEXT";
    case LAB_RX_RESULT_OPENED_GAP:
        return "OPENED_GAP";
    case LAB_RX_RESULT_OPENED_REPLAY:
        return "OPENED_REPLAY";
    default:
        return "UNKNOWN";
    }
}

static void execute_rxsim_text(char *cursor) {
    char *hopping_text = next_token(&cursor);
    char *serial_text = next_token(&cursor);
    char *button_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    uint32_t hopping = 0, serial = 0, button = 0;
    if (hopping_text == NULL || serial_text == NULL || button_text == NULL ||
        extra != NULL || !parse_hex(hopping_text, &hopping) ||
        !parse_hex(serial_text, &serial) || !parse_hex(button_text, &button)) {
        send_text("Usage: rxsim <hopping> <serial> <button>\r\n");
        return;
    }
    if (!lab_rx_is_paired()) {
        send_text("RX NOT_PAIRED (use rxpair first)\r\n");
        return;
    }
    lab_rx_result_t result = lab_rx_feed(hopping, serial, (uint8_t)button);
    send_text("RX ");
    send_text(rx_result_name(result));
    send_text(" count=0x");
    send_hex(lab_rx_current_count(), 8);
    send_text("\r\n");
}

static void handle_text_line(char *line) {
    if (*line == '\0') {
        return;
    }
    char *cursor = line;
    char *first = next_token(&cursor);
    if (first == NULL) {
        return;
    }
    if (strcmp(first, "tx") == 0) {
        execute_tx_text(cursor);
        return;
    }
    if (strcmp(first, "rxpair") == 0) {
        execute_rxpair_text(cursor);
        return;
    }
    if (strcmp(first, "rxconfig") == 0) {
        execute_rxconfig_text(cursor);
        return;
    }
    if (strcmp(first, "rxsim") == 0) {
        execute_rxsim_text(cursor);
        return;
    }
    uint8_t command;
    if (*cursor == '\0' && scenario_for_name(first, &command)) {
        int ok = lab_tx_run_scenario(command);
        send_text(ok ? "RUN DONE\r\n" : "RUN ERROR\r\n");
        return;
    }
    send_text("Unknown command. Use: tx <hopping> <serial> <button> [frames]\r\n");
}

// ---- shared byte reassembly ----

void command_rx_init(void) {
    rx_length = 0U;
    rx_looks_like_text = 1;
    lab_rx_init();
}

void command_rx_poll(void) {
    uint8_t value;
    while (usb_cdc_read_byte(&value)) {
        if (value == 0U) {
            if (rx_length > 0U) {
                handle_binary_packet(rx_buffer, rx_length);
            }
            rx_length = 0U;
            rx_looks_like_text = 1;
        } else if (rx_looks_like_text && (value == '\r' || value == '\n')) {
            if (rx_length > 0U) {
                rx_buffer[rx_length] = '\0';
                handle_text_line((char *)rx_buffer);
            }
            rx_length = 0U;
            rx_looks_like_text = 1;
        } else if (rx_length + 1U < RX_BUFFER_MAX) {
            if (value < 0x20U || value > 0x7EU) {
                rx_looks_like_text = 0;
            }
            rx_buffer[rx_length++] = value;
        } else {
            // Oversized/malformed input: drop it and resync at the next
            // delimiter.
            rx_length = 0U;
            rx_looks_like_text = 1;
        }
    }
}
