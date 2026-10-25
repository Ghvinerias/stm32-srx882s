#!/usr/bin/env python3
"""Render captured HCS301 / KeeLoq transmissions in the raw code-word layout.

`decode_hcs301.py` answers "which button, which serial". This tool answers a
different question: what does the whole message actually look like on the air,
which parts of it can be read without a key, and which parts cannot.

A KeeLoq code-hopping word is 66 bits, transmitted LSB first:

    bits 31:0   hopping word   -- the 32-bit KeeLoq ciphertext block
    bits 59:32  serial         -- 28-bit device serial, in the clear
    bits 63:60  button         -- 4-bit button nibble, in the clear
    bit  64     VLOW           -- battery-low flag, in the clear
    bit  65     repeat         -- 0 on the code word, 1 on its repeats

The hopping word is not a field you can read. It is the output of the KeeLoq
block cipher (64-bit key, 32-bit block, 528 rounds) applied to a 32-bit source
block. The source block is where the interesting numbers live:

    bits 15:0   counter        -- 16-bit synchronisation counter, +1 per press
    bits 25:16  discrimination -- 10 bits, must match the serial
    bits 27:26  overflow       -- 2-bit counter overflow
    bits 31:28  button         -- 4-bit button nibble, duplicated in the clear

Everything above the dashed line is readable from the capture. Everything below
it needs the 64-bit device key. This tool shows both, marks which is which, and
-- if you supply a key you own -- decrypts and prints the source block so you
can see the counter increment.

It never guesses, derives, or searches for a key. Supply one, or don't.
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

import decode_hcs301 as d

# KeeLoq non-linear feedback function, as an 32-entry truth table.
NLF_CONSTANT = 0x3A5C742E
ROUNDS = 528

# Published KeeLoq test vectors (key, plaintext, ciphertext). Used by
# --selftest to prove this implementation matches the reference cipher.
SELF_TEST_VECTORS = [
    (0x5CEC6701B79FD949, 0xF741E2DB, 0xE44F4CDF),
    (0x5CEC6701B79FD949, 0x0CA69B92, 0xA6AC0EA2),
]


# --------------------------------------------------------------------------
# KeeLoq block cipher (public algorithm; the key remains the only secret)
# --------------------------------------------------------------------------

def _nlf(index: int) -> int:
    return (NLF_CONSTANT >> index) & 1


def keeloq_encrypt(key: int, block: int, rounds: int = ROUNDS) -> int:
    x = block & 0xFFFFFFFF
    for i in range(rounds):
        nlf_input = (((x >> 31) & 1) << 4) | (((x >> 26) & 1) << 3) | \
                    (((x >> 20) & 1) << 2) | (((x >> 9) & 1) << 1) | ((x >> 1) & 1)
        feedback = ((key >> (i % 64)) & 1) ^ ((x >> 16) & 1) ^ (x & 1) ^ _nlf(nlf_input)
        x = (x >> 1) | (feedback << 31)
    return x


def keeloq_decrypt(key: int, block: int, rounds: int = ROUNDS) -> int:
    x = block & 0xFFFFFFFF
    for i in range(rounds):
        nlf_input = (((x >> 30) & 1) << 4) | (((x >> 25) & 1) << 3) | \
                    (((x >> 19) & 1) << 2) | (((x >> 8) & 1) << 1) | (x & 1)
        feedback = ((key >> ((15 - i) % 64)) & 1) ^ ((x >> 31) & 1) ^ ((x >> 15) & 1) ^ _nlf(nlf_input)
        x = ((x << 1) & 0xFFFFFFFF) | feedback
    return x


def selftest() -> int:
    ok = True
    for key, plaintext, expected in SELF_TEST_VECTORS:
        got = keeloq_encrypt(key, plaintext)
        back = keeloq_decrypt(key, got)
        good = got == expected and back == plaintext
        ok = ok and good
        print(f"key=0x{key:016X} plain=0x{plaintext:08X} "
              f"cipher=0x{got:08X} expected=0x{expected:08X} "
              f"roundtrip={'ok' if back == plaintext else 'FAIL'} "
              f"{'PASS' if good else 'FAIL'}")
    return 0 if ok else 1


# --------------------------------------------------------------------------
# Plaintext (source) block field access
# --------------------------------------------------------------------------

def source_fields(block: int) -> dict[str, int]:
    """Split a decrypted 32-bit source block into its HCS301 fields."""
    return {
        "counter": block & 0xFFFF,
        "discrimination": (block >> 16) & 0x3FF,
        "overflow": (block >> 26) & 0x3,
        "button": (block >> 28) & 0xF,
    }


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------

def bits_of(value: int, width: int) -> str:
    return format(value & ((1 << width) - 1), f"0{width}b")


def grouped_bits(value: int, width: int, size: int = 4) -> str:
    raw = bits_of(value, width)
    return " ".join(raw[i:i + size] for i in range(0, len(raw), size))


def render_code_word(frame: dict, index: int) -> None:
    hop = frame["hopping"]
    serial = frame["serial"]
    button = frame["button"]
    vlow = frame["vlow"]
    repeat = frame["repeat"]

    print(f"  frame {index}")
    print("    RAW CODE WORD  (66 bits, LSB transmitted first)")
    print("    +-------------------------------+------------------+------+----+-----+")
    print("    |      HOPPING WORD  31:0       |   SERIAL 59:32   | BTN  |VLOW| REP |")
    print("    |   KeeLoq ciphertext block     |  28 bits (clear) |63:60 | 64 | 65  |")
    print("    +-------------------------------+------------------+------+----+-----+")
    print(f"    | {f'0x{hop:08X}':<30}| {f'0x{serial:07X}':<17}| "
          f"{f'0x{button:X}':<5}| {vlow:<3}| {repeat:<4}|")
    print()
    print("    FIELD TABLE")
    print(f"      {'field':<11} {'bits':<7} {'hex':<12} {'binary':<40} state")
    print(f"      {'hopping':<11} {'31:0':<7} {f'0x{hop:08X}':<12} "
          f"{grouped_bits(hop, 32):<40} ENCRYPTED")
    print(f"      {'serial':<11} {'59:32':<7} {f'0x{serial:07X}':<12} "
          f"{grouped_bits(serial, 28):<40} clear")
    print(f"      {'button':<11} {'63:60':<7} {f'0x{button:X}':<12} "
          f"{bits_of(button, 4):<40} clear")
    print(f"      {'vlow':<11} {'64':<7} {vlow:<12} {bits_of(vlow, 1):<40} clear")
    print(f"      {'repeat':<11} {'65':<7} {repeat:<12} {bits_of(repeat, 1):<40} clear")


def render_source_block(frame: dict, key: int | None) -> None:
    hop = frame["hopping"]
    print()
    print("    INSIDE THE HOPPING WORD  (the 32-bit source block KeeLoq encrypted)")
    print("    +-------------+----------------+------+--------+")
    print("    | counter 16  | discrim. 10    | ovf 2| btn 4  |")
    print("    |   bits 15:0 | bits 25:16     |27:26 | 31:28  |")
    print("    +-------------+----------------+------+--------+")

    if key is None:
        print("      ??????????    ??????????       ??     ???")
        print("      -- not readable from the capture; needs the 64-bit device key --")
        return

    src = keeloq_decrypt(key, hop)
    f = source_fields(src)
    print(f"      {f['counter']:<11}   0x{f['discrimination']:03X} "
          f"({f['discrimination']:<4})    {f['overflow']:<4}   0x{f['button']:X}")
    match = "matches transmitted button" if f["button"] == frame["button"] \
        else "DOES NOT match transmitted button -- wrong key or wrong word order"
    print(f"      source block 0x{src:08X}   button check: {match}")
    print(f"      discrimination 0x{f['discrimination']:03X} should match the low "
          f"bits of serial 0x{frame['serial']:07X} "
          f"(low 10 bits = 0x{frame['serial'] & 0x3FF:03X})")


def hamming(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("capture", type=Path, nargs="?", help="NDJSON capture file")
    parser.add_argument("--key", default=None,
                        help="64-bit device key in hex (only if you own one) to "
                             "decrypt the hopping word and show the counter")
    parser.add_argument("--gap", type=float, default=0.5,
                        help="seconds of silence that separates presses (default 0.5)")
    parser.add_argument("--limit", type=int, default=0,
                        help="show at most N presses (0 = all)")
    parser.add_argument("--frames", action="store_true",
                        help="show every frame, not just the first of each press")
    parser.add_argument("--selftest", action="store_true",
                        help="verify the KeeLoq implementation against test vectors")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()
    if args.capture is None:
        parser.error("a capture file is required (or use --selftest)")

    key = None
    if args.key:
        key = int(args.key, 0)
        if not 0 <= key <= 0xFFFFFFFFFFFFFFFF:
            parser.error("--key must be a 64-bit value")

    packets = d.load_frames(args.capture)
    scale = d.estimate_scale(packets)

    decoded: list[dict] = []
    undecoded = 0
    for packet in packets:
        result = d.decode_frame(packet.get("pulses_us", []), scale)
        if result is None:
            undecoded += 1
            continue
        result["t"] = datetime.fromisoformat(packet["host_time_utc"])
        decoded.append(result)

    # Group frames into presses: a run with no gap larger than --gap seconds.
    presses: list[list[dict]] = []
    for frame in decoded:
        if presses and (frame["t"] - presses[-1][-1]["t"]).total_seconds() <= args.gap:
            presses[-1].append(frame)
        else:
            presses.append([frame])

    print(f"{args.capture}: {len(packets)} frames, {len(decoded)} decoded, "
          f"{undecoded} not, {len(presses)} press(es)")
    if key is not None:
        print(f"device key supplied: 0x{key:016X} -- hopping words below are decrypted")
    print()

    shown = presses[:args.limit] if args.limit else presses

    for n, press in enumerate(shown, start=1):
        first = press[0]
        print("=" * 78)
        print(f"PRESS #{n}   t={first['t'].strftime('%H:%M:%S.%f')[:-3]}   "
              f"frames={len(press)}   button=0x{first['button']:X}   "
              f"serial=0x{first['serial']:07X}")
        print("=" * 78)
        frames = press if args.frames else [first]
        for i, frame in enumerate(frames):
            render_code_word(frame, i)
            render_source_block(frame, key)
            print()
        repeats = "".join(str(f["repeat"]) for f in press)
        print(f"    repeat bits across the press: {repeats}"
              "   (first frame 0 = code word, then 1 = copies)")
        print()

    # ---- sequence view: what changes between presses, and what that proves ----
    print("=" * 78)
    print("PRESS SEQUENCE  (hopping word across separate presses)")
    print("=" * 78)
    print(f"  {'#':>3}  {'time':>12}  {'button':>6}  {'hopping':>10}  "
          f"{'dist':>4}  {'delta':>10}  frames")
    prev = None
    for n, press in enumerate(presses, start=1):
        first = press[0]
        hop = first["hopping"]
        dist = "-" if prev is None else str(hamming(hop, prev))
        delta = "-" if prev is None else f"{hop - prev:+d}"
        print(f"  {n:>3}  {first['t'].strftime('%H:%M:%S.%f')[:-3]:>12}  "
              f"0x{first['button']:<4X}  0x{hop:08X}  {dist:>4}  {delta:>10}  {len(press)}")
        prev = hop

    hops = [p[0]["hopping"] for p in presses]
    if len(hops) > 1:
        dists = [hamming(b, a) for a, b in zip(hops, hops[1:])]
        print()
        print(f"  consecutive hopping words differ in {min(dists)}-{max(dists)} of 32 bits")
        if key is None:
            print("  a plaintext counter would differ in 1-3 bits; this is a ciphertext")
            print("  block, so the counter inside it cannot be read without the key")

    sys.stderr.write(f"{len(decoded)} frames decoded, {undecoded} not\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
