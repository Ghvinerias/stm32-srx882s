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
#include "hw_capture.h"
#include "lab_console.h"
#include "lab_tx.h"
#include "system_clock.h"
#include "transport.h"
#include "uart_out.h"
#include "usb_cdc.h"

int main(void) {
    HAL_Init();
    int clock_ok = system_clock_configure();

    uint16_t boot_flags = clock_ok ? 0U : TRANSPORT_FLAG_CLOCK_FALLBACK;
    int uart_ok = uart_init();
    int capture_ok = hw_capture_init();
    int tx_ok = lab_tx_init();
    int usb_ok = usb_cdc_init();
    lab_console_init();
    if (!capture_ok || !hw_capture_start()) {
        capture_ok = 0;
        boot_flags |= TRANSPORT_FLAG_CAPTURE_INIT_FAILED;
    }

    transport_init(boot_flags);
    if (uart_ok) {
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

    (void)usb_ok;

    capture_frame_t frame;
    uint32_t last_status_tick = HAL_GetTick();
    while (1) {
        if (tx_ok && usb_ok) {
            lab_console_poll();
        }
        if (tx_ok && uart_ok) {
            uint8_t command;
            while (uart_read_byte(&command)) {
                lab_tx_run_scenario(command);
            }
        }
        if (capture_ok && hw_capture_poll(&frame)) {
            transport_send_frame(&frame, hw_capture_dropped_frames());
        }
        uint32_t now = HAL_GetTick();
        if (uart_ok && (now - last_status_tick) >= 1000U) {
            last_status_tick = now;
            transport_send_status(system_clock_hz(), hw_capture_dropped_frames(),
                                  hw_capture_total_edges());
        }
        __WFI();
    }
}

#endif
