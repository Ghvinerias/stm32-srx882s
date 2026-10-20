# Agent brief: Android USB-serial control app for the STM32 RF lab

Hand this document to an implementing agent (or follow it yourself) to
build the app. It contains the exact wire protocol and decode algorithm
the firmware actually uses, pulled directly from the current source, so
there should be no need to re-derive anything from scratch.

## Scope and boundary (carries over from the rest of this repo)

This app reconstructs and replays only the **clear-text** fields of an
HCS301-shaped frame: the 32-bit hopping word *as transmitted* (still
ciphertext -- this app does not decrypt it), the 28-bit serial, the 4-bit
button, VLOW, and repeat. It does not attempt decryption, key derivation,
or brute-forcing of anything. "Receive, display, select, replay exactly"
is the entire feature set -- same boundary as `tools/decode_hcs301.py`,
`firmware/src/lab_tx.c`, and the ESP32 sketch in `esp32-bt-console/`.

## What the app does

1. Connects to the STM32 over USB (it enumerates as a CDC-ACM device).
2. **Receive**: continuously reads the binary capture packet stream,
   decodes HCS301-shaped frames locally (a Kotlin port of
   `tools/decode_hcs301.py`'s algorithm -- not an embedded Python
   interpreter), and shows a readable, scrolling list of decoded signals
   (timestamp, button, hopping, serial).
3. The user can select one or more signals from that list and add them to
   a **send queue** (this is the "copy" step).
4. **Send**: a queue/batch screen lists pending items (editable frame
   count per item), plus a manual-entry row for typing in
   hopping/serial/button/frames directly. Sending always uses the binary
   protocol (never the firmware's ASCII text-command alternative -- that
   exists only for manual terminal testing, not for this app).
5. Batch send walks the queue strictly one item at a time, waiting for
   each item's ACK (or timing out) before sending the next -- see
   "Batch-sending timing constraint" below; this is a correctness
   requirement, not just a UX nicety.

## Connecting to the device

- The board enumerates as a CDC-ACM USB device, USB VID `0xCAFE`, PID
  `0x4001` (strings: manufacturer "EE Lab", product "Blue Pill RF Lab").
- Recommended library: `io.github.mik3y:usb-serial-for-android` (actively
  maintained; check current Maven coordinates/version at integration
  time). It ships a generic `CdcAcmSerialDriver` for standard CDC-ACM
  devices.
- **Important**: this VID/PID is not in that library's auto-detection
  table (a vendor-specific pair that happens to use the standard CDC
  class). Don't rely on `UsbSerialProber.getDefaultProber()` to find it.
  Instead: enumerate `UsbManager.deviceList`, match on
  `device.vendorId == 0xCAFE && device.productId == 0x4001`, then
  construct `CdcAcmSerialDriver(device)` directly against that device.
- Standard Android USB host permission flow applies: request
  `UsbManager.requestPermission()`, handle the result via a
  `BroadcastReceiver` for the permission-granted intent, then open the
  connection and claim the interface.
- Baud rate is irrelevant to a native USB CDC-ACM device like this one
  (there's no real UART underneath) -- any value (e.g. 115200 8N1) is
  fine to set for API compliance.
- Keep one background thread/coroutine continuously reading the bulk IN
  endpoint and feeding it into the packet reassembler described below;
  it should run for the lifetime of the connection.

## Wire protocol

Every packet, both directions, is COBS-encoded with a trailing `0x00`
byte as the packet delimiter on the wire. That `0x00` is **not** part of
the COBS-encoded body itself -- it's appended after encoding, purely as a
stream delimiter (mirrors `transport_cobs_encode`/`transport_cobs_decode`
in `firmware/src/transport.c`, which is the authoritative reference if
anything here is ambiguous).

### COBS

Standard COBS (Consistent Overhead Byte Stuffing). Implement the general
algorithm (don't take a small-packet-only shortcut) -- group runs of
non-zero bytes, prefixed by a length-code byte (max group 254 bytes
before forcing a new code), replacing each embedded `0x00` with a group
boundary. Decode is the exact inverse. Reference implementation to port
sits in `firmware/src/transport.c` (`transport_cobs_encode` /
`transport_cobs_decode`); the two are intentionally dual-use on the
device (device encodes outgoing packets, decodes incoming ones) and your
app needs both directions too.

### CRC16

Every packet's last two bytes are a CRC-16 (little-endian) over every
preceding byte. Algorithm (CRC-16/CCITT-FALSE equivalent): init
`0xFFFF`, polynomial `0x1021`, MSB-first per bit, no final XOR, no
reflection:

```
crc = 0xFFFF
for each byte b in data:
    crc ^= (b << 8)
    repeat 8 times:
        if crc & 0x8000: crc = (crc << 1) ^ 0x1021
        else:            crc = crc << 1
    crc &= 0xFFFF
return crc
```

(Exact port of `transport_crc16` in `transport.c`.)

### Packet types

```
1 = FRAME             (device -> host)
2 = STATUS             (device -> host)
3 = TX_COMMAND          (host -> device)
4 = SCENARIO_COMMAND    (host -> device)
5 = ACK                 (device -> host, reply to 3 or 4)
```

All multi-byte integer fields below are little-endian.

### FRAME / STATUS (device -> host), raw layout before COBS encoding

18-byte header, then payload, then 2-byte CRC:

| offset | size | field |
|---|---|---|
| 0 | 1 | version (`1`) |
| 1 | 1 | type (`1`=FRAME or `2`=STATUS) |
| 2 | 2 | flags (bitfield, see below) |
| 4 | 2 | sequence number |
| 6 | 2 | total raw packet length (header+payload+crc, this whole packet) |
| 8 | 4 | timestamp_us (FRAME only; 0 for STATUS) |
| 12 | 2 | dropped_frames / capture_drops counter |
| 14 | 2 | payload word count (`pulse_count` for FRAME, always `5` for STATUS) |
| 16 | 1 | initial_level (FRAME only; 0 for STATUS) |
| 17 | 1 | receiver_id (always `1`) |
| 18.. | 2*N | payload: N x u16 words, N = the count at offset 14 |
| (end-2) | 2 | CRC-16 over every byte from offset 0 up to (not including) this field |

Flags bitfield (offset 2): bit0 = capture truncated, bit1 = DMA overrun,
bit8 = clock fallback (persistent, set once at boot if the system clock
didn't lock), bit9 = capture init failed (persistent).

**FRAME payload**: the N u16 words are pulse widths in microseconds --
feed this array straight into the decode algorithm below (it's exactly
`pulses_us` from `tools/*.ndjson` captures).

**STATUS payload** (always 5 words): `[clock_hz_low16, clock_hz_high16,
total_edges_low16, total_edges_high16, dropped_packets]`. Mainly useful
as a liveness heartbeat (STATUS is sent once a second); the app can
reassemble `clock_hz`/`total_edges` as 32-bit values if useful for a
diagnostics screen, but there's no hard requirement to surface them.

### TX_COMMAND (host -> device), 14 raw bytes

| offset | size | field |
|---|---|---|
| 0 | 1 | version (`1`) |
| 1 | 1 | type (`3`) |
| 2 | 4 | hopping (u32) |
| 6 | 4 | serial (u32; device masks to 28 bits) |
| 10 | 1 | button (nibble, 0-0xF) |
| 11 | 1 | frame_count (1-10; device clamps 0->1 and >10->10) |
| 12 | 2 | CRC-16 over bytes 0-11 |

### SCENARIO_COMMAND (host -> device), 5 raw bytes

| offset | size | field |
|---|---|---|
| 0 | 1 | version (`1`) |
| 1 | 1 | type (`4`) |
| 2 | 1 | command_char: one of `'r'` `'s'` `'z'` `'p'` `'x'` `'d'` |
| 3 | 2 | CRC-16 over bytes 0-2 |

(`'r'`=stale-replay, `'s'`=synthetic-stale, `'z'`=zero-hop,
`'p'`=plaintext-counter, `'x'`=random-hop, `'d'`=different-serial -- see
`lab_tx_run_scenario` in `firmware/src/lab_tx.c` for what each does; all
six are reachable through this packet type even though the firmware's
separate ASCII text-command mode only exposes five by name.)

### ACK (device -> host), 6 raw bytes

| offset | size | field |
|---|---|---|
| 0 | 1 | version (`1`) |
| 1 | 1 | type (`5`) |
| 2 | 1 | for_type: echoes the type it's answering (`3` or `4`) |
| 3 | 1 | accepted: `1` if executed, `0` if rejected/failed |
| 4 | 2 | CRC-16 over bytes 0-3 |

### Batch-sending timing constraint (read this before implementing batch send)

**The app must wait for each command's ACK (or a timeout of a few
seconds) before sending the next queued command.** This is not optional
polish -- it's required for correctness:

- A `TX_COMMAND` with a high `frame_count` blocks the device for a while
  (each frame plus inter-frame gaps; worst case, `frame_count=10`, is on
  the order of several hundred milliseconds) before its ACK is sent.
- The device's USB receive ring buffer is a fixed 256 bytes. If the app
  fires a whole batch of commands back-to-back without pacing, and the
  device is busy blocking on one command's transmission for long enough,
  incoming bytes can arrive faster than they're drained and get silently
  dropped by the ring buffer -- corrupting the framing of whatever
  packet was mid-transmission at that moment.
- A generous per-item timeout (e.g. 2 seconds) comfortably covers
  worst-case `frame_count=10` and keeps the implementation simple: send
  one, await its ACK or timeout, then send the next.
- The ACK protocol has no per-request ID -- it only echoes `for_type`
  (3 or 4), not which specific queued item it's answering. Strict
  sequential send-then-wait is also what makes "the next ACK received
  corresponds to the item just sent" an unambiguous, race-free
  assumption; don't pipeline multiple in-flight commands.

## Decode algorithm (Kotlin port of `tools/decode_hcs301.py`)

Port this exactly; it's the authoritative, already-validated reference
(validated against real captures and against the PulseView decoder in
`tools/sigrok-hcs301-lab/`).

Constants (microseconds):
```
PREAMBLE_EDGES = 11
HEADER_ZERO_US = 4512
HEADER_ONE_US  = 4170
SHORT_US = 795
MID_US   = 1138
LONG_US  = 1482
TOLERANCE = 0.10   // relative, i.e. +/- 10% of the (scaled) center value
```

A `FRAME` packet's pulse array decodes to one signal only if it has
exactly `PREAMBLE_EDGES + 1 + 65 = 77` entries; otherwise skip it (not a
valid frame -- noise, truncation, or a different protocol).

**Per-capture (or per-connection-session) timing scale, estimated once
you have a handful of frames, and re-usable across all of them**: take
the median of every data-cell pulse width across all frames you've seen
so far (i.e. every pulse *after* the header edge, `pulses[12..]`, pooled
across frames), divide by `MID_US`. If you don't have at least ~8 pooled
data-cell values yet, or the computed scale falls outside `[0.85, 1.15]`,
use `1.0` instead -- this guards against estimating nonsense from noise.
This mirrors `estimate_scale()` in `decode_hcs301.py` exactly: it is a
median-based estimate off the data cells specifically, *not* an
average of the preamble edges (a different, simpler scheme used
elsewhere in this repo's PulseView decoder -- don't port that one here,
use this one, it's the current authoritative version).

Per frame, given `pulses: IntArray` of length 77 and the scale factor:

```
header = pulses[11]
zero = HEADER_ZERO_US * scale
one  = HEADER_ONE_US * scale
if header not within 10% of either zero or one: reject frame
bit0 = 0 if |header - zero| <= |header - one| else 1

bits = IntArray(66); bits[0] = bit0
for i in 0 until 65:
    duration = pulses[12 + i]
    short = SHORT_US * scale; mid = MID_US * scale; long = LONG_US * scale
    pick the nearest of {short, mid, long} to `duration`
    if |duration - nearest| > mid * TOLERANCE: reject frame
    if nearest == short:
        if bits[i] != 0: reject frame   // must be a 0->1 transition
        bits[i+1] = 1
    else if nearest == long:
        if bits[i] != 1: reject frame   // must be a 1->0 transition
        bits[i+1] = 0
    else: // mid
        bits[i+1] = bits[i]

hopping = bits[0..31] read LSB-first as a 32-bit value
serial  = bits[32..59] read LSB-first as a 28-bit value
button  = bits[60..63] read LSB-first as a 4-bit value
vlow    = bits[64]
repeat  = bits[65]
```

## UI suggestions (flexible; the protocol/decode sections above are the
hard constraints, this part is just a reasonable starting shape)

- **Connect screen**: permission/connect button, a simple
  connected/disconnected indicator (STATUS packets arriving ~1/s is a
  good liveness signal).
- **Receive screen**: scrolling list of decoded signals (grouped
  sensibly -- e.g. collapse a press's repeats, which share the same
  hopping/serial/button and arrive within milliseconds of each other,
  into one row with a repeat count). Each row: timestamp, button
  (hex nibble), hopping (hex), serial (hex). Multi-select (checkboxes or
  long-press) + an "Add N to send queue" action.
- **Send screen**: the current queue (reorderable, removable, per-item
  editable frame count 1-10), a manual-entry row (four hex fields:
  hopping/serial/button/frames) to add a custom item without needing a
  captured signal, optional quick-action buttons for the six preset
  scenarios (sends `SCENARIO_COMMAND` directly), and a "Send All" button
  that runs the batch per the timing constraint above, showing per-item
  state (pending/sending/acked-ok/acked-failed/timeout).

## Suggested project layout

New top-level `android-app/` directory (sibling to `firmware/`,
`esp32-bt-console/`, `tools/`), a standard Gradle Android Studio project,
Kotlin, single `app` module. Minimum SDK: whatever the chosen USB-serial
library requires for its USB host APIs (API 23+ is a safe floor for USB
host features; confirm against the library's own requirements at
integration time).
