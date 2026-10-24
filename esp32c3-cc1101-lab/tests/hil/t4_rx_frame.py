#!/usr/bin/env python3
"""T4: STM32 binary TX_COMMAND, received through the air by the ESP32.

N loops (default 20, HIL_N to override).  Per loop:
  * TX_COMMAND (frames=3) over the STM32's USB CDC port; require an ACK
    with accepted=1
  * a sigrok capture records D1 (STX882 data in = the STX882 burst the
    STM32 drives) and D0 (SRX882 data out = the receiver's demodulated
    output); D1 must segment and decode to the transmitted codeword
  * the ESP32-C3 must emit FRAME packets whose decoded fields match the
    transmitted codeword exactly (this also exercises the ESP RMT RX path)
Required: >= 19/20; per-iteration lines and a summary are printed.
"""

import os
import sys

import hil_lib as lib
from t3_tx_frame import fields_match

TAG = "T4"
NAME = "rx_frame"

N = int(os.environ.get("HIL_N", "20"))
WINDOW_S = float(os.environ.get("HIL_T4_WINDOW_S", "2.5"))
SETTLE_S = float(os.environ.get("HIL_T4_SETTLE_S", "0.6"))
REQUIRED = int(os.environ.get("HIL_T4_REQUIRED", str(N - 1)))


def collect_matching(device: lib.Device, target: tuple[int, int, int],
                     minimum: int = 1) -> tuple[int, str]:
    """Count FRAME packets from a serial device whose decoded fields match
    the codeword; return (count, mismatch_reason_of_first)."""
    matches = 0
    mismatch = None
    for packet in list(device.packets):
        if packet.get("type") != "frame":
            continue
        decoded, _scale = lib.decode_segment(packet["pulses_us"])
        if decoded is None:
            continue
        if fields_match(decoded, target) is None:
            matches += 1
        elif mismatch is None:
            mismatch = fields_match(decoded, target)
    return matches, mismatch or ""


def run_iteration(index: int, target: tuple[int, int, int],
                  source: lib.Device, sink: lib.Device,
                  cap_name: str) -> tuple[bool, str]:
    with lib.CaptureWindow(cap_name, WINDOW_S, settle_s=SETTLE_S) as window:
        source.clear()
        sink.clear()
        source.send_tx_command(*target, 3)
        ack = source.wait_ack(lib.TYPE_TX_COMMAND, 5.0)
        sink.wait_frame(WINDOW_S + 2.0)
        capture = window.finish()

    if ack is None:
        return False, f"no ACK for TX_COMMAND accepted=1 [stm32: {source.stats()}]"

    d1_frames = capture.decoded_frames("D1")
    d1_matching = sum(1 for decoded in d1_frames
                      if fields_match(decoded, target) is None)
    if d1_matching == 0:
        segments = capture.pulses("D1")
        detail = f"D1 segments={len(segments)} " + \
            " ".join(f"pulses={len(segment)}" for segment in segments[:3])
        decoded_bad = [fields_match(decoded, target) for decoded in d1_frames]
        decoded_bad = [why for why in decoded_bad if why]
        if decoded_bad:
            detail += f" wrong fields: {decoded_bad[0]}"
        return False, f"D1 shows no STX882 burst decoding to the codeword ({detail})"

    sink_ok, sink_mismatch = collect_matching(sink, target)
    if sink_ok == 0:
        return False, (
            f"STM32 burst decodes on D1 ({d1_matching} frames) but the ESP "
            f"emitted no matching FRAME packet [esp: {sink.stats()}]"
        )

    lib.iter_report(
        f"it={index}",
        f"ack=1 d1_bursts={d1_matching} d0_frames="
        f"{sum(1 for f in capture.decoded_frames('D0'))} "
        f"esp_frames={sink_ok}"
    )
    return True, f"D1 frames={d1_matching}, esp matching frames={sink_ok}"


def main() -> int:
    try:
        target = lib.pick_codeword()[:3]
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error}")
        return 1
    try:
        source = lib.open_device(lib.stm32_port(), "stm32")
        sink = lib.open_device(lib.esp_port(), "esp")
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL cannot open serial port: {error}")
        return 1

    passed = 0
    failed_notes = []
    try:
        for index in range(1, N + 1):
            try:
                ok, why = run_iteration(index, target, source, sink, f"t4-it{index}")
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
        sink.close()


if __name__ == "__main__":
    sys.exit(main())
