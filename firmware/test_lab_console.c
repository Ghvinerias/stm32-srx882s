// Host-side test for the lab console's line parser (src/lab_console.c).
// Stubs usb_cdc and lab_tx so the parsing/dispatch logic runs unmodified
// off-target. Does not touch RF hardware or any key material.
#include <stdio.h>
#include <string.h>
#include "src/lab_console.h"
#include "src/lab_tx.h"
#include "src/usb_cdc.h"

// ---- usb_cdc stub: byte queue in, text buffer out ----

static const uint8_t *rx_data;
static size_t rx_length;
static size_t rx_pos;

static char tx_buffer[2048];
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
    if (tx_length + length < sizeof(tx_buffer)) {
        memcpy(tx_buffer + tx_length, data, length);
        tx_length += length;
        tx_buffer[tx_length] = '\0';
    }
    return (int)length;
}

// ---- lab_tx stub: record the last call ----

static int tx_ready = 1;
static int tx_calls;
static uint32_t last_hopping, last_serial, last_frames;
static uint8_t last_button;

static int scenario_calls;
static uint8_t last_scenario;

int lab_tx_init(void) { return 1; }
int lab_tx_send_stale_replay(void) { return tx_ready; }

int lab_tx_send_codeword(uint32_t hopping, uint32_t serial, uint8_t button,
                          uint8_t frame_count) {
    ++tx_calls;
    last_hopping = hopping;
    last_serial = serial;
    last_button = button;
    last_frames = frame_count;
    return tx_ready;
}

int lab_tx_run_scenario(uint8_t command) {
    ++scenario_calls;
    last_scenario = command;
    return tx_ready;
}

// ---- harness ----

static int failures;

static void run_line(const char *line) {
    char buffer[64];
    size_t n = strlen(line);
    memcpy(buffer, line, n);
    buffer[n] = '\r';
    rx_data = (const uint8_t *)buffer;
    rx_length = n + 1U;
    rx_pos = 0U;
    tx_length = 0U;
    tx_buffer[0] = '\0';
    tx_calls = 0;
    scenario_calls = 0;
    lab_console_poll();
}

static void expect_contains(const char *label, const char *needle) {
    if (strstr(tx_buffer, needle) == NULL) {
        printf("FAIL %-28s expected to contain %-40s got: %s\n", label, needle,
               tx_buffer);
        ++failures;
    } else {
        printf("ok   %-28s\n", label);
    }
}

static void expect_tx_calls(const char *label, int expected) {
    if (tx_calls != expected) {
        printf("FAIL %-28s expected %d tx call(s), got %d\n", label, expected,
               tx_calls);
        ++failures;
    }
}

int main(void) {
    lab_console_init();

    run_line("tx CF3EED68 FB3EC77 2 5");
    expect_contains("explicit tx", "hopping=0xcf3eed68");
    expect_contains("explicit tx serial", "serial=0xfb3ec77");
    expect_contains("explicit tx frames", "frames=5");
    expect_tx_calls("explicit tx", 1);

    run_line("CF3EED68 FB3EC77 2 5");
    expect_contains("bare tx", "hopping=0xcf3eed68");
    expect_tx_calls("bare tx", 1);

    run_line("tx cf3eed68 fb3ec77 a 2");
    expect_contains("lowercase button", "button=0xa");
    expect_tx_calls("lowercase button", 1);

    run_line("tx DEADBEEF 1234 1");
    expect_contains("default frame count", "frames=3");
    expect_tx_calls("default frame count", 1);

    run_line("tx 1 2 3 FF");
    expect_contains("frame count clamp high", "frames=a");
    expect_tx_calls("frame count clamp high", 1);

    run_line("tx 1 2 3 0");
    expect_contains("frame count clamp low", "frames=1");
    expect_tx_calls("frame count clamp low", 1);

    run_line("tx CF3EED68 FB3EC77");
    expect_contains("missing fields rejected", "Usage:");
    expect_tx_calls("missing fields rejected", 0);

    run_line("tx 1 2 1F");
    expect_contains("button out of range rejected", "Usage:");
    expect_tx_calls("button out of range rejected", 0);

    run_line("tx ZZZZ 2 3");
    expect_contains("non-hex field rejected", "Usage:");
    expect_tx_calls("non-hex field rejected", 0);

    run_line("tx 1 2 3 4 5");
    expect_contains("extra token rejected", "Usage:");
    expect_tx_calls("extra token rejected", 0);

    // Scenario shortcuts must still dispatch, not get misread as hex.
    run_line("d");
    if (scenario_calls != 1 || last_scenario != 'd') {
        printf("FAIL %-28s scenario 'd' did not dispatch\n", "scenario d");
        ++failures;
    } else {
        printf("ok   %-28s\n", "scenario d");
    }

    run_line("p");
    if (scenario_calls != 1 || last_scenario != 'p') {
        printf("FAIL %-28s scenario 'p' did not dispatch\n", "scenario p");
        ++failures;
    } else {
        printf("ok   %-28s\n", "scenario p");
    }

    run_line("x");
    if (scenario_calls != 1 || last_scenario != 'x') {
        printf("FAIL %-28s scenario 'x' did not dispatch\n", "scenario x");
        ++failures;
    } else {
        printf("ok   %-28s\n", "scenario x");
    }

    run_line("zero-hop");
    if (scenario_calls != 1 || last_scenario != 'z') {
        printf("FAIL %-28s scenario name did not dispatch\n", "scenario zero-hop");
        ++failures;
    } else {
        printf("ok   %-28s\n", "scenario zero-hop");
    }

    run_line("different-serial");
    if (scenario_calls != 1 || last_scenario != 'd') {
        printf("FAIL %-28s scenario name did not dispatch\n",
               "scenario different-serial");
        ++failures;
    } else {
        printf("ok   %-28s\n", "scenario different-serial");
    }

    run_line("");
    expect_contains("empty line reprompts", "> ");
    expect_tx_calls("empty line reprompts", 0);

    if (failures == 0) {
        printf("ALL PASS\n");
        return 0;
    }
    printf("%d FAILURE(S)\n", failures);
    return 1;
}
