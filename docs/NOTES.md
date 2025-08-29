# Engineering Notes (MVP)

Acquisition:
- EXTI on PA0 timestamps both edges using TIM2 @ 1 MHz. This keeps ISR light and avoids double-edge TIM capture complexities on F1.
- Ring buffer depth 512 is sufficient for typical frames and repeats; overruns are counted.

Classification:
- A simple symbol estimator and tolerance-based matching detect EV1527/PT2262 reliably at 350–600 us base symbols.
- Oregon v2 is detected by tight clustering in half-bit timing (Manchester). Full payload decode can be added next.

UART Output:
- One line per classified frame.
- Unknown bursts with inconsistent ratios are flagged as UNKNOWN-ROLLING.

Future:
- DMA input-capture ring for higher resilience.
- CC1101 support for FSK/GFSK.
- CRC/parity validation for weather sensors.