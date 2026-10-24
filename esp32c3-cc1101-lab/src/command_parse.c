#include "command_parse.h"

#include <string.h>

#include "lab_rx.h"
#include "lab_tx.h"
#include "radio_mode.h"
#include "ota_session.h"

// Incoming packet layouts (raw, before COBS encoding):
//   TX_COMMAND:       [version][type][hopping LE32][serial LE32][button]
//                     [frame_count][crc16 LE]  (14 bytes)
//   SCENARIO_COMMAND: [version][type][command_char][crc16 LE]  (5 bytes)
//   MODE_COMMAND:     [version][8][mode: 0 idle, 1 rx][crc16 LE]  (5 bytes)
//
// Input also accepts a human-typed ASCII alternative, e.g.
// "tx 114766B9 FB58C0C 2 5\r\n".  Output stays binary (capture frames +
// ACKs) regardless of input format, exactly as command_rx.c does.
//
// Every COBS wire packet starts with a code byte followed by the raw version
// byte (0x01), and OTA CHUNK packets are up to 201 raw / 203 wire bytes, so
// both buffers must hold 256.  The text line limit grows with them; everything
// else behaves exactly as before.
#define RX_BUFFER_MAX 256U
#define RX_DECODED_MAX 256U
#define LINK_COUNT 2U

#define DEFAULT_FRAMES 3U

typedef struct {
    uint8_t buffer[RX_BUFFER_MAX];
    uint16_t length;
    int looks_like_text;
    uint8_t pending_eol; // leading CR/LF held back: blank line or COBS code?
} rx_state_t;

static rx_state_t rx_states[LINK_COUNT];
static const proto_sink_t *command_sink;
static const command_ops_t *command_ops;
static command_parse_stats_t stats;

command_parse_stats_t command_parse_stats(void) { return stats; }

static uint32_t get_u32_le(const uint8_t *data) {
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8U) |
           ((uint32_t)data[2] << 16U) | ((uint32_t)data[3] << 24U);
}

static int link_index(uint8_t link) {
    if (link == PROTO_LINK_USB) {
        return 0;
    }
    if (link == PROTO_LINK_BLE) {
        return 1;
    }
    return -1;
}

static void send_text(uint8_t link, const char *value) {
    if (!command_sink || !command_sink->write || !value) {
        return;
    }
    command_sink->write(command_sink->context, link,
                        (const uint8_t *)value, strlen(value));
}

static void send_hex(uint8_t link, uint32_t value, uint8_t digits) {
    static const char alphabet[] = "0123456789abcdef";
    char text[9];
    uint8_t i = digits;
    text[digits] = '\0';
    while (i-- > 0U) {
        text[i] = alphabet[value & 0xFU];
        value >>= 4U;
    }
    send_text(link, text);
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
        accumulator = (accumulator << 4U) | nibble;
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
    {"synthetic-stale", 's'}, {"zero-hop", 'z'},
    {"plaintext-counter", 'p'}, {"random-hop", 'x'},
    {"different-serial", 'd'}, {"s", 's'},
    {"z", 'z'}, {"p", 'p'},
    {"x", 'x'}, {"d", 'd'},
};

static int scenario_for_name(const char *name, uint8_t *command) {
    for (size_t i = 0;
         i < sizeof(scenario_mappings) / sizeof(scenario_mappings[0]); ++i) {
        if (strcmp(name, scenario_mappings[i].name) == 0) {
            *command = scenario_mappings[i].command;
            return 1;
        }
    }
    return 0;
}

static void handle_binary_packet(uint8_t link, const uint8_t *encoded,
                                 size_t length) {
    uint8_t decoded[RX_DECODED_MAX];
    ++stats.packets;
    size_t decoded_length =
        proto_cobs_decode(encoded, length, decoded, sizeof(decoded));
    if (decoded_length < 4U || decoded[0] != PROTO_VERSION) {
        ++stats.rejected;
        return;
    }
    uint16_t crc = proto_crc16(decoded, decoded_length - 2U);
    uint16_t received_crc = (uint16_t)decoded[decoded_length - 2U] |
                            ((uint16_t)decoded[decoded_length - 1U] << 8U);
    if (crc != received_crc) {
        ++stats.rejected;
        return;
    }

    uint8_t type = decoded[1];

    // OTA commands have their own [op][status] response packet, not an ACK.
    // The session validates the op/length and answers only the link that sent
    // the command.
    if (type == PROTO_TYPE_OTA_CMD) {
        if (decoded_length < 5U) {
            ++stats.rejected;
            return;
        }
        ota_session_handle(link, decoded[2], &decoded[3],
                           decoded_length - 5U);
        return;
    }

    if (type == PROTO_TYPE_TX_COMMAND && decoded_length == 14U) {
        uint32_t hopping = get_u32_le(&decoded[2]);
        uint32_t serial = get_u32_le(&decoded[6]);
        uint8_t button = decoded[10];
        uint8_t frame_count = decoded[11]; // 1..255, 0 means 1
        // The burst is streamed; its ACK (1 complete / 2 aborted / 0 failed)
        // is sent when it ends.  A refused start (busy, radio not ready) is
        // answered at once with accepted = 0.
        if (!lab_tx_start_codeword(hopping, serial, button, frame_count, type,
                                   link) &&
            proto_send_ack(type, 0U, link)) {
            ++stats.acked;
        }
        return;
    }
    if (type == PROTO_TYPE_SCENARIO_COMMAND && decoded_length == 5U) {
        if (!lab_tx_start_scenario(decoded[2], type, link) &&
            proto_send_ack(type, 0U, link)) {
            ++stats.acked;
        }
        return;
    }
    if (type == PROTO_TYPE_MODE_COMMAND && decoded_length == 5U) {
        int accepted = radio_mode_request(decoded[2]);
        if (proto_send_ack(type, accepted ? 1U : 0U, link)) {
            ++stats.acked;
        }
        return;
    }
    ++stats.rejected;
    // Unrecognized/malformed: no ack, just drop.
}

// Result of a streamed TX burst: ACK the originating command, or print the
// text reply for a typed command.
static void tx_done(void *context, uint8_t kind, uint8_t link, uint8_t result) {
    (void)context;
    if (kind == LAB_TX_KIND_TEXT_TX || kind == LAB_TX_KIND_TEXT_RUN) {
        const char *label = kind == LAB_TX_KIND_TEXT_TX ? "TX" : "RUN";
        send_text(link, label);
        send_text(link, result == LAB_TX_RESULT_COMPLETE  ? " DONE\r\n"
                        : result == LAB_TX_RESULT_ABORTED ? " ABORTED\r\n"
                                                           : " ERROR\r\n");
        return;
    }
    if (proto_send_ack(kind, result, link)) {
        ++stats.acked;
    }
}

static void execute_tx_text(uint8_t link, char *cursor) {
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
        send_text(link,
                  "Usage: tx <hopping> <serial> <button> [frames]\r\n"
                  "  e.g. tx 114766B9 FB58C0C 2 5\r\n");
        return;
    }
    if (frames == 0U) {
        frames = 1U;
    }
    if (frames > LAB_TX_MAX_FRAME_COUNT) {
        frames = LAB_TX_MAX_FRAME_COUNT;
    }

    int ok = lab_tx_start_codeword(hopping, serial, (uint8_t)button,
                                   (uint8_t)frames, LAB_TX_KIND_TEXT_TX, link);
    send_text(link, "TX hopping=0x");
    send_hex(link, hopping, 8);
    send_text(link, " serial=0x");
    send_hex(link, serial, 7);
    send_text(link, " button=0x");
    send_hex(link, button, 1);
    send_text(link, " frames=");
    send_hex(link, frames, 2);
    // The burst result ("TX DONE" / "TX ABORTED") follows when it ends.
    send_text(link, ok ? " START\r\n" : " BUSY\r\n");
}

static void execute_rxpair_text(uint8_t link, char *cursor) {
    char *serial_text = next_token(&cursor);
    char *button_text = next_token(&cursor);
    char *count_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    uint32_t serial = 0, button = 0, count = 0;
    if (serial_text == NULL || button_text == NULL || count_text == NULL ||
        extra != NULL || !parse_hex(serial_text, &serial) ||
        !parse_hex(button_text, &button) || !parse_hex(count_text, &count)) {
        send_text(link, "Usage: rxpair <serial> <button> <starting_count>\r\n");
        return;
    }
    lab_rx_pair(serial, (uint8_t)button, count);
    send_text(link, "RXPAIR DONE\r\n");
}

static void execute_rxconfig_text(uint8_t link, char *cursor) {
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
        replay_text == NULL || extra != NULL ||
        !parse_hex(next_text, &window_next) || !future_ok ||
        !parse_hex(gap_text, &window_gap) || !parse_hex(replay_text, &replay) ||
        replay > 1U) {
        send_text(link,
                  "Usage: rxconfig <window_next> <window_future|all> "
                  "<window_gap> <replay 0|1>\r\n");
        return;
    }
    if (strcmp(future_text, "all") == 0) {
        window_future = 0xFFFFFFFFU;
    }
    lab_rx_config_t config = {window_next, window_future, window_gap,
                              (int)replay};
    lab_rx_configure(&config);
    send_text(link, "RXCONFIG DONE\r\n");
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

static void execute_rxsim_text(uint8_t link, char *cursor) {
    char *hopping_text = next_token(&cursor);
    char *serial_text = next_token(&cursor);
    char *button_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    uint32_t hopping = 0, serial = 0, button = 0;
    if (hopping_text == NULL || serial_text == NULL || button_text == NULL ||
        extra != NULL || !parse_hex(hopping_text, &hopping) ||
        !parse_hex(serial_text, &serial) || !parse_hex(button_text, &button)) {
        send_text(link, "Usage: rxsim <hopping> <serial> <button>\r\n");
        return;
    }
    if (!lab_rx_is_paired()) {
        send_text(link, "RX NOT_PAIRED (use rxpair first)\r\n");
        return;
    }
    lab_rx_result_t result = lab_rx_feed(hopping, serial, (uint8_t)button);
    send_text(link, "RX ");
    send_text(link, rx_result_name(result));
    send_text(link, " count=0x");
    send_hex(link, lab_rx_current_count(), 8);
    send_text(link, "\r\n");
}

static void execute_help_text(uint8_t link) {
    send_text(link,
              "Commands:\r\n"
              "  tx <hopping> <serial> <button> [frames]\r\n"
              "  s|z|p|x|d  (synthetic-stale|zero-hop|plaintext-counter|"
              "random-hop|different-serial)\r\n"
              "  rxpair <serial> <button> <starting_count>\r\n"
              "  rxconfig <window_next> <window_future|all> <window_gap> "
              "<replay 0|1>\r\n"
              "  rxsim <hopping> <serial> <button>\r\n"
              "  radio\r\n"
              "  mode [idle|rx]  (radio mode; boots in idle)\r\n"
              "  txtest [pad]  (1 ms square wave x200 on GPIO3)\r\n"
              "  txtest gpio  (plain bit-bang of GPIO3, no RMT)\r\n"
              "  carrier  (CC1101 continuous carrier ~2 s)\r\n"
              "  gpio | rssi | rxedges | reinit  (bench diagnostics)\r\n"
              "  regs  (CC1101 register read-back)\r\n"
              "  ook <270|650>\r\n"
              "  help\r\n");
}

static void execute_radio_text(uint8_t link) {
    char report[448];
    report[0] = '\0';
    if (command_ops && command_ops->radio_report) {
        command_ops->radio_report(report, sizeof(report));
    } else {
        strncpy(report, "RADIO unavailable\r\n", sizeof(report) - 1U);
        report[sizeof(report) - 1U] = '\0';
    }
    send_text(link, report);
}

static void execute_txtest_text(uint8_t link, char *cursor) {
    char *mode = next_token(&cursor);
    char *extra = next_token(&cursor);
    int with_radio = COMMAND_TEST_RADIO;
    if (extra != NULL ||
        (mode != NULL && strcmp(mode, "pad") != 0 && strcmp(mode, "gpio") != 0)) {
        send_text(link, "Usage: txtest [pad|gpio]\r\n");
        return;
    }
    if (mode != NULL) {
        with_radio = strcmp(mode, "gpio") == 0 ? COMMAND_TEST_GPIO
                                               : COMMAND_TEST_PAD;
    }
    char report[96];
    report[0] = '\0';
    if (command_ops && command_ops->tx_selftest) {
        command_ops->tx_selftest(with_radio, report, sizeof(report));
    } else {
        strncpy(report, "TXTEST unavailable\r\n", sizeof(report) - 1U);
        report[sizeof(report) - 1U] = '\0';
    }
    send_text(link, report);
}

static void execute_carrier_text(uint8_t link) {
    char report[96];
    report[0] = '\0';
    if (command_ops && command_ops->tx_selftest) {
        command_ops->tx_selftest(COMMAND_TEST_CARRIER, report, sizeof(report));
    } else {
        strncpy(report, "CARRIER unavailable\r\n", sizeof(report) - 1U);
        report[sizeof(report) - 1U] = '\0';
    }
    send_text(link, report);
}

static void execute_diag_text(uint8_t link, const char *name) {
    char report[480];
    report[0] = '\0';
    if (command_ops && command_ops->diag) {
        command_ops->diag(name, report, sizeof(report));
    } else {
        strncpy(report, "DIAG unavailable\r\n", sizeof(report) - 1U);
        report[sizeof(report) - 1U] = '\0';
    }
    send_text(link, report);
}

static void execute_regs_text(uint8_t link) {
    char report[448];
    report[0] = '\0';
    if (command_ops && command_ops->regs_dump) {
        command_ops->regs_dump(report, sizeof(report));
    } else {
        strncpy(report, "REGS unavailable\r\n", sizeof(report) - 1U);
        report[sizeof(report) - 1U] = '\0';
    }
    send_text(link, report);
}

static void execute_mode_text(uint8_t link, char *cursor) {
    char *value = next_token(&cursor);
    char *extra = next_token(&cursor);
    if (extra != NULL) {
        send_text(link, "Usage: mode [idle|rx]\r\n");
        return;
    }
    if (value != NULL) {
        uint8_t mode;
        if (strcmp(value, "idle") == 0) {
            mode = RADIO_MODE_IDLE;
        } else if (strcmp(value, "rx") == 0) {
            mode = RADIO_MODE_RX;
        } else {
            send_text(link, "Usage: mode [idle|rx]\r\n");
            return;
        }
        (void)radio_mode_request(mode);
    }
    send_text(link, "MODE ");
    send_text(link, radio_mode_name(radio_mode_current()));
    send_text(link, "\r\n");
}

static void execute_ook_text(uint8_t link, char *cursor) {
    char *value_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    unsigned khz = 0;
    if (value_text != NULL && extra == NULL) {
        if (strcmp(value_text, "270") == 0) {
            khz = 270;
        } else if (strcmp(value_text, "650") == 0) {
            khz = 650;
        }
    }
    if (khz == 0U) {
        send_text(link, "Usage: ook <270|650>\r\n");
        return;
    }
    int ok = command_ops && command_ops->set_ook_preset
                 ? command_ops->set_ook_preset(khz)
                 : 0;
    send_text(link, ok ? "OOK DONE\r\n" : "OOK ERROR\r\n");
}

static void handle_text_line(uint8_t link, char *line) {
    if (*line == '\0') {
        return;
    }
    char *cursor = line;
    char *first = next_token(&cursor);
    if (first == NULL) {
        return;
    }
    if (strcmp(first, "tx") == 0) {
        execute_tx_text(link, cursor);
        return;
    }
    if (strcmp(first, "rxpair") == 0) {
        execute_rxpair_text(link, cursor);
        return;
    }
    if (strcmp(first, "rxconfig") == 0) {
        execute_rxconfig_text(link, cursor);
        return;
    }
    if (strcmp(first, "rxsim") == 0) {
        execute_rxsim_text(link, cursor);
        return;
    }
    if (strcmp(first, "help") == 0) {
        execute_help_text(link);
        return;
    }
    if (strcmp(first, "radio") == 0) {
        execute_radio_text(link);
        return;
    }
    if (strcmp(first, "gpio") == 0 || strcmp(first, "rssi") == 0 ||
        strcmp(first, "rxedges") == 0 || strcmp(first, "reinit") == 0 ||
        strcmp(first, "spitest") == 0 ||
        strcmp(first, "sweep") == 0 ||
        strcmp(first, "rxraw") == 0) {
        execute_diag_text(link, first);
        return;
    }
    if (strcmp(first, "carrier") == 0) {
        execute_carrier_text(link);
        return;
    }
    if (strcmp(first, "regs") == 0) {
        execute_regs_text(link);
        return;
    }
    if (strcmp(first, "mode") == 0) {
        execute_mode_text(link, cursor);
        return;
    }
    if (strcmp(first, "txtest") == 0) {
        execute_txtest_text(link, cursor);
        return;
    }
    if (strcmp(first, "ook") == 0) {
        execute_ook_text(link, cursor);
        return;
    }
    uint8_t command;
    if (*cursor == '\0' && scenario_for_name(first, &command)) {
        int ok = lab_tx_start_scenario(command, LAB_TX_KIND_TEXT_RUN, link);
        send_text(link, ok ? "RUN START\r\n" : "RUN BUSY\r\n");
        return;
    }
    send_text(link,
              "Unknown command. Use: tx <hopping> <serial> <button> "
              "[frames]\r\n");
}

void command_parse_init(const proto_sink_t *sink, const command_ops_t *ops) {
    command_sink = sink;
    command_ops = ops;
    for (size_t i = 0; i < LINK_COUNT; ++i) {
        rx_states[i].length = 0U;
        rx_states[i].looks_like_text = 1;
        rx_states[i].pending_eol = 0U;
    }
    lab_rx_init();
    lab_tx_set_done(tx_done, NULL);
}

void command_parse_reset_link(uint8_t link) {
    int index = link_index(link);
    if (index < 0) {
        return;
    }
    rx_states[index].length = 0U;
    rx_states[index].looks_like_text = 1;
    rx_states[index].pending_eol = 0U;
}

void command_parse_feed(uint8_t link, const uint8_t *data, size_t length) {
    int index = link_index(link);
    if (index < 0 || !data) {
        return;
    }

    rx_state_t *state = &rx_states[index];
    for (size_t pos = 0; pos < length; ++pos) {
        uint8_t value = data[pos];

        // A COBS code byte is the distance to the first zero in the raw
        // packet, so it can be 0x0A or 0x0D (e.g. a TX_COMMAND whose serial
        // is below 0x01000000).  At the start of a line such a byte is
        // ambiguous with a blank text line, so hold it back until the next
        // byte: the raw packet's version byte (0x01) never appears in text.
        if (state->pending_eol != 0U) {
            uint8_t eol = state->pending_eol;
            state->pending_eol = 0U;
            if (value == PROTO_VERSION) {
                state->buffer[0] = eol;
                state->length = 1U;
                state->looks_like_text = 0;
                // fall through: `value` is buffered below as packet data
            }
        }
        // For a COBS packet the byte after the code byte is always the raw
        // version (0x01).  A 203-byte OTA packet can have a printable code
        // byte, so use this position to lock binary mode before a 0x0A/0x0D
        // inside the payload is mistaken for the end of a text line.
        if (state->looks_like_text && state->length == 1U &&
            value == PROTO_VERSION) {
            state->looks_like_text = 0;
        }
        if (value == 0U) {
            if (state->length > 0U) {
                handle_binary_packet(link, state->buffer, state->length);
            }
            state->length = 0U;
            state->looks_like_text = 1;
        } else if (state->looks_like_text &&
                   (value == '\r' || value == '\n')) {
            if (state->length == 0U) {
                state->pending_eol = value;
                continue;
            }
            if (state->length > 0U) {
                state->buffer[state->length] = '\0';
                handle_text_line(link, (char *)state->buffer);
            }
            state->length = 0U;
            state->looks_like_text = 1;
        } else if (state->length + 1U < RX_BUFFER_MAX) {
            if (value < 0x20U || value > 0x7EU) {
                state->looks_like_text = 0;
            }
            state->buffer[state->length++] = value;
        } else {
            // Oversized/malformed input: drop it and resync at the next
            // delimiter.
            state->length = 0U;
            state->looks_like_text = 1;
        }
    }
}
