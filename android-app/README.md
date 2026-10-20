# STM32 RF Lab — Android app (WIP)

**Status: protocol primitives, raw packet layer, USB transport, HCS301
decoder, live receive screen, send queue, sequential batch-send engine and the
real Connect screen/lifecycle wiring are in place** (session 9 of the
10-session plan in [`PLAN.md`](PLAN.md)); the authoritative protocol and
decode details live in [`AGENT_BRIEF.md`](AGENT_BRIEF.md).

The **Connect** tab finds the board (VID `0xCAFE` / PID `0x4001`), requests
USB permission through the Android permission dialog, opens the CDC-ACM port
and attaches it to the shared session. It then shows a STATUS-heartbeat
indicator (fresh/stale, 3 s threshold) and the optional clock/edge
diagnostics. The **Receive** tab decodes FRAME packets from that same
connection, collapses press repeats into rows and can copy selected rows to a
shared send queue. The **Send** tab manages that queue, supports manual entry
and the six preset scenarios, and sends strictly one command at a time:
each item is written, then the next ACK (or a 2 s timeout) must resolve
before the next item starts. Sending is disabled while disconnected; queue
editing still works offline.

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
- `com.eelab.rflab.ui.receive` and the live `ReceiveScreen` (session 6):
  - `ReceiveViewModel` subscribes to a `Flow<TransportEvent>`, keeps one
    running `ScaleEstimator` for the ViewModel (adding every frame's
    pulses before decoding, including malformed ones), and runs
    `Hcs301Decoder.decodeFrame` at the current scale. Rejected frames and
    non-FRAME packets are dropped.
  - `ReceivedSignalAccumulator` collapses decodes with the same
    hopping+serial+button that arrive within **500 ms** of the previous
    decode into one row (`repeatCount`, first-seen timestamp retained).
    Grouping uses host arrival time from an injected clock, not the device's
    32-bit microsecond timestamp (which wraps every ~71 min).
  - Rows are newest first; tapping a row or its checkbox toggles selection,
    and "Add N to send queue" copies every selected row into
    `SendQueueRepository.shared` as `SendQueueItem(id, hopping, serial,
    button)` and clears the selection. The header shows the current queue
    size.
  - The screen is split into a stateful wrapper plus a stateless body, so
    the row/selection/queue UI has no ViewModel or USB dependency.
- `com.eelab.rflab.queue.SendQueueRepository` (session 7): process-wide
  `StateFlow<List<SendQueueItem>>` with add/remove/reorder/frame-count edit
  and `setStatus`. Items carry `PENDING`/`SENDING`/`ACKED_OK`/
  `ACKED_FAILED`/`TIMEOUT`.
- `com.eelab.rflab.send.BatchSendEngine` (session 8): the strict
  sequential send-then-await-ACK engine required by the brief's timing
  constraint.
  - `sendBatch(items, onStatusChange)` marks one item `SENDING`, writes
    its encoded `TX_COMMAND`, waits for the next `for_type = 3` ACK or a
    configurable timeout (2 s in production), reports the terminal
    status, and only then starts the next item. It never pipelines.
  - `sendScenario(cmd)` is the one-shot `SCENARIO_COMMAND` path, waiting
    for `for_type = 4`.
  - Both share one mutex, so a preset press cannot overlap a batch.
  - Each wait subscribes to the shared event stream before writing with
    `CoroutineStart.UNDISPATCHED`, ignores FRAME/STATUS/decode errors and
    ACKs for the other command type, and cancels its collector on
    resolution. Other collectors (the receive screen) are unaffected.
  - A failed write aborts the remaining batch items (left `PENDING`) and
    reports the failed item as `TIMEOUT`; a normal timeout continues with
    the next item, per the brief.
  - `SendGraph` is the composition root: it binds the engine to
    `RfLabSession.events` and the current connection's `sendPacket`, and
    `RfLabApp` passes the single instance to `SendScreen`/`SendViewModel`.
  - JVM tests use a `MutableSharedFlow` fake event source and a captured
    write function to cover prompt ACK, `accepted = 0`, timeout,
    unrelated traffic, strict 3-item ordering, and the scenario path.
- `com.eelab.rflab.usb.ConnectPort` / `UsbConnectController` (session 9):
  the connection-lifecycle seam. `ConnectPort` is the small JVM-testable
  interface (`connect()`, `disconnect()`, `isConnected()`, `events`);
  `UsbConnectController` is the production implementation that enumerates
  `UsbManager.deviceList`, asks `UsbPermission` for access, opens
  `RfLabConnection` and calls `RfLabSession.attach(...)`. It holds only the
  **application Context** — USB permission is delivered through a
  `PendingIntent` broadcast, so no Activity is needed. The permission wait is
  a coroutine suspension and the blocking port open runs off the main
  thread; an open that finishes after the user cancelled is closed instead of
  attached.
- `com.eelab.rflab.ui.connect` (session 9), the Connect screen state:
  - `ConnectViewModel` exposes `Disconnected` / `Connecting` /
    `PermissionDenied` / `Connected` / `Error(message)` and drives
    connect/disconnect through `ConnectPort`. A `ConnectionLost` transport
    event moves to `Error` and detaches the dead link. Leaving the tab does
    not disconnect; re-creating the ViewModel while attached starts as
    `Connected`.
  - `StatusHeartbeat` is a pure, clock-injected liveness tracker: `WAITING`
    until the first STATUS, `LIVE` while one has arrived within **3 s**,
    `STALE` after 3 s without one (including before the first STATUS, using
    the connection time as the reference). The ViewModel re-checks it every
    **500 ms**, so a silent link goes stale with no packets arriving.
  - STATUS's `clock_hz`, `total_edges`, `dropped_packets` and sequence are
    reassembled for the optional diagnostics card.
  - `ConnectScreen` replaces the stub: colored state dot, phase and
    heartbeat text, connect/disconnect (and cancel-while-connecting)
    buttons, and the diagnostics readout once STATUS arrives.
- Session 9 send gating: `SendViewModel` also observes
  `RfLabSession.connection`, so "Send All" and the preset buttons are
  disabled and refuse to run with a clear message while disconnected.
  `canSendAll` requires both a non-empty queue and a live link. Receive
  needs no change: with nothing attached, `RfLabSession.events` is empty and
  the screen keeps its existing "No signals yet" state.
- `UsbPermission` now scopes its mutable `PendingIntent` action to the app's
  package. Android 14+ rejects implicit mutable `PendingIntent`s, which
  would otherwise make the permission request crash before the dialog.
- `com.eelab.rflab.usb.RfLabSession`: the app-wide current
  `RfLabConnection`. The Connect path attaches after a successful open and
  detaches on disconnect (or connection loss); `events` switches to/from an
  empty stream with it.
- Dependencies added for the ViewModel/Compose wiring:
  `lifecycle-runtime-compose`, `lifecycle-viewmodel-ktx`,
  `lifecycle-viewmodel-compose`, and `kotlinx-coroutines-test` for JVM tests.
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

- Session 7 reads `SendQueueRepository.shared.items` and can render/reorder/
  remove from it. The repository assigns ids and allows duplicates on
  purpose; the receive screen only appends.
- The Connect path is the only place that should call
  `RfLabSession.attach(...)` / `detach()`. `UsbConnectController` attaches
  after a successful `RfLabConnection.open(...)`; the Receive and Send
  screens observe the session, so they need no reconnect logic of their own.
- USB permission and the blocking port open do not need an Activity: the
  permission answer arrives by broadcast to the application context, and the
  screen only calls the ViewModel. If a future change adds an
  `ActivityResult`-based flow, keep it above `ConnectPort` rather than
  leaking an Activity into the ViewModel.
- Liveness is heartbeat-based, not connection-based: `ConnectUiState` can be
  `Connected` + `STALE` when the port is open but STATUS stops. The 3 s
  threshold and 500 ms poll live in `StatusHeartbeat` /
  `ConnectViewModel.LIVENESS_TICK_MS`.
- The receive screen keeps one `ScaleEstimator` for the ViewModel's
  lifetime, matching the brief's per-connection-session scale. Session 10 can
  start a fresh receive ViewModel when opening a new connection if it wants
  calibration restarted for a different transmitter; reusing the pooled
  estimate still decodes, it just mixes both transmitters' data cells.
- `RfLabConnection.events` has no replay: subscribe before connecting if
  every packet matters. The buffer applies backpressure rather than dropping
  packets under a slow collector. The Connect screen's heartbeat starts
  before the next STATUS (up to 1 s later), so the first ~1 s shows
  `WAITING`, which is expected.
- The USB-dependent parts (`UsbManager`, `CdcAcmSerialDriver`, the permission
  dialog) need a real device or instrumentation. `ConnectPort` is the seam
  used to JVM-test the whole connect state machine, permission-denial/error
  paths and heartbeat staleness with a fake; stream/framing logic remains in
  `PacketReassembler` and is unit-tested separately.
- Sending stays strictly sequential: one command, wait for its ACK or a
  timeout, then the next. Any change to `BatchSendEngine` must preserve that
  and the single shared instance from `SendGraph`; the ACK protocol has no
  request id, so pipelining makes ACK attribution ambiguous. Session 10
  should decide how much of a mid-batch disconnect to surface beyond the
  current "abort remaining items, mark the failed write as timeout" rule.
  The UI already disables sends once the session detaches; a disconnect in
  the middle of an in-flight batch still ends that item as a timeout.
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
