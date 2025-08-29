# STM32F4 433 MHz Protocol Classifier & Decoder (MVP)

## Overview
This project aims to build a standalone device based on the **STM32F4 MCU** and a **433 MHz receiver** that:

- Listens for signals in the 433.92 MHz ISM band  
- Captures pulse timings in real-time  
- Identifies the underlying protocol on-the-fly  
- Decodes supported protocols (e.g. PT2262, EV1527, common weather sensors)  
- Flags unsupported/rolling-code protocols  

---

## Scope (MVP)

### Hardware
- **MCU:** STM32F4 series (e.g., STM32F407/STM32F411)  
- **Receiver:** 433 MHz superheterodyne OOK module (e.g., SYN480R, RXB6)  
  - *Future extension:* CC1101 transceiver for FSK/GFSK support
- **Peripherals:**  
  - Timer input-capture pin for RF data  
  - UART/USB for output & debugging  
  - Power via USB or 5 V regulator  

### Software
- **Signal acquisition**
  - Timer Input Capture @ 1–2 MHz, DMA ring buffer
  - Capture rising/falling edges → compute pulse widths  
- **Protocol classifier**
  - Detect line codes:  
    - PWM/PT2262/EV1527  
    - Manchester (Oregon Scientific v2)  
    - Pulse-distance modulation  
  - Mark unknown/rolling-code signals  
- **Decoder module**
  - Extract bits, sync words, payloads  
  - Validate with CRC/parity if present  
- **Output**
  - Transmit classification + payload over UART/USB  
  - Example:
    ```
    [PROTO:EV1527] RAW:101010110110 Payload:0x1A2F3C
    [PROTO:UNKNOWN-ROLLING] LEN=66 SYMBOL=500us
    ```

---

## Deliverables
- Firmware (C, STM32 HAL/LL)  
- Test harness (Python/PC script to parse UART logs & visualize signals)  
- Minimal schematic (STM32 + RF receiver + antenna)  
- Demo: classification of 3 different devices:
  1. Cheap remote (EV1527/PT2262)  
  2. Wireless weather sensor  
  3. Car/gate remote → flagged as rolling-code  

---

## Success Criteria
1. **Detection**: RF signals detected on button press  
2. **Classification**: Correct protocol ID in >90% of test cases  
3. **Decoding**: At least 2–3 fixed-code protocols fully decoded  
4. **Resilience**: Handles noise and repeated frames  
5. **Output**: Engineers can log and analyze via PC  

---

## Future Extensions
- Add CC1101 transceiver for FSK/GFSK  
- Onboard OLED/LCD for standalone display  
- Flash/SD logging of frames  
- Rolling-code recognition database  
- BLE/WiFi support (ESP8266/ESP32 module)  
- GUI visualization tool for pulse/bit streams  

---

## Risks & Constraints
- Cheap OOK modules have slow AGC → need multiple repeats  
- Large variety of 433 MHz protocols → not all will be decodable  
- Timing accuracy depends on external crystal  
- Make sure to attempt cloning/replay of rolling-code systems  

---

## Team Responsibilities 
- **Firmware Engineer**
  - Implement timer/DMA capture, pulse extraction, classification & decoding  
  - Optimize real-time performance  
- **Software Tools Engineer**
  - Build Python script for PC logging/visualization  
  - Validate decoding against real remotes  

---

## License
TBD (internal MVP prototype only)  
