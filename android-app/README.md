# STM32 RF Lab — Android app (WIP)

**Status: scaffold only.** This is session 1 of the 10-session plan in
[`PLAN.md`](PLAN.md); the authoritative protocol and decode details live in
[`AGENT_BRIEF.md`](AGENT_BRIEF.md).

There is no USB, packet, COBS/CRC or HCS301 logic yet. The app currently opens
on a three-tab shell with placeholder **Connect**, **Receive** and **Send**
screens.

## What exists

- Standard Gradle / Android Studio project, Kotlin, single `app` module.
- Jetpack Compose + Material 3 with a `NavigationBar`/`NavHost` shell.
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
