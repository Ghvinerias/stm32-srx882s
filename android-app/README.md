# STM32 RF Lab — Android app (WIP)

**Status: protocol primitives and the raw packet layer in place** (session 3
of the 10-session plan in [`PLAN.md`](PLAN.md)); the authoritative protocol and
decode details live in [`AGENT_BRIEF.md`](AGENT_BRIEF.md).

There is no USB transport or HCS301 decoding yet. The app still opens on a
three-tab shell with placeholder **Connect**, **Receive** and **Send** screens.

## What exists

- Standard Gradle / Android Studio project, Kotlin, single `app` module.
- Jetpack Compose + Material 3 with a `NavigationBar`/`NavHost` shell.
- `com.eelab.rflab.protocol.Cobs` and `Crc16`: JVM-testable Kotlin ports of
  `transport_cobs_encode` / `transport_cobs_decode` and `transport_crc16`
  from `firmware/src/transport.c`, with unit tests under `app/src/test/`.
  The COBS codec handles bodies only; the trailing `0x00` wire delimiter is
  left to the transport layer (session 4).
- `com.eelab.rflab.protocol.PacketCodec` plus the packet types in
  `Packets.kt` (`FramePacket`, `StatusPacket`, `TxCommand`,
  `ScenarioCommand`, `AckPacket`): decoding for all five packet types with
  typed CRC/truncation/unknown-type failures, and encoders for `TX_COMMAND`
  and `SCENARIO_COMMAND`. This layer consumes/produces the raw bytes *after*
  COBS decoding (CRC included, delimiter excluded); COBS and the `0x00`
  delimiter remain session 4's job.
- `com.github.mik3y:usb-serial-for-android:3.11.0` dependency.
- USB host feature declared, plus `res/xml/device_filter.xml` matching the lab
  board (VID `0xCAFE` / PID `0x4001`, decimal `51966` / `16385`) and an
  `USB_DEVICE_ATTACHED` intent filter.
- `minSdk 23`, `targetSdk 35`, `compileSdk 35`.

## Build

Requires JDK 17+ and Android SDK platform 35 (build-tools 35.0.0). Open the
`android-app/` directory in Android Studio, or:

```sh
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Notes for later sessions

- The board is CDC-ACM but its VID/PID is not in the library's default prober.
  Enumerate `UsbManager.deviceList`, match VID/PID explicitly, and construct
  `CdcAcmSerialDriver(device)` (session 4).
- Sending must stay strictly sequential: one command, wait for its ACK or a
  timeout, then the next (session 8).
