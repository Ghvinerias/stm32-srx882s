# stm32-srx882s

STM32F103C8T6 (Blue Pill) + SRX882 433 MHz superheterodyne receiver — MVP for pulse capture and basic decoder.

Status: WIP (initial scaffold)

Hardware
- MCU: STM32F103C8T6 (Blue Pill)
- RF receiver: SRX882 (DATA, VCC=3.3V, GND)
- UART: USART1 @ 115200 for logging

Wiring (minimal)
- SRX882 DATA -> PA0 (TIM2 CH1 / EXTI0)
- SRX882 VCC  -> 3V3
- SRX882 GND  -> GND
- USART1 TX (PA9)  -> USB‑TTL RX
- USART1 RX (PA10) -> USB‑TTL TX

Planned MVP
- Clock + GPIO + UART bring‑up
- Pulse capture via TIM2 input capture + EXTI for edges
- Ring buffer of pulse widths
- Heuristics for common 433 MHz ASK/OOK protocols (EV1527, PT2262, basic Oregon v2 sampling)
- Python tools: serial logger and pulse plotter

Build and flash (planned)
- Toolchain: arm-none-eabi-gcc, OpenOCD, ST‑Link V2
- Commands (once Makefile is completed):
  - make
  - make flash

License
- MIT
