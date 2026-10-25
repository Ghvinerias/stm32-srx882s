#include "keeloq.h"

// Nonlinear feedback lookup: five state taps select one bit.
#define KEELOQ_NLF 0x3A5C742EU

// The 32-bit state shifts right one bit per round and the bit that falls off
// the bottom is folded back into the feedback, which is what makes the round a
// permutation:
//
//     tap_index = (s31, s26, s20, s9, s1)
//     feedback  = NLF[tap_index] ^ s16 ^ s0 ^ key[round % 64]
//     s'        = (s >> 1) | (feedback << 31)
//
// Dropping the s0 term discards state and the cipher stops being invertible.
static uint8_t keeloq_lookup(uint8_t index) {
    return (uint8_t)((KEELOQ_NLF >> index) & 1U);
}

uint32_t keeloq_encrypt(uint32_t data, uint64_t key) {
    for (uint16_t round = 0; round < KEELOQ_ROUNDS; ++round) {
        const uint8_t index = (uint8_t)((((data >> 31) & 1U) << 4) |
                                        (((data >> 26) & 1U) << 3) |
                                        (((data >> 20) & 1U) << 2) |
                                        (((data >> 9) & 1U) << 1) |
                                        ((data >> 1) & 1U));
        const uint32_t feedback =
            (uint32_t)keeloq_lookup(index) ^ ((data >> 16) & 1U) ^
            (data & 1U) ^ (uint32_t)((key >> (round % 64U)) & 1U);
        data = (data >> 1) | (feedback << 31);
    }
    return data;
}

uint32_t keeloq_decrypt(uint32_t data, uint64_t key) {
    for (uint16_t round = 0; round < KEELOQ_ROUNDS; ++round) {
        const uint8_t index = (uint8_t)((((data >> 30) & 1U) << 4) |
                                        (((data >> 25) & 1U) << 3) |
                                        (((data >> 19) & 1U) << 2) |
                                        (((data >> 8) & 1U) << 1) |
                                        (data & 1U));
        const uint32_t feedback =
            (uint32_t)keeloq_lookup(index) ^ ((data >> 31) & 1U) ^
            ((data >> 15) & 1U) ^
            (uint32_t)((key >> ((KEELOQ_ROUNDS - 1U - round) % 64U)) & 1U);
        data = (data << 1) | (feedback & 1U);
    }
    return data;
}
