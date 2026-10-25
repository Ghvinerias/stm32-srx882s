#include "firmware_app.h"

#include <Arduino.h>
#include <esp_log.h>
#include <esp_ota_ops.h>
#include <soc/rtc_cntl_reg.h>
#include <soc/soc.h>
#include <stdio.h>
#include <string.h>

#include "ble_serial.h"
#include "cc1101.h"
#include "command_parse.h"
#include "lab_config.h"
#include "lab_tx.h"
#include "ota_backend.h"
#include "ota_session.h"
#include "proto_transport.h"
#include "radio_mode.h"
#include "radio_rmt.h"
#include "usb_tx_policy.h"

static uint32_t last_status_ms;
static uint32_t boot_ms;
static uint32_t last_arm_try_ms;
static bool last_arm_failed;
#define RX_REARM_RETRY_MS 500U
static bool usb_was_up;
static bool ble_was_up;
static bool ble_ready;
static bool app_validated;

static bool usb_link_up(void) { return Serial.isConnected(); }

static uint8_t link_write(void *context, uint8_t targets,
                          const uint8_t *data, size_t length) {
    (void)context;
    uint8_t delivered = 0U;

    if (targets & PROTO_LINK_USB) {
        // Never gate on Serial.isConnected(): a passive host (the phone only
        // reads) can never flip it unless we queue data first.
        bool connected = Serial.isConnected();
        switch (usb_tx_decide(connected, connected ? (size_t)Serial.availableForWrite() : 0U,
                              length)) {
        case USB_TX_BOOTSTRAP:
            Serial.flush(); // not connected: just discards stale queued bytes
            // fall through
        case USB_TX_SEND:
            if (Serial.write(data, length) == length) {
                delivered |= PROTO_LINK_USB;
            }
            break;
        case USB_TX_DROP:
            break;
        }
    }

    if ((targets & PROTO_LINK_BLE) && ble_serial_subscribed()) {
        if (ble_serial_write(data, length)) {
            delivered |= PROTO_LINK_BLE;
        }
    }

    return delivered;
}

// ---- radio modes (IDLE / RX / transient TX) ----------------------------------

static void send_status_now(void) {
    (void)proto_send_status(1000000U, radio_rmt_dropped_frames(),
                            radio_rmt_total_edges(), PROTO_LINK_ALL);
    last_status_ms = millis();
}

// Put the hardware into the base mode.  Also used after the bench self-tests
// that borrow the radio.  An OTA session owns the RMT receiver while it
// writes flash, so the receiver is only re-enabled once that ends.
static void apply_base_mode(void *context, uint8_t base) {
    (void)context;
    if (base == RADIO_MODE_RX) {
        if (cc1101_is_initialized()) {
            (void)cc1101_set_rx();
        }
        radio_rmt_set_rx_wanted(true, ota_session_rx_suspended() == 0);
    } else {
        radio_rmt_set_rx_wanted(false, false);
        if (cc1101_is_initialized()) {
            cc1101_idle();
        }
    }
}

static void restore_radio(void) { apply_base_mode(NULL, radio_mode_base()); }

static void mode_changed(void *context, uint8_t mode) {
    (void)context;
    proto_set_mode(mode);
    send_status_now(); // the app's UI follows the device, not its own guess
}

static void mode_abort_tx(void *context) {
    (void)context;
    lab_tx_abort();
}

static int tx_hw_begin(void *context) {
    (void)context;
    if (!cc1101_is_initialized()) {
        return 0;
    }
    digitalWrite(LAB_PIN_STATUS_LED, LOW);
    radio_rmt_rx_suspend();
    if (!cc1101_set_tx()) {
        digitalWrite(LAB_PIN_STATUS_LED, HIGH);
        restore_radio();
        return 0;
    }
    radio_mode_tx_begin(); // reports mode TX
    return 1;
}

static int tx_hw_queue(void *context, const uint16_t *pulses, size_t count) {
    (void)context;
    return radio_rmt_tx_queue(pulses, count);
}

static unsigned tx_hw_inflight(void *context) {
    (void)context;
    return radio_rmt_tx_inflight();
}

static void tx_hw_end(void *context, int aborted) {
    (void)context;
    (void)aborted;
    digitalWrite(LAB_PIN_STATUS_LED, HIGH);
    radio_mode_tx_end(); // restores IDLE/RX and reports it
}

static uint32_t tx_now_ms(void *context) {
    (void)context;
    return millis();
}

static void radio_report_cb(char *out, size_t capacity) {
    cc1101_describe(out, capacity);
    // Append the binary-command counters so a phone/host that sees ACK
    // timeouts can tell "never arrived" from "arrived, rejected" from
    // "handled, ACK not delivered".
    size_t used = strlen(out);
    if (used < capacity) {
        used += (size_t)snprintf(out + used, capacity - used, "MODE %s base=%s\r\n",
                                 radio_mode_name(radio_mode_current()),
                                 radio_mode_name(radio_mode_base()));
    }
    radio_rmt_stats_t rmt = radio_rmt_stats();
    if (used < capacity) {
        used += (size_t)snprintf(out + used, capacity - used,
                                 "RMT rx_irq=%u rx_sym=%u hcs=%u raw=%d tx_n=%u "
                                 "tx_fail=%u tx_us=%u/%u\r\n",
                                 (unsigned)rmt.rx_irq, (unsigned)rmt.rx_symbols,
                                 (unsigned)radio_rmt_hcs_reduced(),
                                 radio_rmt_raw_mode() ? 1 : 0,
                                 (unsigned)rmt.tx_count,
                                 (unsigned)rmt.tx_failures,
                                 (unsigned)rmt.last_tx_us,
                                 (unsigned)rmt.expected_tx_us);
    }
    if (used > capacity) {
        used = capacity;
    }
    ble_serial_stats_t ble = ble_serial_stats();
    if (used < capacity) {
        used += (size_t)snprintf(out + used, capacity - used,
                                 "BLE conn=%u sub=%u mtu=%u handle=%u events c/d/s=%u/%u/%u notifies=%u\r\n",
                                 ble.connected, ble.subscribed, ble.mtu,
                                 ble.conn_handle, (unsigned)ble.connect_events,
                                 (unsigned)ble.disconnect_events,
                                 (unsigned)ble.subscribe_events, (unsigned)ble.notifies);
    }
    if (used > capacity) {
        used = capacity;
    }
    command_parse_stats_t stats = command_parse_stats();
    if (used < capacity) {
        snprintf(out + used, capacity - used,
                 "CMD packets=%u rejected=%u acked=%u arm_fail=%u\r\n",
                 (unsigned)stats.packets, (unsigned)stats.rejected,
                 (unsigned)stats.acked, (unsigned)radio_rmt_arm_failures());
    }
}

static int ook_preset_cb(unsigned khz) {
    int ok = cc1101_set_preset(khz) ? 1 : 0;
    restore_radio(); // cc1101_set_preset() ends in RX
    return ok;
}

static void diag_cb(const char *name, char *out, size_t capacity) {
    if (strcmp(name, "gpio") == 0) {
        radio_rmt_gpio_t g;
        radio_rmt_gpio_state(&g);
        uint8_t pkt = cc1101_is_initialized() ? cc1101_pktstatus() : 0U;
        snprintf(out, capacity,
                 "GPIO gpio3(GDO0)=%u en=%u oen_sel=%u out_sel=0x%x "
                 "gpio10(GDO2)=%u | CC1101 pktstatus=0x%02x gdo0=%u gdo2=%u "
                 "(%s)\r\n",
                 g.level_gdo0, g.enable_gdo0, g.oen_sel_gdo0, g.out_sel_gdo0,
                 g.level_gdo2, pkt, pkt & 1U, (pkt >> 2) & 1U,
                 (((pkt >> 2) & 1U) == g.level_gdo2) ? "gdo2 matches gpio10"
                                                      : "MISMATCH gdo2 vs gpio10");
    } else if (strcmp(name, "rssi") == 0) {
        if (!cc1101_is_initialized()) {
            snprintf(out, capacity, "RSSI NOT_READY\r\n");
            return;
        }
        int lo = 0, hi = -200, sum = 0;
        lo = 100;
        for (int i = 0; i < 50; ++i) {
            int v = cc1101_rssi_dbm();
            lo = v < lo ? v : lo;
            hi = v > hi ? v : hi;
            sum += v;
            delay(4);
        }
        snprintf(out, capacity,
                 "RSSI min=%d max=%d avg=%d dBm state=0x%02x (50 samples/200 ms)\r\n",
                 lo, hi, sum / 50, cc1101_marcstate());
    } else if (strcmp(name, "rxedges") == 0) {
        uint32_t high = 0;
        uint32_t edges = radio_rmt_count_edges(1000U, &high);
        radio_rmt_stats_t rmt = radio_rmt_stats();
        snprintf(out, capacity,
                 "RXEDGES gpio10 edges_in_1s=%u high=%u.%u%% rmt_rx_irq=%u "
                 "rmt_rx_sym=%u\r\n",
                 (unsigned)edges, (unsigned)(high / 10U), (unsigned)(high % 10U),
                 (unsigned)rmt.rx_irq, (unsigned)rmt.rx_symbols);
    } else if (strcmp(name, "rxraw") == 0) {
        radio_rmt_set_raw_mode(!radio_rmt_raw_mode());
        snprintf(out, capacity, "RXRAW raw=%d (1 = plain both-edge intervals)\r\n",
                 radio_rmt_raw_mode() ? 1 : 0);
    } else if (strcmp(name, "spitest") == 0) {
        cc1101_spi_selftest(out, capacity);
    } else if (strcmp(name, "sweep") == 0) {
        radio_rmt_rx_suspend();
        cc1101_reg_sweep(out, capacity);
        (void)cc1101_init(CC1101_PRESET_OOK270);
        restore_radio();
    } else if (strcmp(name, "reinit") == 0) {
        radio_rmt_rx_suspend();
        bool ok = cc1101_init(CC1101_PRESET_OOK270);
        restore_radio();
        char desc[160];
        cc1101_describe(desc, sizeof(desc));
        snprintf(out, capacity, "REINIT ok=%d %s", ok ? 1 : 0, desc);
    } else {
        snprintf(out, capacity, "DIAG unknown\r\n");
    }
}

static void regs_dump_cb(char *out, size_t capacity) {
    cc1101_dump_regs(out, capacity);
}

static void tx_selftest_cb(int mode, char *out, size_t capacity) {
    static uint16_t pulses[400];
    if (lab_tx_busy()) {
        snprintf(out, capacity, "TXTEST busy (a TX burst is running)\r\n");
        return;
    }
    size_t count;
    uint32_t expected_us;
    if (mode == COMMAND_TEST_GPIO) {
        for (size_t i = 0; i < 400U; ++i) {
            pulses[i] = 1000U;
        }
        radio_rmt_rx_suspend();
        int ok = radio_rmt_gpio_bitbang(pulses, 400U);
        restore_radio();
        snprintf(out, capacity,
                 "TXTEST gpio ok=%d (bit-bang, RMT TX channel rebuilt)\r\n", ok);
        return;
    }
    if (mode == COMMAND_TEST_CARRIER) {
        // ~2 s of carrier: GDO0 high in 30 ms slices separated by 1 us lows
        // (below the receivers' resolution).
        count = 0;
        for (int i = 0; i < 66; ++i) {
            pulses[count++] = 30000U;
            pulses[count++] = 1U;
        }
        expected_us = 66U * 30001U;
    } else {
        // 200 cycles of 1 ms high / 1 ms low: visible on any logic analyser.
        for (size_t i = 0; i < 400U; ++i) {
            pulses[i] = 1000U;
        }
        count = 400U;
        expected_us = 400000U;
    }

    radio_rmt_rx_suspend();
    int ok = 0;
    int keyed = 0;
    if (mode == COMMAND_TEST_PAD) {
        ok = radio_rmt_transmit(pulses, count);
    } else if (cc1101_is_initialized() && cc1101_set_tx()) {
        keyed = 1;
        ok = radio_rmt_transmit(pulses, count);
    }
    restore_radio();
    radio_rmt_stats_t rmt = radio_rmt_stats();
    snprintf(out, capacity, "%s ok=%d cc1101_tx=%d tx_us=%u expected=%u\r\n",
             mode == COMMAND_TEST_CARRIER ? "CARRIER"
             : mode == COMMAND_TEST_PAD   ? "TXTEST pad"
                                          : "TXTEST radio",
             ok, keyed, (unsigned)rmt.last_tx_us, (unsigned)expected_us);
}

// Re-arm retries are rate-limited so a persistent failure can never starve
// the loop (or flood any log) -- ACK latency matters more.  Re-arm at once
// after a normal reception; back off only after a failed arm.
static void rx_rearm(uint32_t now) {
    if (!radio_rmt_rx_armed() && !ota_session_rx_suspended() &&
        (!last_arm_failed || (uint32_t)(now - last_arm_try_ms) >= RX_REARM_RETRY_MS)) {
        last_arm_try_ms = now;
        last_arm_failed = !radio_rmt_rx_arm();
    }
}

static proto_sink_t transport_sink;
static command_ops_t command_ops;

void firmware_app_init(void) {
    // Nothing but the framed protocol may ever appear on the USB-CDC stream:
    // IDF/Arduino log text would corrupt COBS framing and HWCDC writes can
    // block.  Silenced again at the end of init for tags registered later.
    esp_log_level_set("*", ESP_LOG_NONE);

    // A previous reboot-to-bootloader sets RTC_CNTL_FORCE_DOWNLOAD_BOOT, which
    // survives resets; clear it so the next reset boots this app again.
    REG_WRITE(RTC_CNTL_OPTION1_REG, 0);

    // An OTA CHUNK is up to 201 raw / 203 wire bytes; the HWCDC default RX
    // queue is only 256 bytes, so enlarge it before the port starts.
    Serial.setRxBufferSize(1024);
    Serial.setTxBufferSize(2048); // room for several FRAMEs (<=537 B each)
    Serial.setTxTimeoutMs(20); // never stall the loop >20 ms on a slow host
    Serial.begin(115200);

    pinMode(LAB_PIN_STATUS_LED, OUTPUT);
    digitalWrite(LAB_PIN_STATUS_LED, HIGH); // active low

    // Bring the radio up first so the first captured frame is not missed
    // while the BLE stack starts.  A missing CC1101 is not fatal: the framed
    // transport still runs so the host can see STATUS and ACK traffic.
    uint16_t boot_flags = 0U;
    bool radio_ok = cc1101_init(CC1101_PRESET_OOK270);
    bool rmt_ok = radio_rmt_init();
    if (!radio_ok || !rmt_ok) {
        boot_flags |= PROTO_FLAG_CAPTURE_INIT_FAILED;
    }
    // Boot default is IDLE: CC1101 in SIDLE, RMT receiver disabled.
    radio_mode_ops_t mode_ops = {apply_base_mode, mode_changed, mode_abort_tx, NULL};
    radio_mode_init(&mode_ops);
    restore_radio();

    ble_ready = ble_serial_init();
    transport_sink.write = link_write;
    transport_sink.context = NULL;
    proto_init(&transport_sink, boot_flags);

    command_ops.radio_report = radio_report_cb;
    command_ops.set_ook_preset = ook_preset_cb;
    command_ops.tx_selftest = tx_selftest_cb;
    command_ops.regs_dump = regs_dump_cb;
    command_ops.diag = diag_cb;
    command_parse_init(&transport_sink, &command_ops);

    ota_backend_init();
    ota_session_init(ota_backend_get());

    lab_tx_ops_t tx_ops = {tx_hw_begin, tx_hw_queue, tx_hw_inflight, tx_hw_end,
                           tx_now_ms, NULL};
    lab_tx_init(&tx_ops);

    last_status_ms = millis();
    boot_ms = last_status_ms;
}

void firmware_app_loop(void) {
    uint8_t buffer[64];
    uint32_t now = millis();

    // A dropped link must not leave half a command/line in the parser and then
    // prepend it to the next connection's first bytes.  It also discards an
    // OTA session owned by that link.
    bool usb_up = usb_link_up();
    if (usb_was_up && !usb_up) {
        command_parse_reset_link(PROTO_LINK_USB);
        ota_session_link_lost(PROTO_LINK_USB);
    }
    usb_was_up = usb_up;

    bool ble_up = ble_serial_connected();
    if (ble_was_up && !ble_up) {
        command_parse_reset_link(PROTO_LINK_BLE);
        ota_session_link_lost(PROTO_LINK_BLE);
    }
    ble_was_up = ble_up;

    // Idle timeout, plus the ~200/300 ms delayed reboot requested by the OTA
    // session.  Never reboot from inside the parser.
    ota_session_poll(now);

    // Rollback-enabled bootloaders keep a freshly flashed image in
    // PENDING_VERIFY until the app confirms it.  Do that once, ~10 s after
    // boot with the BLE stack up, so a bad image still rolls back on reset.
    if (!app_validated && ble_ready && (uint32_t)(now - boot_ms) >= 10000U) {
        app_validated = true;
        (void)esp_ota_mark_app_valid_cancel_rollback();
    }

    // ---- USB-CDC input ----
    int available = Serial.available();
    while (available > 0) {
        size_t want = (size_t)available < sizeof(buffer) ? (size_t)available
                                                         : sizeof(buffer);
        size_t got = Serial.readBytes(buffer, want);
        if (got == 0U) {
            break;
        }
        command_parse_feed(PROTO_LINK_USB, buffer, got);
        available = Serial.available();
    }

    // ---- BLE input (drained outside the NimBLE callback) ----
    size_t ble_bytes;
    while ((ble_bytes = ble_serial_read(buffer, sizeof(buffer))) > 0U) {
        command_parse_feed(PROTO_LINK_BLE, buffer, ble_bytes);
    }

    // ---- streamed TX burst: queue frames, detect the end, send the ACK ----
    lab_tx_poll();

    // ---- captured frames ----
    // Re-arm before each send: the next frame of a remote's burst starts
    // ~7 ms after the RX-done event, while a FRAME over BLE (several paced
    // notify chunks) or a slow USB host can hold the loop longer than that.
    // The ISR already copied the symbols out, so the RMT buffer is free.
    bool listening = radio_mode_current() == RADIO_MODE_RX;
    if (listening) {
        rx_rearm(now);
    }
    capture_frame_t frame;
    while (radio_rmt_rx_pop(&frame)) {
        if (!listening) {
            continue; // IDLE/TX: nothing may be reported as received
        }
        rx_rearm(now);
        (void)proto_send_frame(&frame, radio_rmt_dropped_frames(),
                               PROTO_LINK_ALL);
        digitalWrite(LAB_PIN_STATUS_LED, LOW);
        delayMicroseconds(200);
        digitalWrite(LAB_PIN_STATUS_LED, HIGH);
    }
    if (listening) {
        rx_rearm(now);
    }

    // ---- STATUS heartbeat ----
    // Signed compare: an immediate mode-change STATUS may have refreshed
    // last_status_ms after `now` was sampled at the top of this iteration.
    if ((int32_t)(now - last_status_ms) >= 1000) {
        last_status_ms = now;
        // Always attempt USB (this is what wakes a passive host's link up);
        // BLE only once the phone has subscribed to notifications.
        (void)proto_send_status(1000000U, radio_rmt_dropped_frames(),
                                radio_rmt_total_edges(), PROTO_LINK_ALL);
    }
}
