# Bluetooth LE (ESP32-C3 NUS) transport — 3 sessions

> **Status: DONE (commit `818c070`).** The feature shipped in a single pass
> rather than the three sessions sketched below, and with a slightly different
> seam than §"How BLE maps onto the existing seams" describes. As built, the
> `com.eelab.rflab.ble` package is:
> - `BleLink` — injectable seam (`isScanPermissionGranted`,
>   `isConnectPermissionGranted`, `scan(): Flow<List<BleEndpoint>>`,
>   `suspend openTransport(address): RfLabTransport`) + the NUS UUID constants.
> - `AndroidBleLink` — production `BleLink`: `BluetoothLeScanner` filtered on
>   the NUS service, and the GATT connect → discover → request-MTU-247 →
>   subscribe-CCCD sequence.
> - `BleUartTransport` + `BleGattChannel` — the async→blocking bridge: the GATT
>   callback pushes TX notifications into a `BlockingQueue` the transport's
>   `read` drains, and resolves each write-with-response latch; `write` chunks
>   to `MTU-3`. (This replaced the `BleInboundBuffer`/`BleGattPort` split
>   sketched below — same job, one class.)
> - `BleConnectController` — `ConnectPort` mirroring the USB/SPP controllers,
>   JVM-tested with a fake `BleLink` (`BleConnectControllerTest`).
> - Connect screen: 3-way USB / Bluetooth Classic / Bluetooth LE chooser with a
>   multi-permission launcher (Scan+Connect on API 31+, Fine+Coarse Location on
>   ≤30) and a live BLE scan picker.
>
> The original plan follows, unchanged, for context.

Follow-up to PLAN_BLUETOOTH.md. The RF-lab fleet now includes **ESP32-C3**
nodes (`../esp32c3-cc1101-lab/`), which are **BLE-only — no Bluetooth Classic**.
The Classic SPP/RFCOMM transport added in PLAN_BLUETOOTH.md cannot reach them.
This plan adds a **third** transport, Bluetooth LE, *alongside* the existing
USB and Classic paths (the user chose to keep all three). USB and Classic SPP
are left working and unchanged.

Scope boundary is unchanged: the app only receives/displays/selects/replays
clear-text HCS301-shaped frames; it does not decrypt, derive keys, or
brute-force anything. This is a transport addition only. The firmware is
already written and flashed — **this plan touches `android-app/` only.**

## The firmware's BLE interface (authoritative; from `../esp32c3-cc1101-lab/`)

Nordic UART Service (NUS) style GATT, **no pairing/bonding** (just-works):

| Role | UUID | Properties |
| --- | --- | --- |
| Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | advertised |
| Host → device (write) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | Write, Write Without Response |
| Device → host (notify) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Notify, CCCD `0x2902` |

- Advertises as `KeeLoqLab-C3-A` (unit id 1, receiver_id `0xE1`) or
  `KeeLoqLab-C3-B` (unit id 2, `0xE2`); the service UUID is in the advert.
- Payload is the **same COBS + CRC-16/CCITT-FALSE packet stream** the app
  already decodes (version 1; types FRAME=1/STATUS=2/TX_COMMAND=3/
  SCENARIO_COMMAND=4/ACK=5; CRC poly 0x1021 init 0xFFFF; little-endian). So
  **nothing under `protocol/`, `usb/PacketReassembler`, `send/`, or
  `ui/receive/` changes** — notifications are just serial bytes.
- Request **ATT MTU 247** → 244-byte notifications; firmware chunks every
  outgoing packet to `min(MTU-3, 244)` and a long FRAME (~537 encoded bytes)
  spans up to 3 notifications. The host appends every notification to a buffer
  and splits on `0x00` — exactly what `PacketReassembler` already does.
- Unlike the Classic console, the C3 **has an RX path**: it sends real FRAME
  packets, so the Receive screen is populated over BLE. STATUS arrives ~1/s as
  before, so the Connect heartbeat works unchanged.
- Only one central connection at a time; the board re-advertises on disconnect.

Connect recipe (from the firmware README "Future Android transport"): scan for
the service UUID (or `KeeLoqLab-C3-` name prefix), `connectGatt`, request MTU
247, enable notifications on `…0003` (write `0x0001` to its CCCD), write
command bytes to `…0002`, feed notifications through the existing decoder.

## How BLE maps onto the existing seams

`usb/RfLabConnection` already runs a transport-agnostic read loop over the
`usb/RfLabTransport` seam (`fun read(buffer): Int` blocking, `fun write(bytes)`,
`close()`), introduced for the Classic work. BLE is callback/async, so the work
is bridging that async GATT model onto the blocking seam; the connection, the
reassembler, the send engine and the session all stay as-is. New code lives in
a new `com.eelab.rflab.ble` package; the Classic `com.eelab.rflab.bluetooth`
package is left alone.

Sessions run sequentially (2 depends on 1, 3 on 2); each compiles, tests, and
is committed on its own.

---

## Session 1 — BLE permissions + the async→blocking transport bridge (JVM-testable)

1. **Manifest** (`app/src/main/AndroidManifest.xml`):
   - `<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
     android:usesPermissionFlags="neverForLocation" />` (API 31+ scanning
     without location).
   - `BLUETOOTH_CONNECT` is already declared (Classic work) — reused for GATT.
   - `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
     android:maxSdkVersion="30" />` — BLE **scanning** on API 23–30 requires a
     location permission; not needed on 31+ thanks to `neverForLocation`.
   - `<uses-feature android:name="android.hardware.bluetooth_le"
     android:required="false" />`.
   - Leave the existing Classic permissions/features intact.
2. **`ble/BleInboundBuffer.kt`** — a small, pure-Kotlin, thread-safe byte FIFO,
   no Android types, fully JVM-unit-tested:
   - `fun offer(bytes: ByteArray)` — called from the (future) GATT notification
     callback thread; appends.
   - `fun read(dest: ByteArray, timeoutMs: Long): Int` — blocks up to
     `timeoutMs` for at least one byte, then copies out as many as fit and
     returns the count; returns 0 on timeout (so `RfLabConnection`'s loop keeps
     cycling, matching the USB 200 ms behaviour); throws `IOException` once
     `close()` has been called and the buffer is drained (signals link-down to
     the read loop → `ConnectionLost`).
   - `fun close()` — unblocks a waiting reader. Use `synchronized`/`wait`/
     `notifyAll` or a lock+condition; cover concurrent offer/read/close with
     tests (bytes split across reads, timeout returns 0, read-after-close
     throws, wakeup on close).
3. **`ble/BleGattPort.kt`** — the Android seam the transport writes through, so
   the transport's chunking/close logic is testable with a fake:
   ```
   interface BleGattPort {
       val mtu: Int                       // negotiated ATT MTU (>= 23)
       fun writeChunk(value: ByteArray)   // blocking; writes one <= mtu-3 chunk
                                          // to the RX characteristic, awaiting
                                          // the GATT write callback; throws
                                          // IOException on failure/disconnect
       fun disconnect()                   // close the GATT client
   }
   ```
4. **`ble/BleUartTransport.kt`** — `RfLabTransport` built from a
   `BleInboundBuffer` + a `BleGattPort`:
   - `read(buffer)` = `inbound.read(buffer, READ_TIMEOUT_MS)` (use 200 ms to
     match USB).
   - `write(bytes)` = split into `min(port.mtu - 3, 244)`-byte chunks and
     `port.writeChunk(each)` in order (one command is normally a single chunk;
     the host→device packets are ≤14 bytes). Throw `IOException` as the port
     does.
   - `close()` = `inbound.close()` then `port.disconnect()` (idempotent).
   - Unit-test with a fake `BleGattPort` (captures written chunks, lets a test
     push inbound bytes through the buffer): chunking at small MTU, write
     failure propagates, close is idempotent.

Acceptance: `./gradlew :app:assembleDebug test` green; new JVM tests for
`BleInboundBuffer` and `BleUartTransport` pass; USB and Classic untouched;
nothing under `protocol/`/`send/`/`ui/` changed.

---

## Session 2 — Android BLE connector + `ConnectPort` (GATT scan/connect/subscribe)

New Android glue; keep the outcome logic behind a JVM-testable seam exactly as
`UsbConnectController`/`BluetoothConnectController` did.

1. **`ble/AndroidBleGattPort.kt`** — the production `BleGattPort` plus the
   `BluetoothGattCallback` that drives one connection. It owns the
   `BluetoothGatt`, exposes the negotiated `mtu`, implements `writeChunk`
   (write to the `…0002` characteristic — use Write **Without** Response for
   throughput, awaiting `onCharacteristicWrite`; serialise so only one GATT op
   is outstanding), `disconnect()` (`gatt.disconnect()` + `gatt.close()`), and
   forwards `onCharacteristicChanged` (TX `…0003`) bytes into the connection's
   `BleInboundBuffer`. A disconnect callback must `close()` that buffer so the
   read loop ends.
2. **`ble/BleScanner.kt`** (seam + Android impl) — scan for the NUS service
   UUID, returning discovered `BleEndpoint(name, address, rssi)` items (a plain
   data class). A production impl uses `BluetoothLeScanner` with a
   `ScanFilter` on the service UUID and a timeout; keep the Android types behind
   an interface so controller logic is fakeable.
3. **`ble/BleConnectController.kt`** — a `usb/ConnectPort` for a selected
   `address` (the UI picks it; see session 3). `connect()`:
   - If the required runtime permissions are not granted (31+:
     `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`; ≤30: `ACCESS_FINE_LOCATION`),
     return `ConnectAttempt.PermissionDenied`.
   - `RfLabSession.detach()` any stale link. Then open a GATT connection to the
     address, discover services, verify the NUS service + both characteristics
     exist, request MTU 247, subscribe to notifications on `…0003` (write
     `0x0001` to the CCCD `00002902-0000-1000-8000-00805F9B34FB`). Only once
     subscribed+ready, build a `BleInboundBuffer` + `AndroidBleGattPort` +
     `BleUartTransport`, wrap in `RfLabConnection`, `RfLabSession.attach(it)`,
     and return `ConnectAttempt.Connected`. Any failure/timeout → close the
     GATT and return `ConnectAttempt.Failed(<user message>)`; honour
     cancellation (close a GATT that finished connecting after cancel). Mirror
     `UsbConnectController`'s `NonCancellable`/IO handling.
   - `events = RfLabSession.events`; `isConnected()`/`disconnect()` as in the
     other controllers.
   - Keep a JVM-unit-testable outcome seam (fake scanner + fake GATT port
     factory) covering permission-denied, no-device/not-found, service-missing,
     connect-failure, and success→attach. No instrumented test required;
     document the on-device gap in KDoc like the other controllers.
4. The scan itself (session 3 drives the device list) can live here behind the
   `BleScanner` seam so both the picker (session 3) and any auto-connect reuse
   it. minSdk 23: guard every API 31 (`S`) permission constant behind
   `Build.VERSION.SDK_INT`; avoid API 24+ stdlib extensions (lint NewApi).

Acceptance: `./gradlew :app:assembleDebug test` + `:app:lintDebug` green; new
JVM controller tests pass; USB and Classic unchanged.

---

## Session 3 — Connect screen: three-way transport chooser + BLE scan picker

1. **`ui/connect/ConnectTransport.kt`** — extend the existing enum from two
   values to **three**: `USB` (default), `BLUETOOTH_CLASSIC`, `BLUETOOTH_LE`,
   with per-transport display names and the existing text helpers updated.
   Update `ConnectTransportTextTest` accordingly.
2. **`ui/RfLabApp.kt`** — build what the BLE path needs at the composition root
   (an `AndroidBleScanner`/port factory over the app context), alongside the
   existing USB controller and Classic `AndroidBluetoothLink`. Pass all into
   `ConnectScreen`.
3. **Connect screen** (`ui/screens/ConnectScreen.kt`):
   - The transport selector now offers three options (SegmentedButton row /
     chips). Switching transport while CONNECTED/CONNECTING stays disabled.
   - **USB** and **Bluetooth Classic** paths: unchanged behaviour from the
     prior plans.
   - **Bluetooth LE** path:
     - Request the right runtime permissions via a Compose
       `rememberLauncherForActivityResult` — on API 31+ request
       `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`; on ≤30 request
       `ACCESS_FINE_LOCATION` (and note scanning needs Location services on).
       Show a rationale + retry when denied.
     - Once granted, run a BLE **scan** (via the session-2 `BleScanner`) and
       list discovered `KeeLoqLab-C3-*` devices (name + address + RSSI), with a
       scanning indicator and a "make sure the board is powered and advertising"
       empty state. Pre-select a single match. Tap selects.
     - Connect/Disconnect drive a `ConnectViewModel` over a
       `BleConnectController` for the selected address; rebuild the VM
       (`viewModel(key = "connect-ble:<address>")`) when the selection changes
       while disconnected. Heartbeat/status dot/diagnostics work unchanged.
   - Generalise any remaining transport-specific copy so all three read
     correctly. Keep `ConnectViewModel` transport-agnostic (it only knows
     `ConnectPort`).
4. minSdk 23: guard all `Build.VERSION_CODES.S` permission code; avoid API 24+
   stdlib extensions (lint NewApi — same trap already noted in `RfLabApp.kt`).

Acceptance: `./gradlew :app:assembleDebug test :app:lintDebug` green; USB and
Classic flows unchanged and USB still the default; selecting Bluetooth LE,
granting permission, scanning, picking a `KeeLoqLab-C3` node and tapping
Connect attaches a live session whose heartbeat goes LIVE on the first STATUS
and whose FRAME captures appear on the Receive screen.

---

## After all three sessions

Full `./gradlew :app:assembleDebug test :app:lintDebug` from clean. **Do not
install to any phone** — hand the build back for the user to review and install
themselves.
