#!/usr/bin/env python3
"""Manual RX check: listen on the ESP for N seconds (default 60) while a human
presses the real 433.92 MHz remote near the CC1101 antenna.  Prints every FRAME
(pulse count, decode result) and the raw RMT counters before/after.

    HIL_ESP_PORT=... ~/venv/bin/python watch_rx.py [seconds]
"""
import sys
import time

import hil_lib as lib


def main() -> int:
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
    device = lib.open_device(lib.esp_port(), "esp")
    try:
        device.clear()
        device.send_text("radio")
        before = device.wait_line("RMT", timeout=3.0)
        print("before:", before[1] if before else "?")
        print(f"listening {seconds:.0f}s - press the remote now ...", flush=True)
        end = time.monotonic() + seconds
        seen = good = 0
        decoded_set = []
        cursor = 0
        while time.monotonic() < end:
            time.sleep(0.2)
            with device.lock:
                new = device.packets[cursor:]
                cursor = len(device.packets)
            for packet in new:
                if packet.get("type") != "frame":
                    continue
                seen += 1
                pulses = packet.get("pulses_us") or []
                fields, scale = lib.decode_segment(list(pulses))
                if fields:
                    good += 1
                    decoded_set.append((fields['hopping'], fields['serial'], fields['button']))
                    print(f"FRAME seq={packet['sequence']} pulses={len(pulses)} "
                          f"DECODED hopping=0x{fields['hopping']:08X} "
                          f"serial=0x{fields['serial']:07X} button=0x{fields['button']:X}")
                else:
                    print(f"frame seq={packet['sequence']} pulses={len(pulses)} (noise/undecoded)")
        device.clear()
        device.send_text("radio")
        after = device.wait_line("RMT", timeout=3.0)
        print("after: ", after[1] if after else "?")
        print(f"frames={seen} decoded={good}")
        hops = sorted({h for h, _, _ in decoded_set})
        sers = sorted({s for _, s, _ in decoded_set})
        print("distinct serials:", ["0x%07X" % x for x in sers],
              "| distinct hoppings:", ["0x%08X" % x for x in hops],
              "| buttons:", sorted({b for _, _, b in decoded_set}))
        if good:
            print("serial constant:", len(sers) == 1, "| hopping changes between presses:", len(hops) > 1)
        print("WATCH_RX:", "PASS" if good else "NO DECODED FRAME")
        return 0 if good else 1
    finally:
        device.close()


if __name__ == "__main__":
    sys.exit(main())
