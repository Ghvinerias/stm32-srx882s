#pragma once
#include <stdint.h>
#include <stddef.h>
#include "protocol_classifier.h"
#include "pulse_utils.h"

typedef struct {
    proto_t proto;
    size_t nbits;
    uint8_t payload[16]; // up to 128 bits
} decode_result_t;

// Try to decode bits into payload for known protocols.
// bits: array of 0/1 values, nbits length
// out: decode_result_t filled on success; returns 1 on success, 0 otherwise
int decode_bits(const uint8_t *bits, size_t nbits, decode_result_t *out, proto_t hint);

// Convert decode_result to a JSON-ish string in buffer (buflen length)
// Returns number of bytes written (excluding null) or -1 on error
int decode_result_to_json(const decode_result_t *res, char *buf, size_t buflen, uint32_t symbol_us, pulse_mode_t mode);
#pragma once

// TODO: Protocol decoder API
