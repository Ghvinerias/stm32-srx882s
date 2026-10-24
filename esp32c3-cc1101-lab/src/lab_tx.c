#include "lab_tx.h"

#include <stddef.h>

// Edge-to-edge durations, in microseconds, from the first complete frame in
// tools/captures/keeloq-button1.ndjson.  Identical to the STM32 table: the
// frame has already been transmitted by the original remote, so a conforming
// rolling-code receiver must reject it as stale.
static const uint16_t stale_frame_us[] = {
    785,  758,  758,  761,  757,  759,  761,  754,  761,  762,  757,  4516,
    795,  1481, 797,  1477, 1139, 797,  1139, 1136, 1482, 1140, 1136, 797,
    1480, 795,  1136, 1483, 1140, 1137, 799,  1478, 798,  1482, 795,  1136,
    1137, 1482, 799,  1136, 1137, 1480, 795,  1482, 1141, 795,  1136, 1481,
    1141, 1136, 1137, 1141, 1140, 795,  1137, 1482, 1139, 1137, 796,  1139,
    1482, 796,  1480, 796,  1137, 1485, 792,  1139, 1136, 1141, 1137, 1485,
    795,  1477, 1141, 1137, 1140,
};

#define STALE_FRAME_PULSES \
    (sizeof(stale_frame_us) / sizeof(stale_frame_us[0]))

#define ORIGINAL_SERIAL 0x0FB58C0CU
#define COPIER_SERIAL 0x01114F9AU
#define ORIGINAL_BUTTON_1 0x02U
#define STALE_HOPPING 0xBBA8D1CAU

static lab_tx_emit_fn emit_fn;
static void *emit_context;
static uint16_t plaintext_counter = 1U;
static uint32_t prng_state = 0x6D2B79F5U;

void lab_tx_init(lab_tx_emit_fn emit, void *context) {
    emit_fn = emit;
    emit_context = context;
}

void lab_tx_build_bits(uint8_t bits[LAB_CODEWORD_BITS], uint32_t hopping,
                       uint32_t serial, uint8_t button, uint8_t frame_index) {
    serial &= 0x0FFFFFFFU;
    button &= 0x0FU;

    for (uint8_t i = 0; i < 32U; ++i) {
        bits[i] = (uint8_t)((hopping >> i) & 1U);
    }
    for (uint8_t i = 0; i < 28U; ++i) {
        bits[32U + i] = (uint8_t)((serial >> i) & 1U);
    }
    for (uint8_t i = 0; i < 4U; ++i) {
        bits[60U + i] = (uint8_t)((button >> i) & 1U);
    }
    bits[64] = 0U;                                // VLOW
    bits[65] = frame_index == 0U ? 0U : 1U;       // repeat
}

size_t lab_tx_build_waveform(uint32_t hopping, uint32_t serial, uint8_t button,
                             uint8_t frame_count, uint16_t *pulses,
                             size_t capacity) {
    if (pulses == NULL || frame_count == 0U) {
        return 0;
    }

    size_t count = 0;
    for (uint8_t frame = 0; frame < frame_count; ++frame) {
        if (frame != 0U) {
            if (count >= capacity) {
                return 0;
            }
            pulses[count++] = (uint16_t)LAB_INTER_FRAME_GAP_US;
        }

        uint8_t bits[LAB_CODEWORD_BITS];
        lab_tx_build_bits(bits, hopping, serial, button, frame);

        // Twelve preamble edges produce eleven measured edge-to-edge
        // intervals.  Preamble finishes low.
        for (uint8_t i = 0; i < LAB_PREAMBLE_INTERVALS; ++i) {
            if (count >= capacity) {
                return 0;
            }
            pulses[count++] = (uint16_t)LAB_PREAMBLE_EDGE_US;
        }

        // The first data transition occurs earlier or later in the cell
        // depending on bit zero.
        if (count >= capacity) {
            return 0;
        }
        pulses[count++] = bits[0] ? (uint16_t)LAB_HEADER_BIT_ONE_US
                                  : (uint16_t)LAB_HEADER_BIT_ZERO_US;

        for (uint8_t i = 0; i < LAB_CODEWORD_BITS - 1U; ++i) {
            uint16_t duration = (uint16_t)LAB_DATA_MID_US;
            if (!bits[i] && bits[i + 1U]) {
                duration = (uint16_t)LAB_DATA_SHORT_US;
            } else if (bits[i] && !bits[i + 1U]) {
                duration = (uint16_t)LAB_DATA_LONG_US;
            }
            if (count >= capacity) {
                return 0;
            }
            pulses[count++] = duration;
        }
    }

    return count;
}

static int emit_waveform(uint32_t hopping, uint32_t serial, uint8_t button,
                         uint8_t frame_count) {
    if (!emit_fn || frame_count == 0U) {
        return 0;
    }
    uint16_t pulses[LAB_TX_MAX_PULSES];
    size_t count = lab_tx_build_waveform(hopping, serial, button, frame_count,
                                        pulses, LAB_TX_MAX_PULSES);
    if (count == 0U) {
        return 0;
    }
    return emit_fn(emit_context, pulses, count) ? 1 : 0;
}

size_t lab_tx_stale_frame(const uint16_t **pulses) {
    if (pulses) {
        *pulses = stale_frame_us;
    }
    return STALE_FRAME_PULSES;
}

int lab_tx_send_stale_replay(void) {
    if (!emit_fn) {
        return 0;
    }
    return emit_fn(emit_context, stale_frame_us, STALE_FRAME_PULSES) ? 1 : 0;
}

int lab_tx_send_codeword(uint32_t hopping, uint32_t serial, uint8_t button,
                         uint8_t frame_count) {
    return emit_waveform(hopping, serial, button, frame_count);
}

int lab_tx_run_scenario(uint8_t command) {
    switch (command) {
    case 'r':
        return lab_tx_send_stale_replay();
    case 's':
        return lab_tx_send_codeword(STALE_HOPPING, ORIGINAL_SERIAL,
                                    ORIGINAL_BUTTON_1, 3U);
    case 'z':
        return lab_tx_send_codeword(0U, ORIGINAL_SERIAL, ORIGINAL_BUTTON_1, 3U);
    case 'p': {
        // HCS301 plaintext organization before encryption: sync[15:0],
        // discrimination[25:16], overflow[27:26], button[31:28].
        uint32_t hopping = (uint32_t)plaintext_counter++ |
                           ((ORIGINAL_SERIAL & 0x3FFU) << 16) |
                           ((uint32_t)ORIGINAL_BUTTON_1 << 28);
        return lab_tx_send_codeword(hopping, ORIGINAL_SERIAL,
                                    ORIGINAL_BUTTON_1, 3U);
    }
    case 'x':
        prng_state ^= prng_state << 13;
        prng_state ^= prng_state >> 17;
        prng_state ^= prng_state << 5;
        return lab_tx_send_codeword(prng_state, ORIGINAL_SERIAL,
                                    ORIGINAL_BUTTON_1, 3U);
    case 'd':
        return lab_tx_send_codeword(0U, COPIER_SERIAL, ORIGINAL_BUTTON_1, 3U);
    default:
        return 0;
    }
}
