#if defined(BUILD_HOST)

#include <stdio.h>
#include "protocol_classifier.h"
#include "protocol_decoder.h"
#include "pulse_utils.h"

int main(void) {
    const uint32_t pulses[] = {
        350, 950, 350, 950, 950, 350, 350, 950,
        350, 950, 950, 350, 350, 950, 950, 350,
        350, 950, 350, 950, 950, 350, 350, 950,
        350, 950, 950, 350, 350, 950, 950, 350,
    };
    uint8_t bits[128] = {0};
    pulse_mode_t mode = PULSE_MODE_UNKNOWN;
    size_t nbits = pulses_to_bits(pulses, sizeof(pulses) / sizeof(pulses[0]),
                                  bits, sizeof(bits), 0, &mode);
    classification_t classification = classify_pulses(
        pulses, sizeof(pulses) / sizeof(pulses[0]));
    decode_result_t result;
    char output[256];
    if (decode_bits(bits, nbits, &result, classification.proto)) {
        decode_result_to_json(&result, output, sizeof(output),
                              classification.symbol_us, mode);
        fputs(output, stdout);
    }
    return 0;
}

#else

#include "stm32f1xx_hal.h"
#include "command_rx.h"
#include "hw_capture.h"
#include "lab_tx.h"
#include "system_clock.h"
#include "transport.h"
#include "uart_out.h"
#include "usb_cdc.h"

// Capture streaming (device -> host) and command input (host -> device)
// now share the native USB CDC port at all times -- no mode-select pin.
// Both directions speak the same COBS+CRC16-framed binary protocol (see
// transport.h/command_rx.h), so there's no free-text console protocol left
// to collide with the packet stream, unlike the earlier design where a
// human-readable console and the binary capture stream both wanted the
// same endpoint.
//
// firmware/src/lab_console.c (the old text console) is unused now and
// left in the tree only for reference; nothing calls it.
//
// UART1/PA9-PA10 is still initialized and the single-byte scenario
// trigger on its RX line still works, as an independent secondary path --
// it's a separate physical wire and was never part of this contention.

int main(void) {
    HAL_Init();
    int clock_ok = system_clock_configure();

    uint16_t boot_flags = clock_ok ? 0U : TRANSPORT_FLAG_CLOCK_FALLBACK;
    int uart_ok = uart_init();
    int capture_ok = hw_capture_init();
    int tx_ok = lab_tx_init();
    int usb_ok = usb_cdc_init();
    command_rx_init();
    if (!capture_ok || !hw_capture_start()) {
        capture_ok = 0;
        boot_flags |= TRANSPORT_FLAG_CAPTURE_INIT_FAILED;
    }

    transport_init(boot_flags);
    if (usb_ok) {
        transport_send_status(system_clock_hz(), hw_capture_dropped_frames(),
                              hw_capture_total_edges());
    }

#if defined(LAB_TX_STALE_REPLAY_ON_BOOT) && LAB_TX_STALE_REPLAY_ON_BOOT
    // Explicit lab-only build option. Emit exactly once after reset; never
    // repeat autonomously. The captured frame is stale and should be rejected
    // by a conforming rolling-code receiver.
    if (capture_ok && tx_ok) {
        HAL_Delay(1500U);
        lab_tx_send_stale_replay();
    }
#else
    (void)tx_ok;
#endif

    capture_frame_t frame;
    uint32_t last_status_tick = HAL_GetTick();
    while (1) {
        if (tx_ok && usb_ok) {
            command_rx_poll();
        }
        // Independent secondary path: single-byte scenario triggering over
        // UART RX, unrelated to the USB CDC protocol above.
        if (tx_ok && uart_ok) {
            uint8_t command;
            while (uart_read_byte(&command)) {
                lab_tx_run_scenario(command);
            }
        }
        usb_cdc_poll();
        if (capture_ok && hw_capture_poll(&frame)) {
            transport_send_frame(&frame, hw_capture_dropped_frames());
        }
        uint32_t now = HAL_GetTick();
        if (usb_ok && (now - last_status_tick) >= 1000U) {
            last_status_tick = now;
            transport_send_status(system_clock_hz(), hw_capture_dropped_frames(),
                                  hw_capture_total_edges());
        }
        __WFI();
    }
}

#endif
