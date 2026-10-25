#!/usr/bin/env python3
"""Characterise an arbitrary OOK burst without assuming which protocol it is.

This is layers 1 and 2 of the plan: measure, then infer the encoding. It
deliberately stops before naming a protocol -- putting a brand on a signal is a
descriptor-table lookup, and doing it from timings alone is how the existing
`classify_pulses` in the firmware ended up labelling almost everything EV1527.

What it does, per burst:

1. Clusters every edge duration into classes on a relative-gap criterion, so a
   receiver's few-percent jitter and the capture's sample quantisation merge
   instead of inventing classes.
2. Pulls out a sync/header class: one that is several times the shortest class
   and appears about once per burst. KeeLoq's 4512 us header and PT2262's sync
   gap both land here.
3. Describes the remaining classes by their measured ratio, which is the part
   that actually distinguishes the families:
       1 class        biphase / Manchester (every run is a half-cell)
       2 classes ~2:1 short/long PWM, one bit per cell
       3 classes 1:1.43:1.86  KeeLoq/HCS-style ternary cell
       3 classes 1:1:3        PT2262-style tri-state
4. Extracts a payload with the rule the ratios imply, and compares payloads
   across the capture's bursts. Constant payload across presses is a fixed
   code; a changing one is rolling or a sensor frame.

It is explicit about what it could not do rather than guessing: an unrecognised
ratio is reported as a ratio, not as a name.
"""

from __future__ import annotations

import argparse
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from pulseview_session import Burst, Session, load_session, split_bursts  # noqa: E402

# Relative gap between neighbouring durations that starts a new class. Receiver
# jitter is a few percent and 20 kHz sampling quantises to 50 us, so anything
# under this is the same symbol measured twice.
MERGE_RATIO = 0.25

# A class this many times the shortest is treated as a sync/header rather than
# a data cell, provided it also appears rarely.
SYNC_FACTOR = 2.5

# Bursts shorter than this are noise chatter, not a frame.
MIN_BURST_EDGES = 16


@dataclass
class DurationClass:
    """One cluster of edge durations, in microseconds."""

    center: float
    count: int
    lo: int
    hi: int

    def describe(self) -> str:
        return f"{self.center:.0f}us x{self.count} ({self.lo}-{self.hi})"


@dataclass
class Shape:
    """Everything measured about one burst."""

    edges: int
    classes: list[DurationClass]
    sync: DurationClass | None
    payload_classes: list[DurationClass]
    scheme: str
    symbols: list[int] = field(default_factory=list)
    bits: list[int] = field(default_factory=list)
    note: str = ""


def cluster(durations: list[int], merge_ratio: float = MERGE_RATIO) -> list[DurationClass]:
    """Group durations into classes, merging neighbours within `merge_ratio`."""
    if not durations:
        return []
    values = sorted(durations)
    groups: list[list[int]] = [[values[0]]]
    for value in values[1:]:
        current = groups[-1]
        mean = sum(current) / len(current)
        if (value - current[-1]) / mean > merge_ratio:
            groups.append([value])
        else:
            current.append(value)
    return [
        DurationClass(
            center=sum(g) / len(g), count=len(g), lo=g[0], hi=g[-1]
        )
        for g in groups
    ]


def name_scheme(payload_classes: list[DurationClass]) -> str:
    """Name the encoding from the measured class ratios, or say what it isn't."""
    if not payload_classes:
        return "no data classes"
    centers = [c.center for c in payload_classes]
    base = centers[0]
    ratios = [c / base for c in centers]
    ratio_text = ":".join(f"{r:.2f}" for r in ratios)

    if len(centers) == 1:
        return (
            f"1 class, ratio {ratio_text} -> single cell length: biphase "
            "(Manchester) candidate, or a fixed-interval stream"
        )
    if len(centers) == 2:
        return (
            f"2 classes, ratio {ratio_text} -> one bit per cell, short=0 "
            "long=1 (the shape EV1527/PT2262-style encoders and any simple "
            "1:2 PWM produce)"
        )
    if len(centers) == 3:
        triple = tuple(round(r, 2) for r in ratios)
        if all(abs(a - b) < 0.15 for a, b in zip(triple, (1.0, 1.43, 1.86))):
            return (
                f"3 classes, ratio {ratio_text} -> KeeLoq/HCS-style ternary "
                "cell (equal repeats the bit, short is 0->1, long is 1->0)"
            )
        if all(abs(a - b) < 0.2 for a, b in zip(triple, (1.0, 1.0, 3.0))):
            return (
                f"3 classes, ratio {ratio_text} -> PT2262-style tri-state "
                "(short/long pulse pairs plus a long sync gap)"
            )
        return f"3 classes, ratio {ratio_text} -> ternary, no family matched"
    return f"{len(centers)} classes, ratio {ratio_text} -> unrecognised encoding"


def analyse(session: Session, burst: Burst) -> Shape:
    """Measure one burst: classes, sync, scheme, symbols and bits."""
    durations = [session.us(edge.samples) for edge in burst.edges]
    classes = cluster(durations)
    shape = Shape(edges=len(durations), classes=classes, sync=None,
                  payload_classes=classes)

    if len(classes) >= 2:
        shortest = classes[0]
        longest = classes[-1]
        if (
            longest.center >= shortest.center * SYNC_FACTOR
            and longest.count <= max(2, len(durations) // 8)
        ):
            shape.sync = longest
            shape.payload_classes = classes[:-1]

    shape.scheme = name_scheme(shape.payload_classes)
    payload = shape.payload_classes
    if not payload:
        return shape

    if len(payload) == 2:
        threshold = (payload[0].center + payload[1].center) / 2
        shape.bits = [1 if d > threshold else 0 for d in durations]
        shape.symbols = list(shape.bits)
    elif len(payload) == 3:
        short, mid, long_ = (c.center for c in payload)
        bits = [0]
        consistent = True
        for duration in durations:
            nearest = min(
                (short, mid, long_), key=lambda c: abs(duration - c)
            )
            previous = bits[-1]
            if nearest == short and abs(duration - short) < abs(duration - mid):
                if previous != 0:
                    consistent = False
                bits.append(1)
            elif nearest == long_ and abs(duration - long_) < abs(duration - mid):
                if previous != 1:
                    consistent = False
                bits.append(0)
            else:
                bits.append(previous)
        shape.bits = bits
        shape.symbols = bits
        shape.note = f"transition rule self-consistent: {consistent}"
    else:
        centers = [c.center for c in payload]
        shape.symbols = [
            min(range(len(centers)), key=lambda i: abs(d - centers[i]))
            for d in durations
        ]

    return shape


def payload_key(shape: Shape) -> str:
    """A stable string for comparing one burst's payload with another's.

    The last symbol can be truncated by the burst boundary, and the first by
    the capture start, so both are dropped before hashing.
    """
    if not shape.symbols:
        return ""
    body = shape.symbols[1:-1] if len(shape.symbols) > 8 else shape.symbols
    if shape.bits and len(shape.bits) == len(shape.symbols):
        return pack_hex(body)
    return "".join(str(s) for s in body)


def pack_hex(bits: list[int]) -> str:
    """Pack a bit list MSB-first into hex, ignoring a trailing partial byte."""
    out = bytearray()
    for start in range(0, (len(bits) // 8) * 8, 8):
        value = 0
        for offset in range(8):
            value = (value << 1) | bits[start + offset]
        out.append(value)
    return out.hex()


def report_channel(
    session: Session,
    name: str,
    bit: int,
    gap_ms: float,
    min_edges: int,
    show: int,
) -> None:
    """Measure every substantial burst on one channel and summarise it."""
    bursts = [
        b
        for b in split_bursts(session, session.edges(bit), gap_ms)
        if len(b.edges) >= min_edges
    ]
    print(f"  --- {name} (bit {bit}): {len(bursts)} burst(s) with >= {min_edges} edges")
    if not bursts:
        return

    shapes = [analyse(session, b) for b in bursts]
    first = shapes[0]
    print(f"      classes: {', '.join(c.describe() for c in first.classes)}")
    print(f"      scheme:  {first.scheme}")
    if first.sync:
        print(f"      sync/header class: {first.sync.describe()}")
    if first.note:
        print(f"      {first.note}")

    edge_counts = [s.edges for s in shapes]
    print(
        f"      frame length: median {statistics.median(edge_counts):.0f} edges "
        f"(min {min(edge_counts)}, max {max(edge_counts)})"
    )

    keys = [payload_key(s) for s in shapes]
    distinct = sorted(set(keys), key=keys.count, reverse=True)
    print(f"      payloads: {len(distinct)} distinct across {len(bursts)} burst(s)")
    for key in distinct[:show]:
        print(f"        x{keys.count(key):<3} {key[:72]}")
    if len(distinct) == 1:
        print("        -> same payload every burst: fixed code or a constant frame")
    else:
        print("        -> payload changes between bursts: rolling, or sensor data")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path, help=".sr file or session directory")
    parser.add_argument("--gap-ms", type=float, default=5.0,
                        help="idle gap that ends a burst (default 5)")
    parser.add_argument("--min-edges", type=int, default=MIN_BURST_EDGES,
                        help=f"ignore bursts shorter than this (default {MIN_BURST_EDGES})")
    parser.add_argument("--channel", action="append", default=None,
                        help="restrict to a probe name (repeatable)")
    parser.add_argument("--show", type=int, default=4,
                        help="how many distinct payloads to print (default 4)")
    args = parser.parse_args(argv)

    session = load_session(args.session)
    print(f"=== {session.path} ===")
    wanted = set(args.channel) if args.channel else None
    channels = [
        (name, bit)
        for name, bit, _ in session.toggling_probes()
        if wanted is None or name in wanted
    ]
    if not channels:
        print("  no matching toggling channel")
        return 1
    for name, bit in channels:
        report_channel(session, name, bit, args.gap_ms, args.min_edges, args.show)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
