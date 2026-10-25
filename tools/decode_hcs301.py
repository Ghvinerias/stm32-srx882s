#!/usr/bin/env python3
"""Decode captured HCS301-shaped frames from the RF lab's NDJSON output.

This only reconstructs the bit fields that the chip transmits in the clear
over the air (sync/hopping block as received, serial, button nibble, status
bits). It does not attempt decryption and does not derive or guess any key
material. Its purpose is purely diagnostic: confirm which button nibble a
given transmission reports and whether the "hopping" 32-bit field changes or
stays constant across repeated presses of the same button combination.

Bit layout mirrors firmware/src/lab_tx.c's encoder (bits[0..31] hopping LSB
first, bits[32..59] serial LSB first, bits[60..63] button LSB first,
bit[64] VLOW, bit[65] repeat), and the same measured timing constants
(preamble edge, header bit zero/one, short/mid/long data cells).

Timing is auto-calibrated per capture. The reference constants below were
measured on one transmitter; other units run a few percent faster or slower
because the encoder clock is an RC oscillator. A fixed tolerance rejects a
perfectly good capture whose symbol rate is offset (this happened: a unit at
~4.1% slower produced 18 frames that all failed to decode). The scale factor
is estimated from the median data-cell width, which lands on the MID class
for a roughly random bit stream, then applied to all four symbol classes.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

PREAMBLE_EDGES = 11
HEADER_ZERO_US = 4512
HEADER_ONE_US = 4170
SHORT_US = 795
MID_US = 1138
LONG_US = 1482
TOLERANCE = 0.10


def estimate_scale(frames: list[dict]) -> float:
    """Estimate the transmitter's symbol-rate scale from data-cell widths.

    The MID class is the most common (adjacent bits equal ~half the time), so
    the median of all data cells is a robust estimate of its centre.
    """
    values = sorted(
        pulse for frame in frames for pulse in frame.get("pulses_us", [])[PREAMBLE_EDGES + 1:]
    )
    if len(values) < 8:
        return 1.0
    scale = statistics.median(values) / MID_US
    # Reject nonsense rather than silently decoding noise.
    if not 0.85 <= scale <= 1.15:
        return 1.0
    return scale


def classify(duration: int, center: float) -> bool:
    return abs(duration - center) <= center * TOLERANCE


def decode_frame(pulses: list[int], scale: float = 1.0) -> dict | None:
    if len(pulses) != PREAMBLE_EDGES + 1 + 65:
        return None

    header = pulses[PREAMBLE_EDGES]
    zero = HEADER_ZERO_US * scale
    one = HEADER_ONE_US * scale
    if not (classify(header, zero) or classify(header, one)):
        return None
    bit0 = 0 if abs(header - zero) <= abs(header - one) else 1

    bits = [0] * 66
    bits[0] = bit0
    data = pulses[PREAMBLE_EDGES + 1:]
    for i, duration in enumerate(data):
        short = SHORT_US * scale
        mid = MID_US * scale
        long_ = LONG_US * scale
        candidates = [(abs(duration - short), "s"), (abs(duration - mid), "m"),
                      (abs(duration - long_), "l")]
        error, kind = min(candidates)
        if error > mid * TOLERANCE:
            return None
        if kind == "s":
            # 0 -> 1 transition
            if bits[i] != 0:
                return None
            bits[i + 1] = 1
        elif kind == "l":
            # 1 -> 0 transition
            if bits[i] != 1:
                return None
            bits[i + 1] = 0
        else:
            bits[i + 1] = bits[i]

    def bits_to_int(start: int, count: int) -> int:
        value = 0
        for i in range(count):
            value |= bits[start + i] << i
        return value

    hopping = bits_to_int(0, 32)
    serial = bits_to_int(32, 28)
    button = bits_to_int(60, 4)
    vlow = bits[64]
    repeat = bits[65]
    return {
        "hopping": hopping,
        "serial": serial,
        "button": button,
        "vlow": vlow,
        "repeat": repeat,
    }


def load_frames(path: Path) -> list[dict]:
    frames = []
    with path.open() as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            packet = json.loads(line)
            if packet.get("type") != "frame":
                continue
            frames.append(packet)
    return frames


def iter_frames(frames: list[dict], scale: float):
    for packet in frames:
        decoded = decode_frame(packet.get("pulses_us", []), scale)
        if decoded is None:
            continue
        decoded["sequence"] = packet.get("sequence")
        decoded["host_time_utc"] = packet.get("host_time_utc")
        yield decoded


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, help="NDJSON capture file")
    parser.add_argument(
        "--seed-button", type=lambda s: int(s, 0), default=0xF,
        help="button nibble value to flag as a seed candidate (default 0xF)",
    )
    parser.add_argument(
        "--scale", default="auto",
        help="timing scale factor, or 'auto' to estimate from the capture (default)",
    )
    args = parser.parse_args(argv)

    frames = load_frames(args.capture)
    if args.scale == "auto":
        scale = estimate_scale(frames)
        print(f"timing scale: {scale:.4f} (auto, {len(frames)} frames)", file=sys.stderr)
    else:
        scale = float(args.scale)

    decoded_count = 0
    undecoded = 0
    seed_hits = []
    by_button_hopping: dict[int, set[int]] = {}

    for decoded in iter_frames(frames, scale):
        decoded_count += 1
        button = decoded["button"]
        by_button_hopping.setdefault(button, set()).add(decoded["hopping"])
        if button == args.seed_button:
            seed_hits.append(decoded)
        print(
            f"seq={decoded['sequence']:<6} button=0x{button:X} "
            f"hopping=0x{decoded['hopping']:08X} serial=0x{decoded['serial']:07X} "
            f"vlow={decoded['vlow']} repeat={decoded['repeat']} "
            f"t={decoded['host_time_utc']}"
        )

    undecoded = len(frames) - decoded_count
    print(f"\n{decoded_count} frames decoded from {args.capture}", file=sys.stderr)
    if undecoded:
        print(f"{undecoded} frame(s) did not match a code word", file=sys.stderr)
    for button, hoppings in sorted(by_button_hopping.items()):
        flag = " <-- seed-button candidate" if button == args.seed_button else ""
        print(
            f"button=0x{button:X}: {len(hoppings)} distinct hopping value(s) "
            f"over its transmissions{flag}",
            file=sys.stderr,
        )
        if button == args.seed_button and len(hoppings) == 1:
            print(
                "  constant hopping field across repeats is consistent with a "
                "seed/non-rolling transmission; a genuinely hopping/encrypted "
                "frame would differ almost every press.",
                file=sys.stderr,
            )

    if not seed_hits:
        print(
            f"\nNo frame used button nibble 0x{args.seed_button:X} in this "
            "capture.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
