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

size_t lab_tx_pwm_frame(const uint8_t bits[LAB_CODEWORD_BITS], uint16_t *pulses,
                        size_t capacity) {
    if (!bits || !pulses || capacity < LAB_FRAME_PULSES) {
        return 0;
    }
    size_t n = 0;
    // Preamble: 12 highs of 1 TE separated by 1 TE lows; the 12th low is the
    // 10 TE header.
    for (unsigned i = 0; i < LAB_PREAMBLE_HIGHS; ++i) {
        pulses[n++] = (uint16_t)LAB_TE_US;
        pulses[n++] = (uint16_t)(i + 1U < LAB_PREAMBLE_HIGHS
                                     ? LAB_TE_US
                                     : LAB_HEADER_LOW_TE * LAB_TE_US);
    }
    for (unsigned i = 0; i < LAB_CODEWORD_BITS; ++i) {
        unsigned high = bits[i] ? 1U : 2U;
        unsigned low = bits[i] ? 2U : 1U;
        pulses[n++] = (uint16_t)(high * LAB_TE_US);
        pulses[n++] = (uint16_t)(low * LAB_TE_US);
    }
    // Stretch the final low into the inter-frame guard.
    pulses[n - 1U] = (uint16_t)LAB_FRAME_IDLE_LOW_US;
    return n;
}

size_t lab_tx_frame_periods(const uint16_t *pulses, size_t count,
                            uint16_t *periods, size_t capacity) {
    if (!pulses || !periods || count < LAB_FRAME_PULSES ||
        capacity < LAB_FRAME_PERIODS) {
        return 0;
    }
    // Falling edges sit at the end of every high (even index) pulse.
    for (size_t k = 0; k < LAB_FRAME_PERIODS; ++k) {
        periods[k] = (uint16_t)(pulses[2U * k + 1U] + pulses[2U * k + 2U]);
    }
    return LAB_FRAME_PERIODS;
}

static uint32_t abs_diff(uint32_t a, uint32_t b) { return a > b ? a - b : b - a; }

int lab_tx_bits_from_periods(const uint16_t *periods, size_t count,
                             uint8_t bits[LAB_CODEWORD_BITS]) {
    if (!periods || !bits || count != LAB_FRAME_PERIODS) {
        return 0;
    }
    // Scale = median data cell / MID (the MID class dominates).
    uint16_t sorted[65];
    for (size_t i = 0; i < 65U; ++i) {
        size_t j = i;
        while (j > 0U && sorted[j - 1U] > periods[12U + i]) {
            sorted[j] = sorted[j - 1U];
            --j;
        }
        sorted[j] = periods[12U + i];
    }
    uint32_t scale_milli = (uint32_t)sorted[32] * 1000U / LAB_DATA_MID_US;
    if (scale_milli < 850U || scale_milli > 1150U) {
        scale_milli = 1000U;
    }
    uint32_t zero = LAB_HEADER_BIT_ZERO_US * scale_milli / 1000U;
    uint32_t one = LAB_HEADER_BIT_ONE_US * scale_milli / 1000U;
    uint32_t header = periods[11];
    uint32_t dz = abs_diff(header, zero), d1 = abs_diff(header, one);
    if (dz > zero / 10U && d1 > one / 10U) {
        return 0;
    }
    bits[0] = dz <= d1 ? 0U : 1U;
    uint32_t sh = LAB_DATA_SHORT_US * scale_milli / 1000U;
    uint32_t md = LAB_DATA_MID_US * scale_milli / 1000U;
    uint32_t lg = LAB_DATA_LONG_US * scale_milli / 1000U;
    for (size_t i = 0; i < 65U; ++i) {
        uint32_t d = periods[12U + i];
        uint32_t es = abs_diff(d, sh), em = abs_diff(d, md), el = abs_diff(d, lg);
        if (es <= em && es <= el) {
            if (es > md / 10U || bits[i] != 0U) return 0;
            bits[i + 1U] = 1U;
        } else if (el <= em && el <= es) {
            if (el > md / 10U || bits[i] != 1U) return 0;
            bits[i + 1U] = 0U;
        } else {
            if (em > md / 10U) return 0;
            bits[i + 1U] = bits[i];
        }
    }
    return 1;
}

size_t lab_tx_build_waveform(uint32_t hopping, uint32_t serial, uint8_t button,
                             uint8_t frame_count, uint16_t *pulses,
                             size_t capacity) {
    if (pulses == NULL || frame_count == 0U ||
        capacity < (size_t)frame_count * LAB_FRAME_PULSES) {
        return 0;
    }
    size_t count = 0;
    for (uint8_t frame = 0; frame < frame_count; ++frame) {
        uint8_t bits[LAB_CODEWORD_BITS];
        lab_tx_build_bits(bits, hopping, serial, button, frame);
        count += lab_tx_pwm_frame(bits, pulses + count, capacity - count);
    }
    return count;
}

static int emit_waveform(uint32_t hopping, uint32_t serial, uint8_t button,
                         uint8_t frame_count) {
    if (!emit_fn || frame_count == 0U) {
        return 0;
    }
    static uint16_t pulses[LAB_TX_MAX_PULSES]; // too big for the loop stack
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
    // The stored capture is in the reduced 77-period form: decode it and
    // transmit it as real PWM.
    uint8_t bits[LAB_CODEWORD_BITS];
    uint16_t pulses[LAB_FRAME_PULSES];
    if (!lab_tx_bits_from_periods(stale_frame_us, STALE_FRAME_PULSES, bits)) {
        return 0;
    }
    size_t count = lab_tx_pwm_frame(bits, pulses, LAB_FRAME_PULSES);
    return count && emit_fn(emit_context, pulses, count) ? 1 : 0;
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
