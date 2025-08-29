#include "protocol_decoder.h"
#include <string.h>
#include <stdio.h>

// Helper: pack bits (0/1) into bytes (MSB-first)
static void pack_bits_into_bytes(const uint8_t *bits, size_t nbits, uint8_t *out) {
    memset(out, 0, 16);
    for (size_t i = 0; i < nbits && i < 128; ++i) {
        if (bits[i]) out[i/8] |= (1 << (7 - (i%8)));
    }
}

// Try to decode EV1527: look for a repeated frame within the bitstream (common for cheap remotes)
// We search for a candidate frame length L between 12 and 36 bits where the first L bits repeat immediately.
static int try_decode_ev1527_from_bits(const uint8_t *bits, size_t nbits, decode_result_t *out) {
    if (nbits < 12) return 0;
    for (size_t L = 12; L <= 36 && L*2 <= nbits; ++L) {
        int match = 1;
        for (size_t i = 0; i < L; ++i) {
            if (bits[i] != bits[i + L]) { match = 0; break; }
        }
        if (match) {
            out->nbits = L;
            out->proto = PROTO_EV1527;
            pack_bits_into_bytes(bits, L, out->payload);
            return 1;
        }
    }
    return 0;
}

int decode_bits(const uint8_t *bits, size_t nbits, decode_result_t *out, proto_t hint) {
    if (!bits || !out || nbits == 0) return 0;
    // If hint says EV1527, try the EV1527-specific routine
    if (hint == PROTO_EV1527) {
        if (try_decode_ev1527_from_bits(bits, nbits, out)) return 1;
        // else fallthrough to generic
    }
    // Generic fallback: pack and pick a proto by size
    out->nbits = nbits;
    out->proto = (nbits <= 32) ? PROTO_EV1527 : PROTO_PT2262;
    pack_bits_into_bytes(bits, nbits, out->payload);
    return 1;
}

int decode_result_to_json(const decode_result_t *res, char *buf, size_t buflen, uint32_t symbol_us, pulse_mode_t mode) {
    if (!res || !buf) return -1;
    const char *pname = "UNKNOWN";
    switch (res->proto) {
        case PROTO_EV1527: pname = "EV1527"; break;
        case PROTO_PT2262: pname = "PT2262"; break;
        case PROTO_MANCHESTER: pname = "MANCHESTER"; break;
        case PROTO_UNKNOWN_ROLLING: pname = "UNKNOWN-ROLLING"; break;
        default: pname = "UNKNOWN"; break;
    }
    const char *mode_s = "?";
    switch (mode) {
        case PULSE_MODE_MANCHESTER: mode_s = "MANCHESTER"; break;
        case PULSE_MODE_PULSE_DISTANCE: mode_s = "PULSE_DISTANCE"; break;
        case PULSE_MODE_PWM: mode_s = "PWM"; break;
        default: mode_s = "UNKNOWN"; break;
    }

    // hex payload
    char payload_hex[64] = {0};
    size_t nbytes = (res->nbits + 7)/8;
    for (size_t i=0;i<nbytes && i<16;i++) sprintf(payload_hex + i*2, "%02X", res->payload[i]);

    int written = snprintf(buf, buflen, "{\"proto\":\"%s\",\"bits\":%zu,\"symbol_us\":%u,\"mode\":\"%s\",\"payload\":\"%s\"}\n",
                           pname, res->nbits, (unsigned)symbol_us, mode_s, payload_hex);
    return (written >= 0 && (size_t)written < buflen) ? written : -1;
}

