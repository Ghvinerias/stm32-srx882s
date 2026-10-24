# fit4 HIL bench — setup and runbook

Machine: `fit4.local.slick.ge` (user `slick`, passwordless sudo)
OS: Ubuntu 24.04 LTS (noble), kernel `6.8.0-147-generic`
CPU: AMD GX-420CA SOC, 4 cores @ 2.0 GHz, 7.4 GiB RAM, 54 GB disk (43 GB free)
Hostname: `fit4`

Everything below runs **as user `slick` on fit4**, no root needed except where `sudo` is shown.

---

## 1. Stable device paths (always use these, never `/dev/ttyACMn`)

| Device | Stable path | Underlying node |
|---|---|---|
| STM32 "Blue Pill RF Lab" (cafe:4001), RF433-001 | `/dev/serial/by-id/usb-EE_Lab_Blue_Pill_RF_Lab_RF433-001-if00` | `ttyACM0` |
| ESP32-C3 USB-Serial/JTAG (303a:1001), MAC 68:9d:d2:79:2a:a4 | `/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_68:9D:D2:79:2A:A4-if00` | `ttyACM1` |
| Saleae Logic clone (0925:3881, fx2lafw) | libsigrok device `fx2lafw` (USB bus path changes; never hardcode) | `/dev/bus/usb/00X/00Y` |
| Intel Bluetooth 8087:07dc | `hci0` / controller `E4:70:B8:0A:DA:BA` | — |

Logic analyser wiring: **D0 = SRX882 data out**, **D1 = STX882 data in**.

Both tty nodes are `crw-rw---- root:dialout`, so serial access requires the `dialout` group (see §5).

Power-on serial `tx <hopping> <serial> <button> [frames]` goes to the **ESP32 only**; the STM32 has no ASCII console.

---

## 2. Installed versions

Host packages (`apt`):

```
build-essential  12.10ubuntu1   (gcc 13.3.0)
git              2.43.0
python3          3.12.3
python3-venv     3.12.3
pipx             1.4.3
rsync            3.2.7
libssl-dev       3.0.13-0ubuntu3.16 (OpenSSL 3.0.13)
curl             8.5.0
sigrok-cli       0.7.2            (libsigrok 0.5.2, libusb 1.0.27, libftdi 1.5)
libsigrokdecode4 0.5.3            (308 protocol decoders)
sigrok-firmware-fx2lafw 0.1.7
bluez            5.72-0ubuntu5.8  (+ bluez-tools)
```

User-level tools:

```
arduino-cli  1.5.1   (~/.local/bin/arduino-cli, official tarball, sha256 28a8e119c498a25607821c36cb2dc49e8463941b261a0d99091baa7bc692dd2b;
                     symlinked to /usr/local/bin/arduino-cli so non-interactive `ssh fit4 'arduino-cli ...'` finds it)
esp32 core   esp32:esp32 3.3.12  (exact pin; ~/.arduino15/packages/esp32/hardware/esp32/3.3.12, 7.8 GB with toolchains)
venv         ~/venv (python 3.12) → esptool 5.6.0, pyserial 3.5, bleak 3.0.2
```

arduino-cli board manager config (`arduino-cli config dump`):

```yaml
board_manager:
    additional_urls:
        - https://espressif.github.io/arduino-esp32/package_esp32_index.json
```

The sketch needs **no external Arduino libraries** — every include
(`BLE*`, `SPI`, `Update`, `driver/rmt_*`, `esp_ota_ops`, `mbedtls/*`,
`freertos/*`, `soc/*`, `hal/gpio_ll.h`) is bundled in esp32:esp32 3.3.12.
`arduino-cli lib list` → `No libraries installed.`

Repo checkout on fit4: `~/stm32-srx882s/{esp32c3-cc1101-lab,tools,firmware}`
(rsynced from the omarchy box, excluding build/dist/flashsite/*.bin).

---

## 3. Logic analyser (sigrok / fx2lafw)

Scan:

```bash
sigrok-cli --scan
#   demo     - Demo device with 13 channels: D0..D7 A0..A4
#   fx2lafw  - Saleae Logic with 8 channels: D0..D7
```

1 s capture at 2 MHz on D0+D1, native `.sr`:

```bash
sigrok-cli -d fx2lafw --config samplerate=2m --channels D0,D1 --time 1s -o /tmp/t.sr
```

Export — **`-O` is required**, `-o name.csv` alone does *not* pick the format
from the extension (it writes a `.sr` zip into `name.csv`):

```bash
sigrok-cli -i /tmp/t.sr -O csv  -o /tmp/t.csv     # 2,000,001 rows = 1 s @ 2 MHz
sigrok-cli -i /tmp/t.sr -O vcd  -o /tmp/t.vcd
sigrok-cli -i /tmp/t.sr -O bits -o /tmp/t.bits
```

Count transitions on the CSV (`col1 = D0`, `col2 = D1`, data starts on line 6):

```bash
awk -F, 'NR>5{if(p0!=""&&$1!=p0)c0++; if(p1!=""&&$2!=p1)c1++; p0=$1;p1=$2}
         END{printf "D0=%d D1=%d\n", c0+0, c1+0}' /tmp/t.csv
```

Decode with any of the bundled decoders (`sigrok-cli -L` lists 308 of them), e.g.:

```bash
sigrok-cli -i /tmp/t.sr -P uart:rx=D0:baudrate=2400 -A uart=rx_data
# fanned out while sampling:
sigrok-cli -d fx2lafw --config samplerate=2m --channels D0 --time 5s \
           -P uart:rx=D0:baudrate=2400 -A uart=rx_data
```

Measured on 2026-10-24 with the RF lab idle: 2,000,000 samples, **D0 constant 1
(idle high), D1 constant 0, zero transitions**. That is the expected idle level
of SRX882 data-out / STX882 data-in when nothing is transmitting — the LA
itself is fine (VCD export produced a single `#0 1! 0"` sample).

---

## 4. Talking to the boards

### 4.1 STM32 RF lab — COBS+CRC16 packet stream (read-only)

Baud 115200, host receives `STATUS` (type 2) heartbeats at 1 Hz and `FRAME`
(type 1) packets when the RF lab captures edges. Framing: frames split on
`0x00`, COBS-encoded payload, CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF)
as a little-endian trailer. `~/stm32-srx882s/tools/rf_capture.py` implements
the whole thing (stdlib `termios`, no pyserial needed):

```bash
STM=/dev/serial/by-id/usb-EE_Lab_Blue_Pill_RF_Lab_RF433-001-if00
timeout 6 ~/venv/bin/python ~/stm32-srx882s/tools/rf_capture.py \
        --port "$STM" --save /tmp/stm32.bin > /tmp/stm32.ndjson
```

Observed (5 s, 6 STATUS packets, **no FRAME**):

```
status seq 975 rid 1 clock 72000000 edges 1310 dropped_frames 31 dropped_packets 944
status seq 976 rid 1 clock 72000000 edges 1311 dropped_frames 32 dropped_packets 944
...
status seq 980 rid 1 clock 72000000 edges 1312 dropped_frames 33 dropped_packets 944
```

`receiver_id 1` = the STM32 unit, `clock_hz 72e6` = its timer clock.
`total_edges` is cumulative since boot and did not move during the capture →
no RF traffic on the STM32 at that moment (matches the LA capture).

### 4.2 ESP32-C3 — same protocol, plus an ASCII console

```bash
ESP=/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_68:9D:D2:79:2A:A4-if00
timeout 6 ~/venv/bin/python ~/stm32-srx882s/tools/rf_capture.py --port "$ESP" > /tmp/esp.ndjson
```

Observed (1 Hz STATUS, no FRAME):

```
status seq 861 rid 225 clock 1000000 edges 0 dropped_frames 1 dropped_packets 0
```

`receiver_id 225 = 0xE1` → **unit A** (`0xE2` would be unit B).
`clock_hz 1e6` = RMT capture clock (vs 72e6 on the STM32).

Plain-text diagnostic (harmless; only ever send this to the **ESP**):

```bash
printf 'radio\r\n' > "$ESP"      # or use pyserial in ~/venv
# reply: b'Unknown command. Use: tx <hopping> <serial> <button> [frames]\r\n'
```

So this board runs the newer lab firmware: the text console exists and accepts
`tx <hopping> <serial> <button> [frames]`, but `radio` is not a command in this
build. (Do not run `tx` unless you intend to transmit.)

### 4.3 esptool (no BOOT button needed)

The C3's native USB-Serial/JTAG resets itself into the ROM loader, so
`chip-id` / `flash-id` work with the board just sitting there:

```bash
~/venv/bin/esptool --chip esp32c3 --port "$ESP" chip-id
~/venv/bin/esptool --chip esp32c3 --port "$ESP" flash-id
```

Observed:

```
Chip type:          ESP32-C3 (QFN32) (revision v0.4)
Features:           Wi-Fi, BT 5 (LE), Single Core, 160MHz, Embedded Flash 4MB (XMC)
Crystal frequency:  40MHz
USB mode:           USB-Serial/JTAG
MAC:                68:9d:d2:79:2a:a4
WARNING: ESP32-C3 has no chip ID. Reading MAC address instead.
...
Manufacturer: 46  Device: 4016  Detected flash size: 4MB
```

`--after hard-reset` (esptool 5 default) is applied at the end, so the app is
running again afterwards — confirmed by STATUS packets reappearing immediately
(`seq 3`, `rid 225`). esptool 5.6 warns that `--before default_reset` /
`--after hard_reset` spellings are deprecated (use `default-reset` /
`hard-reset`).

### 4.4 Firmware builds (arduino-cli)

```bash
cd ~/stm32-srx882s/esp32c3-cc1101-lab
arduino-cli compile --warnings all --jobs 2 \
  --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc,PartitionScheme=no_fs,DebugLevel=none \
  --build-path /tmp/fwtest .
```

Result on this box (cold build ≈ 4.5 min, `--jobs 2`):

```
Sketch uses 665381 bytes (32%) of program storage space. Maximum is 2031616 bytes.
Global variables use 27232 bytes (8%) of dynamic memory, leaving 300448 bytes ...
EXIT=0        # zero warnings, zero errors with --warnings all
```

Artefacts land in `/tmp/fwtest/` (`esp32c3-cc1101-lab.ino.bin`, `.elf`,
`.bootloader.bin`, `.partitions.bin`, `.merged.bin`).

**Don't flash** unless told to. If you ever are, the command is
`arduino-cli upload -p "$ESP" --fqbn <same fqbn> --build-path /tmp/fwtest .`

### 4.5 Host unit tests

```bash
cd ~/stm32-srx882s/esp32c3-cc1101-lab/tests && make test
# ... ALL PASS   (needs libssl-dev for the OTA/CRC vectors)
```

### 4.6 Interactive capture/command TUI

`~/stm32-srx882s/tools/rf_console.py --port "$STM"` (or the ESP port) records
FRAME/STATUS to NDJSON and can send TX/scenario commands; it is a curses app,
so it needs a real TTY (ssh -t). Not used for any read-only check here.

### 4.7 Bluetooth

BlueZ daemon must be running; the Intel 8087:07dc radio is `hci0`
(controller `E4:70:B8:0A:DA:BA`, firmware already patched by the kernel driver).

```bash
sudo systemctl enable --now bluetooth
bluetoothctl power on
bluetoothctl --timeout 12 scan on
```

Observed:

```
[NEW] Device 68:9D:D2:79:2A:A6 KeeLoqLab-C3-A     # the ESP32-C3 (BLE addr = MAC+2)
[NEW] Device D7:E6:E8:3C:BC:3F tcl_B14S45H0_BC3F   # unrelated TV in range
```

Same result from Python (bleak 3.0.2, name + RSSI + NUS service):

```
68:9D:D2:79:2A:A6  rssi=-84  name='KeeLoqLab-C3-A'
                   services=['6e400001-b5a3-f393-e0a9-e50e24dcca9e']
```

`-B` (unit B) was **not** advertising; only unit A is on the bench.
A minimal bleak scan lives at `/tmp/bleak_scan.py` (copy it somewhere durable
if you want to keep it).

---

## 5. Groups, udev, ModemManager

* `slick` was added to **`dialout`** and **`plugdev`**:
  ```bash
  sudo usermod -aG dialout,plugdev slick
  id   # groups=...,20(dialout),...,46(plugdev),...
  ```
  Existing ssh sessions pick the new group up on the **next login**. All
  `/dev/ttyACM*` nodes are `root:dialout 0660`, so `dialout` is what makes
  serial work without sudo.

* Logic analyser: the distro ships libsigrok's rules already, nothing to write:
  `60-libsigrok.rules`, `61-libsigrok-plugdev.rules`, `61-libsigrok-uaccess.rules`
  in `/usr/lib/udev/rules.d/` (and `/lib/udev/rules.d` is the same dir on 24.04).
  The device gets `TAGS=:seat:uaccess:` from systemd-logind, so it works
  non-root in a local/ssh session. Applied without replugging via:
  ```bash
  sudo udevadm control --reload-rules && sudo udevadm trigger
  udevadm info -q property -n /dev/bus/usb/002/002 | grep -E 'TAGS|ID_MODEL'
  # TAGS=:seat:uaccess:  ID_MODEL=3881
  ```

* **ModemManager is disabled** — it probes every new `ttyACM*` and was holding
  the boards:
  ```bash
  sudo systemctl disable --now ModemManager   # disabled / inactive
  fuser -v /dev/serial/by-id/*                # (empty)
  sudo lsof /dev/ttyACM*                      # no holders
  ```

---

## 6. Gotchas hit during setup

1. **bluez was not installed**, only `libbluetooth3`; the kernel driver had
   already brought `hci0` up but there was no `bluetooth.service` /
   `bluetoothctl`. Fixed with `apt install bluez bluez-tools`.
2. **bleak 3.x removed `BLEDevice.rssi`** — use the advertisement object:
   `def cb(dev, adv): adv.rssi`. Older snippets using `dev.rssi` throw
   `AttributeError` inside the dbus callback (once per advertisement, very noisy).
3. **`sigrok-cli -i x.sr -o x.csv` does not infer the output format**; it wrote
   the `.sr` zip into `x.csv`. Always pass `-O csv` / `-O vcd` / `-O bits`.
4. **`arduino-cli` over ssh + `nohup ... &` hangs the ssh call** unless stdin is
   detached; use `nohup bash -c '...' </dev/null >log 2>&1 &` and poll the log.
   Progress output is `\r`-spammed, so read it with `tr '\r' '\n' < log | tail`.
   In a non-TTY, a successful build prints only the two "Sketch uses /
   Global variables" lines — the absence of compiler chatter is not a failure.
   Capture the exit status explicitly (`echo EXIT=$? > rc`) since a backgrounded
   build loses it.
5. **esptool 5.6 deprecation warnings** for `--before default_reset` /
   `--after hard_reset` (new spelling `default-reset` / `hard-reset`); harmless.
6. **ESP32-C3 has no chip id** — `chip-id` prints the MAC instead (by design).
7. **`ssh fit4 'cmd'` is non-interactive**: it does not read `~/.profile`, and
   `~/.local/bin` is *not* on `PATH` (`PATH=/usr/local/sbin:...:/snap/bin`).
   Hence the `/usr/local/bin/arduino-cli` symlink; the venv tools are always
   called as `~/venv/bin/esptool` / `~/venv/bin/python`.
8. The STM32 and ESP both report `dropped_packets` / `dropped_frames` counters
   that are cumulative and **not** reset by reading; don't read them as a
   per-capture error count.
9. `rf_capture.py` loops forever by design → wrap it in `timeout N` for a fixed
   listen window (exit status 124 then is expected).
10. USB bus/device numbers for the LA change between boots; always select by
   driver name (`-d fx2lafw`), never by `/dev/bus/usb/00X/00Y`.

---

## 7. Quick smoke test (copy/paste)

```bash
ESP=/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_68:9D:D2:79:2A:A4-if00
STM=/dev/serial/by-id/usb-EE_Lab_Blue_Pill_RF_Lab_RF433-001-if00

sigrok-cli --scan                                   # fx2lafw present
timeout 3 ~/venv/bin/python ~/stm32-srx882s/tools/rf_capture.py --port "$STM" | head -1
timeout 3 ~/venv/bin/python ~/stm32-srx882s/tools/rf_capture.py --port "$ESP" | head -1
~/venv/bin/esptool --chip esp32c3 --port "$ESP" flash-id | tail -3
bluetoothctl --timeout 8 scan on | grep -i keeloq
```


## 8. Flashing and resetting the C3 (lessons from bring-up)

* Flash: `esptool --chip esp32c3 --port $ESP --after no-reset write-flash 0x0 <merged.bin>`
  then reset with `esptool --chip esp32c3 --port $ESP read-mac` (its default
  hard-reset-via-RTS works; a hand-rolled pyserial DTR/RTS sequence did not).
  Do not use `--before no-reset` unless the chip is already in the ROM loader.
* A bad cable/port can drop the C3 off USB (`-62` errors in dmesg, no
  re-enumeration). Replug on a different port; `uhubctl`/authorized toggling
  cannot recover it.
* After re-flashing, BlueZ may keep a stale GATT cache: `sudo systemctl
  restart bluetooth` before BLE tests.
* ESP text diagnostics: `radio regs gpio rssi rxedges reinit spitest sweep
  txtest [pad|gpio] carrier` (replies are CRLF text mixed into the binary stream).
