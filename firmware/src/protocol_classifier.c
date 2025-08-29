#include "protocol_classifier.h"
#include <stddef.h>
#include "pulse_utils.h"


// Simple heuristics:
// - EV1527/PT2262: pulse pairs with short high (~300-500us) and long low (~900-1200us) repeated
// - Manchester: alternating similar durations
// - Rolling codes: long sequences > 64 bits and inconsistent repeats

classification_t classify_pulses(const uint32_t *pulses, size_t npulses) {
    classification_t res = { .proto = PROTO_UNKNOWN, .nbits = 0, .is_repeated = 0, .symbol_us = 0 };
    if (!pulses || npulses < 4) return res;

    // Use pulses_to_bits to get a mode and symbol estimate
    uint8_t bits[128] = {0};
    pulse_mode_t mode = PULSE_MODE_UNKNOWN;
    size_t nbits = pulses_to_bits(pulses, npulses, bits, 0, &mode);
    res.nbits = nbits;
    // estimate symbol time by averaging pairs
    size_t pairs = npulses/2;
    uint64_t sum = 0;
    for (size_t i=0;i<pairs;i++) sum += pulses[2*i] + pulses[2*i+1];
    res.symbol_us = (uint32_t)(pairs ? (sum / pairs) : 0);

    // Use mode heuristics
    if (mode == PULSE_MODE_MANCHESTER) {
        res.proto = PROTO_MANCHESTER;
        return res;
    }

    if (nbits >= 64) {
        // long frames likely rolling-code or sensor packet
        res.proto = PROTO_UNKNOWN_ROLLING;
        return res;
    }

    // EV1527 frames are often around 24-36 bits with symbols ~1000us
    if (nbits > 12 && nbits <= 48) {
        if (res.symbol_us >= 600 && res.symbol_us <= 1400) {
            // prefer EV1527 for shorter medians
            res.proto = PROTO_EV1527;
            return res;
        }
    }

    // PT2262 often has longer symbols and/or configurable bit lengths
    if (nbits > 32 && res.symbol_us > 900) {
        res.proto = PROTO_PT2262;
        return res;
    }

    res.proto = PROTO_UNKNOWN;
    return res;
}
#include "protocol_classifier.h"

// TODO: Implement protocol classification logic
