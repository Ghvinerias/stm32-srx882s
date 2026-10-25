#!/usr/bin/env python3
"""Analyse PulseView / sigrok session captures from the RF lab bench.

A sigrok session (`.sr`) is a zip archive -- or a directory -- holding:

    version      session format version
    metadata     sigrok INI: samplerate, unitsize, probe names
    logic-1-1    raw samples, one byte per sample at unitsize 1, bit N = probe N+1

This module turns that into per-channel edge lists and runs the *existing*
KeeLoq code-word decoder from `tools/decode_hcs301.py` over them, so a bench
recording is judged by exactly the same rules as a firmware NDJSON capture --
no second decoder that could disagree.

Why it exists: the firmware capture path (TIM4 + DMA, 1 us ticks) only ever
shows what the receiver's data slicer outputs, a minute after the fact, with
no reference. A logic analyser sitting on the driven transmit line *and* the
received line at the same time is the only cheap way to separate a transmitter
fault from a receiver/AGC fault: both channels share one timebase, so their
bursts can be lined up sample-exactly.

Note on resolution: these recordings are at 20 kHz, i.e. 50 us per sample, so
every duration is quantised to 50 us. That is coarse against KeeLoq's
SHORT/MID/LONG cells (795/1138/1482 us) but inside the decoder's 10% tolerance,
which is why the scale factor is estimated from the header rather than assumed
to be 1.0.
"""

from __future__ import annotations

import argparse
import configparser
import re
import statistics
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from decode_hcs301 import (  # noqa: E402  (path set up above)
    HEADER_ONE_US,
    HEADER_ZERO_US,
    LONG_US,
    MID_US,
    PREAMBLE_EDGES,
    SHORT_US,
    decode_frame,
)

# A gap at least this long ends a burst. KeeLoq's longest cell is ~1.5 ms and
# its inter-frame gap is ~15 ms, so 5 ms splits frames without splitting cells.
DEFAULT_GAP_MS = 5.0

# Durations that can plausibly be a KeeLoq header, in microseconds. Wide enough
# to catch a mistimed transmitter; the decoder still validates the cells.
HEADER_SEARCH_US = (3400, 5600)

CELLS_AFTER_HEADER = 65


@dataclass
class Edge:
    """One run of constant level: `samples` long, starting at `start_sample`."""

    level: int
    samples: int
    start_sample: int


@dataclass
class Burst:
    """A run of edges not separated by an idle gap."""

    edges: list[Edge]

    @property
    def start_sample(self) -> int:
        return self.edges[0].start_sample

    @property
    def end_sample(self) -> int:
        last = self.edges[-1]
        return last.start_sample + last.samples

    @property
    def span_samples(self) -> int:
        return self.end_sample - self.start_sample


@dataclass
class Session:
    """A loaded sigrok session."""

    path: Path
    samplerate_hz: float
    unitsize: int
    probes: dict[str, int]  # probe name -> bit index
    data: bytes

    @property
    def sample_count(self) -> int:
        return len(self.data) // max(self.unitsize, 1)

    @property
    def duration_s(self) -> float:
        return self.sample_count / self.samplerate_hz

    def us(self, samples: int) -> int:
        """Convert a sample count to microseconds, rounded."""
        return round(samples * 1_000_000 / self.samplerate_hz)

    def toggling_probes(self) -> list[tuple[str, int, int]]:
        """Probe names whose bit actually changes, with (name, bit, edges)."""
        found = []
        for name, bit in sorted(self.probes.items(), key=lambda kv: kv[1]):
            mask = 1 << bit
            first = self.data[0] & mask
            if any((b & mask) != first for b in self.data):
                found.append((name, bit, len(self.edges(bit))))
        return found

    def edges(self, bit: int) -> list[Edge]:
        """Run-length encode one channel into edges."""
        mask = 1 << bit
        out: list[Edge] = []
        data = self.data
        prev = 1 if (data[0] & mask) else 0
        start = 0
        run = 1
        for index in range(1, len(data)):
            cur = 1 if (data[index] & mask) else 0
            if cur == prev:
                run += 1
                continue
            out.append(Edge(prev, run, start))
            prev = cur
            start = start + run
            run = 1
        out.append(Edge(prev, run, start))
        return out


def parse_samplerate(text: str) -> float:
    """Parse sigrok's `samplerate`, e.g. '20 kHz' or '1 MHz'."""
    cleaned = text.strip().replace(" ", "")
    lowered = cleaned.lower()
    multipliers = (("mhz", 1e6), ("khz", 1e3), ("hz", 1.0))
    for suffix, factor in multipliers:
        if lowered.endswith(suffix):
            return float(lowered[: -len(suffix)]) * factor
    return float(lowered)


def read_members(path: Path) -> dict[str, bytes]:
    """Read a session from either a .sr zip or an unpacked directory."""
    if path.is_dir():
        return {
            entry.name: entry.read_bytes()
            for entry in path.iterdir()
            if entry.is_file()
            and (
                entry.name in ("version", "metadata")
                or entry.name.startswith("logic-")
            )
        }
    with zipfile.ZipFile(path) as archive:
        return {
            name: archive.read(name)
            for name in archive.namelist()
            if name in ("version", "metadata") or name.startswith("logic-")
        }


def load_session(path: Path, capturefile: str = "logic-1-1") -> Session:
    """Load one sigrok session, probing the declared channel names."""
    members = read_members(path)
    if "metadata" not in members:
        raise ValueError(f"{path}: no metadata member (not a sigrok session?)")

    parser = configparser.ConfigParser()
    parser.read_string(members["metadata"].decode("utf-8", "replace"))
    section = next(
        (s for s in parser.sections() if s.startswith("device")), None
    )
    if section is None:
        raise ValueError(f"{path}: no [device] section in metadata")

    samplerate = parse_samplerate(parser.get(section, "samplerate"))
    unitsize = parser.getint(section, "unitsize", fallback=1)
    if unitsize != 1:
        raise ValueError(
            f"{path}: unitsize {unitsize} is not supported (expected 1)"
        )

    probes: dict[str, int] = {}
    for key, value in parser.items(section):
        if key.startswith("probe"):
            try:
                bit = int(key[len("probe") :]) - 1
            except ValueError:
                continue
            probes[value] = bit

    data = members.get(capturefile)
    if data is None:
        capture = parser.get(section, "capturefile", fallback="logic-1")
        data = members.get(f"{capture}-1")
    if data is None:
        raise ValueError(
            f"{path}: no capture member; found {sorted(members)}"
        )

    # A capture can be split across several members. PulseView normally writes
    # one, but sigrok-cli chunks the stream at 10 240 bytes as logic-1-1,
    # logic-1-2, ... -- reading only the first one silently truncates the
    # capture to 10.24 ms at 1 MHz. Concatenate every chunk in numeric order
    # (not lexical: logic-1-10 sorts before logic-1-2) and use that as the
    # sample stream.
    capture = capturefile
    if not any(re.fullmatch(rf"{re.escape(capture)}-\d+", n) for n in members):
        capture = parser.get(section, "capturefile", fallback="logic-1")
    chunks = []
    for name in members:
        match = re.fullmatch(rf"{re.escape(capture)}-(\d+)", name)
        if match:
            chunks.append((int(match.group(1)), name))
    if not chunks:
        raise ValueError(
            f"{path}: no {capture}-N members; found {sorted(members)}"
        )
    data = b"".join(members[name] for _, name in sorted(chunks))

    return Session(path, samplerate, unitsize, probes, data)


def split_bursts(
    session: Session, edges: list[Edge], gap_ms: float
) -> list[Burst]:
    """Split edges into bursts, starting a new one after a gap."""
    gap_samples = gap_ms * session.samplerate_hz / 1000.0
    bursts: list[Burst] = []
    current: list[Edge] = []
    for edge in edges:
        if edge.samples >= gap_samples:
            # The idle run is separation, not signal: close the burst and drop
            # the idle edge itself, so a burst's start/end are its real active
            # window and two channels can be lined up on the shared timebase.
            if current:
                bursts.append(Burst(current))
                current = []
            continue
        current.append(edge)
    if current:
        bursts.append(Burst(current))
    return bursts


def choose_scale(
    session: Session,
    bursts: list[Burst],
    low: float = 0.88,
    high: float = 1.12,
    step: float = 0.004,
) -> tuple[float, list[tuple[int, float, dict]]]:
    """Pick the timing scale that decodes the most code words.

    Estimating the scale from one duration class does not work at this
    resolution. One sample is 50 us, so the preamble is only ~15 samples; and
    the header has *two* nominal values (4512 us for a 0 bit, 4170 us for a 1),
    so a median over headers lands between them and skews the whole cell grid --
    which is how an earlier version of this tool rejected correct captures: the
    scale came out at 0.93 and a genuine 1500 us LONG cell then missed its
    shrunken 1379 us centre by more than the tolerance.

    Searching a narrow range instead lets the decoder's own rules decide: SHORT
    must follow a 0, LONG must follow a 1, and MID must repeat the previous bit.
    Random data almost never satisfies all 65 cells, so the scale with the most
    code words is the right one.

    Returns the chosen scale and (burst index, start seconds, fields) hits.
    """
    best_scale = 1.0
    best_hits: list[tuple[int, float, dict]] = []
    steps = round((high - low) / step)
    for index in range(steps + 1):
        scale = low + index * step
        hits = [
            (burst_index, offset, word)
            for burst_index, burst in enumerate(bursts)
            for offset, word in find_codewords(session, burst, scale)
        ]
        better = len(hits) > len(best_hits)
        tie_and_closer = (
            len(hits) == len(best_hits) and abs(scale - 1.0) < abs(best_scale - 1.0)
        )
        if better or tie_and_closer:
            best_scale, best_hits = scale, hits
    return best_scale, best_hits


def find_codewords(
    session: Session, burst: Burst, scale: float
) -> list[tuple[float, dict]]:
    """Decode every KeeLoq code word in one burst.

    Returns (start time in seconds, decoded fields) for each hit. The header is
    located by value rather than by position, so a burst may hold several code
    words (a repeat train) without the caller tracking frame offsets.
    """
    pulses = [session.us(edge.samples) for edge in burst.edges]
    low, high = HEADER_SEARCH_US
    found: list[tuple[float, dict]] = []

    for index, duration in enumerate(pulses):
        if not low <= duration <= high:
            continue
        start = index - PREAMBLE_EDGES
        end = index + 1 + CELLS_AFTER_HEADER
        if start < 0 or end > len(pulses):
            continue
        decoded = decode_frame(pulses[start:end], scale)
        if decoded is None:
            continue
        offset = (
            burst.edges[start].start_sample / session.samplerate_hz
        )
        found.append((offset, decoded))
    return found


def describe_durations(session: Session, edges: list[Edge], top: int = 8) -> str:
    """A compact histogram of edge durations, in microseconds."""
    if not edges:
        return "    (no edges)"
    counts: dict[int, int] = {}
    for edge in edges:
        us = session.us(edge.samples)
        counts[us] = counts.get(us, 0) + 1
    ranked = sorted(counts.items(), key=lambda kv: -kv[1])[:top]
    return "    " + ", ".join(f"{us}us x{n}" for us, n in ranked)


def report_channel(
    session: Session,
    name: str,
    bit: int,
    edges: list[Edge],
    bursts: list[Burst],
    words_by_burst: dict[int, list[tuple[float, dict]]],
    show_edges: int,
) -> list[tuple[float, float]]:
    """Print one channel's analysis; return its burst time spans in seconds."""
    print(
        f"  {name} (bit {bit}): {len(edges)} edges, "
        f"{len(bursts)} burst(s), initial level {edges[0].level}"
    )
    print(describe_durations(session, edges))
    if len(bursts) > 1:
        periods = [
            (b.start_sample - a.start_sample) / session.samplerate_hz
            for a, b in zip(bursts, bursts[1:])
        ]
        median_period = statistics.median(periods)
        median_edges = statistics.median(len(b.edges) for b in bursts)
        print(
            f"    repetition: median {median_period * 1000:.1f} ms between bursts, "
            f"median {median_edges:.0f} edges per burst"
        )

    spans: list[tuple[float, float]] = []
    for index, burst in enumerate(bursts):
        start_s = burst.start_sample / session.samplerate_hz
        end_s = burst.end_sample / session.samplerate_hz
        spans.append((start_s, end_s))

        if show_edges and index < show_edges:
            raw = [session.us(e.samples) for e in burst.edges]
            print(f"    burst {index + 1} @ {start_s:.4f}s: {len(burst.edges)} edges")
            print(f"      {raw[:24]}{' ...' if len(raw) > 24 else ''}")

        words = words_by_burst.get(index, [])
        for offset, word in words:
            print(
                f"      [{offset:8.4f}s] CODE WORD "
                f"hopping=0x{word['hopping']:08X} "
                f"serial=0x{word['serial']:07X} "
                f"button=0x{word['button']:X} "
                f"vlow={word['vlow']} repeat={word['repeat']}"
            )
        if not words and len(burst.edges) >= 40:
            print(
                f"    burst {index + 1} @ {start_s:.4f}s: {len(burst.edges)} edges, "
                f"no code word"
            )
    return spans


def report_overlap(
    session: Session,
    left_name: str,
    left: list[tuple[float, float]],
    right_name: str,
    right: list[tuple[float, float]],
) -> None:
    """Line up two channels' bursts on the shared timebase."""
    if not left or not right:
        return
    print(f"  --- {left_name} vs {right_name} ---")
    for index, (start, end) in enumerate(left, 1):
        covering = [
            (j, rs, re)
            for j, (rs, re) in enumerate(right, 1)
            if re > start and rs < end
        ]
        if not covering:
            print(
                f"    {left_name} burst {index} @ {start:.4f}s: "
                f"no {right_name} activity in the same window"
            )
            continue
        for j, rs, re in covering:
            print(
                f"    {left_name} burst {index} @ {start:.4f}-{end:.4f}s "
                f"<-> {right_name} burst {j} @ {rs:.4f}-{re:.4f}s "
                f"(offset {rs - start:+.4f}s)"
            )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path, help=".sr file or session directory")
    parser.add_argument("--gap-ms", type=float, default=DEFAULT_GAP_MS,
                        help=f"idle gap that ends a burst (default {DEFAULT_GAP_MS})")
    parser.add_argument("--scale", default="auto",
                        help="timing scale factor, or 'auto' (default)")
    parser.add_argument("--channel", action="append", default=None,
                        help="restrict to a probe name (repeatable)")
    parser.add_argument("--show-edges", type=int, default=2,
                        help="print raw edge lists for the first N bursts")
    args = parser.parse_args(argv)

    session = load_session(args.session)

    print(f"=== {session.path} ===")
    print(f"  samplerate {session.samplerate_hz:.0f} Hz "
          f"({session.us(1)} us/sample), unitsize {session.unitsize}")
    print(f"  {session.sample_count} samples = {session.duration_s:.3f} s")
    toggling = session.toggling_probes()
    print(f"  probes declared: {', '.join(f'{n}->bit{b}' for n, b in sorted(session.probes.items(), key=lambda kv: kv[1]))}")
    if not toggling:
        print("  no channel changed state: the capture is empty")
        return 1
    print(f"  toggling: {', '.join(f'{n} (bit {b}, {c} edges)' for n, b, c in toggling)}")

    wanted = set(args.channel) if args.channel else None
    channels = [t for t in toggling if wanted is None or t[0] in wanted]
    if not channels:
        print(f"  none of {sorted(wanted)} toggled")
        return 1

    spans: dict[str, list[tuple[float, float]]] = {}
    for name, bit, _ in channels:
        edges = session.edges(bit)
        bursts = split_bursts(session, edges, args.gap_ms)
        if args.scale == "auto":
            scale, hits = choose_scale(session, bursts)
            print(
                f"  timing scale for {name}: {scale:.3f} "
                f"({len(hits)} code word(s) decoded)"
            )
        else:
            scale = float(args.scale)
            hits = [
                (burst_index, offset, word)
                for burst_index, burst in enumerate(bursts)
                for offset, word in find_codewords(session, burst, scale)
            ]
        words_by_burst: dict[int, list[tuple[float, dict]]] = {}
        for burst_index, offset, word in hits:
            words_by_burst.setdefault(burst_index, []).append((offset, word))
        spans[name] = report_channel(
            session, name, bit, edges, bursts, words_by_burst, args.show_edges
        )

    if len(channels) == 2:
        (first, _, _), (second, _, _) = channels
        report_overlap(
            session, first, spans[first], second, spans[second]
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
