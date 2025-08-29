#pragma once
#include <stddef.h>
#include <stdint.h>

// Initialize input capture hardware (TIM2 CH1 on PA0)
// Call once during startup.
void hw_capture_init(void);

// Start capturing pulses. Non-blocking; captures stored internally until fetched.
void hw_capture_start(void);

// Fetch collected pulse durations (microseconds) into caller buffer.
// Returns number of uint32_t durations written. Caller should provide adequate buffer.
size_t hw_capture_fetch_pulses(uint32_t *out_buf, size_t max_pulses);

// For host builds we provide a simulation stub in hw_capture.c
