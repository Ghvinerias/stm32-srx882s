#pragma once

#include <stddef.h>
#include <stdint.h>

#include "lab_capture.h"

// RMT-backed OOK transmit and receive for the CC1101 async data pins.
//
// TX: GDO0 (LAB_PIN_GDO0_TX) is driven by an RMT channel only while a burst
// is in flight; outside a burst the pad output is disabled so the CC1101 can
// drive GDO0 itself in RX.  No CPU bit-banging, no interrupt masking.
//
// RX: GDO2 (LAB_PIN_GDO2_RX) is captured by a second RMT channel.  RMT
// symbols carry both levels of every interval, so both edge polarities are
// preserved (the full HCS301 frame is 77 intervals, not 38).

bool radio_rmt_init(void);

// Blocking burst transmit, pulses are edge-to-edge microseconds starting at
// a high level.  Returns 1 when the RMT transaction completed.
int radio_rmt_transmit(const uint16_t *pulses, size_t pulse_count);

bool radio_rmt_rx_arm(void);
bool radio_rmt_rx_armed(void);
void radio_rmt_rx_suspend(void);
void radio_rmt_rx_resume(void);

// Returns 1 and fills out when a complete idle-delimited burst is available.
int radio_rmt_rx_pop(capture_frame_t *out);

// Raw diagnostics (pre-filter).  rx_irq/rx_symbols count every RMT RX
// completion and symbol; total_edges (STATUS) only counts frames that survive
// the filters.  last_tx_us is the measured duration of the last transmit
// (arm -> done semaphore), expected_tx_us the sum of the requested pulses.
typedef struct {
    uint32_t rx_irq;
    uint32_t rx_symbols;
    uint32_t tx_count;
    uint32_t tx_failures;
    uint32_t last_tx_us;
    uint32_t expected_tx_us;
} radio_rmt_stats_t;
radio_rmt_stats_t radio_rmt_stats(void);

// Pad diagnostics: input levels of GPIO3 (GDO0) / GPIO10 (GDO2), GPIO3's
// output-enable bit and matrix routing (out_sel 0x80 = plain GPIO_OUT, other =
// a peripheral signal, e.g. the RMT TX signal).
typedef struct {
    uint8_t level_gdo0;
    uint8_t level_gdo2;
    uint8_t enable_gdo0;
    uint8_t oen_sel_gdo0;
    uint16_t out_sel_gdo0;
} radio_rmt_gpio_t;
void radio_rmt_gpio_state(radio_rmt_gpio_t *out);

// Counts level changes on GPIO10 by polling for `ms` milliseconds, independent
// of the RMT (so it works even if the RMT RX path is broken).  Returns edges;
// *high_permille receives the fraction of samples that were high.
uint32_t radio_rmt_count_edges(uint32_t ms, uint32_t *high_permille);

// Plain bit-bang of GPIO3 with the RMT channel torn down for the duration
// (then recreated).  Returns 1 if the RMT TX channel was rebuilt OK.
int radio_rmt_gpio_bitbang(const uint16_t *pulses, size_t pulse_count);

// HCS301 frames are normally rewritten into the STM32 reference representation
// (77 same-polarity periods) before being queued; raw mode forwards the plain
// both-edge intervals instead (for analysing other protocols).
void radio_rmt_set_raw_mode(bool raw);
bool radio_rmt_raw_mode(void);
uint32_t radio_rmt_hcs_reduced(void);

// Number of failed rmt_receive() arm attempts since boot.
uint32_t radio_rmt_arm_failures(void);
uint16_t radio_rmt_dropped_frames(void);
uint32_t radio_rmt_total_edges(void);
