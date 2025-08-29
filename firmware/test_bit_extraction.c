#include <stdio.h>
#include "src/pulse_utils.h"

int main(void) {
    uint32_t pulses[] = {400, 600, 400, 600, 1200, 200};
    uint8_t bits[8] = {0};
    size_t n = pulses_to_bits(pulses, sizeof(pulses)/sizeof(pulses[0]), bits, 900);
    printf("test_bit_extraction: %zu bits -> ", n);
    for (size_t i=0;i<n;i++) printf("%u", bits[i]);
    printf("\n");
    return 0;
}
