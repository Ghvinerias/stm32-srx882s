#!/usr/bin/env python3
"""Decrypt captured hopping words with a KeeLoq device key you already hold.

KeeLoq is a keyed block cipher, so "reversing" captured hopping words means
decrypting them — and that needs the 64-bit device key. This tool takes the key
as a required argument and never searches for it:

  * A capture contains ciphertext under the key, not the key. The key lives in
    the HCS chip's EEPROM and is never transmitted.
  * Recovering a 64-bit key from a handful of captures is a ciphertext-only
    search over 2^64 keys. That is not implemented here and is not tractable.
  * The practical routes that do exist (side-channel analysis of the chip, or
    the manufacturer-key provisioning derivation) are outside this project's
    scope.

What it is for: closing the loop on hardware you provisioned yourself. Send
`(disc << 16 | counter)` encrypted with your key, capture the over-the-air
frames, then decrypt them here and check that the discriminant is constant and
the counter advances exactly as the encoder intended. A wrong key produces
noise: the high half scatters and the counter does not step uniformly, which is
what the `--selftest` vectors and any mismatched capture demonstrate.

Input may be either:
  * an NDJSON capture from the firmware (one JSON packet per line), or
  * a PulseView/sigrok session file or directory, in which case every toggling
    channel is decoded first (see tools/pulseview_session.py).
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from decode_hcs301 import estimate_scale, iter_frames, load_frames  # noqa: E402
from keeloq import decrypt  # noqa: E402
from pulseview_session import (  # noqa: E402
    choose_scale,
    load_session,
    split_bursts,
)

KEY_LIMIT = 1 << 64


def records_from_ndjson(path: Path) -> list[dict]:
    """Decode code words from a firmware NDJSON capture."""
    frames = load_frames(path)
    if not frames:
        return []
    scale = estimate_scale(frames)
    print(f"timing scale {scale:.4f} from {len(frames)} frame(s)", file=sys.stderr)
    records = []
    for decoded in iter_frames(frames, scale):
        records.append(
            {
                "source": path.name,
                "sequence": decoded.get("sequence"),
                "button": decoded["button"],
                "hopping": decoded["hopping"],
                "serial": decoded["serial"],
                "vlow": decoded["vlow"],
                "repeat": decoded["repeat"],
                "time": decoded.get("host_time_utc"),
            }
        )
    return records


def records_from_session(path: Path, channel: str | None) -> list[dict]:
    """Decode code words from a PulseView/sigrok session, per channel."""
    session = load_session(path)
    records = []
    for name, bit, _ in session.toggling_probes():
        if channel and name != channel:
            continue
        bursts = split_bursts(session, session.edges(bit), 5.0)
        scale, hits = choose_scale(session, bursts)
        for _burst_index, offset, word in hits:
            records.append(
                {
                    "source": name,
                    "sequence": None,
                    "button": word["button"],
                    "hopping": word["hopping"],
                    "serial": word["serial"],
                    "vlow": word["vlow"],
                    "repeat": word["repeat"],
                    "time": offset,
                    "scale": scale,
                }
            )
    return records


def load_records(path: Path, channel: str | None) -> list[dict]:
    if path.is_dir() or zipfile.is_zipfile(path):
        return records_from_session(path, channel)
    return records_from_ndjson(path)


def analyse(source: str, records: list[dict], key: int) -> int:
    """Decrypt one source's records and report the plaintext structure.

    Returns the number of presses that produced a structured plaintext, which
    is 0 for a non-matching key.
    """
    if not records:
        print(f"  {source}: no code words")
        return 0

    # Frames carrying the same hopping word are copies of one press (the HCS
    # repeats a press with the repeat bit set, same ciphertext).
    presses: list[dict] = []
    for record in records:
        if presses and presses[-1]["hopping"] == record["hopping"]:
            presses[-1]["copies"] += 1
            continue
        presses.append(dict(record, copies=1))

    print(f"  {source}: {len(records)} code word(s), {len(presses)} press(es)")
    decrypted = []
    for press in presses:
        plain = decrypt(press["hopping"], key)
        disc = plain >> 16
        counter = plain & 0xFFFF
        decrypted.append((press, disc, counter))
        print(
            f"    hop 0x{press['hopping']:08X}  serial 0x{press['serial']:07X}  "
            f"button 0x{press['button']:X}  x{press['copies']}  ->  "
            f"disc 0x{disc:04X}  counter 0x{counter:04X}"
        )

    discs = {disc for _, disc, _ in decrypted}
    steps = [
        (later - earlier) & 0xFFFF
        for (_, _, earlier), (_, _, later) in zip(decrypted, decrypted[1:])
    ]
    uniform = all(step == 1 for step in steps) if steps else None

    if len(discs) == 1:
        print(
            f"    discriminant: 0x{next(iter(discs)):04X} constant across "
            "all presses"
        )
    else:
        print(
            "    discriminant: "
            + ", ".join(f"0x{d:04X}" for d in sorted(discs))
            + " -- not constant"
        )
    if uniform is None:
        print("    counter: single press, progression not assessable")
    elif uniform:
        print("    counter: increments by exactly 1 between presses")
    else:
        print(f"    counter steps: {steps} -- not uniform")

    structured = len(discs) == 1 and (uniform is not False)
    if structured:
        print("    verdict: structured plaintext -- this key matches the capture")
    else:
        print("    verdict: no structure -- this key does not match the capture")
    return 1 if structured else 0


# (key, hop, expected plaintext) from the NDIAS 2026 CTF writeup, used only to
# prove the analysis path recognises a structured result when one exists.
SELFTEST = [
    (0x6D3A91C46CEE3373, 0x913B00D7),
    (0x6D3A91C46CEE3373, 0x0020D1FB),
    (0x6D3A91C46CEE3373, 0x80ED145B),
    (0x6D3A91C46CEE3373, 0x314C03E4),
]


def self_test() -> int:
    """Check that a known-good key is reported as structured."""
    key = SELFTEST[0][0]
    records = [
        {
            "source": "selftest",
            "sequence": index,
            "button": 0x1,
            "hopping": hop,
            "serial": 0x01D4A2B7,
            "vlow": 0,
            "repeat": 0,
            "time": None,
        }
        for index, (_, hop) in enumerate(SELFTEST)
    ]
    print("self-test: four known (key, hop) pairs")
    hits = analyse("selftest", records, key)
    # And the negative case: the same hops under a different key.
    print("self-test: same hops under a wrong key")
    misses = analyse("selftest", records, 0x0123456789ABCDEF)
    passed = hits == 1 and misses == 0
    print("self-test:", "PASS" if passed else "FAIL")
    return 0 if passed else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, nargs="?",
                        help="NDJSON capture or PulseView/sigrok session")
    parser.add_argument("--key", default=None,
                        help="64-bit device key in hex (required)")
    parser.add_argument("--channel", default=None,
                        help="for a session, restrict to one probe name")
    parser.add_argument("--selftest", action="store_true",
                        help="run the internal known-answer check and exit")
    args = parser.parse_args(argv)

    if args.selftest:
        return self_test()

    if not args.capture:
        parser.error("a capture path is required (or use --selftest)")
    if not args.key:
        parser.error(
            "--key is required: this tool decrypts with a key you hold, it "
            "does not recover one"
        )
    key = int(args.key, 0)
    if not 0 <= key < KEY_LIMIT:
        parser.error("--key must fit in 64 bits")

    print(f"capture: {args.capture}")
    print(f"key:     0x{key:016X}")
    records = load_records(args.capture, args.channel)
    if not records:
        print("no code words decoded from this capture", file=sys.stderr)
        return 1

    sources: dict[str, list[dict]] = {}
    for record in records:
        sources.setdefault(record["source"], []).append(record)

    structured = 0
    for source, source_records in sources.items():
        source_records.sort(key=lambda r: (r["time"] is None, r["time"] or 0))
        structured += analyse(source, source_records, key)

    return 0 if structured else 1


if __name__ == "__main__":
    raise SystemExit(main())
