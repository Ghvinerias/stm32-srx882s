#include "radio_rmt.h"

#include <Arduino.h>
#include <driver/gpio.h>
#include <driver/rmt_rx.h>
#include <hal/gpio_ll.h>
#include <driver/rmt_tx.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>

#include <string.h>

#include "lab_config.h"
#include "lab_tx.h"

#define RMT_RESOLUTION_HZ 1000000U
#define RMT_TX_MEM_SYMBOLS 48U
#define RMT_RX_MEM_SYMBOLS 48U
#define RMT_TX_SYMBOLS ((LAB_TX_MAX_PULSES + 1U) / 2U + 1U)

struct rx_event_t {
    size_t symbol_count;
    int64_t timestamp_us;
    uint32_t seq;
};

static rmt_channel_handle_t tx_channel;
static rmt_encoder_handle_t copy_encoder;
static SemaphoreHandle_t tx_done_semaphore;
static rmt_symbol_word_t tx_symbols[RMT_TX_SYMBOLS];

static rmt_channel_handle_t rx_channel;
static QueueHandle_t rx_queue;
static rmt_symbol_word_t rx_buffer[LAB_RMT_RX_SYMBOLS];
static rmt_symbol_word_t rx_pending[LAB_RMT_RX_SYMBOLS];
static rmt_symbol_word_t rx_claimed[LAB_RMT_RX_SYMBOLS];
static lab_capture_interval_t rx_intervals[LAB_CAPTURE_MAX_PULSES + 2U];
static volatile uint32_t rx_pending_seq;
static portMUX_TYPE rx_mux = portMUX_INITIALIZER_UNLOCKED;
static volatile bool rx_armed;
static volatile uint32_t rx_queue_overflows;
static uint16_t dropped_frames;
static uint32_t arm_failures;
static volatile bool rx_raw_mode;
static uint32_t stat_hcs_reduced;
static volatile uint32_t stat_rx_irq;
static volatile uint32_t stat_rx_symbols;
static uint32_t stat_tx_count;
static uint32_t stat_tx_failures;
static uint32_t stat_last_tx_us;
static uint32_t stat_expected_tx_us;
static uint32_t total_edges;

static bool IRAM_ATTR on_tx_done(rmt_channel_handle_t channel,
                                 const rmt_tx_done_event_data_t *event,
                                 void *user_ctx) {
    (void)channel;
    (void)event;
    (void)user_ctx;
    BaseType_t woken = pdFALSE;
    xSemaphoreGiveFromISR(tx_done_semaphore, &woken);
    return woken == pdTRUE;
}

static bool IRAM_ATTR on_rx_done(rmt_channel_handle_t channel,
                                 const rmt_rx_done_event_data_t *event,
                                 void *user_ctx) {
    (void)channel;
    (void)user_ctx;

    size_t count = event->num_symbols;
    if (count > LAB_RMT_RX_SYMBOLS) {
        count = LAB_RMT_RX_SYMBOLS;
    }
    if (event->received_symbols && count > 0U) {
        memcpy(rx_pending, event->received_symbols,
               count * sizeof(rmt_symbol_word_t));
    }

    stat_rx_irq = stat_rx_irq + 1U;
    stat_rx_symbols = stat_rx_symbols + (uint32_t)event->num_symbols;

    rx_event_t item;
    item.symbol_count = count;
    item.timestamp_us = esp_timer_get_time();
    rx_pending_seq = rx_pending_seq + 1U;
    item.seq = rx_pending_seq;
    rx_armed = false;

    BaseType_t woken = pdFALSE;
    if (xQueueSendFromISR(rx_queue, &item, &woken) != pdPASS) {
        rx_queue_overflows = rx_queue_overflows + 1U;
    }
    return woken == pdTRUE;
}

// GDO0 handling: the CC1101 is configured with IOCFG0 = 0x2E (high
// impedance), so GDO0 is a pure input to the radio in every state (RX data
// comes out on GDO2).  The RMT therefore owns GPIO3 permanently and idles it
// LOW (carrier off) -- no output-enable juggling is needed.

static bool tx_channel_setup(void) {
    rmt_tx_channel_config_t tx_config = {};
    tx_config.gpio_num = (gpio_num_t)LAB_PIN_GDO0_TX;
    tx_config.clk_src = RMT_CLK_SRC_DEFAULT;
    tx_config.resolution_hz = RMT_RESOLUTION_HZ;
    tx_config.mem_block_symbols = RMT_TX_MEM_SYMBOLS;
    tx_config.trans_queue_depth = 4;
    tx_config.flags.init_level = 0; // idle LOW = carrier off
    if (rmt_new_tx_channel(&tx_config, &tx_channel) != ESP_OK) {
        tx_channel = NULL;
        return false;
    }
    rmt_tx_event_callbacks_t tx_callbacks = {};
    tx_callbacks.on_trans_done = on_tx_done;
    if (rmt_tx_register_event_callbacks(tx_channel, &tx_callbacks, NULL) !=
        ESP_OK) {
        return false;
    }
    return rmt_enable(tx_channel) == ESP_OK;
}

bool radio_rmt_init(void) {
    tx_done_semaphore = xSemaphoreCreateBinary();
    if (!tx_done_semaphore) {
        return false;
    }
    rmt_copy_encoder_config_t copy_config = {};
    if (rmt_new_copy_encoder(&copy_config, &copy_encoder) != ESP_OK) {
        return false;
    }
    if (!tx_channel_setup()) {
        return false;
    }

    rmt_rx_channel_config_t rx_config = {};
    rx_config.gpio_num = (gpio_num_t)LAB_PIN_GDO2_RX;
    rx_config.clk_src = RMT_CLK_SRC_DEFAULT;
    rx_config.resolution_hz = RMT_RESOLUTION_HZ;
    rx_config.mem_block_symbols = RMT_RX_MEM_SYMBOLS;
    if (rmt_new_rx_channel(&rx_config, &rx_channel) != ESP_OK) {
        return false;
    }

    rmt_rx_event_callbacks_t rx_callbacks = {};
    rx_callbacks.on_recv_done = on_rx_done;
    if (rmt_rx_register_event_callbacks(rx_channel, &rx_callbacks, NULL) !=
        ESP_OK) {
        return false;
    }

    rx_queue = xQueueCreate(4, sizeof(rx_event_t));
    if (!rx_queue) {
        return false;
    }

    gpio_set_pull_mode((gpio_num_t)LAB_PIN_GDO2_RX, GPIO_FLOATING);
    if (rmt_enable(rx_channel) != ESP_OK) {
        return false;
    }

    return true;
}

static size_t pack_symbols(const uint16_t *pulses, size_t pulse_count) {
    size_t symbol_count = 0;
    for (size_t i = 0; i < pulse_count; i += 2U) {
        rmt_symbol_word_t *symbol = &tx_symbols[symbol_count++];
        symbol->duration0 = pulses[i];
        symbol->level0 = (i & 1U) == 0U ? 1U : 0U;
        if (i + 1U < pulse_count) {
            symbol->duration1 = pulses[i + 1U];
            symbol->level1 = ((i + 1U) & 1U) == 0U ? 1U : 0U;
        } else {
            symbol->duration1 = 0U;
            symbol->level1 = symbol->level0;
        }
    }
    return symbol_count;
}

int radio_rmt_transmit(const uint16_t *pulses, size_t pulse_count) {
    if (!tx_channel || !copy_encoder || !tx_done_semaphore || !pulses ||
        pulse_count == 0U || pulse_count > LAB_TX_MAX_PULSES) {
        return 0;
    }

    size_t symbol_count = pack_symbols(pulses, pulse_count);

    uint64_t duration_us = 0;
    for (size_t i = 0; i < pulse_count; ++i) {
        duration_us += pulses[i];
    }
    uint32_t timeout_ms = (uint32_t)(duration_us / 1000U) + 250U;

    // Drain any stale completion before starting.
    (void)xSemaphoreTake(tx_done_semaphore, 0);

    int64_t tx_started_us = esp_timer_get_time();

    rmt_transmit_config_t transmit_config = {};
    transmit_config.loop_count = 0;
    transmit_config.flags.eot_level = 0;

    esp_err_t err = rmt_transmit(
        tx_channel, copy_encoder, tx_symbols,
        symbol_count * sizeof(rmt_symbol_word_t), &transmit_config);
    int ok = 0;
    if (err == ESP_OK &&
        xSemaphoreTake(tx_done_semaphore, pdMS_TO_TICKS(timeout_ms)) ==
            pdPASS) {
        ok = 1;
    }

    if (!ok) {
        // Abort and re-arm the channel so the next attempt starts clean.
        (void)rmt_disable(tx_channel);
        (void)rmt_enable(tx_channel);
    }

    stat_last_tx_us = (uint32_t)(esp_timer_get_time() - tx_started_us);
    stat_expected_tx_us = (uint32_t)duration_us;
    stat_tx_count++;
    if (!ok) {
        stat_tx_failures++;
    }
    return ok;
}

bool radio_rmt_rx_arm(void) {
    if (!rx_channel || rx_armed) {
        return rx_armed;
    }

    rmt_receive_config_t receive_config = {};
    receive_config.signal_range_min_ns = LAB_RMT_FILTER_NS;
    receive_config.signal_range_max_ns = LAB_CAPTURE_IDLE_MAX_NS;
    receive_config.flags.en_partial_rx = 0;

    esp_err_t err = rmt_receive(rx_channel, rx_buffer, sizeof(rx_buffer),
                               &receive_config);
    if (err == ESP_OK) {
        rx_armed = true;
    } else {
        arm_failures++;
    }
    return rx_armed;
}

bool radio_rmt_rx_armed(void) { return rx_armed; }

void radio_rmt_rx_suspend(void) {
    if (!rx_channel) {
        return;
    }
    (void)rmt_disable(rx_channel);
    rx_armed = false;
}

void radio_rmt_rx_resume(void) {
    if (!rx_channel) {
        return;
    }
    (void)rmt_enable(rx_channel);
    rx_armed = false;
    (void)radio_rmt_rx_arm();
}

int radio_rmt_rx_pop(capture_frame_t *out) {
    if (!out || !rx_queue) {
        return 0;
    }

    rx_event_t event;
    if (xQueueReceive(rx_queue, &event, 0) != pdPASS) {
        return 0;
    }

    // The ISR writes into a single staging buffer, so an event is only valid
    // while it is still the most recent reception.  A stale event (one that
    // completed just before a suspend/resume cycle armed the next reception)
    // must not be paired with newer symbols.  Claim the buffer under the
    // spinlock so the RX ISR cannot overwrite it while being copied out.
    portENTER_CRITICAL(&rx_mux);
    if (event.seq != rx_pending_seq) {
        portEXIT_CRITICAL(&rx_mux);
        return 0;
    }
    if (event.symbol_count > 0U) {
        memcpy(rx_claimed, rx_pending,
               event.symbol_count * sizeof(rmt_symbol_word_t));
    }
    portEXIT_CRITICAL(&rx_mux);

    // Flatten RMT symbols (level+duration pairs) into edge-to-edge intervals.
    // Consecutive same-level pieces are merged: they mean the hardware
    // filtered a sub-threshold glitch rather than a real edge.  File-scope so
    // the 2 KiB array never lands on the Arduino loop task's stack.
    size_t count = 0;

    for (size_t i = 0; i < event.symbol_count; ++i) {
        const rmt_symbol_word_t *symbol = &rx_claimed[i];
        uint32_t pair[2] = {symbol->duration0, symbol->duration1};
        uint8_t pair_level[2] = {(uint8_t)(symbol->level0 & 1U),
                                 (uint8_t)(symbol->level1 & 1U)};
        for (size_t half = 0; half < 2U; ++half) {
            uint32_t duration = pair[half];
            if (duration == 0U) {
                continue;
            }
            if (count > 0U &&
                rx_intervals[count - 1U].level == pair_level[half]) {
                rx_intervals[count - 1U].duration_us += duration;
            } else if (count <
                       sizeof(rx_intervals) / sizeof(rx_intervals[0])) {
                rx_intervals[count].duration_us = duration;
                rx_intervals[count].level = pair_level[half];
                count++;
            }
        }
    }

    // The hardware filter only goes up to ~3 us; drop the remaining sub-20 us
    // slicer chatter here.
    count = lab_capture_filter_glitches(rx_intervals, count,
                                        LAB_CAPTURE_MIN_PULSE_NS / 1000U);

    // The last interval is the idle gap that ended the reception; the shared
    // portable helper removes it, applies the minimum-pulse rule and derives
    // the first-edge timestamp, exactly like lab_capture_poll() does.
    if (!lab_capture_finalize(rx_intervals, count,
                              (uint32_t)event.timestamp_us, out)) {
        if (dropped_frames != UINT16_MAX) {
            dropped_frames++;
        }
        return 0;
    }

    total_edges += (uint32_t)out->pulse_count + 1U;
    if (!rx_raw_mode && lab_capture_reduce_hcs301(out)) {
        stat_hcs_reduced++;
    }
    return 1;
}

uint16_t radio_rmt_dropped_frames(void) {
    uint32_t dropped = (uint32_t)dropped_frames + rx_queue_overflows;
    return dropped > UINT16_MAX ? UINT16_MAX : (uint16_t)dropped;
}

uint32_t radio_rmt_total_edges(void) { return total_edges; }

uint32_t radio_rmt_arm_failures(void) { return arm_failures; }

radio_rmt_stats_t radio_rmt_stats(void) {
    radio_rmt_stats_t stats;
    stats.rx_irq = stat_rx_irq;
    stats.rx_symbols = stat_rx_symbols;
    stats.tx_count = stat_tx_count;
    stats.tx_failures = stat_tx_failures;
    stats.last_tx_us = stat_last_tx_us;
    stats.expected_tx_us = stat_expected_tx_us;
    return stats;
}

void radio_rmt_gpio_state(radio_rmt_gpio_t *out) {
    gpio_dev_t *hw = GPIO_LL_GET_HW(0);
    out->level_gdo0 = (uint8_t)gpio_ll_get_level(hw, LAB_PIN_GDO0_TX);
    out->level_gdo2 = (uint8_t)gpio_ll_get_level(hw, LAB_PIN_GDO2_RX);
    out->enable_gdo0 = (uint8_t)((hw->enable.val >> LAB_PIN_GDO0_TX) & 1U);
    out->oen_sel_gdo0 = (uint8_t)hw->func_out_sel_cfg[LAB_PIN_GDO0_TX].oen_sel;
    out->out_sel_gdo0 =
        (uint16_t)hw->func_out_sel_cfg[LAB_PIN_GDO0_TX].out_sel;
}

uint32_t radio_rmt_count_edges(uint32_t ms, uint32_t *high_permille) {
    gpio_dev_t *hw = GPIO_LL_GET_HW(0);
    uint32_t edges = 0, high = 0, samples = 0;
    int last = gpio_ll_get_level(hw, LAB_PIN_GDO2_RX);
    int64_t end_us = esp_timer_get_time() + (int64_t)ms * 1000;
    while (esp_timer_get_time() < end_us) {
        for (int i = 0; i < 200; ++i) {
            int level = gpio_ll_get_level(hw, LAB_PIN_GDO2_RX);
            edges += (uint32_t)(level != last);
            last = level;
            high += (uint32_t)level;
            ++samples;
        }
    }
    if (high_permille) {
        *high_permille = samples ? (high * 1000U) / samples : 0U;
    }
    return edges;
}

int radio_rmt_gpio_bitbang(const uint16_t *pulses, size_t pulse_count) {
    if (!pulses || pulse_count == 0U) {
        return 0;
    }
    if (tx_channel) {
        (void)rmt_disable(tx_channel);
        (void)rmt_del_channel(tx_channel);
        tx_channel = NULL;
    }
    pinMode(LAB_PIN_GDO0_TX, OUTPUT);
    int level = 1;
    for (size_t i = 0; i < pulse_count; ++i) {
        digitalWrite(LAB_PIN_GDO0_TX, level);
        delayMicroseconds(pulses[i]);
        level ^= 1;
    }
    digitalWrite(LAB_PIN_GDO0_TX, LOW);
    return tx_channel_setup() ? 1 : 0;
}

void radio_rmt_set_raw_mode(bool raw) { rx_raw_mode = raw; }
bool radio_rmt_raw_mode(void) { return rx_raw_mode; }
uint32_t radio_rmt_hcs_reduced(void) { return stat_hcs_reduced; }
