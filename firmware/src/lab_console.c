#include "lab_console.h"

#if defined(BUILD_HOST)

void lab_console_init(void) {}
void lab_console_poll(void) {}

#else

#include "lab_tx.h"
#include "usb_cdc.h"
#include <string.h>

#define LINE_CAPACITY 40U

static char line[LINE_CAPACITY];
static uint8_t line_length;

static void send_text(const char *value) {
    if (value) {
        usb_cdc_write((const uint8_t *)value, strlen(value));
    }
}

static void send_menu(void) {
    send_text(
        "\r\nSTM32 KeeLoq tabletop lab\r\n"
        "Commands:\r\n"
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
    uint8_t command;
    if (!scenario_for_line(line, &command)) {
        send_text("Unknown command. Type help.\r\n> ");
        return;
    }

    send_text("RUN ");
    send_text(scenario_name(command));
    send_text("\r\n");
    int ok = lab_tx_run_scenario(command);
    send_text(ok ? "DONE\r\n> " : "ERROR: transmitter not ready\r\n> ");
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
