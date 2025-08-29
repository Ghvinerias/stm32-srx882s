#pragma once
#include <stdint.h>
#include <stdbool.h>

typedef enum {
    PROTO_UNKNOWN = 0,
    PROTO_EV1527,
    PROTO_PT2262,
    PROTO_OREGON2,
    PROTO_ROLLING_UNKNOWN
} proto_t;

typedef struct {
    // Pulse accumulation
    uint32_t pulses[1024];
    uint16_t count;

    // Classification state
    proto_t current;
    uint32_t symbol_us;
} ProtocolCtx;

void classifier_init(ProtocolCtx* ctx);
void classifier_feed(ProtocolCtx* ctx, uint32_t pulse_us);