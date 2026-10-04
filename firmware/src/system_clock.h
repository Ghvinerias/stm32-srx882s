#pragma once

#include <stdint.h>

// Configure HSE/PLL for 72 MHz. Falls back to the 8 MHz HSI clock.
// Returns 1 when the 72 MHz configuration succeeded, otherwise 0.
int system_clock_configure(void);
uint32_t system_clock_hz(void);

