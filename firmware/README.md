# STM32F4 Firmware

Firmware for 433 MHz protocol classifier/decoder.

- STM32 HAL/LL, CMake
- Input capture via timer + DMA
- Protocol classification/decoding
- UART or USB CDC output (see below)

See root README for project overview.

## Capture output mode

The binary capture packet stream (COBS-framed, CRC-16; same format either
way, see `tools/rf_capture.py`) can go out over either of two channels,
selected once at boot by a jumper on **PB5**:

| PB5 | Mode | Capture stream on | Lab console (`tx ...`) on |
|---|---|---|---|
| floating/high (default, no jumper) | USB capture | USB CDC | not started |
| tied to GND | legacy | UART1 / PA9 | USB CDC |

By default (no jumper needed), point `rf_capture.py --port` at the board's
own CDC device (e.g. `/dev/ttyACM0`) -- no external PA9 serial adapter
required. The lab console isn't started in this mode, since it would
otherwise contend with the capture stream for the same CDC endpoint; wire
PB5 to GND to fall back to the legacy UART1/PA9 capture stream with the
console back on USB CDC. Single-byte scenario triggering over UART RX
(`r`/`s`/`z`/`p`/`x`/`d`) works in either mode, independent of which channel
carries the capture stream.

PB5 is sampled once at boot with an internal pull-up; move the jumper and
reset the board to switch modes.
