# STM32 RF Lab — Android controller

An Android app that talks to the STM32 RF lab board (`firmware/`) over a
USB CDC-ACM serial link: it **receives** HCS301-shaped frames, decodes the
clear-text fields locally, and **replays** them (or manually entered
commands, or the firmware's preset scenarios) back through the board's
transmitter.

The wire protocol and decode algorithm are specified in
[`AGENT_BRIEF.md`](AGENT_BRIEF.md); the ten-session build plan is in
[`PLAN.md`](PLAN.md). Both are the authority for protocol details — this
README explains the shipped app.

## Scope boundary

This app reconstructs and replays **only the clear-text fields** of an
HCS301-shaped frame: the 32-bit hopping word *as transmitted* (still
ciphertext — the app never decrypts it), the 28-bit serial, the 4-bit
button, VLOW and repeat. It performs no decryption, key derivation, or
brute-forcing. "Receive, display, select, replay exactly" is the whole
feature set — the same boundary as `tools/decode_hcs301.py`,
`firmware/src/lab_tx.c`, and the ESP32 sketch in `esp32-bt-console/`.

Sending always uses the binary packet protocol. The firmware's ASCII
text-command mode is not used by this app.

## Architecture

Kotlin, Jetpack Compose + Material 3, single `app` module, `minSdk 23`,
`targetSdk`/`compileSdk` 35. No DI framework: the app has one connection,
one queue and one sender, wired explicitly in the composition roots
([`ui/RfLabApp.kt`](app/src/main/java/com/eelab/rflab/ui/RfLabApp.kt) and
[`send/SendGraph.kt`](app/src/main/java/com/eelab/rflab/send/SendGraph.kt)).

```
USB device
   │  bulk IN (COBS body + 0x00 delimiter)
   ▼
usb.RfLabConnection ──► usb.PacketReassembler ──► protocol.PacketCodec ──► TransportEvent
   (transport + loop)     (split/delim/COBS)        (CRC + layout)          │
                                                                           ├─► ui.connect.ConnectViewModel
                                                                           │     (STATUS heartbeat, diagnostics)
                                                                           ├─► ui.receive.ReceiveViewModel
                                                                           │     (ScaleEstimator → Hcs301Decoder
                                                                           │      → ReceivedSignalAccumulator)
                                                                           └─► send.BatchSendEngine (ACK waits)
                                                                                 ▲
                                          host → device: TX_COMMAND / SCENARIO_COMMAND
                                          (COBS + 0x00, one command in flight at a time)
```

| Package | Responsibility |
|---|---|
| `protocol` | `Cobs` (port of `transport_cobs_encode/decode`), `Crc16` (CRC-16/CCITT-FALSE), `PacketCodec` and the packet types (`FramePacket`, `StatusPacket`, `TxCommand`, `ScenarioCommand`, `AckPacket`). Works on raw bytes after COBS decoding; the trailing `0x00` delimiter is the transport's job. |
| `usb` | `UsbDeviceFinder` (VID `0xCAFE` / PID `0x4001`, direct `CdcAcmSerialDriver` — the pair is deliberately absent from usb-serial-for-android's prober table), `UsbPermission` (suspend permission request with a 15 s timeout), `PacketReassembler` (arbitrary chunk boundaries, resynchronises after corrupt frames), `RfLabTransport` / `UsbSerialTransport` (byte-stream seam and its USB CDC-ACM implementation, which owns the port parameters and transfer timeouts), `RfLabConnection` (transport + background read loop + writes), `RfLabSession` (the app-wide current connection), `ConnectPort` / `UsbConnectController` (connection-lifecycle seam). |
| `decode` | `Hcs301Decoder` and `ScaleEstimator`, exact Kotlin ports of `tools/decode_hcs301.py`, plus the measured timing constants. |
| `queue` | `SendQueueRepository` — the process-wide `StateFlow<List<SendQueueItem>>`, ids, reorder, frame-count clamp, per-item status. |
| `send` | `BatchSendEngine` — strict sequential send-then-await-ACK; `SendGraph` — the app's single engine instance. |
| `ui/connect` | `ConnectViewModel`, `ConnectUiState`, `StatusHeartbeat` (3 s stale threshold, 500 ms poll). |
| `ui/receive` | `ReceiveViewModel` (transport events → decoded signals), `ReceivedSignalAccumulator` (collapses press repeats within 500 ms on host arrival time), row/format types. |
| `ui/send` | `SendViewModel` (queue editing, manual hex entry validation, preset scenarios, batch summary), UI state types. |
| `ui/screens` | Compose screens: a stateful wrapper plus a stateless body, so layouts can be previewed and tested without USB or a ViewModel. |
| `ui` | `RfLabApp` composition root: bottom-nav shell, one `UsbConnectController`, one `BatchSendEngine`, and one activity-scoped `ReceiveViewModel` that decodes from process start. |

## Build and test

Requirements: JDK 17+ (JDK 21 works), Android SDK platform 35 and
build-tools 35.0.0. Point `local.properties` at your SDK, or open the
`android-app/` directory in Android Studio.

```sh
./gradlew test           # JVM unit tests — no device, no emulator
./gradlew assembleDebug  # debug APK in app/build/outputs/apk/debug/
./gradlew lintDebug      # Android lint
./gradlew clean test assembleDebug
```

The tests run on the JVM only: the USB APIs are behind `ConnectPort` /
injected lambdas. Everything with real logic is plain Kotlin and covered —
COBS/CRC vectors, packet layouts and failure modes, reassembly across
arbitrary chunk boundaries, the decoder against real captures, repeat
grouping, the send state machine (fake event flow), and the connect state
machine (fake port).

## Hardware

- STM32 "Blue Pill" RF lab board running the firmware in `firmware/`,
  enumerated as a **CDC-ACM** device with VID `0xCAFE`, PID `0x4001`
  (strings "EE Lab" / "Blue Pill RF Lab").
- An Android phone/tablet with **USB host (OTG)** support (`android.hardware.usb.host`).
  A USB-C OTG adapter is normally required.
- The VID/PID is vendor-specific, so the app matches it explicitly and does
  **not** use `UsbSerialProber`'s default table.
- Baud rate is irrelevant (native USB CDC-ACM, no UART underneath); the app
  sets 115200 8N1 only because the API requires it.
- The device's USB receive ring is 256 bytes, which is why commands are sent
  strictly one at a time (see `BatchSendEngine`).

## Usage walkthrough

1. **Connect** — plug the board in over OTG, open the Connect tab and tap
   *Connect*. Android shows its USB permission dialog the first time;
   a denial or a 15 s timeout lands on a retryable "USB permission denied"
   state. Once connected, the dot turns green when the first STATUS arrives
   (the board sends one per second) and the card shows clock, edge and drop
   diagnostics. "Connected but stale" after 3 s of silence means the link is
   open but the board is not sending.
2. **Receive** — press a remote near the receiver. Each decoded frame
   appears as a row with host timestamp, button nibble, hopping word and
   serial; repeats of one press collapse into a single row with a `×N`
   badge. Tap rows (or their checkboxes) and press *Add N to send queue*.
   Malformed frames are counted in the header ("N malformed frames ignored")
   and otherwise ignored.
3. **Send** — the queue lists what you added. Reorder, remove, and set each
   item's frame count (1–10). You can also add a manual command (hex
   hopping/serial/button, decimal frames) and fire any of the six preset
   scenarios (`r s z p x d`). *Send All* walks the queue strictly one item at
   a time: write → wait for that item's ACK (2 s timeout) → next. Each item
   shows Pending / Sending / Acked OK / Acked failed / Timeout / Link failed.
4. **Disconnect** on the Connect tab closes the port. Leaving the tab does
   not disconnect, and the Receive list keeps decoding in the background.

## Error handling

- **Malformed/corrupt frames** become `TransportEvent.DecodeError` events
  (bad COBS, bad CRC, truncated/foreign packet, oversized frame). The
  reassembler resynchronises at the next delimiter; the Receive screen counts
  them but never feeds them to the decoder, so a noisy link cannot crash or
  wedge the list.
- **Device unplugged / link lost** makes the read loop emit
  `ConnectionLost`, flips `RfLabConnection.openState` false, and detaches the
  session. The Connect screen shows `Error("Connection lost: …")`, the Send
  screen disables itself immediately, and a batch in flight stops at once.
- **Disconnect mid-batch**: the in-flight item becomes `FAILED` (not
  `TIMEOUT` — nothing is known about whether it was transmitted), the
  remaining items stay `PENDING` (never attempted), and the summary reports
  the counts. Cancelling the screen mid-batch also leaves no item stuck on
  `Sending`.
- **ACK timeout** (link still up, one command lost) marks that item
  `TIMEOUT` and continues with the next, per the brief.
- **USB permission denial** is a distinct, retryable phase with its own
  message; it never leaves the UI spinning in "Connecting".

## Tests and real-capture fixtures

`app/src/test/resources/fixtures/` holds byte-for-byte copies of NDJSON
captures from `tools/captures/`. `RealCaptureDecodeTest` estimates the scale
exactly as `tools/decode_hcs301.py` does and asserts every frame's
hopping/serial/button/VLOW/repeat against that reference's output:

| Fixture | Frames | Scale | Notes |
|---|---|---|---|
| `r1-btn-1` | 19 | 0.9991 | nominal-speed transmitter |
| `r3-test` | 52 | 1.0404 | ~4 % slow transmitter (calibration case) |
| `r3-new-test` | 20 | 1.0404 | same transmitter, later press |
| `hold-btn1` | 18 | 1.0404 | held button across nibbles 0x2/0x4/0x8 |
| `r3-usb-sanity` | 34 | 1.0404 | captured through the real USB link |

`ReceivePipelineTest` takes `r3-usb-sanity`, re-encodes each frame exactly as
the firmware transport would (header + CRCs + COBS + `0x00`), feeds the byte
stream through `PacketReassembler` in deliberately awkward chunks, and asserts
the Receive screen ends up with the nine expected grouped rows — an
end-to-end check of sessions 2–6 on real data.

Regenerate expected values with:

```sh
python3 tools/decode_hcs301.py tools/captures/<name>.ndjson
```

## Known limitations / what still needs a real device

- Opening the port, the Android permission dialog, and the actual CDC-ACM
  read/write path are only exercised on hardware; the JVM tests stop at the
  `ConnectPort` and write-lambda seams. A manual QA pass should confirm the
  permission dialog/denial, hot-unplug during a batch, and a real
  board round-trip (captured ACKs, no lost frames).
- The receive `ScaleEstimator` is reset when the link is lost, but a
  deliberate disconnect followed by connecting a *different* transmitter
  keeps the pooled data cells from the previous one. The estimate is a
  median with a ±10 % tolerance, so in practice both still decode; a fully
  clean design would key the estimator to the connection instance.
- The queue is in memory only; it does not survive process death.
- The app does not surface the firmware's `flags` bitfield (truncated
  capture, DMA overrun, clock fallback) in the UI; the fields are decoded and
  available on `FramePacket`/`StatusPacket`.
