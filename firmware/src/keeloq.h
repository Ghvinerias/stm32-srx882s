#pragma once

#include <stdint.h>

#define KEELOQ_ROUNDS 528U

// KeeLoq block cipher: 32-bit block, 64-bit key, 528 rounds.
//
// This is the block cipher only -- what an HCS301 does around a hopping
// counter. It deliberately does not include manufacturer-key to device-key
// derivation: a device key is generated once at provisioning time from a
// secret the manufacturer holds, and the firmware never needs that path when
// the key is chosen by whoever owns the key.
//
// A 32-bit key register or an 8-bit table lookup was not used here so that the
// same code runs unchanged on the host test harness and on the target.
uint32_t keeloq_encrypt(uint32_t data, uint64_t key);

// Inverse of keeloq_encrypt. Key bits are consumed in reverse round order.
uint32_t keeloq_decrypt(uint32_t data, uint64_t key);
