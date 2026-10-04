PlatformIO build & flash helper

Quick commands (run from firmware/):

Build:

  pio run

Build + Upload (ST-Link):

  pio run -t upload

Capture UART (after flashing, from the repository's tools directory):

  python rf_capture.py --port /dev/ttyUSB0 --save capture.bin

The UART stream is binary, not text. A plain terminal monitor will display
unreadable characters. USART1 is PA9 TX / PA10 RX at 115200 8N1. Capture-only
operation needs USB-UART RX connected to PA9. Command-driven transmission also
needs USB-UART TX connected to PA10. Use 3.3 V UART logic and a common ground.

Receiver wiring used by this firmware:

- SRX882R `CS` -> PB7 (firmware drives it high to enable the receiver)
- SRX882R `DATA` -> PB8 (TIM4 channel 3 input capture)
- SRX882R `VCC` -> 3V3
- SRX882R `GND` -> GND

Lab transmitter wiring:

- STX882 `DATA` -> PB6
- STX882 `GND` -> GND
- STX882 `VCC` -> the supply required by the exact module
- STX882 `ANT` -> a suitable 433 MHz antenna

Set `LAB_TX_STALE_REPLAY_ON_BOOT=1` only for the explicit replay experiment. It
emits one previously captured, already-used frame 1.5 seconds after reset and
then stays idle. The normal capture build keeps this option at `0` and never
transmits on startup.

Command-driven tabletop tests (with the option above left at `0`):

  python tools/rf_lab_command.py stale
  python tools/rf_lab_command.py synthetic-stale
  python tools/rf_lab_command.py zero-hop
  python tools/rf_lab_command.py plaintext-counter
  python tools/rf_lab_command.py random-hop
  python tools/rf_lab_command.py different-serial

Each synthetic scenario emits three frames and then returns PB6 low. The
plaintext-counter case increments its test value once per command. These are
black-box decoder experiments; the firmware does not contain or derive a
KeeLoq manufacturer key.

Phone/terminal console:

Connect the Blue Pill Micro-USB port to a USB host/OTG phone. It enumerates as
the CDC ACM device `Blue Pill RF Lab`; baud-rate settings in the terminal app
are ignored by native USB. Type `help` to print the menu, then run scenarios by
their full names. This USB console is always text-only.

On PA10 in raw/hex mode, scenarios can also be selected with one ASCII byte: `r`
(`72`) stale, `s` (`73`) synthetic-stale, `z` (`7A`) zero-hop, `p` (`70`)
plaintext-counter, `x` (`78`) random-hop, and `d` (`64`) different-serial.
PA9/PA10 remain a dedicated 115200-baud binary capture/control interface while
the native USB console is connected.

Notes:
- Requires PlatformIO Core (pio) and stlink installed on host.
- Board is configured as Blue Pill `bluepill_f103c8`. Change `board` in `platformio.ini` if needed.

Troubleshooting:
- On aarch64 (ARM) Linux hosts PlatformIO may not have prebuilt toolchains available. If you see an error about
  `toolchain-gccarmnoneeabi` missing, either install an ARM-compatible toolchain manually or use an x86_64 build host
  or Docker container with PlatformIO.

Docker (recommended on ARM hosts):

- Build container (on an x86_64 host or using multi-platform build):

  docker build -t stm32-pio -f Dockerfile.pio .

- Run a build inside the container (mount current project):

  docker run --rm -v $(pwd):/project stm32-pio pio run

- To upload from container, forward the USB/ST-Link device and run:

  docker run --rm --device=/dev/bus/usb:/dev/bus/usb -v $(pwd):/project stm32-pio pio run -t upload
