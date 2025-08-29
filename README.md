# STM32F1 433 MHz Protocol Classifier & Decoder (MVP)

Goals:
- Capture OOK pulses at 433.92 MHz
- Classify protocol on-the-fly (EV1527/PT2262, Manchester/Oregon v2, Pulse-distance)
- Decode fixed-code protocols, flag rolling/unknown
- Stream results over UART1 @ 115200

Quick start:
1. Install toolchain: `arm-none-eabi-gcc`, `openocd`, Python 3 with `pyserial`, `matplotlib`
2. Install STM32CubeF1 and set:
   ```
   export CUBE_DIR=~/STM32Cube/Repository/STM32Cube_FW_F1_V1.8.5
   ```
3. Build:
   ```
   make
   ```
4. Flash (ST-Link):
   ```
   make flash
   ```
5. Monitor UART logs:
   ```
   make monitor PORT=/dev/ttyUSB0
   ```

Hardware (Blue Pill example):
- RF receiver data → PA0 (EXTI0)
- UART1 TX → PA9, RX → PA10
- LED → PC13

Output examples:
```
[PROTO:EV1527] RAW:101001001011100100101100 Payload:0x1A2F3C
[PROTO:UNKNOWN-ROLLING] LEN=66 SYMBOL≈500us
```

Testing:
```
make test
```

See docs/schematic.md for wiring.