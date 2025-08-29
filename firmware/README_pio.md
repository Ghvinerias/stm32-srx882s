PlatformIO build & flash helper

Quick commands (run from firmware/):

Build:

  pio run

Build + Upload (ST-Link):

  pio run -t upload

Monitor UART (after flashing):

  pio device monitor --baud 115200

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


