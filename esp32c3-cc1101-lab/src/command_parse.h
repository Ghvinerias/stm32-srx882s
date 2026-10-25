#pragma once

#include <stddef.h>
#include <stdint.h>

#include "proto_transport.h"

// Clone of firmware/src/command_rx.c's dual-format input path:
//   * binary COBS+CRC16 TX_COMMAND / SCENARIO_COMMAND packets, and
//   * ASCII "tx <hopping> <serial> <button> [frames]" plus the scenario words
//     and rxpair/rxconfig/rxsim.
//
// Output is always binary framed packets, except for the human-readable
// replies that command_rx.c also emits over the same byte stream.
//
// One reassembly state is kept per link so USB and BLE cannot splice their
// partial packets together.

enum { COMMAND_TEST_PAD = 0, COMMAND_TEST_RADIO = 1, COMMAND_TEST_CARRIER = 2,
       COMMAND_TEST_GPIO = 3 };

typedef struct {
    // Optional ESP32-only diagnostics.  May be NULL (host tests, radio down).
    void (*radio_report)(char *out, size_t capacity);
    int (*set_ook_preset)(unsigned khz);
    // `txtest [pad]` / `carrier`: bench self-tests through the real RMT TX
    // path.  mode: COMMAND_TEST_PAD (square wave, no CC1101 calls),
    // COMMAND_TEST_RADIO (square wave with the CC1101 keyed) or
    // COMMAND_TEST_CARRIER (continuous carrier for ~2 s).
    void (*tx_selftest)(int mode, char *out, size_t capacity);
    // `gpio` / `rssi` / `rxedges` / `reinit`: bench diagnostics, formatted by
    // the device layer.  `name` is the command word.
    void (*diag)(const char *name, char *out, size_t capacity);
    // `regs`: live CC1101 register read-back.
    void (*regs_dump)(char *out, size_t capacity);
} command_ops_t;

#ifdef __cplusplus
extern "C" {
#endif

void command_parse_init(const proto_sink_t *sink, const command_ops_t *ops);
void command_parse_feed(uint8_t link, const uint8_t *data, size_t length);

// Binary-command counters since boot, for the `radio` diagnostic line:
// packets seen (0x00-terminated, non-empty), dropped as malformed (bad COBS /
// version / CRC / length / type), and ACKed.
typedef struct {
    uint16_t packets;
    uint16_t rejected;
    uint16_t acked;
} command_parse_stats_t;

command_parse_stats_t command_parse_stats(void);

// Drop a partially received packet/line, e.g. when a BLE central disconnects.
void command_parse_reset_link(uint8_t link);

#ifdef __cplusplus
}
#endif
