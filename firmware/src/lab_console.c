#include "lab_console.h"

#if defined(BUILD_HOST)

void lab_console_init(void) {}
void lab_console_poll(void) {}

#else

#include "lab_tx.h"
#include "usb_cdc.h"
#include <string.h>

#define LINE_CAPACITY 48U

static char line[LINE_CAPACITY];
static uint8_t line_length;

static void send_text(const char *value) {
    if (value) {
        usb_cdc_write((const uint8_t *)value, strlen(value));
    }
}

// Print a fixed-width lowercase hex field; the firmware has no printf.
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

static void send_menu(void) {
    send_text(
        "\r\nSTM32 KeeLoq tabletop lab\r\n"
        "Commands:\r\n"
        "  tx <hopping> <serial> <button> [frames]\r\n"
        "     e.g. tx CF3EED68 FB3EC77 2 5\r\n"
        "  stale\r\n"
        "  synthetic-stale\r\n"
        "  zero-hop\r\n"
        "  plaintext-counter\r\n"
        "  random-hop\r\n"
        "  different-serial\r\n"
        "  help\r\n"
        "> ");
}

static int scenario_for_line(const char *value, uint8_t *command) {
    struct mapping {
        const char *name;
        uint8_t command;
    };
    static const struct mapping mappings[] = {
        {"stale", 'r'},
        {"synthetic-stale", 's'},
        {"zero-hop", 'z'},
        {"plaintext-counter", 'p'},
        {"random-hop", 'x'},
        {"different-serial", 'd'},
        {"r", 'r'}, {"s", 's'}, {"z", 'z'},
        {"p", 'p'}, {"x", 'x'}, {"d", 'd'},
    };
    for (size_t i = 0; i < sizeof(mappings) / sizeof(mappings[0]); ++i) {
        if (strcmp(value, mappings[i].name) == 0) {
            *command = mappings[i].command;
            return 1;
        }
    }
    return 0;
}

static const char *scenario_name(uint8_t command) {
    switch (command) {
    case 'r': return "stale";
    case 's': return "synthetic-stale";
    case 'z': return "zero-hop";
    case 'p': return "plaintext-counter";
    case 'x': return "random-hop";
    case 'd': return "different-serial";
    default: return "unknown";
    }
}

// ---- tokenising and hex fields ----

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

// Look at the first token without modifying the line.
static const char *peek_token(const char *text) {
    while (*text == ' ') {
        ++text;
    }
    return (*text == '\0') ? NULL : text;
}

static int token_equals(const char *token, const char *name) {
    size_t i = 0;
    while (name[i] != '\0') {
        if (token[i] != name[i]) {
            return 0;
        }
        ++i;
    }
    return token[i] == '\0' || token[i] == ' ';
}

// Return the next space-separated token, terminated in place.
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

static uint8_t count_tokens(const char *text) {
    uint8_t count = 0;
    while (*text != '\0') {
        while (*text == ' ') {
            ++text;
        }
        if (*text == '\0') {
            break;
        }
        ++count;
        while (*text != '\0' && *text != ' ') {
            ++text;
        }
    }
    return count;
}

#define DEFAULT_FRAMES 3U
#define MAX_FRAMES 10U

// tx <hopping> <serial> <button> [frames]
// Transmits exactly the supplied fields. The hopping word goes out verbatim;
// nothing here encrypts, derives or checks anything.
static void execute_tx(char *cursor) {
    char *hopping_text = next_token(&cursor);
    char *serial_text = next_token(&cursor);
    char *button_text = next_token(&cursor);
    char *frames_text = next_token(&cursor);
    char *extra = next_token(&cursor);

    uint32_t hopping = 0;
    uint32_t serial = 0;
    uint32_t button = 0;
    uint32_t frames = DEFAULT_FRAMES;

    if (hopping_text == NULL || serial_text == NULL || button_text == NULL ||
        extra != NULL || !parse_hex(hopping_text, &hopping) ||
        !parse_hex(serial_text, &serial) || !parse_hex(button_text, &button) ||
        button > 0xFU ||
        (frames_text != NULL && !parse_hex(frames_text, &frames))) {
        send_text("Usage: tx <hopping> <serial> <button> [frames]\r\n"
                  "  e.g. tx CF3EED68 FB3EC77 2 5\r\n> ");
        return;
    }
    if (frames == 0U) {
        frames = 1U;
    }
    if (frames > MAX_FRAMES) {
        frames = MAX_FRAMES;
    }

    send_text("TX hopping=0x");
    send_hex(hopping, 8);
    send_text(" serial=0x");
    send_hex(serial, 7);
    send_text(" button=0x");
    send_hex(button, 1);
    send_text(" frames=");
    send_hex(frames, 1);
    send_text("\r\n");

    int ok = lab_tx_send_codeword(hopping, serial, (uint8_t)button, (uint8_t)frames);
    send_text(ok ? "DONE\r\n> " : "ERROR: transmitter not ready\r\n> ");
}

static void execute_line(void) {
    line[line_length] = '\0';
    if (line_length == 0U) {
        send_text("> ");
        return;
    }
    if (strcmp(line, "help") == 0 || strcmp(line, "?") == 0) {
        send_menu();
        return;
    }

    const char *first = peek_token(line);
    if (first == NULL) {
        send_text("> ");
        return;
    }

    uint8_t tokens = count_tokens(line);

    // A bare scenario name keeps its short cut. "tx ..." is explicit, and the
    // bare field form "CF3EED68 FB3EC77 2 5" is accepted too.
    if (token_equals(first, "tx")) {
        char *cursor = line;
        (void)next_token(&cursor); // drop the keyword
        execute_tx(cursor);
        return;
    }

    uint8_t command;
    if (tokens == 1U && scenario_for_line(first, &command)) {
        send_text("RUN ");
        send_text(scenario_name(command));
        send_text("\r\n");
        int ok = lab_tx_run_scenario(command);
        send_text(ok ? "DONE\r\n> " : "ERROR: transmitter not ready\r\n> ");
        return;
    }

    uint32_t ignored;
    if (parse_hex(first, &ignored)) {
        execute_tx(line);
        return;
    }

    send_text("Unknown command. Type help.\r\n> ");
}

void lab_console_init(void) {
    line_length = 0U;
    send_menu();
}

void lab_console_poll(void) {
    uint8_t value;
    while (usb_cdc_read_byte(&value)) {
        if (value == '\r' || value == '\n') {
            if (line_length != 0U || value == '\r') {
                execute_line();
                line_length = 0U;
            }
        } else if (value == 0x08U || value == 0x7FU) {
            if (line_length != 0U) {
                line_length--;
            }
        } else if (value >= 0x20U && value <= 0x7EU &&
                   line_length + 1U < LINE_CAPACITY) {
            if (value >= 'A' && value <= 'Z') {
                value = (uint8_t)(value + ('a' - 'A'));
            }
            line[line_length++] = (char)value;
        }
    }
    usb_cdc_poll();
}

#endif
