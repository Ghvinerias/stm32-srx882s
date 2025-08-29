---
applyTo: '**'
---
Goal: Build an STM32F4-based 433 MHz on-the-fly classifier/decoder (OOK first).
Priorities: Timer+DMA edge capture → pulse widths → rule-based classifier → EV1527/PT2262/Manchester decoders → UART JSON output.
Constraints: No rolling-code decryption; just flag it. Keep ISR light; use DMA ring buffers.
Style: HAL/LL C; CMake; unit tests for bit extraction; Python CLI for log parsing.
Board: (fill in exact board; e.g., NUCLEO-F411RE)
Pins: RF_DATA -> TIMx CHy (input capture), UART over STLink-virtual or USB CDC.