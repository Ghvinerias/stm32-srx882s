#!/usr/bin/env python3
"""Shared library for the ESP32-C3/CC1101 + STM32 hardware-in-the-loop tests.

Everything here runs on the bench machine (fit4).  It reuses the repo's
existing protocol/decode implementations by importing them:

  * tools/rf_console.py      -- cobs/crc16 framing, TX_COMMAND builder,
                                packet parser, scanf nonblocking serial port
  * tools/decode_hcs301.py   -- HCS301-shaped frame decode (77-pulse frames)

The tools directory is located via the HIL_TOOLS_DIR env var, else by
walking up from this file's location ("tests/hil" -> repo root -> tools).

What this module provides on top of that:

  * Device        -- background-reader wrapper around a CDC serial port that
                     splits the byte stream into binary packets (COBS+0x00)
                     and ASCII text lines (\\r\\n), interleaved
  * waiting helpers (wait_ack / wait_packet / wait_line) with timeouts
  * stm32_send_tx -- TX_COMMAND over the STM32 link, waits for the ACK
  * Capture       -- sigrok-cli (fx2lafw) recording one .sr file per step,
                     parsed in-process: edge timestamps, per-channel pulse
                     lists, frame segmentation on idle gaps > 8 ms, frame
                     decode via tools/decode_hcs301.py
  * codeword helpers -- HCS301 frame pulse generation (same constants as
                     firmware/src/lab_tx.c), codeword selection from env
                     or from tools/captures/r3*.ndjson
  * BleSession    -- bleak-based BLE client for the KeeLoqLab-C3- units
  * --selftest    -- offline checks of segmentation/decode on synthetic
                     pulses and on a real capture (no hardware needed)
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

# ---------------------------------------------------------------------------
# import the repo's own implementations; never copy them
# ---------------------------------------------------------------------------


def _setup_tools_path() -> Path:
    override = os.environ.get("HIL_TOOLS_DIR")
    candidates = []
    if override:
        candidates.append(Path(override).expanduser())
    here = Path(__file__).resolve()
    candidates.extend(
        (here.parents[idx] / "tools") for idx in (3, 2, 4)
    )
    for candidate in candidates:
        if (candidate / "rf_console.py").is_file():
            sys.path.insert(0, str(candidate))
            return candidate.resolve()
    raise RuntimeError(
        "cannot locate the repo's tools/ directory (rf_console.py); "
        "set HIL_TOOLS_DIR to the path containing rf_console.py"
    )


TOOLS_DIR = _setup_tools_path()

from rf_console import (  # noqa: E402
    PacketError,
    PacketStream,
    SerialPort,
    build_scenario_command,
    build_tx_command,
    cobs_decode,
    cobs_encode,
    crc16_ccitt,
    parse_packet,
)
from decode_hcs301 import (  # noqa: E402
    HEADER_ONE_US,
    HEADER_ZERO_US,
    LONG_US,
    MID_US,
    PREAMBLE_EDGES,
    SHORT_US,
    decode_frame,
)

# ---------------------------------------------------------------------------
# constants (mirrors of firmware/src/lab_tx.c and transport.h)
# ---------------------------------------------------------------------------

VERSION = 1
TYPE_FRAME = 1
TYPE_STATUS = 2
TYPE_TX_COMMAND = 3
TYPE_SCENARIO_COMMAND = 4
TYPE_ACK = 5

PULSES_PER_FRAME = 77          # 11 preamble intervals + header + 65 data cells
INTER_FRAME_GAP_US = 15500     # gap between frames inside one TX_COMMAND burst
SEGMENT_GAP_US = 8000          # idle gap that ends a frame segment (inter-frame
                               # gap is 15.5 ms; the longest in-frame cell is
                               # the 4512 us header, so 8 ms is unambiguous)
PREAMBLE_EDGE_US = 760

DEFAULT_STM32_PORT = "/dev/serial/by-id/usb-EE_Lab_Blue_Pill_RF_Lab_RF433-001-if00"
DEFAULT_ESP_PORT = "/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_68:9D:D2:79:2A:A4-if00"

DEFAULT_CODEWORD = (0x114766B9, 0x0FB58C0C, 0x02)  # hopping, serial, button

BLE_NAME_PREFIX = "KeeLoqLab-C3-"
BLE_NUS_WRITE_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"   # client -> device
BLE_NUS_NOTIFY_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # device -> client

DEBUG = bool(os.environ.get("HIL_DEBUG"))


def debug(*args) -> None:
    if DEBUG:
        print("debug:", *args, file=sys.stderr)


def stm32_port() -> str:
    return os.environ.get("HIL_STM32_PORT", DEFAULT_STM32_PORT)


def esp_port() -> str:
    return os.environ.get("HIL_ESP_PORT", DEFAULT_ESP_PORT)


# ---------------------------------------------------------------------------
# serial device: interleaved binary packets + text lines
# ---------------------------------------------------------------------------


class Device:
    """Owns one CDC serial port.  A reader thread splits the incoming byte
    stream the way the firmware multiplexes it: binary packets are delimited
    by 0x00 (COBS framing), text diagnostics are CRLF-terminated ASCII lines
    between them.  A corrupted or partial packet degrades into 'text' with a
    parse error recorded, so the listener can never wedge."""

    def __init__(self, port: str, label: str = "") -> None:
        self.port = port
        self.label = label or port
        self.packets: list[dict] = []
        self.lines: list[str] = []          # CRLF lines, interleaved with packets
        self.errors: list[str] = []
        self.packets_seen = {"frame": 0, "status": 0, "ack": 0}
        self.bytes_seen = 0
        self.lock = threading.Lock()
        self._stop = threading.Event()
        self._buffer = bytearray()
        self._open_error: str | None = None
        self._serial: SerialPort | None = None
        self._thread: threading.Thread | None = None

    # -- lifecycle ----------------------------------------------------------

    def start(self) -> "Device":
        try:
            self._serial = SerialPort(self.port, 115200)
        except Exception as error:  # port missing/permission/hang
            self._open_error = f"open {self.port}: {error.__class__.__name__}: {error}"
            debug(self._open_error)
            return self
        self._thread = threading.Thread(
            target=self._reader_loop, name=f"hil-read-{self.label}", daemon=True
        )
        self._thread.start()
        return self

    def ok(self) -> bool:
        return self._serial is not None and self._open_error is None

    def open_error(self) -> str | None:
        return self._open_error

    def close(self) -> None:
        self._stop.set()
        if self._serial is not None:
            try:
                self._serial.close()
            except Exception:
                pass

    def _reader_loop(self) -> None:
        idle_deadline = time.monotonic() + 0.5
        while not self._stop.is_set():
            try:
                chunk = self._serial.read(4096)
            except Exception as error:
                with self.lock:
                    self.errors.append(f"read: {error.__class__.__name__}: {error}")
                return
            if chunk:
                self.bytes_seen += len(chunk)
                self._feed(chunk)
                idle_deadline = time.monotonic() + 0.5
            else:
                self._stop.wait(0.02)

    # -- stream splitting ---------------------------------------------------

    def _feed(self, data: bytes) -> None:
        with self.lock:
            self._buffer.extend(data)
            while True:
                delimiter = self._buffer.find(0)
                if delimiter == -1:
                    # no packet terminator yet: emit complete text lines,
                    # keep the unterminated tail in the buffer
                    newline = self._buffer.rfind(b"\n")
                    if newline == -1:
                        if len(self._buffer) > 8192:
                            self._buffer.clear()
                        break
                    chunk = bytes(self._buffer[: newline + 1])
                    del self._buffer[: newline + 1]
                    self._text_chunk(chunk)
                    break
                chunk = bytes(self._buffer[:delimiter])
                del self._buffer[: delimiter + 1]
                if not chunk:
                    continue
                try:
                    packet = parse_packet(chunk)
                    self._packet_seen(packet)
                except PacketError as error:
                    # Text replies (CRLF lines) share the byte stream with
                    # binary packets: peel the text off the front, then try
                    # the remainder as a packet.
                    newline = chunk.rfind(b"\n")
                    if newline != -1:
                        self._text_chunk(chunk[: newline + 1])
                        rest = chunk[newline + 1:]
                        if rest:
                            try:
                                self._packet_seen(parse_packet(rest))
                            except PacketError as error2:
                                self.errors.append(f"packet: {error2}")
                    else:
                        self.errors.append(f"packet: {error}")

    def _packet_seen(self, packet: dict) -> None:
        kind = packet.get("type", "?")
        packet["host_time"] = time.time()
        self.packets.append(packet)
        if kind in self.packets_seen:
            self.packets_seen[kind] += 1

    def _text_chunk(self, chunk: bytes) -> None:
        text = "".join(
            chr(b) if 32 <= b < 127 or b in (9, 10, 13) else "." for b in chunk
        )
        for line in text.split("\n"):
            line = line.strip("\r")
            if line.strip():
                self.lines.append(line)

    # -- commands -----------------------------------------------------------

    def write(self, data: bytes) -> None:
        if self._serial is None:
            raise RuntimeError(f"{self.label}: port not open ({self._open_error})")
        self._serial.write(data)

    def send_text(self, command: str) -> None:
        """Text diagnostics go out on the same stream, CRLF-terminated."""
        self.write(command.encode("ascii") + b"\r\n")
        debug(f"{self.label}: sent text {command!r}")

    def send_tx_command(self, hopping: int, serial: int, button: int, frames: int = 3) -> None:
        self.write(build_tx_command(hopping, serial, button, frames))
        debug(
            f"{self.label}: sent TX_COMMAND hopping=0x{hopping:08X} "
            f"serial=0x{serial:07X} button=0x{button:X} frames={frames}"
        )

    def clear(self) -> None:
        with self.lock:
            self.packets.clear()
            self.lines.clear()
            self.errors.clear()
            self.packets_seen = {"frame": 0, "status": 0, "ack": 0}

    # -- waiting ------------------------------------------------------------

    def wait_packet(self, predicate, timeout: float) -> dict | None:
        """Return the first packet matching predicate, or None on timeout.
       _predicate_q is safe: polling a deque under a lock is cheap."""
        deadline = time.monotonic() + timeout
        cursor = 0
        while True:
            with self.lock:
                while cursor < len(self.packets):
                    packet = self.packets[cursor]
                    cursor += 1
                    if predicate(packet):
                        return packet
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.01)

    def wait_line(self, prefixes, timeout: float) -> None | tuple[str, str]:
        """Return (matched_prefix, line) or None."""
        deadline = time.monotonic() + timeout
        cursor = 0
        prefixes = [prefixes] if isinstance(prefixes, str) else list(prefixes)
        while True:
            with self.lock:
                while cursor < len(self.lines):
                    line = self.lines[cursor]
                    cursor += 1
                    for prefix in prefixes:
                        if line.startswith(prefix):
                            return prefix, line
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.01)

    def wait_ack(self, for_type: int = TYPE_TX_COMMAND, timeout: float = 3.0,
                 want_accepted: bool = True) -> dict | None:
        def predicate(packet: dict) -> bool:
            return (
                packet.get("type") == "ack"
                and packet.get("for_type") == for_type
                and (packet.get("accepted") == want_accepted or not want_accepted)
            )

        return self.wait_packet(predicate, timeout)

    def wait_status(self, timeout: float = 2.0) -> dict | None:
        return self.wait_packet(lambda p: p.get("type") == "status", timeout)

    def wait_frame(self, timeout: float = 2.0) -> dict | None:
        return self.wait_packet(lambda p: p.get("type") == "frame", timeout)

    def stats(self) -> str:
        with self.lock:
            return (
                f"packets={len(self.packets)} lines={len(self.lines)} "
                f"errors={len(self.errors)} bytes={self.bytes_seen} "
                f"frames={self.packets_seen['frame']} status={self.packets_seen['status']}"
            )


def open_device(port: str, label: str = "") -> Device:
    device = Device(port, label).start()
    if not device.ok():
        raise RuntimeError(device.open_error() or "port open failed")
    return device


def stm32_send_tx(device: Device, codeword: tuple[int, int, int],
                  frames: int = 3, timeout: float = 5.0) -> dict:
    """Send a binary TX_COMMAND and wait for an accepted ACK.  Raises on
    failure with a clear reason (test scripts turn that into FAIL text)."""
    hopping, serial, button = codeword
    device.send_tx_command(hopping, serial, button, frames)
    ack = device.wait_ack(TYPE_TX_COMMAND, timeout)
    if ack is None:
        raise RuntimeError(
            f"no ACK for TX_COMMAND within {timeout:.0f}s (device stats: {device.stats()})"
        )
    if not ack["accepted"]:
        raise RuntimeError("ACK for TX_COMMAND reports accepted=0")
    return ack


# ---------------------------------------------------------------------------
# codeword / waveform helpers (same constants as firmware/src/lab_tx.c)
# ---------------------------------------------------------------------------


def codeword_bits(hopping: int, serial: int, button: int, frame_index: int) -> list[int]:
    bits = [0] * 66
    for i in range(32):
        bits[i] = (hopping >> i) & 1
    for i in range(28):
        bits[32 + i] = (serial >> i) & 1
    for i in range(4):
        bits[60 + i] = (button >> i) & 1
    bits[64] = 0                        # VLOW
    bits[65] = 1 if frame_index > 0 else 0  # repeat after the first frame
    return bits


def frame_pulses(hopping: int, serial: int, button: int, frame_index: int) -> list[int]:
    """Edge-to-edge durations of one HCS301-shaped frame, exactly what
    lab_tx_build_waveform() emits (77 entries)."""
    bits = codeword_bits(hopping, serial, button, frame_index)
    pulses = [PREAMBLE_EDGE_US] * PREAMBLE_EDGES
    pulses.append(HEADER_ONE_US if bits[0] else HEADER_ZERO_US)
    for i in range(65):
        if not bits[i] and bits[i + 1]:
            pulses.append(SHORT_US)
        elif bits[i] and not bits[i + 1]:
            pulses.append(LONG_US)
        else:
            pulses.append(MID_US)
    return pulses


def codeword_frames(hopping: int, serial: int, button: int, frame_count: int) -> list[list[int]]:
    return [frame_pulses(hopping, serial, button, i) for i in range(frame_count)]


def decode_segment(pulses: list[int]) -> tuple[dict | None, float]:
    """Decode one segment (list of edge-to-edge durations) with the repo's
    decoder, auto-calibrated when the fixed scale fails."""
    frames = [{"pulses_us": pulses}]
    scale = _single_frame_scale(pulses)
    for candidate in ([scale, 1.0] if scale is not None else [1.0]):
        decoded = decode_frame(pulses, candidate)
        if decoded is not None:
            return decoded, candidate
    return None, (scale or 1.0)


def decode_air_segment(deltas: list[int]) -> tuple[dict | None, float]:
    """Decode an over-the-air capture: edge-to-edge deltas of real HCS301 PWM
    (as seen on the SRX882 pin).  The STM32 reference reports falling-edge to
    falling-edge periods, so reduce to those first (trying both parities in case
    the first edge was missed); fall back to the already-reduced form."""
    for parity in (1, 0):
        periods = [deltas[i] + deltas[i + 1] for i in range(parity, len(deltas) - 1, 2)]
        for start in range(0, 3):
            candidate = periods[start:start + 77]
            if len(candidate) == 77:
                decoded, scale = decode_segment(candidate)
                if decoded:
                    return decoded, scale
    return decode_segment(deltas)


def _single_frame_scale(pulses: list[int]) -> float | None:
    values = [p for p in pulses[PREAMBLE_EDGES + 1:]]
    if len(values) < 8:
        return None
    scale = statistics.median(values) / MID_US
    if not 0.85 <= scale <= 1.15:
        return None
    return scale


def segment_pulses(pulses: list[int], gap_us: int = SEGMENT_GAP_US) -> list[list[int]]:
    """Split a pulse list into frame segments wherever the idle gap between
    edges exceeds gap_us (> 8 ms), mirroring what a logic analyzer would
    record across the inter-frame gap."""
    segments: list[list[int]] = []
    current: list[int] = []
    for pulse in pulses:
        if pulse > gap_us and current:
            segments.append(current)
            current = []
        else:
            current.append(pulse)
    if current:
        segments.append(current)
    return segments


def pick_codeword() -> tuple[int, int, int, str]:
    """Choose the codeword the tests transmit:
      HIL_CODEWORD=hopping:serial:button   (hex, hex, hex)
      HIL_CODEWORD=cap:<glob>              (first decodable frame from a
                                           tools/captures/<glob>.ndjson)
    default: the known-good lab encoder codeword."""
    raw = os.environ.get("HIL_CODEWORD", "").strip()
    if not raw:
        return (*DEFAULT_CODEWORD, "default lab codeword")
    if raw.startswith("cap:"):
        pattern = raw[4:].strip() or "r3"
        capture_dir = TOOLS_DIR / "captures"
        paths = sorted(capture_dir.glob(f"{pattern}*"))
        ndjson_paths = [path for path in paths if path.suffix == ".ndjson"]
        if not ndjson_paths:
            raise RuntimeError(f"HIL_CODEWORD=cap:{pattern}: no matches under {capture_dir}")
        for path in ndjson_paths:
            values = codeword_from_capture(path)
            if values:
                return (*values, f"HIL_CODEWORD=cap:{pattern} via {path.name}")
        raise RuntimeError(f"HIL_CODEWORD=cap:{pattern}: no decodable 77-pulse frame found")
    parts = raw.split(":")
    if len(parts) != 3:
        raise RuntimeError(f"bad HIL_CODEWORD {raw!r}; want hopping:serial:button in hex")
    try:
        hopping, serial, button = (int(part, 16) for part in parts)
    except ValueError as error:
        raise RuntimeError(f"bad HIL_CODEWORD {raw!r}: {error}")
    return hopping, serial, button, f"HIL_CODEWORD={raw}"


def codeword_from_capture(path: Path) -> tuple[int, int, int] | None:
    """Decode the first 77-pulse frame in a repo-style NDJSON capture and
    return its (hopping, serial, button)."""
    with path.open() as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                packet = json.loads(line)
            except json.JSONDecodeError:
                continue
            if packet.get("type") != "frame":
                continue
            pulses = packet.get("pulses_us") or []
            if len(pulses) != PULSES_PER_FRAME:
                continue
            decoded, _scale = decode_segment(pulses)
            if decoded:
                return decoded["hopping"], decoded["serial"], decoded["button"]
    return None


# ---------------------------------------------------------------------------
# sigrok capture (fx2lafw), parsed in-process
# ---------------------------------------------------------------------------


def _rate_str(rate: int) -> str:
    if rate % 1_000_000 == 0:
        return f"{rate // 1_000_000}m"
    if rate % 1000 == 0:
        return f"{rate // 1000}k"
    return str(rate)


def log_channels() -> list[str]:
    """D0 (SRX882 data out) and D1 (STX882 data in) are always recorded;
    optional ESP GPIO taps come from the environment."""
    channels = ["D0", "D1"]
    for name in ("HIL_CH_ESP_TX", "HIL_CH_ESP_RX"):
        value = os.environ.get(name, "").strip().upper()
        if value and value not in channels:
            channels.append(value)
    return channels


class Capture:
    """Run sigrok-cli once (one file per test step), then parse the .sr in
    Python: no numpy needed -- edge positions are found with big-int XOR so
    even ~1.5 M samples parse in well under a second on a 4-core box."""

    def __init__(self, name: str, seconds: float, channels: list[str] | None = None,
                 rate: int | None = None, out_dir: Path | None = None,
                 keep_raw: bool | None = None) -> None:
        self.name = name.replace("/", "_")
        self.seconds = seconds
        self.channels = channels or log_channels()
        self.rate = rate if rate is not None else _default_rate()
        root = Path(__file__).resolve().parent
        self.out_dir = out_dir or (root / "captures")
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.path = self.out_dir / f"{self.name}.sr"
        limit = int(os.environ.get("HIL_SR_MAX_MB", "2")) * 1024 * 1024
        self.keep_raw = limit if keep_raw is None else keep_raw
        self.returncode: int | None = None
        self.stderr = ""
        # filled in by load()
        self.samples = 0
        self.data = b""
        self.bits: dict[str, int] = {}

    def command(self) -> list[str]:
        return [
            "sigrok-cli", "-d", "fx2lafw",
            "--config", f"samplerate={_rate_str(self.rate)}",
            "--channels", ",".join(self.channels),
            "--time", f"{int(round(self.seconds * 1000))}ms",
            "-o", str(self.path),
        ]

    def run(self) -> "Capture":
        if self.path.exists():
            self.path.unlink()
        started = time.monotonic()
        result = subprocess.run(  # noqa: S603 - fixed argv, no shell
            self.command(), capture_output=True, timeout=self.seconds + 20, text=True
        )
        self.returncode = result.returncode
        self.stderr = (result.stderr or "").strip()[-2000:]
        if DEBUG:
            debug(f"capture {self.name}: rc={result.returncode} "
                  f"el={time.monotonic() - started:.1f}s {self.stderr[:200]}")
        return self

    def ok(self) -> bool:
        return self.returncode == 0 and self.path.is_file()

    # -- parsing ------------------------------------------------------------

    def load(self) -> "Capture":
        with zipfile.ZipFile(self.path) as bundle:
            names = bundle.namelist()
            chunks = []
            def chunk_key(n: str) -> int:
                tail = n.rsplit("-", 1)[-1]
                return int(tail) if tail.isdigit() else 0
            # numeric order: lexicographic sorting puts logic-1-10 before -2
            for name in sorted((n for n in names if n.startswith("logic-1")),
                               key=chunk_key):
                chunks.append(bundle.read(name))
            if not chunks:
                raise RuntimeError(f"no logic-1-* samples inside {self.path}")
        self.data = b"".join(chunks)
        self.samples = len(self.data)
        # fx2lafw packs one 8-bit sample byte per clock; channel i maps to
        # bit i (D0 -> bit 0) in the order the channels were requested
        for index, channel in enumerate(self.channels):
            self.bits[channel] = index
        return self

    # -- channel views ------------------------------------------------------

    def _validate(self, channel: str) -> int:
        if channel not in self.bits:
            raise KeyError(f"channel {channel} not captured (have {self.channels})")
        return self.bits[channel]

    def edge_times(self, channel: str) -> list[float]:
        """Transition sample positions in microseconds since the recording
        started (so edges can be correlated between channels)."""
        bit = self._validate(channel)
        if self.samples < 2:
            return []
        # isolate this channel's bit: one 0/1 byte per sample
        table = bytes(((value >> bit) & 1) for value in range(256))
        single = self.data.translate(table)
        changed = single[:-1]
        current = single[1:]
        xor = int.from_bytes(current, "big") ^ int.from_bytes(changed, "big")
        if xor == 0:
            return []
        mask = xor.to_bytes(len(changed), "big")
        indices = [match.start() + 1 for match in self._nonzero_iter(mask)]
        us_per_sample = 1_000_000.0 / self.rate
        return [index * us_per_sample for index in indices]

    @staticmethod
    def _nonzero_iter(data: bytes):
        return re.finditer(rb"[^\x00]", data)

    def pulses(self, channel: str) -> list[list[int]]:
        """Frame segments as pulse durations (edge-to-edge microseconds),
        split on idle gaps > SEGMENT_GAP_US."""
        times = self.edge_times(channel)
        segments: list[list[int]] = []
        current: list[int] = []
        for earlier, later in zip(times, times[1:]):
            delta = later - earlier
            if delta > SEGMENT_GAP_US:
                if current:
                    segments.append(current)
                current = []
            else:
                current.append(int(delta))
        if current:
            segments.append(current)
        return segments

    def decoded_frames(self, channel: str) -> list[dict]:
        frames = []
        for segment in self.pulses(channel):
            decoded, scale = decode_air_segment(segment)
            if decoded:
                decoded["scale"] = scale
                decoded["pulse_count"] = len(segment)
                frames.append(decoded)
        return frames

    def activity(self, channel: str, window_s: float | None = None) -> dict:
        """Transition count + high-duty ratio for the channel; window_s
        measures the last window_s seconds of the capture."""
        bit = self._validate(channel)
        use = self.data
        if window_s is not None:
            count = min(self.samples, int(window_s * self.rate))
            use = self.data[-count:]
        if not use:
            return {"transitions": 0, "high_ratio": 0.0, "samples": 0}
        transitions = len(self.edge_times(channel)) if window_s is None else len(
            self.edge_times_window(channel, use)
        )
        mask = int.from_bytes(bytes([1 << bit]) * len(use), "big")
        ones = 0
        try:
            ones = (int.from_bytes(use, "big") & mask).bit_count()
        except AttributeError:  # pragma: no cover - py < 3.10 fallback
            ones = sum(bin(byte).count("1") for byte in use if byte)
        return {
            "transitions": transitions,
            "high_ratio": ones / len(use),
            "samples": len(use),
        }

    def edge_times_window(self, channel: str, window: bytes) -> list[float]:
        """Edges inside a byte window, expressed relative to the START of
        the window (only used for analysing a suffix)."""
        bit = self.bits[channel]
        if len(window) < 2:
            return []
        table = bytes(((value >> bit) & 1) for value in range(256))
        window = window.translate(table)
        changed = window[:-1]
        current = window[1:]
        xor = int.from_bytes(current, "big") ^ int.from_bytes(changed, "big")
        if xor == 0:
            return []
        mask = xor.to_bytes(len(changed), "big")
        return [
            (match.start() + 1) * 1_000_000.0 / self.rate
            for match in self._nonzero_iter(mask)
        ]


class SigrokCaptureResult:
    """Returned by capture_window(); small pure-data wrapper."""


def _default_rate() -> int:
    raw = os.environ.get("HIL_SR_RATE", "")
    if raw.strip():
        return int(float(raw.strip()))
    return 500_000


class CaptureWindow:
    """Context manager: start sigrok in the background, let the caller
    trigger RF traffic, wait for the file to land, then parse."""

    def __init__(self, name: str, seconds: float, channels: list[str] | None = None,
                 rate: int | None = None, settle_s: float | None = None) -> None:
        self.capture = Capture(name, seconds, channels, rate)
        self.settle = settle_s if settle_s is not None else _default_settle(seconds)
        self.process: subprocess.Popen | None = None

    def __enter__(self) -> "CaptureWindow":
        cmd = self.capture.command()
        if DEBUG:
            debug("sigrok:", " ".join(cmd))
        self.process = subprocess.Popen(  # noqa: S603
            cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True
        )
        # sigrok-cli opens /dev and starts sampling within this settle; the
        # caller triggers the RF event once it returns
        deadline = time.monotonic() + self.settle
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(remaining, 0.05))
        return self

    def trigger_sentinel(self) -> None:
        """For symmetry; tests trigger directly when convenient."""

    def finish(self) -> Capture:
        assert self.process is not None
        try:
            _stdout, stderr = self.process.communicate(timeout=self.capture.seconds + 20)
        except subprocess.TimeoutExpired as error:
            self.process.kill()
            _stdout, stderr = self.process.communicate()
        stderr_text = (stderr or "").strip()[-2000:]
        if self.process.returncode not in (0, None):
            raise RuntimeError(
                f"sigrok-cli exit {self.process.returncode}: {stderr_text}"
            )
        if not self.capture.path.is_file():
            raise RuntimeError(f"sigrok-cli produced no file: {stderr_text}")
        self.capture.load()
        return self.capture

    def __exit__(self, exc_type, exc, tb) -> bool:
        if self.process is None:
            return False
        try:
            self.process.communicate(timeout=5)
        except Exception:
            self.process.kill()
        self.process = None
        return False


def _default_settle(seconds: float) -> float:
    return min(0.8, max(0.3, seconds * 0.15))


# ---------------------------------------------------------------------------
# BLE helper (bleak), mirroring the device-side NUS-style bridge
# ---------------------------------------------------------------------------


def _ble_import():
    from bleak import BleakClient, BleakScanner  # noqa: F401
    return BleakClient, BleakScanner


class BleSession:
    def __init__(self, client) -> None:
        self.client = client
        self.packets: list[dict] = []
        self.errors: list[str] = []
        self.lines: list[str] = []
        self.bytes_seen = 0
        self._buffer = bytearray()
        self._queue: asyncio.Queue = asyncio.Queue()
        self._started = False

    async def start(self) -> "BleSession":
        await self.client.start_notify(BLE_NUS_NOTIFY_UUID, self._notify)
        self._started = True
        return self

    async def close(self) -> None:
        if self._started:
            try:
                await self.client.stop_notify(BLE_NUS_NOTIFY_UUID)
            except Exception:
                pass
        try:
            await self.client.disconnect()
        except Exception:
            pass

    def _notify(self, _char, data: bytearray) -> None:
        self.bytes_seen += len(data)
        self._buffer.extend(data)
        while True:
            delimiter = self._buffer.find(0)
            if delimiter == -1:
                newline = self._buffer.rfind(b"\n")
                if newline == -1:
                    if len(self._buffer) > 8192:
                        self._buffer.clear()
                    break
                chunk = bytes(self._buffer[: newline + 1])
                del self._buffer[: newline + 1]
                text = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
                for line in text.split("\n"):
                    line = line.strip("\r").strip()
                    if line:
                        self.lines.append(line)
                break
            chunk = bytes(self._buffer[:delimiter])
            del self._buffer[: delimiter + 1]
            if not chunk:
                continue
            try:
                packet = parse_packet(chunk)
            except PacketError as error:
                self.errors.append(f"packet: {error}")
                continue
            packet["host_time"] = time.time()
            self.packets.append(packet)
            self._queue.put_nowait(packet)

    async def write(self, data: bytes) -> None:
        """Chunk to MTU-3 like the app does; reassembly on the device
        side is on 0x00 framing, so chunking is transport-only."""
        mtu = int(getattr(self.client, "mtu_size", 247) or 247)
        chunk_size = max(mtu - 3, 20)
        uuid = BLE_NUS_WRITE_UUID
        for offset in range(0, len(data), chunk_size):
            piece = data[offset : offset + chunk_size]
            if DEBUG:
                debug(f"ble write chunk {offset}/{len(data)} len={len(piece)}")
            try:
                await self.client.write_gatt_char(uuid, piece, response=False)
            except TypeError:
                await self.client.write_gatt_char(uuid, piece)
            # pace multi-chunk packets like ble_serial.cpp does
            if offset + chunk_size < len(data):
                await asyncio.sleep(0.06)

    async def send_text(self, text: str) -> None:
        await self.write(text.encode("ascii") + b"\r\n")

    async def send_packet(self, raw: bytes) -> None:
        await self.write(cobs_encode(raw) + b"\x00")

    async def next_packet(self, predicate, timeout: float) -> dict | None:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            try:
                packet = await asyncio.wait_for(self._queue.get(), remaining)
            except asyncio.TimeoutError:
                return None
            if predicate(packet):
                return packet

    async def wait_status(self, timeout: float = 2.0) -> dict | None:
        return await self.next_packet(lambda p: p.get("type") == "status", timeout)

    async def wait_ack(self, for_type: int = TYPE_TX_COMMAND, timeout: float = 5.0) -> dict | None:
        return await self.next_packet(
            lambda p: p.get("type") == "ack" and p.get("for_type") == for_type, timeout
        )

    def stats(self) -> str:
        return (
            f"packets={len(self.packets)} bytes={self.bytes_seen} "
            f"errors={len(self.errors)} lines={len(self.lines)}"
        )


async def ble_connect(name_prefix: str = BLE_NAME_PREFIX, scan_seconds: float = 6.0,
                      connect_seconds: float = 12.0) -> tuple[BleSession, str]:
    BleakClient, BleakScanner = _ble_import()
    devices = await BleakScanner.discover(timeout=scan_seconds)
    matches = [d for d in devices if (d.name or "").startswith(name_prefix)]
    if not matches:
        seen = [(d.name or "<unnamed>") for d in devices]
        raise RuntimeError(
            f"no device named {name_prefix}* found in {scan_seconds:.0f}s; "
            f"seen: {seen[:8]}"
        )
    target = sorted(matches, key=lambda d: len(d.name))[0]
    name = target.name or ""
    addresses = []
    for value in (getattr(target, "address", None),):
        if value:
            addresses.append(value)
    client_kwargs = {}
    address = addresses[0]
    client = BleakClient(address, **client_kwargs)
    await client.connect(timeout=connect_seconds)
    session = BleSession(client)
    await session.start()
    return session, name


# ---------------------------------------------------------------------------
# small output helpers for the test scripts
# ---------------------------------------------------------------------------


def report(test_id: str, name: str, ok: bool, reason: str) -> None:
    """Print the exact required verdict line and exit code accordingly."""
    verdict = "PASS" if ok else "FAIL"
    print(f"{test_id} {name}: {verdict} {reason}".rstrip())
    return 0 if ok else 1


def iter_report(prefix: str, message: str) -> None:
    print(f"  {prefix} {message}", flush=True)


# ---------------------------------------------------------------------------
# --selftest: offline checks, no hardware
# ---------------------------------------------------------------------------


def selftest() -> int:
    status = "PASS"
    notes: list[str] = []

    def fail(note: str) -> None:
        nonlocal status
        status = "FAIL"
        notes.append(note)

    # 1) synthetic codeword: segmentation + decode round trip
    hopping, serial, button = DEFAULT_CODEWORD
    build = []
    for index, frame in enumerate(codeword_frames(hopping, serial, button, 3)):
        if index:
            build.append(INTER_FRAME_GAP_US)
        build.extend(frame)
    segments = segment_pulses(build)
    if len(segments) != 3:
        fail(f"segmentation: expected 3 segments from a 3-frame codeword, got {len(segments)}")
    for index, segment in enumerate(segments):
        if len(segment) != PULSES_PER_FRAME:
            fail(f"segment {index}: expected {PULSES_PER_FRAME} pulses, got {len(segment)}")
            continue
        decoded, scale = decode_segment(segment)
        if decoded is None:
            fail(f"segment {index}: decode failed")
            continue
        if (decoded["hopping"], decoded["serial"], decoded["button"]) != (hopping, serial, button):
            fail(f"segment {index}: fields mismatch {decoded}")
        if decoded["vlow"] != 0:
            fail(f"segment {index}: vlow not 0")
        if decoded["repeat"] != (1 if index else 0):
            fail(f"segment {index}: repeat={decoded['repeat']} expected {1 if index else 0}")
        notes.append(f"synthetic segment {index} decode ok scale={scale:.3f}")

    # 1b) corrupted pulse must not decode
    corrupted = list(codeword_frames(hopping, serial, button, 1)[0])
    corrupted[-1] += 5000
    decoded, _scale = decode_segment(corrupted)
    if decoded is not None:
        fail("corrupted pulse list still decoded (robustness)")

    # 2) real capture from tools/captures
    capture_dir = TOOLS_DIR / "captures"
    candidates = sorted(capture_dir.glob("r3*.ndjson")) + sorted(capture_dir.glob("keeloq-button*.ndjson"))
    decodable = None
    for path in candidates:
        raw = codeword_from_capture(path)
        if raw:
            decodable = (path, raw)
            break
    if decodable is None:
        fail(f"no decodable 77-pulse frame found in {capture_dir} (r3*/keeloq-button*)")
    else:
        path, (hop, ser, btn) = decodable
        notes.append(
            f"{path.name}: hopping=0x{hop:08X} serial=0x{ser:07X} button=0x{btn:X}"
        )

    reason = " | ".join(notes)
    print(f"selftest: {status} {reason}".rstrip())
    return 0 if status == "PASS" else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true", help="offline checks, no hardware")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    parser.print_usage()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
