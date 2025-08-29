#include <stdio.h>
#include <string.h>
#include "src/pulse_utils.h"
#include "src/protocol_decoder.h"

int main(void) {
    // Build a 24-bit EV1527-like frame repeated twice (simple PWM: short=350us, long=950us)
    uint8_t frame_bits[24] = {1,0,1,1,0,0,1,0, 1,0,0,1,1,0,1,0, 0,1,1,0,1,0,0,1};
    uint32_t pulses[24*2*2 + 10]; // each bit -> pair of pulses (high/low), frame repeated
    size_t idx = 0;
    for (int rep=0; rep<2; rep++) {
        for (size_t i=0;i<24;i++) {
            // pulse-distance style: short high (350us) then short/long low
            pulses[idx++] = 350; // high
            if (frame_bits[i]) pulses[idx++] = 950; else pulses[idx++] = 350;
        }
        // no explicit gap; frames are back-to-back for the unit test
    }

    uint8_t bits[128] = {0};
    pulse_mode_t mode = PULSE_MODE_UNKNOWN;
    size_t nbits = pulses_to_bits(pulses, idx, bits, 0, &mode);
    printf("pulses->bits: nbits=%zu mode=%d\n", nbits, mode);
    printf("bits: ");
    for (size_t i=0;i<nbits;i++) printf("%u", bits[i]);
    printf("\n");

    decode_result_t res;
    int ok = decode_bits(bits, nbits, &res, PROTO_EV1527);
    if (!ok) { printf("EV1527 decode failed\n"); return 1; }
    char out[256];
    decode_result_to_json(&res, out, sizeof(out), 1200, mode);
    printf("decoded: %s", out);

    // Simple check: decoded bits should match original 24-bit frame
    // Extract back bytes from payload and compare bitwise
    uint8_t decoded_bits[24] = {0};
    for (size_t i=0;i<res.nbits && i<24;i++) {
        decoded_bits[i] = (res.payload[i/8] >> (7 - (i%8))) & 1;
    }
    int match = 1;
    for (size_t i=0;i<24;i++) if (decoded_bits[i] != frame_bits[i]) { match = 0; break; }
    printf("test_match=%d\n", match);
    return match ? 0 : 2;
}
