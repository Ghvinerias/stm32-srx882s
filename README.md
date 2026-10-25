# 433 MHz RF Lab — capture, decode, replay (KeeLoq/HCS301 focus)

> This file described the original single-MCU MVP pitch and had drifted from
> what the repo actually is. See `.claude/.../memory/` (project memory) for
> the authoritative map if this and the code ever disagree again.

## Overview
A 433.92 MHz RF lab for capturing, decoding, and replaying garage/gate-style
remotes using the HCS301/KeeLoq hopping-code protocol, built across three
hardware generations that speak the same wire protocol:

- **`firmware/`** — STM32 "Blue Pill" + STX882 (TX) / SRX882 (RX): the
  original reference implementation (HAL/LL, timer input capture + DMA,
  bit-banged OOK TX, USB CDC or legacy UART1).
- **`esp32c3-cc1101-lab/`** — ESP32-C3 + CC1101: CC1101 SPI driver in async
  OOK mode, RMT for TX/RX, BLE (NUS-style GATT) alongside USB CDC, OTA.
  Byte-identical COBS/CRC16 packet protocol to the STM32 build.
- **`esp32-bt-console/`** — ESP32 DevKitV1: the STM32 TX/console logic over
  Bluetooth SPP, RMT timing, TX-only.

Supporting pieces:
- **`tools/`** — Python host tools: capture (`rf_capture.py`), a curses TUI
  (`rf_console.py`), HCS301 frame/press decoders (`decode_hcs301.py`,
  `summarize_capture.py`, `keeloq_message_view.py`), sigrok session decode
  (`pulseview_session.py`), and the KeeLoq block cipher plus
  decrypt-with-a-key-you-hold (`keeloq.py`, `keeloq_reverse.py`).
- **`android-app/`** — Kotlin/Compose app (USB CDC-ACM host) that receives
  and decodes frames, replays them, and (Keys tab) holds a user-supplied
  KeeLoq key to decrypt hops and build correctly-encrypted "next code" sends
  for the user's own receiver.
- **`keeloq_t5502_authorized_duplication/`** — scoped research on
  T5502/HCS301 receiver identification and *authorized* enrollment routes;
  explicitly excludes key/seed extraction, replay, or receiver-bypass
  instructions.

---

## Scope and implementation status

**Implemented and tested:**
- The public KeeLoq block cipher (`firmware/src/keeloq.c`, `tools/keeloq.py`),
  validated against two independent published known-answer sources. Run
  `tools/keeloq.py` directly, or build+run `firmware/test_keeloq.c` against
  `firmware/src/keeloq.c`, to re-check.
- Full HCS301 hop-code structure decode (serial, button, counter position,
  discrimination, VLOW, repeat) on the clear-text fields, across all three
  hardware generations and the Python tools (`tools/decode_hcs301.py`,
  `tools/keeloq_message_view.py`, `tools/summarize_capture.py`).
- Decrypting a *captured* hop and validating counter behavior using a key
  the device owner already holds (`tools/keeloq_reverse.py --selftest`,
  Android Keys tab) — this is diagnostic/interoperability use on your own
  provisioned encoder, not key recovery.
- Encoding a correctly-encrypted "next code" for the user's **own**
  receiver, with the counter auto-tracked per serial (Android Send screen).
- The COBS/CRC16 capture/transport protocol, exercised by
  `tools/test_rf_capture.py` (11/11 passing).
- Manufacturer key-derivation ("learning") formulas — `normal`, `secure`,
  `magic_xor_type1`, `faac`, `magic_serial_type1/2/3`, `aerf`, `erreka`,
  `pujol`, `jcm_gen2` — ported from `keeloq_common.c`
  (DarkFlippers/unleashed-firmware) into the Android crypto package
  (`KeeLoqLearning`). Every function takes the manufacturer key as a
  caller-supplied parameter, exactly like the cipher takes a device key;
  this ships no manufacturer-key dictionary. Scope note: because HCS-family
  serials travel in the clear over the air, holding a manufacturer key
  turns any captured serial into that device's real key — use this only
  for systems you are authorized to provision or service (e.g. as the
  installer/dealer holding that manufacturer's key), not for devices you've
  merely captured a signal from. See `KeeLoqLearning.kt`'s doc comment and
  `docs/KEELOQ_LEARNING_PROVENANCE.md` for the pinned upstream commit,
  per-function source mapping, and an honest statement of what the test
  suite does and does not validate. Keys in the Android keystore now carry
  a `KeyPurpose` (`DEVICE`/`MANUFACTURER`) so the two kinds can't be
  silently swapped; `KeeLoqLearningIsolationTest` fails the build if any
  code outside `KeeLoqLearning.kt` itself starts depending on it.

**Still out of scope — not building:** a next-valid-code generator wired
to run automatically off a *derived* (not owner-held) key picked up from a
captured signal. The learning formulas above are provided as key-derivation
utilities for a manufacturer key you already legitimately hold; turning
"capture a signal" directly into "generate valid codes" without a human
in the loop holding that authorization is the general-purpose cloning tool
this project still won't ship. Still fine as alternatives: decoding further
protocol variants (e.g. HCS361) for analysis, and measuring/documenting the
rolling-code weakness without shipping a working forgery tool.

**Known gap:** `tools/keeloq.py` and `tools/keeloq_reverse.py` only have
inline `--selftest`/`self_test()` entry points, not a `unittest`/pytest
file alongside `tools/test_rf_capture.py`. Android unit tests
(`KeeLoqCipherTest`, `KeeLoqHopTest`, `KeeLoqKeyRepositoryTest`) exist under
`android-app/app/src/test/` but need a JVM + Gradle to actually run —
not verified in this pass for lack of a local Java toolchain.

---

## Risks & Constraints
- Cheap OOK modules have slow AGC → need multiple repeats
- Large variety of 433 MHz protocols → not all will be decodable
- Timing accuracy depends on external crystal
- Scope is authorized rolling-code analysis, interoperability testing, and
  receiver-compatibility validation on hardware/keys the operator holds —
  not key recovery or cloning devices the operator doesn't control

---

## License
TBD (internal MVP prototype only)
