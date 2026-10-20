# STM32 RF Lab — Android app (WIP)

**Status: protocol primitives, raw packet layer, USB transport and HCS301
decoder in place** (session 5 of the 10-session plan in [`PLAN.md`](PLAN.md));
the authoritative protocol and decode details live in
[`AGENT_BRIEF.md`](AGENT_BRIEF.md).

There is no UI wiring yet. The app still opens on a three-tab shell with
placeholder **Connect**, **Receive** and **Send** screens; session 6/9 will
bind the transport and decoder to them.

## What exists

- Standard Gradle / Android Studio project, Kotlin, single `app` module.
- Jetpack Compose + Material 3 with a `NavigationBar`/`NavHost` shell.
- `com.eelab.rflab.protocol.Cobs` and `Crc16`: JVM-testable Kotlin ports of
  `transport_cobs_encode` / `transport_cobs_decode` and `transport_crc16`
  from `firmware/src/transport.c`, with unit tests under `app/src/test/`.
  The COBS codec handles bodies only; the trailing `0x00` wire delimiter is
  handled by the transport layer.
- `com.eelab.rflab.protocol.PacketCodec` plus the packet types in
  `Packets.kt` (`FramePacket`, `StatusPacket`, `TxCommand`,
  `ScenarioCommand`, `AckPacket`): decoding for all five packet types with
  typed CRC/truncation/unknown-type failures, and encoders for `TX_COMMAND`
  and `SCENARIO_COMMAND`. This layer consumes/produces the raw bytes *after*
  COBS decoding (CRC included, delimiter excluded).
- `com.eelab.rflab.usb` (session 4), the transport layer:
  - `UsbDeviceFinder` matches VID `0xCAFE` / PID `0x4001` in
    `UsbManager.deviceList` and constructs `CdcAcmSerialDriver` directly
    (the pair is not in the library's default prober table).
  - `UsbPermission.requestPermission(...)` wraps `requestPermission()` plus
    the result `BroadcastReceiver` as a suspend function returning
    granted/denied (15 s default timeout, receiver always unregistered).
  - `PacketReassembler` buffers raw bulk-read bytes, splits on the `0x00`
    delimiter, and runs each body through `Cobs.decode` then
    `PacketCodec.decode`. It is plain Kotlin and fully JVM-tested, including
    arbitrary chunk boundaries, multiple packets per chunk, CRC/COBS
    failures, empty frames, and size-capped recovery from noise.
  - `RfLabConnection.open(...)` claims the port, sets 115200 8N1 (required by
    the API, irrelevant to the native USB link), runs a background read
    coroutine for the connection's lifetime, and exposes `events`
    (`SharedFlow<TransportEvent>`), `write(bytes)`, `sendPacket(rawPacket)`
    (COBS + delimiter) and `close()`.
- `com.eelab.rflab.decode` (session 5), the HCS301-shaped signal decoder:
  - `Hcs301Timing.kt` holds the measured constants from the brief
    (`PREAMBLE_EDGES = 11`, `HEADER_ZERO_US = 4512`,
    `HEADER_ONE_US = 4170`, `SHORT_US = 795`, `MID_US = 1138`,
    `LONG_US = 1482`, `TOLERANCE = 0.10`) and the 77-pulse frame length.
  - `ScaleEstimator` is an incremental port of `estimate_scale()` from
    `tools/decode_hcs301.py`: pool every data cell (`pulses[12..]`) across
    frames, take the median, divide by `MID_US`; below 8 samples or outside
    `[0.85, 1.15]` it returns 1.0.
  - `Hcs301Decoder.decodeFrame(pulses, scale)` rejects wrong-length frames,
    out-of-tolerance headers/data cells and invalid 0->1 / 1->0 transitions,
    then extracts the 32-bit hopping word, 28-bit serial, 4-bit button, VLOW
    and repeat LSB-first. `DecodedSignal` carries those fields plus the
    source `FramePacket` timestamp/sequence and the scale used.
- `com.github.mik3y:usb-serial-for-android:3.11.0` dependency.
- USB host feature declared, plus `res/xml/device_filter.xml` matching the lab
  board (VID `0xCAFE` / PID `0x4001`, decimal `51966` / `16385`) and an
  `USB_DEVICE_ATTACHED` intent filter.
- `minSdk 23`, `targetSdk 35`, `compileSdk 35`.

## Build

Requires JDK 17+ and Android SDK platform 35 (build-tools 35.0.0). Open the
`android-app/` directory in Android Studio, or:

```sh
./gradlew test          # JVM unit tests, no device required
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Notes for later sessions

- `RfLabConnection.events` has no replay: subscribe before connecting if
  every packet matters. The buffer applies backpressure rather than dropping
  packets under a slow collector.
- The USB-dependent parts (`UsbManager`, `CdcAcmSerialDriver`, permission
  dialog) need a real device or instrumentation; all stream/framing logic
  lives in `PacketReassembler` and is unit-tested on the JVM.
- Sending must stay strictly sequential: one command, wait for its ACK or a
  timeout, then the next (session 8).
- Feed every received `FramePacket.pulses` to `ScaleEstimator.addFrame(...)`
  before decoding (even malformed-length frames: the reference pools their
  data cells too). Decode each frame with the estimator's current `scale`.
  The first frame before any calibration uses 1.0; for a transmitter running
  a few percent off, its header can land exactly between the two header
  centres, where the reference's `<=` tie-break picks the wrong leading bit
  and rejects the frame. That is expected parity with the Python tool, not a
  decoder bug — calibration applies from the next frame onward.
- The decoder's unit tests are anchored to three real captures copied into
  `app/src/test/resources/fixtures/` (`r1-btn-1`, `r3-test`,
  `r3-new-test`), with expected values generated by
  `tools/decode_hcs301.py`. Session 10 can extend this fixture set.
