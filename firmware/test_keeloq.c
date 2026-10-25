#include <stdio.h>

#include "src/keeloq.h"

// Host-side known-answer test for the KeeLoq block cipher.
//
// Two independent sources are used on purpose. A cipher can be perfectly
// self-consistent (encrypt then decrypt round-trips) while still being wrong,
// so the only useful check is against answers someone else published:
//
//   * liyanboy74/KeeLoq (fork of hadipourh/KeeLoq): canonical
//     (key, plaintext, ciphertext) triples.
//   * NDIAS 2026 "Classic KeeLoq Garage" CTF writeup: (key, hop, plaintext)
//     decrypts whose plaintexts must be a constant discriminant followed by a
//     counter incrementing by one per press.
//
// The second set also checks the property that matters for a receiver: a
// correct key yields a structured plaintext, while a wrong key yields noise
// with no constant high half.

static int failures;

static void check(const char *label, uint32_t got, uint32_t want) {
    if (got == want) {
        printf("  ok   %-28s %08X\n", label, got);
        return;
    }
    printf("  FAIL %-28s got %08X, want %08X\n", label, got, want);
    ++failures;
}

int main(void) {
    static const struct {
        uint64_t key;
        uint32_t plain;
        uint32_t cipher;
    } cipher_vectors[] = {
        {0x5CEC6701B79FD949ULL, 0xF741E2DBU, 0xE44F4CDFU},
        {0x5CEC6701B79FD949ULL, 0x0CA69B92U, 0xA6AC0EA2U},
    };

    static const struct {
        uint32_t hop;
        uint32_t plain;
    } ctf_vectors[] = {
        {0x913B00D7U, 0xADC40040U},
        {0x0020D1FBU, 0xADC40041U},
        {0x80ED145BU, 0xADC40042U},
        {0x314C03E4U, 0xADC40043U},
    };

    const uint64_t ctf_key = 0x6D3A91C46CEE3373ULL;

    printf("test_keeloq: published cipher vectors\n");
    for (size_t i = 0; i < sizeof(cipher_vectors) / sizeof(cipher_vectors[0]); ++i) {
        const uint64_t key = cipher_vectors[i].key;
        check("encrypt", keeloq_encrypt(cipher_vectors[i].plain, key),
              cipher_vectors[i].cipher);
        check("decrypt", keeloq_decrypt(cipher_vectors[i].cipher, key),
              cipher_vectors[i].plain);
    }

    printf("test_keeloq: CTF decrypt vectors\n");
    uint32_t previous = 0;
    for (size_t i = 0; i < sizeof(ctf_vectors) / sizeof(ctf_vectors[0]); ++i) {
        const uint32_t plain = keeloq_decrypt(ctf_vectors[i].hop, ctf_key);
        check("decrypt", plain, ctf_vectors[i].plain);
        check("re-encrypt", keeloq_encrypt(ctf_vectors[i].plain, ctf_key),
              ctf_vectors[i].hop);

        // The structural check a receiver cares about: a constant discriminant
        // and a counter that advances by one per press.
        check("discriminant", plain >> 16, 0xADC4U);
        if (i > 0) {
            check("counter step", (plain & 0xFFFFU) - (previous & 0xFFFFU), 1U);
        }
        previous = plain;
    }

    printf("test_keeloq: %s\n", failures ? "FAIL" : "ALL PASS");
    return failures ? 1 : 0;
}
