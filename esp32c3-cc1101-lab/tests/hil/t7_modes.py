#!/usr/bin/env python3
"""T7: radio modes (IDLE / RX / TX) on the ESP32-C3 + CC1101 over USB.

The device boots in IDLE: CC1101 idle, RMT capture off, no FRAME packets.
MODE_COMMAND (type 8) selects IDLE/RX; every mode change is followed by an
immediate STATUS whose header flags bits 2..3 carry the new mode.

Steps (all over one USB CDC link, no STM32 involved):
  a) 6 s of STATUS right after connecting, WITHOUT sending anything: every
     STATUS must report mode IDLE and no FRAME may appear
  b) MODE_COMMAND rx -> ACK(for_type=8) accepted_raw=1, an immediate STATUS
     with mode RX within 1 s, and `radio` shows the RMT rx_irq counter
     climbing over 3 s (the capture path is really running)
  c) MODE_COMMAND 7 (unknown) -> ACK accepted_raw=0 and the mode stays RX
  d) MODE_COMMAND idle -> ACK accepted_raw=1, STATUS mode IDLE, rx_irq stops
     climbing and no FRAME arrives for 3 s
  e) text console: bare `mode` prints a MODE line, `mode rx` and `mode idle`
     switch the mode too

Verdict line: `T7 modes: PASS|FAIL <evidence>`; exit code 0/1.
"""

import os
import sys

import hil_lib as lib

TAG = "T7"
NAME = "modes"

IDLE_WINDOW_S = float(os.environ.get("HIL_T7_IDLE_WINDOW_S", "6"))
RX_WINDOW_S = float(os.environ.get("HIL_T7_RX_WINDOW_S", "3"))
IDLE_TAIL_S = float(os.environ.get("HIL_T7_IDLE_TAIL_S", "3"))
MODE_TIMEOUT_S = float(os.environ.get("HIL_T7_MODE_TIMEOUT_S", "1.0"))
ACK_TIMEOUT_S = float(os.environ.get("HIL_T7_ACK_TIMEOUT_S", "2.0"))
INVALID_MODE = int(os.environ.get("HIL_T7_INVALID_MODE", "7"))


def read_mode_line(device: lib.Device, timeout: float = 2.0) -> str | None:
    """Bare `mode`: the firmware answers with one MODE line."""
    lines = device.text_block("mode", until="MODE", timeout=timeout)
    for line in lines:
        if line.startswith("MODE"):
            return line
    return None


def main() -> int:
    try:
        esp = lib.open_device(lib.esp_port(), "esp")
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL cannot open ESP serial port "
              f"{lib.esp_port()}: {error}")
        return 1

    problems: list[str] = []
    evidence: list[str] = []
    try:
        # ---- (a) boot state: IDLE, silent, no FRAME -----------------------
        packets = lib.collect_packets(esp, IDLE_WINDOW_S)
        statuses = [p for p in packets if p.get("type") == "status"]
        frames = [p for p in packets if p.get("type") == "frame"]
        modes = sorted({lib.status_mode(p) for p in statuses})
        evidence.append(
            f"a: {len(statuses)} status in {IDLE_WINDOW_S:.0f}s modes={modes} "
            f"frames={len(frames)}"
        )
        if not statuses:
            problems.append(
                f"a: no STATUS in {IDLE_WINDOW_S:.0f}s after connecting "
                f"[{esp.stats()}]"
            )
        elif any(lib.status_mode(p) != lib.MODE_IDLE for p in statuses):
            problems.append(
                f"a: STATUS modes {modes} (want all {lib.MODE_IDLE}=idle)"
            )
        if frames:
            problems.append(f"a: {len(frames)} FRAME packet(s) while IDLE "
                            f"(want none)")

        # ---- (b) MODE_COMMAND rx -----------------------------------------
        esp.clear()
        try:
            ack = esp.set_mode(lib.MODE_RX, timeout=ACK_TIMEOUT_S)
            evidence.append(f"b: mode rx ack accepted_raw={ack.get('accepted_raw')}")
        except Exception as error:
            problems.append(f"b: MODE_COMMAND rx failed: {error}")
            ack = None
        status = esp.wait_status_mode(lib.MODE_RX, MODE_TIMEOUT_S)
        if status is None:
            problems.append(
                f"b: no STATUS with mode RX within {MODE_TIMEOUT_S:.1f}s "
                f"after MODE_COMMAND rx [{esp.stats()}]"
            )
        else:
            evidence.append(
                f"b: status mode={lib.status_mode_name(status)} "
                f"seq={status['sequence']}"
            )

        radio_before = lib.read_radio(esp)
        rx_frames = lib.collect_packets(esp, RX_WINDOW_S, "frame")
        radio_after = lib.read_radio(esp)
        try:
            irq_before = int(radio_before.get("rx_irq", "-1"))
            irq_after = int(radio_after.get("rx_irq", "-1"))
            sym_before = int(radio_before.get("rx_sym", "-1"))
            sym_after = int(radio_after.get("rx_sym", "-1"))
        except ValueError:
            irq_before = irq_after = sym_before = sym_after = -1
        irq_delta = irq_after - irq_before
        sym_delta = sym_after - sym_before
        evidence.append(
            f"b: rx_irq {irq_before}->{irq_after} (delta={irq_delta}) "
            f"rx_sym delta={sym_delta} frames_in_rx={len(rx_frames)}"
        )
        if irq_delta <= 0 and sym_delta <= 0 and not rx_frames:
            problems.append(
                f"b: RX looks dead: rx_irq {irq_before}->{irq_after}, "
                f"rx_sym delta={sym_delta}, no FRAME in {RX_WINDOW_S:.0f}s"
            )

        # ---- (c) unknown mode is rejected, mode unchanged -----------------
        esp.clear()
        esp.send_mode_command(INVALID_MODE)
        bad_ack = esp.wait_ack(lib.TYPE_MODE_COMMAND, ACK_TIMEOUT_S,
                               want_raw=lib.ACK_REJECTED)
        if bad_ack is None:
            problems.append(
                f"c: no ACK accepted_raw=0 for MODE_COMMAND {INVALID_MODE} "
                f"within {ACK_TIMEOUT_S:.1f}s [{esp.stats()}]"
            )
        else:
            evidence.append(
                f"c: invalid mode {INVALID_MODE} ack accepted_raw="
                f"{bad_ack.get('accepted_raw')}"
            )
        mode_line = read_mode_line(esp)
        got = lib.mode_from_line(mode_line) if mode_line else None
        if mode_line is None:
            problems.append("c: bare `mode` printed no MODE line")
        else:
            evidence.append(f"c: {mode_line.strip()}")
            if got != lib.MODE_RX:
                problems.append(
                    f"c: mode changed to {lib.mode_name(got) if got is not None else got}"
                    f" after an invalid mode (want rx)"
                )

        # ---- (d) MODE_COMMAND idle ---------------------------------------
        esp.clear()
        try:
            ack = esp.set_mode(lib.MODE_IDLE, timeout=ACK_TIMEOUT_S)
            evidence.append(f"d: mode idle ack accepted_raw={ack.get('accepted_raw')}")
        except Exception as error:
            problems.append(f"d: MODE_COMMAND idle failed: {error}")
        status = esp.wait_status_mode(lib.MODE_IDLE, MODE_TIMEOUT_S)
        if status is None:
            problems.append(
                f"d: no STATUS with mode IDLE within {MODE_TIMEOUT_S:.1f}s "
                f"after MODE_COMMAND idle [{esp.stats()}]"
            )
        else:
            evidence.append(
                f"d: status mode={lib.status_mode_name(status)} "
                f"seq={status['sequence']}"
            )

        radio_before = lib.read_radio(esp)
        idle_frames = lib.collect_packets(esp, IDLE_TAIL_S, "frame")
        radio_after = lib.read_radio(esp)
        try:
            idle_irq_before = int(radio_before.get("rx_irq", "-1"))
            idle_irq_after = int(radio_after.get("rx_irq", "-1"))
        except ValueError:
            idle_irq_before = idle_irq_after = -1
        idle_delta = idle_irq_after - idle_irq_before
        evidence.append(
            f"d: rx_irq {idle_irq_before}->{idle_irq_after} "
            f"(delta={idle_delta}) frames_in_idle={len(idle_frames)}"
        )
        if idle_delta > 0:
            problems.append(
                f"d: rx_irq still climbing in IDLE "
                f"({idle_irq_before}->{idle_irq_after})"
            )
        if idle_frames:
            problems.append(
                f"d: {len(idle_frames)} FRAME packet(s) while IDLE "
                f"(want none)"
            )

        # ---- (e) text console equivalents --------------------------------
        mode_line = read_mode_line(esp)
        got = lib.mode_from_line(mode_line) if mode_line else None
        if mode_line is None:
            problems.append("e: bare `mode` printed no MODE line")
        else:
            evidence.append(f"e: {mode_line.strip()}")
            if got != lib.MODE_IDLE:
                problems.append(
                    f"e: bare `mode` says "
                    f"{lib.mode_name(got) if got is not None else got} (want idle)"
                )

        for mode, want in ((lib.MODE_RX, lib.MODE_RX), (lib.MODE_IDLE, lib.MODE_IDLE)):
            with esp.lock:
                mark = len(esp.packets)
            line = esp.set_mode_text(mode)
            got = lib.mode_from_line(line) if line else None
            if line is None:
                problems.append(f"e: `mode {lib.mode_name(mode)}` printed no MODE line")
            else:
                evidence.append(f"e: {line.strip()}")
                if got != want:
                    problems.append(
                        f"e: `mode {lib.mode_name(mode)}` reported "
                        f"{lib.mode_name(got) if got is not None else got}"
                    )
            # the binary path and the text path must agree
            status = esp.wait_status_mode(want, MODE_TIMEOUT_S, since=mark)
            if status is None:
                problems.append(
                    f"e: no STATUS with mode {lib.mode_name(want)} after "
                    f"`mode {lib.mode_name(mode)}`"
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
