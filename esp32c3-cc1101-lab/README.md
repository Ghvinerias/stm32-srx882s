# ESP32-C3 + CC1101 KeeLoq/HCS301 lab node

A second implementation of the STM32 + STX882/SRX882 lab firmware
(`../firmware/`), rebuilt around an **ESP32-C3** and a **CC1101** in
asynchronous OOK mode at **433.92 MHz**.

The wire protocol is byte-identical to the STM32 build: the same COBS framing,
CRC-16/CCITT, packet types, 18-byte header, little-endian pulse arrays and the
same `command_rx.c` / `lab_tx.c` / `lab_rx.c` behaviour. Existing host tools
(`../tools/rf_capture.py`, `rf_console.py`, `decode_hcs301.py`, ...) speak to it
unchanged over USB CDC.

Everything lives in this directory; no file outside it is modified by this
firmware.

---

## 1. Status

| Item | Result |
| --- | --- |
| Host tests (`tests/`) | pass (`make test`) |
| `arduino-cli compile` (ESP32-C3, CDC on boot) | pass, 0 warnings |
| OTA (types 6/7, `src/ota_session.c`) | host-tested; hardware reboot/flash path not verified |
| Flash / hardware bring-up | **not done** — flashing is deliberately left to you |
| RF validation | pending, checklist in §12 |

Built with `arduino-cli 1.5.1`, core `esp32:esp32` **3.3.12** (ESP-IDF 5.5).

---

## 2. Layout

```
esp32c3-cc1101-lab/
├── esp32c3-cc1101-lab.ino     Arduino entry point (setup/loop only)
├── build_opt.h.example        copy to build_opt.h to build "unit B"
├── ota/                       OTA spec, reference encoder, golden vectors
├── src/
│   ├── lab_config.h           pins, unit identity, BLE UUIDs, capture constants
│   ├── proto_transport.[ch]   COBS + CRC16 + FRAME/STATUS/ACK/OTA_RESP packets
│   ├── command_parse.[ch]     binary + ASCII command input (portable C)
│   ├── ota_session.[ch]       OTA/bootloader state machine (portable C)
│   ├── ota_backend.[ch]       ESP32 flash/HMAC/RNG/reboot side effects
│   ├── lab_tx.[ch]            codeword bits, µs waveform, scenarios (portable C)
│   ├── lab_capture.[ch]       idle-gap frame assembler (portable C)
│   ├── lab_rx.[ch]            hopping state machine (port of firmware/src/lab_rx.c)
│   ├── radio_rmt.[ch]         RMT TX/RX glue (ESP32)
│   ├── cc1101.[ch]            SPI driver + OOK presets (ESP32)
│   ├── ble_serial.[ch]        NUS-style GATT byte-stream transport (ESP32)
│   └── firmware_app.[ch]      links, routing, main loop (ESP32)
└── tests/                     host-side C tests + Makefile
```

The portable `.c` files (including `ota_session.c`) build both on the device and
on a PC, so the protocol, parser, codeword layout, RX state machine and OTA
state machine are tested without hardware, mirroring `firmware/test_*.c`.

---

## 3. Hardware and pinout

Target board: **ESP32-C3 SuperMini** (native USB Serial/JTAG, BLE 5 only) plus a
common **CC1101 433 MHz module**. Both are 3.3 V parts; power the CC1101 from
3.3 V, not 5 V.

| CC1101 pin | ESP32-C3 pin | Notes |
| --- | --- | --- |
| VCC / VDD | 3V3 | 3.3 V only |
| GND | GND | common ground |
| SCK | GPIO4 | SPI clock, 4 MHz |
| MISO (SO) | GPIO5 | |
| MOSI (SI) | GPIO6 | |
| CSN (CS) | GPIO7 | active low, driven by the driver |
| GDO0 | GPIO3 | async TX data **into** the CC1101 (RMT TX) |
| GDO2 | GPIO10 | async RX data **out of** the CC1101 (RMT RX) |
| — | GPIO8 | on-board status LED (active low) |

Notes and constraints:

* GPIO11–17 are the SPI flash pins and GPIO18/19 are USB; none are used here.
* **GDO0 is shared.** In RX the CC1101 drives GDO0 as the sliced data output
  (`IOCFG0 = 0x0D`); in TX the CC1101 samples GDO0 as the async data input.
  `radio_rmt.cpp` enables the ESP32 pad driver only for the duration of a burst
  and releases it (high-Z) immediately afterwards, so the two devices never
  drive the net at once. A **1 kΩ series resistor** between GPIO3 and GDO0 is
  recommended as protection during the few microseconds of mode overlap.
* GDO2 is receive-only here; the ESP32 leaves it as a floating input.
* CC1101 modules vary: some need `GD0`/`GD2` broken out, some wire GDO0 only to
  an LED. Verify the module exposes both.
* The CC1101 PA pulls short current peaks; add 100 nF close to the module's VCC
  pin and 10 µF nearby if the SuperMini's 3.3 V rail is marginal (symptoms:
  reboot or garbled captures on key-down).
* Keep the antenna away from the USB cable; 433.92 MHz OOK at 12 dBm
  (PATABLE `0xC0`) is enough to desensitise a receiver sitting centimetres away.
  If two units on the same desk hear each other too well, lower
  `LAB_CC1101_PATABLE_HIGH` (e.g. `0x60`, `0x2D`, `0x1D`).

There is **no bit-banging and no interrupt disabling**: transmit and capture are
done by the ESP32 RMT peripheral (2 TX-capable + 2 RX-capable channels on the
C3; one of each is used).

---

## 4. Build

Prerequisites (already present on this machine):

```bash
arduino-cli version                 # 1.5.1
arduino-cli core list               # esp32:esp32 3.3.12
# if the core were missing:
# arduino-cli core install esp32:esp32
```

Build from **inside this directory** (the sketch folder name must match the
`.ino` name):

```bash
cd esp32c3-cc1101-lab
arduino-cli compile --warnings all \
  --fqbn 'esp32:esp32:esp32c3:CDCOnBoot=cdc,PartitionScheme=no_fs,DebugLevel=none' .
```

Why these options:

* `CDCOnBoot=cdc` — required for `Serial` to be the native USB CDC port
  (`HWCDCSerial`), which is the transport the existing tools open.
* `PartitionScheme=no_fs` — two 2 MB application slots (`app0` at `0x10000`,
  `app1` at `0x200000`, each `0x1F0000` bytes) with no SPIFFS. This is what
  makes OTA possible: the running slot is never written.

On a memory-tight machine add `--jobs 2`; a fully parallel rebuild of the whole
core can exhaust RAM (`cc1plus: Killed`).

Expected tail:

```
Sketch uses 662409 bytes (32%) of program storage space.
Global variables use 26392 bytes (8%) of dynamic memory.
```

### Building "unit B"

Two identical units are told apart at build time by `LAB_UNIT_ID` (default 1).
The easiest way is Arduino's `build_opt.h`, which is applied to C and C++
compiles alike:

```bash
cp build_opt.h.example build_opt.h      # contains: -DLAB_UNIT_ID=2
arduino-cli compile ... .
rm build_opt.h                          # so unit A builds stay at id 1
```

`build_opt.h` is passed to GCC as a response file (`@build_opt.h`), so it may
contain only compiler options — `#` comment lines break the build. The example
file therefore carries the OTA secret example as an inert, C-commented macro;
see §8 before enabling it.

`LAB_UNIT_ID == 1` → receiver id `0xE1`, BLE name `KeeLoqLab-C3-A`.
`LAB_UNIT_ID == 2` → receiver id `0xE2`, BLE name `KeeLoqLab-C3-B`.

The STM32 build uses receiver id `1`, so neither C3 collides with it.

---

## 5. Flash (command documented, **not executed**)

With the board's USB-C port attached (native USB Serial/JTAG, no adapter):

```bash
cd esp32c3-cc1101-lab
arduino-cli upload -p /dev/ttyACM0 \
  --fqbn 'esp32:esp32:esp32c3:CDCOnBoot=cdc,PartitionScheme=no_fs,DebugLevel=none' .
```

`arduino-cli board list` shows the port; the C3 native USB enumerates as
VID:PID `303a:1001`. If the port does not appear, hold **BOOT**, tap **RESET**,
release **BOOT** to enter the ROM downloader. After flashing, the same port is
the application's USB CDC link (115200 baud setting is ignored by USB CDC, but
the host tools open it at 115200).

> Changing to the `no_fs` partition table needs **one USB reflash** of each
> unit. OTA can replace the application afterwards but cannot repartition
> flash.

> This command has intentionally **not** been run. Flashing and RF bring-up are
> the operator's step.

---

## 6. Host tests

```bash
cd esp32c3-cc1101-lab/tests
make test        # or: make && ./test_transport ...
```

The Makefile compiles the shared `.c` files with
`-std=c11 -O2 -g -Wall -Wextra -Werror`. Coverage:

| Binary | What it proves |
| --- | --- |
| `test_transport` | COBS round-trip (incl. 254-byte blocks), CRC-16/CCITT known vectors, 18-byte header field order, FRAME/STATUS/ACK byte layout, drop accounting, link fan-out |
| `test_codeword` | bit positions (hopping 0..31 LSB-first, serial 32..59, button 60..63, VLOW 64, repeat 65), `repeat=(frame==0)?0:1`, 77-pulse frame, 760/4512/4170/795/1138/1482/15500 µs timings, multi-frame gap |
| `test_capture` | idle-gap delimiter (8 ms), minimum pulse count, same-level merging, truncation flag, timestamp back-calculation, and the RMT interval finalizer used by `radio_rmt_rx_pop()` (77-interval frame, stripped idle, first-edge timestamp) |
| `test_command` | binary TX/SCENARIO packets, CRC rejection, frame-count clamping, ASCII `tx`/`rxpair`/`rxconfig`/`rxsim`/scenario letters, ACK routing, text replies |
| `test_rxsim` | hopping state machine: PAST / FUTURE / OPENED_NEXT / OPENED_GAP / OPENED_REPLAY, window and replay configuration |
| `test_ota` | replays `ota/OTA_VECTORS.json` (types 6/7): HELLO/BEGIN/6×CHUNK/END byte-exact responses, boot-partition + delayed reboot, bad MAC, TOO_BIG, BAD_OFFSET resync, duplicate re-ACK, link ownership, idle timeout, ABORT, BOOTLOADER, DISABLED, bad lengths, CRC drops, 1/20-byte chunking. Links `-lcrypto` for SHA-256/HMAC. |

Regenerate the checked-in `tests/ota_vectors.h` after changing the reference
encoder with `python3 ota/gen_vectors_h.py`.

---

## 7. Wire protocol (unchanged from the STM32 build)

Authoritative source: `../firmware/src/transport.c`; this firmware reimplements
it exactly.

Every packet is COBS-encoded and terminated by `0x00`. CRC is CRC-16/CCITT,
polynomial `0x1021`, initial value `0xFFFF`, little-endian on the wire, computed
over all preceding bytes.

```
[version:1=0x01][type:1][flags:u16][seq:u16][length:u16]
[timestamp:u32][capture_drops:u16][pulse_count:u16]
[initial_level:1][receiver_id:1]
[pulses: u16 * pulse_count][crc16:u16]
```

* Header is always 18 bytes; `length` is the raw (pre-COBS) length.
* Pulse durations are microseconds, little-endian `uint16`.
* Types: `FRAME=1`, `STATUS=2`, `TX_COMMAND=3`, `SCENARIO_COMMAND=4`, `ACK=5`,
  `OTA_CMD=6`, `OTA_RESP=7` (the OTA packets use their own payload layout, see
  §8).
* Frames on the air are 77 edge-to-edge intervals: 11 preamble + 1 header +
  65 data. `initial_level` is the level of the first interval (1 for a rising
  start).
* STATUS is sent every 1000 ms while a link is up, with `timestamp=0` and five
  status words: capture clock Hz (1 000 000 on this port), total edges,
  dropped packets.
* ACKs are the STM32 6-byte form (`version,type,for_type,accepted,crc16`) and go
  only to the link the command arrived on.
* FRAME and STATUS are broadcast to both links. `dropped_packets` increments
  only when *no* link accepted the bytes.

`receiver_id` is `0xE1` / `0xE2` per unit (never `1`), so a mixed bench can tell
ESP32 frames from STM32 frames.

---

## 8. OTA / bootloader protocol (types 6 and 7)

Authoritative description: [`ota/OTA_PROTOCOL.md`](ota/OTA_PROTOCOL.md). The
normative encoder is `ota/ota_ref.py`, and `ota/OTA_VECTORS.json` contains the
secret, nonce, image rule and every packet as raw + COBS wire hex. The same
protocol runs over USB-CDC and BLE NUS; responses go only to the link that sent
the command. Types 1-5 and the text commands are unchanged.

Build-time shared secret (32 bytes = 64 hex characters), in `build_opt.h`:

```
-DLAB_OTA_SECRET_HEX=\"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff\"
```

With no secret, or with anything other than exactly 64 hex characters, every
op except `ABORT` answers `DISABLED`. `build_opt.h` is a GCC response file, so
it cannot contain `#` comments; `build_opt.h.example` carries the example as an
inert C-commented macro. Replace that whole line with the form above (keep
`-DLAB_UNIT_ID=2` for unit B). Never commit the real secret.

* `PartitionScheme=no_fs` gives two 2 MB app slots (`0x1F0000` each); the
  running slot is never written. Changing to this table needs **one USB
  reflash** per unit (§5); OTA cannot repartition flash.
* HELLO returns a fresh `esp_random` nonce (single use, consumed by BEGIN or
  BOOTLOADER, good or bad). BEGIN/BOOTLOADER are authenticated with
  HMAC-SHA256 and compared in constant time.
* Chunks are streamed into the inactive slot while hashing. END verifies the
  total size and SHA-256, calls `esp_ota_end` +
  `esp_ota_set_boot_partition`, then reboots ~300 ms later from the main loop.
* BOOTLOADER sets the ESP32-C3 force-download flag
  (`RTC_CNTL_OPTION1_REG`/`RTC_CNTL_FORCE_DOWNLOAD_BOOT`) and restarts into the
  ROM loader ~200 ms later. **Not verified on hardware.**
* The prebuilt bootloader has rollback enabled
  (`CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y`). A new image stays
  `PENDING_VERIFY` until the app calls
  `esp_ota_mark_app_valid_cancel_rollback()` once, ~10 s after boot with BLE
  up; otherwise the next reset reverts to the previous image.
* Parser RX buffers are 256 B (largest CHUNK wire packet is 203 B) and
  `Serial.setRxBufferSize(1024)` is called before `Serial.begin()`.

---

## 9. Transports

### USB CDC

`Serial` is the ESP32-C3 native USB Serial/JTAG CDC port. It carries the exact
COBS byte stream, so `../tools/rf_capture.py --port /dev/ttyACMx` and
`rf_console.py` work as-is. Commands may arrive as binary packets or as ASCII
lines. Frames, STATUS and ACKs are always the binary framed stream; the only
ASCII on the wire is a diagnostic text reply (such as `TX ... DONE`, `help` or
`radio`), sent to the originating link only — identical to the STM32 build.

### BLE (Nordic-UART-Service style)

One GATT service, one write characteristic, one notify characteristic:

| Role | UUID | Properties |
| --- | --- | --- |
| Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | — |
| Host → device | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | Write, Write Without Response |
| Device → host | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Notify (CCCD `0x2902`) |

* Advertising name: `KeeLoqLab-C3-A` / `KeeLoqLab-C3-B`; the service UUID is
  also in the advertisement.
* Preferred ATT MTU **247** (NimBLE in this core is built for up to 256), so a
  notification carries **244 bytes**. The firmware chunks every outgoing packet
  to `min(MTU-3, 244)` and never assumes a packet fits one notification. Before
  MTU exchange the default is 23 → 20-byte payloads; chunking follows the
  negotiated value automatically.
* Notifications carry **raw COBS bytes with no added framing**. The link is a
  byte stream: a host must append every notification to a buffer and split on
  `0x00`, exactly as it would read a serial port. A worst-case 256-pulse FRAME
  is 532 raw bytes ≈ 537 encoded, i.e. three notifications at MTU 247.
* Incoming writes are queued in a 4 KiB FreeRTOS stream buffer, so a long `tx`
  line or binary packet may be split across several writes; the parser keeps
  per-link state and reassembles. A full queue drops the write rather than
  stalling the NimBLE host task.
* Only one connection is supported at a time; advertising restarts on
  disconnect.

**Future Android transport** (the app is *not* modified here): scan for the
service UUID (or the `KeeLoqLab-C3-` name prefix), connect, request MTU 247,
enable notifications on `...0003` (write `0x0001` to its CCCD), write command
bytes to `...0002`, and feed notifications through the existing COBS/CRC packet
decoder. Use the 1 Hz STATUS packet as a liveness signal, and use FRAME
`receiver_id` (`0xE1`/`0xE2`) to label which unit produced a capture.

---

## 10. Radio implementation

### CC1101 (`src/cc1101.cpp`)

SPI mode 0, 4 MHz, single-register writes, burst writes for PATABLE, status
registers read with `addr | 0xC0`. Reset sequence: CS pulse + `SRES`, wait for
`MARCSTATE=IDLE`, read `PARTNUM`/`VERSION` (`0x00`/`0x14`; all-ones means MISO
is not connected → boot flag set, transport stays alive). State changes are
explicit `SIDLE → SFTX/SFRX → STX/SRX` with `MARCSTATE` polling; the radio is
half-duplex and is switched to TX only around a burst, then back to RX.

OOK/async configuration:
`IOCFG2=0x0D` (GDO2 = async RX data), `IOCFG0=0x0D` (GDO0 = async data),
`PKTCTRL0=0x32` (async serial, infinite packet, no whitening),
`MDMCFG2=0x30` (ASK/OOK, no preamble/sync), `FREQ2/1/0=0x10/0xB0/0x71`
(433.92 MHz), `MCSM0=0x18`, `MCSM1=0x0C` (RXOFF→RX, TXOFF→IDLE),
`FSCTRL1=0x06`, `FOCCFG=0x18`, `WORCTRL=0xFB`, `FREND1=0xB6`, `FREND0=0x11`,
PATABLE `{0x00, 0xC0}`.

**Preset provenance:** the 270 kHz and 650 kHz OOK register sets come from
Flipper Zero firmware,
[`lib/subghz/devices/cc1101_configs.c`](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/lib/subghz/devices/cc1101_configs.c),
arrays `subghz_device_cc1101_preset_ook_270khz_async_regs` and
`subghz_device_cc1101_preset_ook_650khz_async_regs`:

| Register | OOK 270 kHz (default) | OOK 650 kHz |
| --- | --- | --- |
| `FIFOTHR` | `0x47` | `0x07` |
| `MDMCFG4` | `0x67` | `0x17` |
| `MDMCFG3` | `0x32` | `0x32` |
| `AGCCTRL2` | `0x03` | `0x07` |
| `AGCCTRL1` | `0x00` | `0x00` |
| `AGCCTRL0` | `0x40` | `0x91` |

Deviations from Flipper's table (documented in `cc1101.cpp`): the frequency
registers are set to 433.92 MHz, and `IOCFG2` is set to `0x0D` so GDO2 carries
the async RX data while GDO0 stays the TX input. Switch presets at runtime with
`ook 270` / `ook 650`, or inspect with `radio`.

### RMT (`src/radio_rmt.cpp`)

* 1 MHz resolution, so one RMT tick is one microsecond.
* **TX**: the 77-interval (up to 779 for 10 frames) µs array from `lab_tx.c` is
  packed two intervals per `rmt_symbol_word_t`, starting at level 1, and sent
  with the copy encoder. `init_level=1`, `eot_level=0` — the waveform starts
  high like `send_bits_once()` and ends low. No CPU timing loops.
* **RX**: `rmt_receive()` with `signal_range_min_ns = 20000` (20 µs; rejects
  slicer chatter) and `signal_range_max_ns = 8000000` (8 ms; identical to
  `hw_capture.c`'s idle delimiter; larger than the 4512 µs header cell, smaller
  than the 15500 µs inter-frame gap). Both edges are captured — the driver
  records every edge with its level, so a full frame is 77 intervals, not the
  old 38-edge pairwise-merge bug. The 128-symbol (256-pulse) buffer is
  ping-ponged by the IDF driver; a 10-frame burst is split into individual
  frames by the 8 ms idle gap.
* The ISR only copies symbols and posts `{count, timestamp}` to a queue;
  flattening, same-level merging, idle-gap stripping, `pulse_count`,
  `initial_level`, truncation and `timestamp_us` back-calculation happen in
  task context. `radio_rmt_rx_suspend()/resume()` stop capture around a TX burst
  so the node never captures its own transmission.
* **TX pin tri-state**: the RMT channel owns the GPIO-matrix route to GDO0.
  The pad driver is gated with `GPIO_ENABLE` (`gpio_ll_output_enable/disable`),
  *not* `gpio_set_direction()` — the latter calls
  `gpio_hal_matrix_out_default()` and would permanently disconnect the RMT
  output signal from the pad. This is the same mechanism `rmt_tx_destroy()`
  uses to release the pin.

---

## 11. Commands

Input accepts both wire-format binary packets and ASCII lines (the binary path
is what the Android app / `rf_console.py` send; ASCII is for a serial terminal).
Output is always binary frames/ACKs; ASCII text replies are diagnostic only and
go back only to the originating link.

| Command | Meaning |
| --- | --- |
| `tx <hopping> <serial> <button> [frames]` | transmit HCS301-style codeword; hex without `0x`, `frames` defaults to 3 and is clamped to 1..10 |
| `s` / `synthetic-stale` | replay the measured 77-entry `stale_frame_us[]` table |
| `z` / `zero-hop` | scenario: zero hopping word |
| `p` / `plaintext-counter` | scenario: plaintext counter pattern |
| `x` / `random-hop` | scenario: pseudo-random hopping word |
| `d` / `different-serial` | scenario: different serial |
| `rxpair <serial> <button> <count>` | pair the hopping state machine |
| `rxconfig <next> <future|all> <gap> <replay 0|1>` | window and replay policy |
| `rxsim <hopping> <serial> <button>` | feed one candidate through the state machine and print the verdict |
| `radio` | ESP32-only: CC1101 part/version/preset/state |
| `ook <270|650>` | ESP32-only: switch the OOK preset |
| `help` | command list |

Codeword layout (identical to `lab_tx.c`): hopping bits 0–31 LSB-first, serial
bits 32–59, button bits 60–63, `VLOW` bit 64 = 0, repeat bit 65 =
`(frame_index == 0) ? 0 : 1`. Timing: preamble 760 µs ×11, header 4512/4170 µs,
data 795/1138/1482 µs, inter-frame gap 15500 µs.

---

## 12. Bring-up checklist for `rf-bringup-validator`

All commands below are for the *validator*; none were run while building this.

1. **Ports.** `arduino-cli board list`; C3 units are VID:PID `303a:1001`
   (`/dev/ttyACM*`), the STM32 reference is `0xcafe:0x4001`. Confirm identity
   before each run; numbering is not stable across replugs.
2. **Boot health.** Open the port, send `radio\r\n`. Expect
   `RADIO CC1101 partnum=0x00 version=0x14 preset=OOK270 freq=433.92MHz
   state=RX`. All-ones part/version means SPI/MISO is not connected; the
   firmware will still answer (STATUS carries `CAPTURE_INIT_FAILED` in flags).
3. **Transport sanity.** With no commands, confirm a STATUS packet every 1 s
   decodes (`type=2`, five status words, `clock_hz=1000000`) and that
   `receiver_id` is `0xE1`/`0xE2` per unit.
4. **TX edge integrity.** Put the logic analyser on GPIO3 (or the CC1101 GDO0
   pad) and send `tx 114766B9 FB58C0C 2 2`. Expect two frames of exactly
   **77 intervals**, preamble 760 µs, header 4512/4170 µs, data
   795/1138/1482 µs, gap 15500 µs, and repeat bit 0 then 1. Compare against the
   STM32 + STX882 reference with the same codeword.
5. **RX edge integrity.** On unit B, start
   `python3 tools/rf_capture.py --port /dev/ttyACMy --save captures/a2b.bin --json captures/a2b.ndjson`,
   then send `tx 114766B9 FB58C0C 1 1` on unit A and run
   `decode_hcs301.py captures/a2b.ndjson`.
   Expect **77 pulses, not 38**, and decoded fields equal to the transmitted
   ones. 38 pulses means the same-polarity merge bug reappeared; compare the
   slicer output on the analyser to separate CC1101 from RMT.
6. **RX polarity.** Check the FRAME's `initial_level` byte: the first interval
   is a high (mark) period, so it must be **1**. If it is 0, the RMT
   `level0`/`level1` semantics assumed in `radio_rmt_rx_pop()` are inverted for
   this IDF version — fix the level assignment there (one line) and re-run;
   everything downstream (timings, decode) is polarity-independent apart from
   this byte.
7. **Cross-device agreement.** A → B, B → A, and A → STM32 reference (STX882
   channel) must all decode to the same hopping/serial/button. Also check
   `rxpair`/`rxconfig`/`rxsim` against `lab_rx` expectations from the STM32
   build (same replies).
8. **Command paths.** Binary TX_COMMAND and SCENARIO_COMMAND (from
   `rf_console.py`), the ASCII `tx` form, and both transports (USB and BLE)
   must produce the same FRAME/ACK behaviour. ACKs must only come back on the
   link that sent the command.
9. **BLE.** Connect from a phone/BlueZ, request MTU 247, subscribe to
   `6E400003-...`. Confirm notifications reassemble to whole packets on `0x00`
   (a 256-pulse frame spans 3 notifications) and that writes split at any byte
   boundary still parse (`tx 114766B9 FB58C0C 2` written in two halves).
10. **Preset and power.** `ook 650` / `ook 270` should survive a command and
    `radio` should report the change; if a bench pair saturates, lower
    `LAB_CC1101_PATABLE_HIGH` and note the value used in the report.
11. **Counters.** `capture_drops` and STATUS dropped-packet counts should stay
    at 0 for a clean run; rising values point at USB back-pressure or RF noise
    producing sub-16-pulse bursts.

---

## 13. Risks and open questions

* **RMT RX `level0` semantics** — the code assumes `level0` is the level of the
  interval *after* the first edge (matching the STM32 `initial_level`). This is
  the one assumption that must be confirmed on hardware (checklist step 6).
  It does not affect timings or the 77-interval count.
* **RMT trailing idle interval** — `lab_capture_finalize()` expects the last
  received symbol to be the 8 ms idle period (that is why the receiver stops).
  If an IDF version omitted it, `timestamp_us` would be ~8 ms late; pulse
  timings, pulse count and decode are unaffected. Compare the FRAME timestamp
  with the transmitter's start time during the first capture.
* **GDO0 hand-over** — the CC1101 needs some microseconds to switch between RX
  output and TX input; the firmware switches the radio first, then drives the
  pad, then releases the pad before switching back. A 1 kΩ series resistor is
  strongly recommended; check for contention with a scope during the overlap.
* **BLE notification flow control** — notifications are queued by NimBLE;
  `BLECharacteristic::notify()` returns `void` in this core, so a transient
  buffer shortage can silently drop one notification. Frames are small
  (≤ 537 bytes, usually ~174 for 77 pulses) and rare, and the COBS decoder
  resynchronises at the next `0x00`. If a long frame is truncated on a slow
  phone, look at the notification interval rather than the framing code.
* **Long TX blocks the loop for up to ~1 s** (10 frames + gap). BLE and USB
  stacks run in their own tasks, and the BLE write queue absorbs commands, but
  a host flooding binary commands during a long burst can overflow the queue.
  The queue deliberately drops rather than stalling the BLE host task.
* **CC1101 sensitivity/bandwidth** are Flipper's lab presets, not a tuning
  exercise for a particular module. If edge fidelity is poor, `ook 650` and/or
  the AGC registers are the first knobs; report exact captures.
* **USB CDC baud** is ignored (native USB). Host tools must not depend on
  DTR/RTS line state.
* No KeeLoq key extraction or hopping-code decryption is attempted: this is an
  operator-owned-signal lab for capture/replay fidelity, exactly like the STM32
  build.

---

## 14. References

* STM32 reference implementation: `../firmware/` (`transport.c`,
  `command_rx.c`, `lab_tx.c`, `lab_rx.c`, `hw_capture.c`).
* Host tools: `../tools/README.md`.
* Flipper Zero CC1101 OOK presets:
  `lib/subghz/devices/cc1101_configs.c` (link in §10).
* ESP-IDF RMT driver: `driver/rmt_tx.h`, `driver/rmt_rx.h`;
  GPIO matrix/output-enable behaviour: `components/esp_driver_gpio/src/gpio.c`
  (`gpio_output_disable`, `gpio_output_enable`) and
  `components/esp_driver_rmt/src/rmt_tx.c`.


### Console/log hygiene (important)

The USB-CDC port carries the COBS protocol, so **no log text may ever appear
on it**. The firmware calls `esp_log_level_set("*", ESP_LOG_NONE)` at start and
end of init, builds with `DebugLevel=none`, and limits HWCDC write blocking
(`setTxTimeoutMs(20)`). Only the ROM/bootloader banner at reset precedes the
app (one junk "frame" that hosts resync past at the next `0x00`).
The RMT glitch filter is set to 3 us (hardware limit is 3187 ns); the 20 us
slicer-chatter rejection is done in software (`lab_capture_filter_glitches`).
`radio` reports `arm_fail=N` (failed rmt_receive arms); RX re-arm is retried
at most every 500 ms.
