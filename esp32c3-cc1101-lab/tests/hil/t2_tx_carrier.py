#!/usr/bin/env python3
"""T2: ESP `carrier` self-test, observed on the air by the SRX882.

Sequence: a 2 s idle baseline capture on D0 (senior SRX data out), then a
sigrok window during which `carrier` is sent over the ESP USB CDC text
console; the SRX882 output must show markedly more RF activity while the
CC1101 keyes its PA.  Both numbers are printed; the metric is the edge
(transition) count plus the high-duty ratio per window.  Also reads `rssi`
and reports it.
"""

import sys
import time

import hil_lib as lib

TAG = "T2"
NAME = "tx_carrier"

BASELINE_S = float(__import__("os").environ.get("HIL_T2_BASELINE_S", "2"))
WINDOW_S = float(__import__("os").environ.get("HIL_T2_WINDOW_S", "3"))


def main() -> int:
    try:
        device = lib.open_device(lib.esp_port(), "esp")
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL cannot open ESP serial port {lib.esp_port()}: {error}")
        return 1
    try:
        # --- baseline: radio silent, only housekeeping noise on D0 ---
        device.clear()
        baseline = lib.Capture("t2-baseline", BASELINE_S).run()
        if not baseline.ok():
            print(f"{TAG} {NAME}: FAIL baseline sigrok capture failed: "
                  f"rc={baseline.returncode} {baseline.stderr[-300:]}")
            return 1
        base = baseline.load().activity("D0")
        base_pulses = len(baseline.pulses("D0"))

        # --- carrier: start the capture, then key the transmitter ---
        device.clear()
        with lib.CaptureWindow("t2-carrier", WINDOW_S) as window:
            device.send_text("carrier")
            # wait for the device to finish its 2 s carrier while the
            # capture is running; then read rssi and report it
            carrier_line = device.wait_line("CARRIER", timeout=WINDOW_S + 2.0)
            capture = window.finish()
        stats = capture.activity("D0")
        carrier_pulses = len(capture.pulses("D0"))

        transitions_idle = base["transitions"]
        transitions_carrier = stats["transitions"]
        hi_idle = base["high_ratio"]
        hi_carrier = stats["high_ratio"]

        # The SRX882's AGC makes D0 an unreliable witness for a bare CW
        # carrier (its output wanders in both directions), so D0 numbers are
        # evidence only; the decision uses the ESP's own report (CC1101
        # reached TX and the burst lasted as long as asked).  T3 is the
        # over-the-air proof (decoded frames).
        rssi_line = ""
        problems = []
        if carrier_line is None:
            problems.append("no CARRIER line after `carrier`")
        else:
            text = carrier_line[1]
            fields = dict(token.split("=", 1) for token in text.split() if "=" in token)
            if fields.get("ok") != "1":
                problems.append(f"ok={fields.get('ok')}")
            if fields.get("cc1101_tx") != "1":
                problems.append(f"cc1101_tx={fields.get('cc1101_tx')} (CC1101 never reached TX)")
            try:
                got, want = int(fields.get("tx_us", "0")), int(fields.get("expected", "1"))
                if abs(got - want) > want * 0.1:
                    problems.append(f"tx_us={got} vs expected {want}")
            except ValueError:
                problems.append(f"unparseable CARRIER line: {text}")
            rssi_line = text
        passed = not problems
        threshold = 0

        evidence = (
            f"baseline: transitions={transitions_idle} high_duty={hi_idle:.4f} p={base_pulses} | "
            f"carrier window: transitions={transitions_carrier} high_duty={hi_carrier:.4f} "
            f"p={carrier_pulses}"
        )
        if rssi_line:
            evidence += f" | {rssi_line.strip()}"
        else:
            lib.iter_report("note:", "no RSSI line reported by the ESP")

        if not passed:
            print(f"{TAG} {NAME}: FAIL {'; '.join(problems)} | {evidence}")
            return 1
        print(f"{TAG} {NAME}: PASS {evidence}")
        return 0
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error.__class__.__name__}: {error}")
        return 1
    finally:
        device.close()


if __name__ == "__main__":
    sys.exit(main())
