#!/usr/bin/env python3
"""T8: 50-frame TX_COMMAND burst -- long burst, TX mode, ACK at the end.

The device is put into IDLE first (`mode idle` text command), then a binary
TX_COMMAND with frame_count=50 is sent (hopping 0x114766B9, serial 0x0FB58C0C,
button 2).  While it runs, sigrok records D0 (SRX882 data out = the air) for
~7.5 s, and the STM32 reference receiver is opened read-only as the oracle.

Required checks:
  1. the ACK for_type=3 arrives only AFTER the burst: 4.5 s < delay < 7 s from
     the command, with accepted_raw == 1 (complete)
  2. the STATUS stream shows mode TX during the burst (>= 3 STATUS packets)
     and returns to the pre-burst mode (IDLE) afterwards
  3. D0 shows 50 frames, each decoding to the sent codeword, with consecutive
     frame START times spaced 106.5 ms +-2 ms (and no gap > 112 ms)
  4. the STM32 reports 50 FRAMEs of 77 pulses decoding to the same fields
  5. the loop stays alive during the burst: a `radio` text command is
     answered within 1 s, and a second TX_COMMAND sent mid-burst is ACKed
     immediately with accepted_raw == 0 (busy)

Verdict line: `T8 long_burst: PASS|FAIL <evidence>`; exit code 0/1.
"""

import os
import sys
import time

import hil_lib as lib

TAG = "T8"
NAME = "long_burst"

FRAMES = int(os.environ.get("HIL_T8_FRAMES", "50"))
BURST_WINDOW_S = float(os.environ.get("HIL_T8_WINDOW_S", "7.5"))
ACK_MIN_S = float(os.environ.get("HIL_T8_ACK_MIN_S", "4.5"))
ACK_MAX_S = float(os.environ.get("HIL_T8_ACK_MAX_S", "7.0"))
ACK_TIMEOUT_S = float(os.environ.get("HIL_T8_ACK_TIMEOUT_S", "7.5"))
BUSY_AFTER_S = float(os.environ.get("HIL_T8_BUSY_AFTER_S", "1.2"))
BUSY_MAX_S = float(os.environ.get("HIL_T8_BUSY_MAX_S", "0.5"))
LOOP_AFTER_S = float(os.environ.get("HIL_T8_LOOP_AFTER_S", "2.0"))
LOOP_MAX_S = float(os.environ.get("HIL_T8_LOOP_MAX_S", "1.0"))
FRAME_PERIOD_US = float(os.environ.get("HIL_T8_FRAME_PERIOD_US", "106500"))
FRAME_TOL_US = float(os.environ.get("HIL_T8_FRAME_TOL_US", "2000"))
MAX_GAP_US = float(os.environ.get("HIL_T8_MAX_GAP_US", "112000"))
SPREAD_US = float(os.environ.get("HIL_T8_SPREAD_US", "4000"))
STM32_REQUIRED = int(os.environ.get("HIL_T8_STM32_REQUIRED", str(FRAMES)))
TX_STATUS_REQUIRED = int(os.environ.get("HIL_T8_TX_STATUS_REQUIRED", "3"))
MODE_CHANGE_S = float(os.environ.get("HIL_T8_MODE_CHANGE_S", "2.0"))


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
    # The STM32 is only read (it is the reference receiver), never written.
    stm32 = lib.Device(lib.stm32_port(), "stm32").start()

    problems: list[str] = []
    evidence: list[str] = []
    try:
        if not stm32.ok():
            problems.append(f"STM32 port not usable: {stm32.open_error()}")
            stm32.close()

        # ---- start from IDLE via the text console -------------------------
        idle_line = esp.set_mode_text(lib.MODE_IDLE)
        if idle_line is None:
            problems.append("`mode idle` printed no MODE line")
        else:
            evidence.append(f"pre: {idle_line.strip()}")
            if lib.mode_from_line(idle_line) != lib.MODE_IDLE:
                problems.append(f"pre: {idle_line.strip()} (want idle)")

        esp.clear()
        stm32.clear()

        # ---- capture the burst -------------------------------------------
        with lib.CaptureWindow("t8-long-burst", BURST_WINDOW_S,
                               settle_s=0.8) as window:
            t0 = time.time()
            esp.send_tx_command(*target, FRAMES)

            # (5a) a second TX_COMMAND mid-burst must be rejected at once
            time.sleep(BUSY_AFTER_S)
            busy_sent = time.time()
            esp.send_tx_command(*target, FRAMES)
            busy_ack = esp.wait_ack(lib.TYPE_TX_COMMAND, 1.0,
                                    want_raw=lib.ACK_REJECTED)
            busy_delay = (busy_ack["host_time"] - busy_sent) if busy_ack else None
            if busy_ack is None:
                problems.append(
                    f"mid-burst TX_COMMAND got no accepted_raw=0 ACK within 1s "
                    f"[{esp.stats()}]"
                )
            else:
                evidence.append(
                    f"busy ack accepted_raw={busy_ack.get('accepted_raw')} "
                    f"in {busy_delay * 1000:.0f} ms"
                )
                if busy_delay is not None and busy_delay > BUSY_MAX_S:
                    problems.append(
                        f"busy ACK took {busy_delay:.2f}s (want < {BUSY_MAX_S:.2f}s)"
                    )

            # (5b) the loop still answers text while the burst runs
            time.sleep(max(0.0, LOOP_AFTER_S - (time.time() - t0)))
            loop_sent = time.time()
            lines = esp.text_block("radio", until="RADIO", timeout=1.5)
            loop_delay = time.time() - loop_sent
            radio_line = next((line for line in lines if line.startswith("RADIO")), None)
            if radio_line is None:
                problems.append(
                    f"`radio` got no RADIO reply while the burst ran "
                    f"(waited {loop_delay:.2f}s) [{esp.stats()}]"
                )
            else:
                evidence.append(
                    f"radio reply in {loop_delay * 1000:.0f} ms during burst"
                )
                if loop_delay > LOOP_MAX_S:
                    problems.append(
                        f"`radio` reply took {loop_delay:.2f}s "
                        f"(want <= {LOOP_MAX_S:.2f}s)"
                    )

            # (1) the TX ACK lands when the burst ends
            done_ack = esp.wait_ack(lib.TYPE_TX_COMMAND, ACK_TIMEOUT_S,
                                    want_raw=lib.ACK_COMPLETE)
            ack_delay = (done_ack["host_time"] - t0) if done_ack else None
            if done_ack is None:
                problems.append(
                    f"no ACK accepted_raw=1 for TX_COMMAND({FRAMES}) within "
                    f"{ACK_TIMEOUT_S:.1f}s [{esp.stats()}]"
                )
            else:
                evidence.append(
                    f"tx ack accepted_raw={done_ack.get('accepted_raw')} "
                    f"after {ack_delay:.2f}s"
                )
                if not (ACK_MIN_S < ack_delay < ACK_MAX_S):
                    problems.append(
                        f"TX ACK after {ack_delay:.2f}s "
                        f"(want {ACK_MIN_S}-{ACK_MAX_S}s: it must follow the burst)"
                    )

            capture = window.finish()

        # ---- (2) mode TX during the burst, IDLE afterwards ----------------
        with esp.lock:
            packets = list(esp.packets)
        statuses = [p for p in packets if p.get("type") == "status"]
        tx_status = [p for p in statuses
                     if lib.status_mode(p) == lib.MODE_TX
                     and p["host_time"] >= t0]
        evidence.append(f"status tx={len(tx_status)} of {len(statuses)}")
        if len(tx_status) < TX_STATUS_REQUIRED:
            problems.append(
                f"only {len(tx_status)} STATUS with mode TX during the burst "
                f"(want >= {TX_STATUS_REQUIRED}; modes seen: "
                f"{sorted({lib.status_mode_name(p) for p in statuses})})"
            )
        else:
            first_tx = tx_status[0]["host_time"] - t0
            last_tx = tx_status[-1]["host_time"] - t0
            evidence.append(
                f"mode tx observed {first_tx:.2f}-{last_tx:.2f}s after the command"
            )
        if done_ack is not None:
            after = [p for p in statuses
                     if lib.status_mode(p) == lib.MODE_IDLE
                     and p["host_time"] >= done_ack["host_time"] - 0.05]
            if not after:
                problems.append(
                    "no STATUS with mode IDLE after the burst ended "
                    "(want the pre-burst mode back)"
                )
            else:
                back = after[0]["host_time"] - done_ack["host_time"]
                evidence.append(
                    f"mode idle back {back * 1000:+.0f} ms around the ACK"
                )
                if back > MODE_CHANGE_S:
                    problems.append(
                        f"mode IDLE reported {back:.2f}s after the ACK "
                        f"(want <= {MODE_CHANGE_S:.1f}s)"
                    )

        # ---- (3) D0: 50 frames, codeword match, 106.5 ms spacing ----------
        segments = capture.segments("D0")
        matching = []
        decoded_any = []
        for segment in segments:
            decoded, _scale = lib.decode_air_segment(segment["pulses"])
            if decoded is None:
                continue
            decoded_any.append(decoded)
            if fields_ok(decoded, target):
                matching.append(segment)
        evidence.append(
            f"d0 segments={len(segments)} decoded={len(decoded_any)} "
            f"matching={len(matching)}"
        )
        if len(matching) != FRAMES:
            problems.append(
                f"D0 shows {len(matching)} matching frame(s), want exactly {FRAMES}"
            )
        if len(decoded_any) != len(matching):
            problems.append(
                f"D0 has {len(decoded_any) - len(matching)} frame(s) decoding to "
                f"different fields"
            )
        if len(matching) >= 2:
            starts = [segment["start_us"] for segment in matching]
            gaps = [later - earlier for earlier, later in zip(starts, starts[1:])]
            worst = max(gaps)
            best = min(gaps)
            spread = worst - best
            evidence.append(
                f"spacing min={best / 1000:.2f}ms max={worst / 1000:.2f}ms "
                f"spread={spread / 1000:.2f}ms"
            )
            off = [gap for gap in gaps
                   if abs(gap - FRAME_PERIOD_US) > FRAME_TOL_US]
            if off:
                problems.append(
                    f"{len(off)}/{len(gaps)} frame gaps outside "
                    f"{FRAME_PERIOD_US / 1000:.1f}+-{FRAME_TOL_US / 1000:.1f} ms "
                    f"(first: {off[0] / 1000:.2f} ms)"
                )
            if worst > MAX_GAP_US:
                problems.append(
                    f"frame gap {worst / 1000:.2f} ms exceeds "
                    f"{MAX_GAP_US / 1000:.1f} ms"
                )
            if spread > SPREAD_US:
                problems.append(
                    f"frame gap spread {spread / 1000:.2f} ms exceeds "
                    f"{SPREAD_US / 1000:.1f} ms"
                )

        # ---- (4) STM32 oracle --------------------------------------------
        if stm32.ok():
            with stm32.lock:
                stm32_frames = [p for p in stm32.packets
                                if p.get("type") == "frame"]
            matching77 = 0
            wrong = None
            for packet in stm32_frames:
                pulses = packet.get("pulses_us") or []
                if len(pulses) != 77:
                    continue
                decoded, _scale = lib.decode_segment(list(pulses))
                if decoded is None:
                    continue
                if fields_ok(decoded, target):
                    matching77 += 1
                elif wrong is None:
                    wrong = (f"hopping=0x{decoded['hopping']:08X} "
                             f"serial=0x{decoded['serial']:07X} "
                             f"button=0x{decoded['button']:X}")
            counts = sorted({len(p.get("pulses_us") or []) for p in stm32_frames})
            evidence.append(
                f"stm32 frames={len(stm32_frames)} matching77={matching77}"
            )
            if matching77 < STM32_REQUIRED:
                detail = f"pulse_counts={counts[:6]}"
                if wrong:
                    detail += f" wrong_fields: {wrong}"
                problems.append(
                    f"STM32 reported {matching77} matching 77-pulse FRAME(s), "
                    f"want >= {STM32_REQUIRED} ({detail})"
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
        stm32.close()
        esp.close()


if __name__ == "__main__":
    sys.exit(main())
