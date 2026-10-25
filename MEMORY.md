# Project memory — source of truth

This file is the up-to-date map of what this repo actually is and where
things stand. `README.md` was brought back in sync with it on 2026-10-25;
treat this file as authoritative if the two ever disagree again, and update
both when scope changes.

## What this project is

A 433.92 MHz RF lab for capturing, decoding, and replaying garage/gate-style
remotes using the HCS301/KeeLoq hopping-code protocol, built across three
hardware generations that all speak the **same wire protocol**:

1. **STM32 "Blue Pill" + STX882 (TX) / SRX882 (RX)** — `firmware/`. The
   original reference implementation: HAL/LL, timer input capture + DMA,
   bit-banged OOK transmit, USB CDC (VID `0xCAFE` PID `0x4001`) or legacy
   UART1, selectable by a PB5 boot-time jumper.
2. **ESP32-C3 + CC1101** — `esp32c3-cc1101-lab/`. Second-generation node:
   CC1101 SPI driver in async OOK mode, RMT peripheral for both TX and RX
   (no bit-banging, no IRQ disabling), BLE (NUS-style GATT) as an additional
   transport alongside USB CDC, OTA update support. Byte-identical COBS/CRC16
   packet protocol to the STM32 build, so all host tools and the Android app
   work unchanged. Two physical units are told apart by `LAB_UNIT_ID`.
3. **ESP32 DevKitV1 (Bluetooth Classic)** — `esp32-bt-console/`. Reproduces
   the STM32 TX/console logic over Bluetooth SPP instead of USB CDC, using
   RMT for jitter-free timing. TX-only (no receive path).

Supporting pieces:
- **`tools/`** — Python host tools: `rf_capture.py` (capture to NDJSON/bin),
  `rf_console.py` (curses TUI, full-duplex capture+send), `decode_hcs301.py`
  / `summarize_capture.py` / `keeloq_message_view.py` (frame/press/raw-field
  decoders), `pulseview_session.py` (sigrok session decode), `keeloq.py` /
  `keeloq_reverse.py` (KeeLoq block cipher + decrypt-with-a-key-you-hold).
- **`android-app/`** — Kotlin/Compose app, USB CDC-ACM host, that receives
  and decodes frames, replays them or manually-entered/preset commands, and
  (Keys tab) holds a user-supplied KeeLoq key to decrypt hops and build
  correctly-encrypted "next code" sends for the user's own receiver. Protocol
  spec lives in `android-app/AGENT_BRIEF.md`; build plan in `PLAN*.md` files.
  `./gradlew test` / `assembleDebug` / `lintDebug`.
- **`keeloq_t5502_authorized_duplication/`** — a scoped research pass
  (outline + per-topic JSON results) on T5502/HCS301 receiver
  identification and *authorized* enrollment/replacement routes — explicitly
  excludes key/seed extraction, replay, or receiver-bypass instructions.

## Standing scope and implementation decisions

**Fine, already built:**
- The public KeeLoq block cipher itself (`firmware`/`tools/keeloq.py`,
  Android `KeeLoqCipher`), and decrypting a *captured* hop using a key the
  user already holds (`tools/keeloq_reverse.py`, Android Keys tab).
- Encoding a correctly-encrypted "next code" for the user's **own**
  receiver, using a key the user already holds, with counter auto-tracked
  per serial (Android Send screen).
- Full HCS301 hop-code structure decode (serial, button, counter position,
  discrimination, VLOW, repeat) on the clear-text fields, across all three
  hardware generations and the Python tools.

**Built (scope decision reversed 2026-10-25, second time):** manufacturer
key-derivation ("learning") functions from Flipper Zero Unleashed
firmware's `keeloq_common.c` — `normal`, `secure`, `magic_xor_type1`,
`faac`, `magic_serial_type1/2/3`, `aerf`, `erreka`, `pujol`, `jcm_gen2` —
ported into `android-app/.../crypto/KeeLoqLearning.kt`, with unit tests in
`KeeLoqLearningTest.kt`. Every function takes the manufacturer key as a
caller-supplied parameter (no manufacturer-key dictionary is shipped or
embedded), mirroring how `KeeLoqCipher` already takes a device key.

Follow-up audit pass (same day): provenance pinned to upstream commit
`0d295f581c1e34cd56e2e590ea85625a64c2b724` with file hashes and a
per-function source-line table in `docs/KEELOQ_LEARNING_PROVENANCE.md`,
which also states plainly that the port is translation-reviewed and
self-consistency-tested, **not** verified against an external oracle (no
independent KAT exists for these 11 functions, unlike the core cipher).
`KeeLoqKey` gained a `KeyPurpose` (`DEVICE`/`MANUFACTURER`) field —
a mix-up-prevention label, explicitly not an authorization check — with
migration handled in `SharedPreferencesKeyStorage` (old documents default
to `DEVICE`). `KeeLoqLearningIsolationTest` fails the test suite if any
file outside `KeeLoqLearning.kt`/its own tests references it, so it can't
silently gain a call site in the send/receive/UI flow. Note: that JSON
migration path itself has no passing JVM test — `org.json` is
Android-stubbed under this project's no-Robolectric unit-test setup, a
pre-existing gap (see provenance doc).

**Audit status, recorded explicitly so it isn't inferred from "tests
pass":** Completed = provenance doc, `KeyPurpose` metadata, isolation
guard, current (421-passing) suite. Open = direct persistence tests for
`SharedPreferencesKeyStorage` serialization/migration (acceptance
criteria and fix options in the provenance doc's final section — not
implemented). Unchanged = the 11 derivation functions are still not
validated against an independent upstream oracle; passing tests
elsewhere do not extend to them.

This reverses an earlier-same-day "not building" decision recorded right
here, which itself had reversed a prior "approved" note in a now-gone
global memory file (`project_keeloq_learning_scope.md`) — i.e. this exact
question has flip-flopped within one day. The operator's stated
authorization context for this reversal: they are an installer/integrator
who legitimately holds manufacturer master keys for systems they service,
not deriving keys from signals captured off devices they don't hold a key
for. The underlying risk the earlier reversal documented is still true and
still worth restating for the next session that re-evaluates this: HCS
serials are sent in the clear over the air, so a manufacturer key turns any
captured serial into that device's real key and all its future valid
codes. What's *not* built, and is the line this project still won't cross,
is a next-valid-code generator wired to run automatically off a signal
capture (vs. a human-in-the-loop utility for a key the operator already
holds). **If this question comes up again: re-derive the decision from this
reasoning and the current conversation's stated authorization context —
do not treat this paragraph's "built" status alone as standing approval.**
Still fine regardless: protocol-variant decoding for analysis (e.g.
HCS361), and documenting the rolling-code weakness without shipping a
working generator.

## Where the hardware lives

Firmware bring-up/validation work runs against hardware on
`fit4.local.slick.ge` (the HIL bench); the Android app development stays on
this machine (`omarchy`). See the user's global hardware-thread memory for
delegation rules and which standing threads/subagents to use for firmware
vs. Android vs. bring-up work, rather than spinning up one-off subagents.

## Known drift / cleanup owed

- `README.md` was rewritten 2026-10-25 to match this file (three hardware
  generations, Android app, KeeLoq keystore, explicit scope boundary) and
  to drop the stale "port key-derivation" next-task item. Keep both in
  sync going forward.
- `tools/keeloq.py` / `tools/keeloq_reverse.py` have inline self-tests but
  no `unittest`/pytest file alongside `tools/test_rf_capture.py` — worth
  adding for CI discoverability.
- Android unit tests exist (`KeeLoqCipherTest`, `KeeLoqHopTest`,
  `KeeLoqKeyRepositoryTest`) but were not run in the 2026-10-25 pass for
  lack of a local JVM/Gradle; verify on a machine that has them.
- `stm32f103c8.txt` at repo root is the MCU reference datasheet dump, not
  project documentation.
