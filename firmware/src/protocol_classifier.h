#pragma once
#include <stdint.h>
#include <stddef.h>

typedef enum {
    PROTO_UNKNOWN = 0,
    PROTO_EV1527,
    PROTO_PT2262,
    PROTO_MANCHESTER,
    PROTO_UNKNOWN_ROLLING,
} proto_t;

typedef struct {
    proto_t proto;
    size_t nbits;
    int is_repeated;
    uint32_t symbol_us; // approximate symbol time
} classification_t;

// Classify pulses (high/low alternating durations in us)
classification_t classify_pulses(const uint32_t *pulses, size_t npulses);
#pragma once

// TODO: Protocol classifier API
