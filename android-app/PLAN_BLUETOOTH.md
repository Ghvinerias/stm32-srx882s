# Bluetooth transport — 3 sessions

Follow-up to PLAN.md / PLAN_UI_TWEAKS.md / PLAN_FAVORITES.md. Adds a second
link to the lab board: **Bluetooth Classic SPP (RFCOMM)** alongside the
existing USB CDC-ACM, for the ESP32 build in `../esp32-bt-console/`.

## Why this is small and safe

The ESP32 sketch speaks the **exact same** COBS + CRC-16/CCITT-FALSE packet
protocol as the STM32 firmware — only the transport differs (RFCOMM instead
of USB CDC). See `../esp32-bt-console/README.md` ("Binary protocol"). So the
whole protocol/receive/send stack is reused unchanged:

- `protocol/` (Cobs, Crc16, PacketCodec, Packets) — no change.
- `usb/PacketReassembler` — no change (already plain Kotlin, delimiter-based).
- `send/BatchSendEngine`, `send/SendGraph` — no change; they observe
  `RfLabSession.events` and write via `RfLabSession.connection.value`.
- `ui/receive/*`, `ui/send/*`, `favorites/*` — no change.

The ESP32 has **no receive path**, so it never sends `FRAME`; over Bluetooth
the Receive screen simply stays empty. It does send `STATUS` once a second,
so the Connect heartbeat works identically. This is expected, not a bug.

Everything new is: one transport abstraction, one Bluetooth implementation of
it, one `ConnectPort` implementation, manifest permissions, and a transport
chooser on the Connect screen. Each session compiles and is committed on its
own; sessions run sequentially (2 depends on 1, 3 depends on 2).

Scope boundary is unchanged from the brief: the app only receives/displays/
selects/replays clear-text HCS301-shaped frames. It does not decrypt, derive
keys, or brute-force anything. This is a transport addition only.

---

## Session 1 — Extract a transport seam (pure refactor, no behavior change)

Today `usb/RfLabConnection` is bound directly to a `UsbSerialPort`. Everything
in it except the three I/O calls (`port.read`, `port.write`, `port.close`) is
transport-agnostic: the read loop, the `PacketReassembler`, the write mutex,
the `openState`/`events` flows, the clean-vs-failure close semantics.

1. **New `usb/RfLabTransport.kt`** — a minimal byte-stream seam:
   ```
   interface RfLabTransport : java.io.Closeable {
       /** Blocking read into [buffer]; returns bytes read (0 == nothing this
        *  round, keep looping), or throws IOException on a dead link. */
       fun read(buffer: ByteArray): Int
       /** Blocking write of all [bytes]; throws IOException on failure. */
       fun write(bytes: ByteArray)
       override fun close()
   }
   ```
2. **New `usb/UsbSerialTransport.kt`** — wraps a `UsbSerialPort`, applying the
   current timeouts: `read` calls `port.read(buffer, READ_TIMEOUT_MS)` (200 ms,
   returns 0 on timeout so the loop stays cancellation-responsive), `write`
   calls `port.write(bytes, WRITE_TIMEOUT_MS)` (2 s), `close` calls
   `port.close()`. Move `READ_TIMEOUT_MS`/`WRITE_TIMEOUT_MS`/`BAUD_RATE` and the
   `setParameters` call here; keep `RfLabConnection.open(usbManager, device,
   dispatcher)` as the public USB factory, now building a `UsbSerialTransport`
   internally and delegating to a generic `RfLabConnection(transport,
   dispatcher)` constructor.
3. **`usb/RfLabConnection.kt`** — change the primary constructor to take an
   `RfLabTransport` instead of a `UsbSerialPort`. The read loop calls
   `transport.read(buffer)` and treats `<= 0` as "continue"; `write()` calls
   `transport.write(bytes)` under the existing `writeMutex`; `close()` calls
   `transport.close()`. `sendPacket` (COBS + `0x00`) is unchanged. Keep all the
   `_open`/`closed`/`ConnectionLost` behavior byte-for-byte.

Acceptance: `./gradlew :app:assembleDebug test` is green; no file under
`protocol/`, `send/`, `ui/`, or `favorites/` changes; the USB connect flow is
behaviorally identical (same factory signature, same timeouts, same events).

---

## Session 2 — Bluetooth transport + connect controller

New `com.eelab.rflab.bluetooth` package. Mirror the USB structure
(`UsbDeviceFinder` + `UsbConnectController` + `ConnectPort`) so the Android
pieces are isolated and the logic is JVM-testable with fakes.

1. **Manifest** (`app/src/main/AndroidManifest.xml`):
   - `BLUETOOTH_CONNECT` (used from API 31+) for connecting to and naming a
     bonded device.
   - Legacy `BLUETOOTH` + `BLUETOOTH_ADMIN` with `android:maxSdkVersion="30"`
     (install-time on older Android).
   - We only use already-**bonded** devices (no discovery/scan), so **no**
     location permission is required.
   - Add `<uses-feature android:name="android.hardware.bluetooth"
     android:required="false" />` and change the existing `usb.host`
     feature to `android:required="false"` so the app installs on a device
     reachable by only one of the two transports.
2. **`bluetooth/BluetoothSerialTransport.kt`** — `RfLabTransport` over a
   connected `BluetoothSocket`: `read` is `inputStream.read(buffer)` (blocking;
   a return of `-1` / EOF throws `IOException` so the read loop reports
   `ConnectionLost`; closing the socket from `close()` also unblocks it),
   `write` is `outputStream.write(bytes); flush()`, `close` closes the socket.
3. **`bluetooth/BluetoothConnectController.kt`** — a `ConnectPort` (same
   interface the Connect screen already drives). Takes the application
   `Context` and a *selected* `BluetoothDevice` provider (the UI picks which
   bonded device; see session 3). `connect()`:
   - If `BLUETOOTH_CONNECT` is required (API 31+) and not granted, return
     `ConnectAttempt.PermissionDenied` (the UI requests it first).
   - `RfLabSession.detach()` any stale connection, then on the IO dispatcher
     (`NonCancellable`, like `UsbConnectController`) call
     `device.createRfcommSocketToServiceRecord(SPP_UUID)` where `SPP_UUID =
     00001101-0000-1000-8000-00805F9B34FB`, `adapter.cancelDiscovery()`,
     `socket.connect()`, wrap in `BluetoothSerialTransport`, build an
     `RfLabConnection`, and `RfLabSession.attach(it)`. Map failures to
     `ConnectAttempt.Failed(<user message>)`; honor cancellation (close a
     socket that finished connecting after cancel).
   - `events = RfLabSession.events`, `isConnected()` / `disconnect()` as in
     `UsbConnectController`.
   - Provide a small injectable seam (interface) for "list bonded devices" and
     "open a socket to this device" so `connect()`'s outcome logic is unit-
     tested on the JVM with fakes, exactly as the USB side kept Android types
     behind `ConnectPort`. No instrumented test is required (document the gap,
     as `RfLabConnection`'s own KDoc already does for USB).

Acceptance: `./gradlew :app:assembleDebug test` green; USB untouched; new JVM
tests cover the controller's permission-denied / no-device / connect-failure /
success-attach outcomes with fakes.

---

## Session 3 — Connect screen: transport chooser + BT device picker

Make the Connect screen pick a transport, keeping USB as the default so the
existing flow is one tap away.

1. **`ui/RfLabApp.kt`** — build both controllers at the composition root:
   keep the `UsbConnectController`, and construct the Bluetooth controller with
   the selected bonded device. Pass both into `ConnectScreen`.
2. **Connect screen** (`ui/screens/ConnectScreen.kt`, and
   `ui/connect/ConnectUiState` wording):
   - A transport segmented control / two-option selector at the top:
     **USB** and **Bluetooth**. Switching transports while connected is
     disabled (disconnect first).
   - **USB** path: unchanged — the existing card/controls, driven by the
     `UsbConnectController` `ConnectViewModel`.
   - **Bluetooth** path:
     - Request `BLUETOOTH_CONNECT` at runtime via a Compose
       `rememberLauncherForActivityResult(RequestPermission())` on API 31+
       (install-time grant is enough below 31). Show a clear rationale/line
       when denied; offer to retry.
     - Once granted, list **bonded** devices (name + MAC) from
       `BluetoothAdapter.bondedDevices`, pre-selecting one whose name matches
       `ESP32-KeeLoq-Lab` when present. A tap selects the target device.
     - Connect/Disconnect drive a `ConnectViewModel` built over the
       `BluetoothConnectController` for the selected device. The heartbeat,
       status dot, and STATUS diagnostics work unchanged (STATUS arrives over
       RFCOMM too).
   - Generalize the USB-specific copy that both paths share: phase label
     "USB permission denied" → neutral/per-transport wording; the footer hint
     and `statusDetail` strings should read correctly for whichever transport
     is active. Keep `ConnectViewModel` transport-agnostic (it already only
     knows `ConnectPort`).
3. If adding `Build.VERSION`/permission handling anywhere, remember **minSdk
   23**: guard API 31 (`S`) permission code behind `Build.VERSION.SDK_INT`,
   and avoid API 24+ Kotlin stdlib extensions in `minSdk 23` code paths (lint
   NewApi — the same trap already noted in `RfLabApp.kt`).

Acceptance: `./gradlew :app:assembleDebug test` green; USB flow unchanged and
still the default; selecting Bluetooth, granting permission, picking the
paired ESP32, and tapping Connect attaches a live session that the Send screen
can drive and whose Connect heartbeat goes LIVE on the first STATUS.

---

## After all three sessions

Full `./gradlew :app:assembleDebug test` from a clean state. **Do not install
to the phone** — the build is handed back for the user to review and approve
the on-device install themselves.
