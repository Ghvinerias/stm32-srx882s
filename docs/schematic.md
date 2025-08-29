# Minimal Schematic / Wiring (Textual)

- MCU: STM32F103C8T6 (Blue Pill)
- Power:
  - 5V USB → 5V pin (on board regulator) or 3.3V directly to 3V3
  - GND to GND
- RF Receiver (e.g., RXB6 / SYN480R):
  - VCC → 5V (if module supports) or 3.3V (check your module)
  - GND → GND
  - DATA → PA0 (MCU)
- UART for PC logging:
  - PA9 (TX) → USB-UART RX
  - PA10 (RX) → USB-UART TX (optional)
  - GND → GND
- Status LED:
  - PC13 → LED → resistor → 3.3V (active low)

Antenna: λ/4 whip ≈ 17.3 cm at 433.92 MHz.
Notes:
- Use short wires from RF receiver to MCU.
- Add a 100 nF decoupling cap near the RF module VCC/GND.