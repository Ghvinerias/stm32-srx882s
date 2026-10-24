#!/usr/bin/env python3
"""T1: ESP32 diagnostics over USB CDC text commands.

Checks, against the live ESP32-C3 + CC1101:
  * `radio`  -> CC1101 ready, partnum=0x00, version 0x14 or 0x04, state=RX
  * `regs`   -> register read-back: IOCFG0=2e PKTCTRL0=32 MDMCFG2=30
                FREND0=11, FREQ=0x10/0xB0/0x71, PATABLE starts 00,c0,...
  * `gpio`   -> pad levels present and gdo2 level matches GPIO10 (not MISMATCH)

The ESP currently may run old firmware without these commands; in that case
the test fails with the exact missing line named in the output.
"""

import re
import sys

import hil_lib as lib

TAG = "T1"
NAME = "diagnostics"


def parse_kv(line: str) -> dict:
    """'REGS IOCFG2=0d IOCFG0=2e ... PATABLE=00,c0,..' -> dict (PATABLE kept raw)."""
    values: dict = {}
    for token in line.split()[1:]:
        if "=" not in token:
            continue
        key, _, value = token.partition("=")
        values[key.upper()] = value.strip().rstrip(",")
    return values


def main() -> int:
    tries: list[str] = []
    try:
        port = lib.esp_port()
        device = lib.open_device(port, "esp")
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL cannot open ESP serial port {lib.esp_port()}: {error}")
        return 1
    try:
        try:
            device.wait_line("", timeout=0.5)
        except Exception:
            pass
        device.clear()

        device.send_text("radio")
        captured = device.wait_line("RADIO", timeout=3.0)
        if captured is None:
            print(f"{TAG} {NAME}: FAIL no 'RADIO ...' line after `radio` command "
                  f"(stats: {device.stats()}; lines seen: {device.lines[-5:]})")
            return 1
        radio = parse_kv(captured[1])
        tries.append(f"radio={captured[1].strip()}")
        if "PARTNUM" not in radio:
            print(f"{TAG} {NAME}: FAIL RADIO line lacks key=value fields: {captured[1]!r}")
            return 1
        problems = []
        try:
            partnum = int(radio["PARTNUM"], 16)
        except ValueError:
            partnum = None
        if partnum != 0x00:
            problems.append(f"partnum={radio.get("PARTNUM")} (want 00)")
        try:
            version = int(radio.get("VERSION", "zz"), 16)
        except ValueError:
            version = None
        if version not in (0x14, 0x04):
            problems.append(f"version={radio.get("VERSION")} (want 14 or 04)")
        if radio.get("STATE") not in ("RX",):
            problems.append(f"state={radio.get('STATE')} (want RX)")
        if "NOT_READY" in captured[1]:
            problems.append("radio NOT_READY (CC1101 init failed)")

        device.send_text("regs")
        captured = device.wait_line("REGS", timeout=3.0)
        if captured is None:
            print(f"{TAG} {NAME}: FAIL no 'REGS ...' line after `regs` command "
                  f"(stats: {device.stats()}; lines seen: {device.lines[-5:]})")
            return 1
        regs = parse_kv(captured[1])
        tries.append(f"regs={captured[1].strip()}")
        for key, want in (("IOCFG0", "2e"), ("PKTCTRL0", "32"),
                          ("MDMCFG2", "30"), ("FREND0", "11"),
                          ("FREQ2", "10"), ("FREQ1", "b0"), ("FREQ0", "71")):
            got = regs.get(key, "").lower()
            if got != want:
                problems.append(f"{key}={got or '<missing>'} (want {want})")
        patable = regs.get("PATABLE", "")
        if not patable:
            problems.append("PATABLE missing from REGS line")
        else:
            pa = [value.lower() for value in patable.split(",")]
            if pa[0] != "00" or (len(pa) > 1 and pa[1] != "c0"):
                problems.append(f"PATABLE={patable} (want to start 00,c0)")

        device.send_text("gpio")
        captured = device.wait_line("GPIO", timeout=3.0)
        if captured is None:
            print(f"{TAG} {NAME}: FAIL no 'GPIO ...' line after `gpio` command")
            return 1
        tries.append(f"gpio={captured[1].strip()}")
        line = captured[1]
        if "MISMATCH" in line:
            problems.append(line.split("|")[-1].strip() if "|" in line else "gdo2 MISMATCH")
        elif "gdo2 matches gpio10" not in line:
            problems.append(f"gpio line lacks the match/mismatch verdict: {line}")
        for name in ("gpio3", "gpio10", "pktstatus"):
            if name not in line:
                problems.append(f"gpio line missing field {name}")

        if problems:
            print(f"{TAG} {NAME}: FAIL " + "; ".join(problems) + " || evidence: " + " ;; ".join(tries))
            return 1
        print(f"{TAG} {NAME}: PASS " + " ;; ".join(tries))
        return 0
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error.__class__.__name__}: {error}")
        return 1
    finally:
        device.close()


if __name__ == "__main__":
    sys.exit(main())
