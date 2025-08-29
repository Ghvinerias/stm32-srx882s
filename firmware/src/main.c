#include <stdio.h>
#include <string.h>
#include "pulse_utils.h"
#include "protocol_classifier.h"
#include "protocol_decoder.h"
#include "hw_capture.h"
#include "uart_out.h"

int main(void) {
    // Simulate three captures: EV1527-like, PT2262-like, and a rolling-code-like long frame
    uint32_t pulses_ev[] = {350,950,350,950,350,950,350,950};
    uint32_t pulses_pt[] = {500,900,500,900,1200,400,500,900};
    uint32_t pulses_roll[140];
    for (int i=0;i<140;i++) pulses_roll[i] = (i%2==0)?400:600; // 70 pairs -> 70 bits -> flagged rolling

    struct { const uint32_t *p; size_t n; } sims[] = {
        {pulses_ev, sizeof(pulses_ev)/sizeof(pulses_ev[0])},
        {pulses_pt, sizeof(pulses_pt)/sizeof(pulses_pt[0])},
        {pulses_roll, sizeof(pulses_roll)/sizeof(pulses_roll[0])},
    };

    hw_capture_init();
    hw_capture_start();
    uart_init();

    while (1) {
#ifdef BUILD_HOST
        // Host: reuse simulated frames
        for (int s=0;s<3;s++) {
            const uint32_t *p = sims[s].p;
            size_t np = sims[s].n;
            classification_t cls = classify_pulses(p, np);
            uint8_t bits[128] = {0};
            pulse_mode_t mode = PULSE_MODE_UNKNOWN;
            size_t nbits = pulses_to_bits(p, np, bits, 0, &mode);

            decode_result_t res;
            int ok = decode_bits(bits, nbits, &res, cls.proto);
            char out[256] = {0};
            if (ok) decode_result_to_json(&res, out, sizeof(out), cls.symbol_us, mode);
#ifdef BUILD_HOST
            printf("%s", ok ? out : "{\"proto\":\"UNKNOWN\"}\n");
#else
            uart_send(ok ? out : "{\"proto\":\"UNKNOWN\"}\n");
#endif
        }
        break; // run once on host
#else
        // Production: read real pulses from HW capture
        uint32_t buf[512];
        size_t n = hw_capture_fetch_pulses(buf, 512);
        if (n == 0) continue;
        classification_t cls = classify_pulses(buf, n);
        uint8_t bits[128] = {0};
        pulse_mode_t mode = PULSE_MODE_UNKNOWN;
        size_t nbits = pulses_to_bits(buf, n, bits, 0, &mode);
        decode_result_t res;
        int ok = decode_bits(bits, nbits, &res, cls.proto);
        char out[256] = {0};
    if (ok) decode_result_to_json(&res, out, sizeof(out), cls.symbol_us, mode);
#ifdef BUILD_HOST
    printf("%s", ok ? out : "{\"proto\":\"UNKNOWN\"}\n");
#else
    uart_send(ok ? out : "{\"proto\":\"UNKNOWN\"}\n");
#endif
#endif
    }
    return 0;
}
