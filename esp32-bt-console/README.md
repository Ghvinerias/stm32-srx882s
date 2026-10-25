# ESP32 Bluetooth KeeLoq Lab Console

Arduino sketch (`esp32_keeloq_lab/esp32_keeloq_lab.ino`) that reproduces
`firmware/src/lab_tx.c` + `firmware/src/lab_console.c` on an ESP32 DevKitV1,
using Bluetooth Classic SPP instead of USB CDC for the console, and the RMT
peripheral instead of a busy-wait GPIO loop for transmit timing.

Same scope and the same disclaimer as the STM32 lab: this sends exactly the
hopping/serial/button fields it's told to send. It does not perform KeeLoq
encryption and does not derive or guess any key material.

## Why ESP32 and not ESP8266

The WiFi/BT stack on either chip can preempt a bit-banged delay loop for
sub-millisecond stretches — risky against this protocol's ±150us timing
tolerance. The ESP32's RMT peripheral clocks the waveform out in hardware
with no CPU involvement once the item buffer is built, so it isn't subject
to that jitter at all. The ESP8266 has no equivalent peripheral.

## Wiring

- STX882 `DATA` -> GPIO4 (`TX_GPIO` in the sketch; change if you wire it
  elsewhere)
- STX882 `GND` -> ESP32 `GND`
- STX882 `VCC` -> per module's rating (most STX882 boards run 3.3-12V); the
  DATA line is a logic input and is fine driven from the ESP32's 3.3V GPIO

## Build

Arduino IDE or `arduino-cli`, board: `ESP32 Dev Module` (or your DevKitV1
variant), any partition scheme with Bluetooth Classic enabled (the sketch
`#error`s at compile time if `CONFIG_BT_ENABLED`/`CONFIG_BLUEDROID_ENABLED`
aren't set, which is the Arduino-ESP32 default for boards with BT).

## Using it

Pair the board over Bluetooth Classic as `ESP32-KeeLoq-Lab`, then open a
serial terminal over the resulting RFCOMM port (e.g. `screen /dev/rfcomm0`
or any BT serial terminal app). Command grammar is identical to the STM32
console:

```
tx <hopping> <serial> <button> [frames]     e.g. tx CF3EED68 FB3EC77 2 10
<hopping> <serial> <button> [frames]        bare form, same fields
zero-hop | plaintext-counter | random-hop | different-serial | synthetic-stale
help
```

`frames` is 1-10 (default 3). Ten frames is a ~1.02 s burst, which is the
convenient length for a logic-analyser capture.

## Binary protocol (Android app)

The board also speaks the STM32 firmware's COBS + CRC16 packet protocol, so
the Android app can drive it with no protocol changes — only a different
transport (RFCOMM instead of USB CDC). Wire formats match
`firmware/src/transport.c` exactly:

| packet | direction | size | layout |
|---|---|---|---|
| `TX_COMMAND` | host → device | 14 | version, type 3, hopping LE32, serial LE32, button, frame_count, CRC |
| `SCENARIO_COMMAND` | host → device | 5 | version, type 4, command char, CRC |
| `ACK` | device → host | 6 | version, type 5, for_type, accepted, CRC |
| `STATUS` | device → host | 30 | 18-byte header + 5 words (clock lo/hi, edges lo/hi, dropped), CRC |

Each packet is a COBS body followed by a single `0x00` delimiter; the CRC is
CRC-16/CCITT-FALSE over everything except the trailing CRC itself. `STATUS`
goes out once a second while a client is connected — the app treats three
seconds without one as a stale link, so it is required for the Connect
screen to show the board as alive. `clock_hz` reports 80 MHz and
`total_edges` is always 0: this board has no receive path, so `FRAME` is
never sent and the app's Receive screen stays STM32-only.

Input is auto-detected the same way `command_rx.c` does it: a frame that
contains any non-printable byte is a packet, an all-printable frame ending in
`\r`/`\n` is a console line. The **first valid packet permanently silences
the text console** for that power cycle, so a banner or `> ` prompt can never
land in the middle of the packet stream. Reset the board to get the text
console back.

`SCENARIO_COMMAND` for `r` (stale replay) is acknowledged with
`accepted = 0`: there is no stale-frame table on this board. `s`, `z`, `p`,
`x` and `d` work.

## Capturing a burst

The inter-frame gap is part of the RMT waveform, not a software delay, so the
whole burst is hardware-timed and the frame cadence does not move with
Bluetooth activity.

- Probe `GPIO4` and `GND`; sample at **1 MHz or more**. A 20 kHz capture (the
  rate used for earlier bench sessions) quantises to 50 us and cannot resolve
  the 760 us preamble cleanly.
- Capture ~2 s, trigger on the rising edge of `GPIO4`.
- Save the PulseView/sigrok session and decode it with the same tool used for
  the STM32 captures:

```
python3 tools/pulseview_session.py <session-file>
```

Expect one burst per frame (the 15.5 ms gaps are split as idle), each decoding
to the same code word with `repeat=0` on the first and `repeat=1` on the rest,
and a `repetition: median ...` line showing the cadence. For
`tx CF3EED68 FB3EC77 2 10` the reference numbers are 77 edges per frame
(780 in total), a first frame-start interval of 102.356 ms and then 102.013 ms
for the rest — the repeat bit shortens the final cell by 343 us (MID 1138 ->
SHORT 795), which is correct, not drift — and 1.021 s for the whole burst.

## What's intentionally different from the STM32 build

- No `stale`/`r` scenario (replaying the captured raw `stale_frame_us` pulse
  array) — that table lives only in the STM32 firmware; port it over if you
  need that specific scenario here too.
- Console transport is Bluetooth SPP, not USB CDC — there is no equivalent
  to the STM32 build's separate UART single-byte command path.

## Verification

The portable logic (console parsing/dispatch, bit layout, and the edge list
the RMT buffer is built from) is checked against stub `BluetoothSerial`/
`driver/rmt.h` headers that record what the real APIs would be given — this
confirms the first preamble edge is 760us/high and that each frame reduces
to 40 packed RMT items: 39 symbols holding the 77 frame edges plus the
inter-frame gap edge, and a zeroed 40th symbol as the explicit
end-of-transmission marker. It does not substitute for an on-target RF capture
comparison against the STM32 build's waveform.

The gap edge is inside that buffer on purpose. An earlier revision left the
gap to `delayMicroseconds()` between frames, which the Bluetooth controller's
high-priority interrupts stretch unpredictably, so the frame cadence wandered
and a 10-frame burst also outlasted the fixed 1000 ms `rmt_tx_wait_all_done()`
timeout. Both are fixed: the gap is encoded, all frames are queued before the
first finishes, and the timeout scales with the frame count.

The packet protocol is exercised end to end over RFCOMM with a host encoder
that mirrors the app's `Cobs`/`Crc16`/`PacketCodec`: `TX_COMMAND` returns
`ACK(accepted=1)`, `SCENARIO_COMMAND` `z` returns `ACK(1)` and `r` returns
`ACK(0)`, and `STATUS` arrives every second with a valid CRC and a
monotonically increasing sequence number. The same run confirms the stream
carries no text at all, and that no bytes are left over after the last
delimiter.
