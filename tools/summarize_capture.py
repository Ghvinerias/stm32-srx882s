#!/usr/bin/env python3
"""Summarise an HCS301 capture as press-bursts instead of a frame list.

A "burst" is a run of frames with no gap larger than --gap seconds. HCS301
transmits one code word followed by repeated copies (repeat bit set) while the
button is held, so a burst maps to one physical press and its hold duration.

Prints one row per burst plus a summary: serial(s) seen, which button nibbles
appear, repeat cadence, longest hold, and frames that did not decode.
"""

from __future__ import annotations

import argparse
import statistics
import sys
from datetime import datetime
from pathlib import Path

import decode_hcs301 as d


def group_bursts(decoded: list[dict], gap_s: float) -> list[list[dict]]:
    if not decoded:
        return []
    bursts = [[decoded[0]]]
    for prev, cur in zip(decoded, decoded[1:]):
        if (cur["t"] - prev["t"]).total_seconds() > gap_s:
            bursts.append([cur])
        else:
            bursts[-1].append(cur)
    return bursts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--gap", type=float, default=0.5,
                        help="seconds of silence that separates bursts (default 0.5)")
    args = parser.parse_args(argv)

    frames = d.load_frames(args.capture)
    scale = d.estimate_scale(frames)

    decoded: list[dict] = []
    undecoded: list[dict] = []
    for packet in frames:
        result = d.decode_frame(packet.get("pulses_us", []), scale)
        if result is None:
            undecoded.append(packet)
            continue
        result["t"] = datetime.fromisoformat(packet["host_time_utc"])
        result["seq"] = packet["sequence"]
        decoded.append(result)

    print(f"{args.capture}: {len(frames)} frames, scale {scale:.4f}, "
          f"{len(decoded)} decoded, {len(undecoded)} not")
    print()

    bursts = group_bursts(decoded, args.gap)
    header = (f"{'#':>3} {'start':>12} {'gap':>7} {'n':>4} {'dur':>6} "
              f"{'btn':>5} {'hopping':>10} {'interval':>8}  repeat")
    print(header)
    print("-" * len(header))

    intervals: list[float] = []
    for i, burst in enumerate(bursts):
        start = burst[0]["t"].strftime("%H:%M:%S.%f")[:-3]
        length = (burst[-1]["t"] - burst[0]["t"]).total_seconds()
        gap = (burst[0]["t"] - bursts[i - 1][-1]["t"]).total_seconds() if i else 0.0
        buttons = sorted({b["button"] for b in burst})
        hoppings = sorted({b["hopping"] for b in burst})
        deltas = [(b["t"] - a["t"]).total_seconds() * 1000
                  for a, b in zip(burst, burst[1:])]
        interval = statistics.median(deltas) if deltas else 0.0
        intervals.extend(deltas)
        repeat = "".join(str(b["repeat"]) for b in burst)
        btn_text = ",".join(f"0x{b:X}" for b in buttons)
        hop_text = ",".join(f"0x{h:08X}" for h in hoppings[:3])
        if len(hoppings) > 3:
            hop_text += f",+{len(hoppings) - 3}"
        print(f"{i:>3} {start:>12} {gap:>6.2f}s {len(burst):>4} "
              f"{length:>5.2f}s {btn_text:>5} {hop_text:>10} "
              f"{interval:>7.1f}ms  {repeat[:28]}")

    print()
    serials = sorted({r["serial"] for r in decoded})
    buttons = sorted({r["button"] for r in decoded})
    print(f"serials : {', '.join(f'0x{s:07X}' for s in serials) or 'none'}")
    print(f"buttons : {', '.join(f'0x{b:X}' for b in buttons) or 'none'}")
    if intervals:
        print(f"repeat  : median {statistics.median(intervals):.1f} ms "
              f"over {len(intervals)} intervals")
    # Rolling-code conformance: a rolling transmitter emits a different hopping
    # word on each separate press, and repeats that word within a burst. A fixed
    # code transmitter sends the same word every press.
    presses: dict[int, int] = {}
    hops_per_button: dict[int, set[int]] = {}
    for burst in bursts:
        first_seen: dict[int, int] = {}
        for frame in burst:
            first_seen.setdefault(frame["button"], frame["hopping"])
        for button, hop in first_seen.items():
            presses[button] = presses.get(button, 0) + 1
            hops_per_button.setdefault(button, set()).add(hop)

    print()
    print("rolling-code conformance (hopping word across separate presses):")
    for button in sorted(hops_per_button):
        count = presses[button]
        distinct = len(hops_per_button[button])
        if count < 2:
            verdict = "inconclusive - needs >=2 separate presses"
        elif distinct >= 2:
            verdict = f"ROLLING - {distinct}/{count} presses differed"
        else:
            verdict = f"FIXED - same word on all {count} presses"
        print(f"  button 0x{button:X}: {verdict}")

    if 0xF in buttons:
        print("WARNING : button nibble 0xF present -- check for a seed frame",
              file=sys.stderr)
    if undecoded:
        counts = sorted({len(p.get("pulses_us", [])) for p in undecoded})
        print(f"undecoded pulse counts: {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
