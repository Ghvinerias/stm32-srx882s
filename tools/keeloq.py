#!/usr/bin/env python3
"""Reference KeeLoq block cipher, validated against published test vectors.

KeeLoq is a 64-bit-key, 32-bit-block cipher with 528 rounds, used by Microchip's
HCS200/300/301 code-hopping chips. The algorithm itself is public: the
specifications leaked in 2006 and it has been independently reimplemented
several times. This module is the host-side reference for the firmware port.

The round structure is worth writing down, because the obvious guess is wrong.
The 32-bit state shifts *right* one bit per round, and the bit that falls off
the bottom is folded back into the feedback:

    tap_index = (s31,s26,s20,s9,s1)          # five taps -> NLF lookup index
    feedback  = NLF[tap_index] ^ s16 ^ s0 ^ key[round % 64]
    s'        = (s >> 1) | (feedback << 31)

Folding in s0 is what makes the round a permutation. A left-shifting variant
without that term discards state, so it is not invertible at all -- a version of
this file did exactly that and its inverse failed after three rounds.

Validation uses two independent sources, which is what pins down the
conventions that differ between implementations (tap order, feedback term, key
schedule, byte order). Self-consistency proves nothing: a wrong cipher round
trips happily.

  * liyanboy74/KeeLoq (a fork of hadipourh/KeeLoq) publishes canonical
    (key, plaintext, ciphertext) triples.
  * The NDIAS 2026 "Classic KeeLoq Garage" CTF writeup publishes four
    (challenge key, hop, plaintext) decrypts whose plaintexts are a constant
    discriminant over an incrementing counter.

Note the deliberate omission: the repo this was ported from also exports
manufacturer-key -> device-key derivation helpers. Those are not reproduced
here; they are the provisioning secret and are useless without the manufacturer
key anyway. This file is only the block cipher.
"""

from __future__ import annotations

# Nonlinear feedback lookup: five state taps select one bit.
NLF = 0x3A5C742E

ROUNDS = 528
MASK = 0xFFFFFFFF


def _lookup(index: int) -> int:
    return (NLF >> index) & 1


def _encrypt_round(state: int, key_bit: int) -> int:
    """One forward round: shift right, inject feedback at the top."""
    index = (
        (((state >> 31) & 1) << 4)
        | (((state >> 26) & 1) << 3)
        | (((state >> 20) & 1) << 2)
        | (((state >> 9) & 1) << 1)
        | ((state >> 1) & 1)
    )
    feedback = _lookup(index) ^ ((state >> 16) & 1) ^ (state & 1) ^ key_bit
    return (state >> 1) | ((feedback & 1) << 31)


def _decrypt_round(state: int, key_bit: int) -> int:
    """One reverse round: shift left, inject feedback at the bottom."""
    index = (
        (((state >> 30) & 1) << 4)
        | (((state >> 25) & 1) << 3)
        | (((state >> 19) & 1) << 2)
        | (((state >> 8) & 1) << 1)
        | (state & 1)
    )
    feedback = _lookup(index) ^ ((state >> 31) & 1) ^ ((state >> 15) & 1) ^ key_bit
    return ((state << 1) | (feedback & 1)) & MASK


def encrypt(data: int, key: int) -> int:
    """Encrypt a 32-bit block with a 64-bit key."""
    state = data & MASK
    for loop in range(ROUNDS):
        state = _encrypt_round(state, (key >> (loop % 64)) & 1)
    return state


def decrypt(data: int, key: int) -> int:
    """Decrypt a 32-bit block with a 64-bit key.

    Key bits are consumed in reverse, i.e. round r uses bit (527 - r) % 64.
    """
    state = data & MASK
    for loop in range(ROUNDS):
        key_bit = (key >> ((ROUNDS - 1 - loop) % 64)) & 1
        state = _decrypt_round(state, key_bit)
    return state


# (key, plaintext, ciphertext) from liyanboy74/KeeLoq.
CIPHER_VECTORS = [
    (0x5CEC6701B79FD949, 0xF741E2DB, 0xE44F4CDF),
    (0x5CEC6701B79FD949, 0x0CA69B92, 0xA6AC0EA2),
]

# (key, hop, plaintext) from the NDIAS 2026 CTF writeup: constant 0xADC4
# discriminant, counter incrementing by one per press.
DECRYPT_VECTORS = [
    (0x6D3A91C46CEE3373, 0x913B00D7, 0xADC40040),
    (0x6D3A91C46CEE3373, 0x0020D1FB, 0xADC40041),
    (0x6D3A91C46CEE3373, 0x80ED145B, 0xADC40042),
    (0x6D3A91C46CEE3373, 0x314C03E4, 0xADC40043),
]


def self_test() -> tuple[int, int]:
    """Check every known answer in both directions. Returns (passed, total)."""
    passed = 0
    total = 0

    for key, plain, cipher in CIPHER_VECTORS:
        for name, got, want in (
            ("encrypt", encrypt(plain, key), cipher),
            ("decrypt", decrypt(cipher, key), plain),
        ):
            total += 1
            if got == want:
                passed += 1
            else:
                print(f"  FAIL {name} key={key:016X}: got {got:08X} want {want:08X}")

    for key, hop, plain in DECRYPT_VECTORS:
        for name, got, want in (
            ("decrypt", decrypt(hop, key), plain),
            ("encrypt", encrypt(plain, key), hop),
        ):
            total += 1
            if got == want:
                passed += 1
            else:
                print(f"  FAIL {name} key={key:016X}: got {got:08X} want {want:08X}")

    return passed, total


if __name__ == "__main__":
    passed, total = self_test()
    print(f"{passed}/{total} published known answers")
    if passed == total:
        print("  cipher, tap order, feedback term, key schedule and byte order all agree")
    raise SystemExit(0 if passed == total else 1)
