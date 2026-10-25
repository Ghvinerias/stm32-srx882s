# Android app build plan — 10 sessions

Source of truth for every protocol/decode detail: `android-app/AGENT_BRIEF.md`.
Each session is implemented by an OpenCode DeepSeek sub-agent, working directly
in this checkout, building strictly on the previous session's committed code.
Sessions are sequential (each depends on the last), not parallel.

1. **Scaffold** — Gradle/Android Studio project under `android-app/`, Kotlin,
   single `app` module, `usb-serial-for-android` dependency, manifest with
   USB host permission + device filter (VID `0xCAFE` PID `0x4001`), empty
   Connect/Receive/Send screen stubs wired into basic navigation.
2. **COBS + CRC16** — Kotlin port of `transport_cobs_encode/decode` and
   `transport_crc16` from `firmware/src/transport.c`. Unit tests with
   hand-built vectors (zero bytes at edges, >254-byte run, round-trip).
3. **Packet protocol layer** — data classes for FRAME/STATUS/TX_COMMAND/
   SCENARIO_COMMAND/ACK matching the byte layouts in the brief, encode/decode
   against the COBS+CRC layer from session 2, unit tests.
4. **USB transport** — enumerate `UsbManager.deviceList`, match VID/PID,
   request permission, open via `CdcAcmSerialDriver`, background
   read-coroutine feeding a byte stream reassembler that splits on the `0x00`
   delimiter and emits decoded packets from session 3.
5. **HCS301 decoder** — Kotlin port of `tools/decode_hcs301.py`'s exact
   algorithm (scale estimation + per-frame decode) from the brief. Unit tests
   validated against real pulse arrays pulled from `tools/captures/*.ndjson`.
6. **Receive screen** — decoded-signal list fed by sessions 4+5, grouping
   repeats (same hopping/serial/button within a few ms) into one row with a
   repeat count, multi-select, "add N to send queue" action.
7. **Send queue screen** — queue model + manual-entry row (hex fields for
   hopping/serial/button/frames), editable per-item frame count, preset
   scenario buttons issuing `SCENARIO_COMMAND`.
8. **Batch send engine** — strict sequential send-then-await-ACK-or-timeout
   state machine (never pipeline), per-item status
   pending/sending/acked-ok/acked-failed/timeout, per the brief's timing
   constraint.
9. **Connect screen + liveness** — permission/connect button,
   connected/disconnected indicator driven by STATUS heartbeat (~1/s),
   optional clock_hz/total_edges diagnostics, final navigation wiring across
   all screens.
10. **Integration + polish** — end-to-end wiring review, error-handling edge
    cases (disconnect mid-batch, malformed frames, permission denial),
    `android-app/README.md`, and a pass of the existing `tools/captures/`
    fixtures through the decoder as a regression check.
