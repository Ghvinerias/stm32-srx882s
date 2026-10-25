#!/usr/bin/env python3
"""T9: aborting a 50-frame TX_COMMAND burst with MODE_COMMAND idle.

Sequence: `mode idle` (text), then a binary TX_COMMAND with frame_count=50.
After ~1.5 s a binary MODE_COMMAND idle is sent while the burst is on the air.
sigrok records D0 (SRX882 data out) across the abort and for 3 s afterwards.

Required checks:
  * the TX_COMMAND ACK arrives with accepted_raw == 2 (aborted) within 0.6 s
    of the MODE_COMMAND
  * the MODE_COMMAND ACK has accepted_raw == 1
  * STATUS shows mode TX during the burst and mode IDLE after the abort
  * D0 shows between 5 and 30 frames in total (the burst is truncated), and
    nothing is transmitted in the last 3 s of the capture

Verdict line: `T9 abort: PASS|FAIL <evidence>`; exit code 0/1.
"""

import os
import sys
import time

import hil_lib as lib

TAG = "T9"
NAME = "abort"

FRAMES = int(os.environ.get("HIL_T9_FRAMES", "50"))
ABORT_AFTER_S = float(os.environ.get("HIL_T9_ABORT_AFTER_S", "1.5"))
TAIL_S = float(os.environ.get("HIL_T9_TAIL_S", "3.0"))
WINDOW_S = float(os.environ.get("HIL_T9_WINDOW_S", "6.0"))
ABORT_ACK_MAX_S = float(os.environ.get("HIL_T9_ABORT_ACK_MAX_S", "0.6"))
MIN_FRAMES = int(os.environ.get("HIL_T9_MIN_FRAMES", "5"))
MAX_FRAMES = int(os.environ.get("HIL_T9_MAX_FRAMES", "30"))


def fields_ok(decoded: dict, target: tuple[int, int, int]) -> bool:
    return (decoded["hopping"], decoded["serial"], decoded["button"]) == target


def main() -> int:
    try:
        target = lib.pick_codeword()[:3]
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error}")
        return 1
    try:
        esp = lib.open_device(lib.esp_port(), "esp")
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL cannot open ESP serial port "
              f"{lib.esp_port()}: {error}")
        return 1

    problems: list[str] = []
    evidence: list[str] = []
    try:
        idle_line = esp.set_mode_text(lib.MODE_IDLE)
        if idle_line is None:
            problems.append("`mode idle` printed no MODE line")
        else:
            evidence.append(f"pre: {idle_line.strip()}")

        esp.clear()
        with lib.CaptureWindow("t9-abort", WINDOW_S, settle_s=0.8) as window:
            t0 = time.time()
            esp.send_tx_command(*target, FRAMES)

            # let the burst run, then abort it from the host
            time.sleep(max(0.0, ABORT_AFTER_S - (time.time() - t0)))
            abort_sent = time.time()
            esp.send_mode_command(lib.MODE_IDLE)

            abort_ack = esp.wait_ack(lib.TYPE_TX_COMMAND, 1.5,
                                     want_raw=lib.ACK_ABORTED)
            abort_delay = (abort_ack["host_time"] - abort_sent) if abort_ack else None
            if abort_ack is None:
                problems.append(
                    f"TX_COMMAND was not ACKed with accepted_raw=2 within 1.5s "
                    f"of MODE_COMMAND idle [{esp.stats()}]"
                )
            else:
                evidence.append(
                    f"tx abort ack accepted_raw={abort_ack.get('accepted_raw')} "
                    f"{abort_delay * 1000:.0f} ms after mode idle"
                )
                if abort_delay > ABORT_ACK_MAX_S:
                    problems.append(
                        f"abort ACK took {abort_delay:.2f}s "
                        f"(want <= {ABORT_ACK_MAX_S:.2f}s)"
                    )

            mode_ack = esp.wait_ack(lib.TYPE_MODE_COMMAND, 1.5,
                                    want_raw=lib.ACK_COMPLETE)
            if mode_ack is None:
                problems.append(
                    "no MODE_COMMAND ACK accepted_raw=1 for mode idle "
                    f"[{esp.stats()}]"
                )
            else:
                evidence.append(
                    f"mode ack accepted_raw={mode_ack.get('accepted_raw')}"
                )

            # the tail of the capture must be silent: finish() waits for the
            # whole window (abort + 3 s of quiet) to be recorded
            capture = window.finish()

        # ---- STATUS: TX during the burst, IDLE after the abort ------------
        with esp.lock:
            packets = list(esp.packets)
        statuses = [p for p in packets if p.get("type") == "status"]
        tx_status = [p for p in statuses
                     if lib.status_mode(p) == lib.MODE_TX
                     and p["host_time"] <= abort_sent + 0.2]
        idle_after = [p for p in statuses
                      if lib.status_mode(p) == lib.MODE_IDLE
                      and p["host_time"] >= abort_sent - 0.05]
        evidence.append(
            f"status tx_before_abort={len(tx_status)} "
            f"idle_after_abort={len(idle_after)}"
        )
        if not tx_status:
            problems.append(
                "no STATUS with mode TX while the burst ran (modes seen: "
                f"{sorted({lib.status_mode_name(p) for p in statuses})})"
            )
        if not idle_after:
            problems.append("no STATUS with mode IDLE after the abort")

        # ---- D0: truncated burst, then silence ---------------------------
        segments = capture.segments("D0")
        matching = []
        for segment in segments:
            decoded, _scale = lib.decode_air_segment(segment["pulses"])
            if decoded is not None and fields_ok(decoded, target):
                matching.append(segment)
        evidence.append(
            f"d0 segments={len(segments)} matching={len(matching)}"
        )
        if not (MIN_FRAMES <= len(matching) <= MAX_FRAMES):
            problems.append(
                f"D0 shows {len(matching)} frame(s), want {MIN_FRAMES}..{MAX_FRAMES} "
                f"(the burst must be truncated, not completed)"
            )
        if matching:
            last_start = max(segment["start_us"] for segment in matching)
            quiet_s = capture.seconds - last_start / 1_000_000.0
            evidence.append(f"quiet tail {quiet_s:.2f}s")
            if quiet_s < TAIL_S:
                problems.append(
                    f"only {quiet_s:.2f}s of silence after the last frame "
                    f"(want >= {TAIL_S:.1f}s: nothing may be transmitted after "
                    f"the abort)"
                )
            late = [segment for segment in segments
                    if segment["start_us"] > last_start + 1_000_000]
            if late:
                problems.append(
                    f"{len(late)} burst(s) on D0 more than 1 s after the last "
                    f"matching frame (post-abort transmission?)"
                )

        if problems:
            print(f"{TAG} {NAME}: FAIL " + "; ".join(problems)
                  + " || " + " | ".join(evidence))
            return 1
        print(f"{TAG} {NAME}: PASS " + " | ".join(evidence))
        return 0
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error.__class__.__name__}: {error}"
              + (" || " + " | ".join(evidence) if evidence else ""))
        return 1
    finally:
        try:
            esp.set_mode(lib.MODE_IDLE, timeout=1.0)
        except Exception:
            pass
        esp.close()


if __name__ == "__main__":
    sys.exit(main())
