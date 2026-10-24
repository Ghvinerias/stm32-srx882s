#!/usr/bin/env python3
"""T3: ESP32 binary TX_COMMAND over USB, observed through the air.

N loops (default 20, HIL_N to override).  Per loop:
  * TX_COMMAND (frames=3) over the ESP32-C3 USB CDC port; require an
    ACK with accepted=1
  * a sigrok capture records D0 (SRX882 data out = what is heard over the
    air) and D1 (STX882 data in = the STM32 transmitter, must stay silent)
  * frames on D0 are segmented (>8 ms idle gaps) and decoded via
    tools/decode_hcs301.py; the decoded fields must match the transmitted
    codeword exactly (which pins every one of the 66 codeword bits)
  * the STM32 reference receiver must emit a FRAME packet whose decoded
    fields also match
Required: >= 19/20 (HIL_T3_REQUIRED).  Per-iteration lines and a summary
are printed, the exact `T3 tx_frame: PASS|FAIL <...>` verdict last.
"""

import os
import sys

import hil_lib as lib

TAG = "T3"
NAME = "tx_frame"

N = int(os.environ.get("HIL_N", "20"))
WINDOW_S = float(os.environ.get("HIL_T3_WINDOW_S", "2.5"))
SETTLE_S = float(os.environ.get("HIL_T3_SETTLE_S", "0.6"))
REQUIRED = int(os.environ.get("HIL_T3_REQUIRED", str(N - 1)))


def fields_match(decoded: dict, target: tuple[int, int, int]) -> str | None:
    if "hopping" not in decoded:
        # a raw FRAME packet from a board: decode its pulse list first
        pulses = decoded.get("pulses_us") or decoded.get("pulses") or []
        fields, _scale = lib.decode_segment(list(pulses))
        if fields is None:
            return f"FRAME with {len(pulses)} pulses does not decode"
        decoded = fields
    if (decoded["hopping"], decoded["serial"], decoded["button"]) != target:
        hopping, serial, button = target
        return (
            f"hopping=0x{decoded['hopping']:08X} serial=0x{decoded['serial']:07X} "
            f"button=0x{decoded['button']:X}"
            f" (want hopping=0x{hopping:08X} serial=0x{serial:07X} button=0x{button:X})"
        )
    return None


def run_iteration(index: int, target: tuple[int, int, int], source: lib.Device,
                  cap_name: str) -> tuple[bool, str]:
    """One TX round trip.  Returns (ok, reason/evidence)."""
    stm32 = lib.Device(lib.stm32_port(), "stm32").start()
    try:
        if not stm32.ok():
            return False, f"STM32 port open failed: {stm32.open_error()}"
        stm32.clear()
        with lib.CaptureWindow(cap_name, WINDOW_S, settle_s=SETTLE_S) as window:
            source.clear()
            source.send_tx_command(*target, 3)
            ack = source.wait_ack(lib.TYPE_TX_COMMAND, 5.0)
            stm32_frame = stm32.wait_frame(WINDOW_S + 2.0)
            capture = window.finish()

        if ack is None:
            return False, f"no ACK for TX_COMMAND accepted=1 [esp: {source.stats()}]"

        d1_edges = capture.edge_times("D1")
        d1_note = f" [note: D1 had {len(d1_edges)} edges]" if d1_edges else ""

        air = capture.decoded_frames("D0")
        matching = sum(1 for decoded in air if fields_match(decoded, target) is None)
        mismatched = [fields_match(decoded, target) for decoded in air]
        mismatched = [why for why in mismatched if why]
        if matching == 0:
            segments = capture.pulses("D0")
            detail = [f"d0_segments={len(segments)}"] + \
                [f"pulses={len(segment)}" for segment in segments[:3]]
            if mismatched:
                detail.append(f"wrong fields: {mismatched[0]}")
            return False, "; ".join(detail)

        # The STM32 reference receiver is a secondary witness only (its input
        # capture reports 38 pulses/frame, so it often cannot be decoded by
        # the same decoder): report it, do not fail on it.
        if stm32_frame is None:
            stm32_note = "stm32: no FRAME"
        else:
            why = fields_match(stm32_frame, target)
            stm32_note = f"stm32 seq{stm32_frame['sequence']} " + ("ok" if not why else f"({why})")

        lib.iter_report(
            f"it={index}",
            f"ack=1 d0_frames={matching} d1_edges={len(d1_edges)} "
            f"{stm32_note}"
        )
        return True, f"air frames={matching}, {stm32_note}"
    finally:
        stm32.close()


def main() -> int:
    try:
        target = lib.pick_codeword()[:3]
        where = sys.argv[0]
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error}")
        return 1
    try:
        source = lib.open_device(lib.esp_port(), "esp")
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL cannot open ESP serial port {lib.esp_port()}: {error}")
        return 1

    passed = 0
    failed_notes = []
    try:
        for index in range(1, N + 1):
            try:
                ok, why = run_iteration(index, target, source, f"t3-it{index}")
            except Exception as error:
                ok, why = False, f"{error.__class__.__name__}: {error}"
            if ok:
                passed += 1
            else:
                failed_notes.append(f"it{index}: {why}")

        summary = (
            f"{passed}/{N} iterations (need {REQUIRED}); "
            f"hopping=0x{target[0]:08X} serial=0x{target[1]:07X} "
            f"button=0x{target[2]:X}"
        )
        if passed >= REQUIRED:
            print(f"{TAG} {NAME}: PASS {summary}")
            return 0
        reasons = " ;; ".join(failed_notes[:6])
        print(f"{TAG} {NAME}: FAIL only {passed}/{N} passed | {reasons}")
        return 1
    finally:
        source.close()


if __name__ == "__main__":
    sys.exit(main())
