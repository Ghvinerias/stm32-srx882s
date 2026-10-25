#!/usr/bin/env python3
"""Interactive TUI: record RF captures and send TX/scenario commands over
the same USB CDC link, at the same time if you want to.

Speaks the exact binary protocol described in firmware/src/transport.h /
command_rx.h -- COBS+CRC16 framing, both directions, on one serial port.
Device -> host: FRAME/STATUS (capture stream). Host -> device: TX_COMMAND /
SCENARIO_COMMAND / MODE_COMMAND. Device -> host, in reply: ACK.

Radio modes (ESP32-C3/CC1101 firmware): IDLE (boot default: CC1101 idle,
RMT capture off, no FRAME packets), RX (listening) and TX (transient while a
TX_COMMAND burst is on the air -- the device returns to the mode it was in
before the burst).  The host selects IDLE/RX with MODE_COMMAND (type 8,
[ver][type=8][mode][crc16 LE], mode 0 = IDLE, 1 = RX); the device answers
ACK(for_type=8).  STATUS packets carry the current mode in header flags
bits 2..3 (0 = IDLE, 1 = RX, 2 = TX) and an ACK's accepted byte is now
0 = rejected/busy, 1 = complete, 2 = TX aborted by MODE IDLE.

    python rf_console.py --port /dev/ttyACM0

Keys (shown in the footer too):
  r        toggle recording capture frames to the NDJSON/raw files
  o        set/change the output path (prompts for a new one)
  c        open the command menu (send a single TX_COMMAND, a scenario, or
           switch the radio between IDLE and RX)
  b        paste/type a batch of "tx <hopping> <serial> <button> [frames]"
           lines (the same syntax as firmware's ASCII console -- one per
           line; finish with a blank line or Ctrl-D) and send them in order
  q        quit

Recording and sending share one full-duplex serial connection and can run
at the same time -- reads and writes on the fd don't block each other, so
nothing here serializes them. The actual caveat is on the firmware side,
not this tool: lab_tx_send_codeword() in firmware/src/lab_tx.c disables
interrupts for the whole bit-banged transmission (so its timing isn't
jittered by anything else on the MCU), which means capture edges that would
land inside that exact window are not seen -- not "mangled," just not
recorded. This only matters if you need to capture something arriving in
the same few milliseconds as your own outgoing TX; a capture of an
unrelated remote running before/after a send is unaffected.
"""

from __future__ import annotations

import argparse
import curses
import json
import os
import select
import struct
import sys
import termios
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

VERSION = 1
TYPE_FRAME = 1
TYPE_STATUS = 2
TYPE_TX_COMMAND = 3
TYPE_SCENARIO_COMMAND = 4
TYPE_ACK = 5
TYPE_MODE_COMMAND = 8
HEADER = struct.Struct("<BBHHHIHHBB")
CRC = struct.Struct("<H")
MAX_ENCODED_PACKET = 1024

# Header flags bits 2..3 carry the radio mode (firmware/src/proto_transport.h).
MODE_FLAG_SHIFT = 2
MODE_FLAG_MASK = 0x0003

MODE_IDLE = 0
MODE_RX = 1
MODE_TX = 2
MODE_NAMES = {MODE_IDLE: "idle", MODE_RX: "rx", MODE_TX: "tx"}

MAX_TX_FRAMES = 255

SCENARIOS = [
    ("synthetic-stale", ord("s")),
    ("zero-hop", ord("z")),
    ("plaintext-counter", ord("p")),
    ("random-hop", ord("x")),
    ("different-serial", ord("d")),
]


class PacketError(ValueError):
    pass


# ---- wire protocol: shared with firmware/src/transport.c ----

def crc16_ccitt(data: bytes) -> int:
    crc = 0xFFFF
    for value in data:
        crc ^= value << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def cobs_encode(data: bytes) -> bytes:
    output = bytearray([0])
    code_index = 0
    code = 1
    for value in data:
        if value == 0:
            output[code_index] = code
            code_index = len(output)
            output.append(0)
            code = 1
        else:
            output.append(value)
            code += 1
            if code == 0xFF:
                output[code_index] = code
                code_index = len(output)
                output.append(0)
                code = 1
    output[code_index] = code
    return bytes(output)


def cobs_decode(data: bytes) -> bytes:
    if not data:
        raise PacketError("empty COBS frame")
    output = bytearray()
    index = 0
    while index < len(data):
        code = data[index]
        if code == 0:
            raise PacketError("zero byte inside COBS frame")
        index += 1
        end = index + code - 1
        if end > len(data):
            raise PacketError("truncated COBS block")
        output.extend(data[index:end])
        index = end
        if code != 0xFF and index < len(data):
            output.append(0)
    return bytes(output)


def build_tx_command(hopping: int, serial: int, button: int, frames: int) -> bytes:
    raw = bytearray(14)
    raw[0] = VERSION
    raw[1] = TYPE_TX_COMMAND
    struct.pack_into("<I", raw, 2, hopping & 0xFFFFFFFF)
    struct.pack_into("<I", raw, 6, serial & 0xFFFFFFFF)
    raw[10] = button & 0xFF
    raw[11] = frames & 0xFF
    crc = crc16_ccitt(bytes(raw[:12]))
    struct.pack_into("<H", raw, 12, crc)
    return cobs_encode(bytes(raw)) + b"\x00"


def build_scenario_command(command_char: int) -> bytes:
    raw = bytearray(5)
    raw[0] = VERSION
    raw[1] = TYPE_SCENARIO_COMMAND
    raw[2] = command_char & 0xFF
    crc = crc16_ccitt(bytes(raw[:3]))
    struct.pack_into("<H", raw, 3, crc)
    return cobs_encode(bytes(raw)) + b"\x00"


def build_mode_command(mode: int) -> bytes:
    """MODE_COMMAND (type 8): [ver=1][type=8][mode u8][crc16 LE], COBS + 0x00.

    mode 0 = IDLE, 1 = RX.  Any byte value is encodable on purpose: a host
    needs to be able to prove the device ACKs an unknown mode with
    accepted=0 instead of acting on it."""
    if not 0 <= mode <= 0xFF:
        raise ValueError(f"mode must fit in one byte, got {mode!r}")
    raw = bytearray(5)
    raw[0] = VERSION
    raw[1] = TYPE_MODE_COMMAND
    raw[2] = mode
    crc = crc16_ccitt(bytes(raw[:3]))
    struct.pack_into("<H", raw, 3, crc)
    return cobs_encode(bytes(raw)) + b"\x00"


def parse_packet(encoded: bytes) -> dict:
    raw = cobs_decode(encoded)
    if len(raw) < CRC.size + 2:
        raise PacketError("packet too short")
    expected_crc = CRC.unpack_from(raw, len(raw) - CRC.size)[0]
    actual_crc = crc16_ccitt(raw[:-CRC.size])
    if actual_crc != expected_crc:
        raise PacketError(f"CRC mismatch: expected {expected_crc:04x}, got {actual_crc:04x}")

    version, message_type = raw[0], raw[1]
    if version != VERSION:
        raise PacketError(f"unsupported protocol version {version}")

    if message_type == TYPE_ACK:
        if len(raw) != 6:
            raise PacketError("malformed ACK length")
        accepted_raw = raw[3]
        return {
            "type": "ack",
            "for_type": raw[2],
            # 0 = rejected/busy, 1 = complete, 2 = TX aborted by MODE IDLE.
            # The boolean keeps older callers working (raw 2 counts as true).
            "accepted": accepted_raw != 0,
            "accepted_raw": accepted_raw,
        }

    if message_type not in (TYPE_FRAME, TYPE_STATUS):
        raise PacketError(f"unrecognized message type {message_type}")
    if len(raw) < HEADER.size + CRC.size:
        raise PacketError("packet shorter than header")
    (_, _, flags, sequence, packet_length, timestamp_us, dropped_frames,
     pulse_count, initial_level, receiver_id) = HEADER.unpack_from(raw)
    if packet_length != len(raw):
        raise PacketError(f"length field {packet_length} does not match {len(raw)} bytes")
    if packet_length != HEADER.size + pulse_count * 2 + CRC.size:
        raise PacketError("pulse count does not match packet length")
    pulses = list(struct.unpack_from(f"<{pulse_count}H", raw, HEADER.size)) if pulse_count else []

    packet = {
        "type": "frame" if message_type == TYPE_FRAME else "status",
        "sequence": sequence,
        "flags": flags,
        "timestamp_us": timestamp_us,
        "dropped_frames": dropped_frames,
        "initial_level": initial_level,
        "receiver_id": receiver_id,
    }
    if message_type == TYPE_STATUS:
        packet["mode"] = (flags >> MODE_FLAG_SHIFT) & MODE_FLAG_MASK
        packet["mode_name"] = MODE_NAMES.get(
            packet["mode"], f"unknown({packet['mode']})"
        )
        if len(pulses) >= 2:
            packet["clock_hz"] = pulses[0] | pulses[1] << 16
        if len(pulses) >= 4:
            packet["total_edges"] = pulses[2] | pulses[3] << 16
        if len(pulses) >= 5:
            packet["dropped_packets"] = pulses[4]
    else:
        packet["pulse_count"] = pulse_count
        packet["pulses_us"] = pulses
    return packet


class PacketStream:
    def __init__(self) -> None:
        self.buffer = bytearray()

    def feed(self, data: bytes):
        self.buffer.extend(data)
        while True:
            try:
                delimiter = self.buffer.index(0)
            except ValueError:
                if len(self.buffer) > MAX_ENCODED_PACKET:
                    self.buffer.clear()
                    raise PacketError("stream exceeded max packet size without delimiter")
                return
            encoded = bytes(self.buffer[:delimiter])
            del self.buffer[:delimiter + 1]
            if encoded:
                yield encoded


class SerialPort:
    """Full-duplex raw access to the USB CDC port, no pyserial dependency
    (matches rf_capture.py's approach), opened read+write so sending a
    command and receiving the capture stream share one fd."""

    def __init__(self, port: str, baud: int) -> None:
        if baud != 115200:
            raise ValueError("this backend currently supports 115200 baud only")
        self.fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        settings = termios.tcgetattr(self.fd)
        settings[0] = 0
        settings[1] = 0
        settings[2] = termios.CS8 | termios.CLOCAL | termios.CREAD
        settings[3] = 0
        settings[4] = termios.B115200
        settings[5] = termios.B115200
        settings[6][termios.VMIN] = 0
        settings[6][termios.VTIME] = 1
        termios.tcsetattr(self.fd, termios.TCSANOW, settings)
        termios.tcflush(self.fd, termios.TCIOFLUSH)

    def read(self, size: int = 4096) -> bytes:
        readable, _, _ = select.select([self.fd], [], [], 0)
        return os.read(self.fd, size) if readable else b""

    def write(self, data: bytes) -> None:
        os.write(self.fd, data)

    def close(self) -> None:
        os.close(self.fd)


# ---- application state ----

@dataclass
class AppState:
    save_path: Path
    recording: bool = False
    frame_count: int = 0
    status_count: int = 0
    log_lines: list[str] = field(default_factory=list)
    last_status: dict | None = None
    ndjson_fh: object = None
    raw_fh: object = None

    def log(self, message: str) -> None:
        stamp = datetime.now().strftime("%H:%M:%S")
        self.log_lines.append(f"[{stamp}] {message}")
        self.log_lines = self.log_lines[-500:]

    def open_files(self) -> None:
        self.close_files()
        self.ndjson_fh = open(self.save_path, "a", buffering=1)
        raw_path = self.save_path.with_suffix(self.save_path.suffix + ".bin")
        self.raw_fh = open(raw_path, "ab")
        self.log(f"recording -> {self.save_path} (+ {raw_path.name})")

    def close_files(self) -> None:
        if self.ndjson_fh:
            self.ndjson_fh.close()
            self.ndjson_fh = None
        if self.raw_fh:
            self.raw_fh.close()
            self.raw_fh = None

    def handle_packet(self, packet: dict, raw_encoded: bytes) -> None:
        if self.recording and self.raw_fh:
            self.raw_fh.write(raw_encoded + b"\x00")

        if packet["type"] == "ack":
            kind = {
                TYPE_TX_COMMAND: "TX_COMMAND",
                TYPE_SCENARIO_COMMAND: "SCENARIO_COMMAND",
                TYPE_MODE_COMMAND: "MODE_COMMAND",
            }.get(packet["for_type"], f"type={packet['for_type']}")
            raw = packet.get("accepted_raw", 1 if packet["accepted"] else 0)
            note = {2: " (aborted by MODE IDLE)"}.get(raw, "")
            self.log(f"ACK {kind} accepted={raw}{note}")
            return

        if packet["type"] == "status":
            self.status_count += 1
            self.last_status = packet
            if self.recording and self.ndjson_fh:
                record = dict(packet)
                record["host_time_utc"] = datetime.now(timezone.utc).isoformat()
                self.ndjson_fh.write(json.dumps(record, separators=(",", ":")) + "\n")
            return

        # frame
        self.frame_count += 1
        if self.recording and self.ndjson_fh:
            record = dict(packet)
            record["host_time_utc"] = datetime.now(timezone.utc).isoformat()
            self.ndjson_fh.write(json.dumps(record, separators=(",", ":")) + "\n")
        pulses = packet["pulses_us"]
        self.log(
            f"frame seq={packet['sequence']:<6} pulses={len(pulses):<3} "
            f"dur={sum(pulses):>7}us dropped={packet['dropped_frames']}"
            f"{'  [recorded]' if self.recording else ''}"
        )


# ---- small curses helpers ----

def prompt_line(stdscr, prompt: str, initial: str = "") -> str | None:
    """Blocking single-line text prompt on the bottom row. Enter accepts,
    Esc cancels (returns None)."""
    height, width = stdscr.getmaxyx()
    row = height - 1
    curses.curs_set(1)
    stdscr.nodelay(False)
    text = initial
    while True:
        stdscr.move(row, 0)
        stdscr.clrtoeol()
        line = f"{prompt}{text}"
        stdscr.addnstr(row, 0, line, width - 1)
        stdscr.move(row, min(len(line), width - 1))
        stdscr.refresh()
        ch = stdscr.get_wch()
        if ch in ("\n", "\r") or ch == curses.KEY_ENTER:
            curses.curs_set(0)
            stdscr.nodelay(True)
            return text
        if ch == chr(27):  # Esc
            curses.curs_set(0)
            stdscr.nodelay(True)
            return None
        if ch in (curses.KEY_BACKSPACE, "\x7f", "\x08"):
            text = text[:-1]
            continue
        if isinstance(ch, str) and ch.isprintable():
            text += ch


def prompt_hex(stdscr, prompt: str, default: int = 0) -> int | None:
    text = prompt_line(stdscr, prompt, f"{default:X}")
    if text is None:
        return None
    text = text.strip()
    if not text:
        return default
    try:
        return int(text, 16)
    except ValueError:
        return None


def parse_tx_line(line: str) -> tuple[int, int, int, int] | None:
    """Parses one "tx <hopping> <serial> <button> [frames]" line -- the same
    grammar firmware's ASCII console accepts (execute_tx_text in
    command_rx.c) -- and returns (hopping, serial, button, frames), or None
    if the line doesn't match."""
    parts = line.strip().split()
    if not parts or parts[0] != "tx":
        return None
    parts = parts[1:]
    if not (3 <= len(parts) <= 4):
        return None
    try:
        hopping = int(parts[0], 16)
        serial_number = int(parts[1], 16)
        button = int(parts[2], 16)
        frames = int(parts[3], 16) if len(parts) == 4 else 3
    except ValueError:
        return None
    if not (0 <= button <= 0xF):
        return None
    if frames == 0:
        frames = 1
    frames = min(frames, MAX_TX_FRAMES)
    return hopping, serial_number, button, frames


def prompt_multiline(stdscr, prompt: str) -> list[str]:
    """Collects multiple lines (paste-friendly: a terminal paste delivers
    each embedded newline as its own Enter keypress, so a whole block comes
    through naturally). Finishes on an empty line, Ctrl-D, or Esc (which
    discards everything collected so far)."""
    height, width = stdscr.getmaxyx()
    row = height - 1
    curses.curs_set(1)
    stdscr.nodelay(False)
    lines: list[str] = []
    current = ""
    while True:
        stdscr.move(row, 0)
        stdscr.clrtoeol()
        status = f"{prompt} [{len(lines)} queued, blank line/^D to send, Esc to cancel]: {current}"
        stdscr.addnstr(row, 0, status, width - 1)
        stdscr.move(row, min(len(status), width - 1))
        stdscr.refresh()
        ch = stdscr.get_wch()
        if ch == chr(27):
            curses.curs_set(0)
            stdscr.nodelay(True)
            return []
        if ch == "\x04":  # Ctrl-D
            if current.strip():
                lines.append(current)
            curses.curs_set(0)
            stdscr.nodelay(True)
            return lines
        if ch in ("\n", "\r") or ch == curses.KEY_ENTER:
            if current.strip() == "":
                curses.curs_set(0)
                stdscr.nodelay(True)
                return lines
            lines.append(current)
            current = ""
            continue
        if ch in (curses.KEY_BACKSPACE, "\x7f", "\x08"):
            current = current[:-1]
            continue
        if isinstance(ch, str) and ch.isprintable():
            current += ch


def batch_tx_menu(stdscr, state: AppState, serial: SerialPort) -> None:
    lines = prompt_multiline(stdscr, "paste tx lines")
    if not lines:
        state.log("batch send cancelled (nothing queued)")
        return
    sent = 0
    for line in lines:
        parsed = parse_tx_line(line)
        if parsed is None:
            state.log(f"skipped (bad syntax, want 'tx <hopping> <serial> <button> [frames]'): {line!r}")
            continue
        hopping, serial_number, button, frames = parsed
        serial.write(build_tx_command(hopping, serial_number, button, frames))
        state.log(
            f"sent TX hopping=0x{hopping:08X} serial=0x{serial_number:07X} "
            f"button=0x{button:X} frames={frames}"
        )
        sent += 1
        # The device ACKs a TX_COMMAND only when its burst ends, and rejects
        # a second command while the burst is on the air, so wait out this
        # burst (106.5 ms per frame, the longest spacing used by the lab
        # firmware, plus margin) before queueing the next line.
        time.sleep(frames * 0.1065 + 0.2)
    state.log(f"batch done: {sent}/{len(lines)} line(s) sent")


def command_menu(stdscr, state: AppState, serial: SerialPort) -> None:
    height, width = stdscr.getmaxyx()
    row = height - 1
    options = ["[t] custom TX_COMMAND"] + [f"[{chr(code)}] {name}" for name, code in SCENARIOS] + ["[m] mode idle/rx", "[Esc] cancel"]
    stdscr.move(row, 0)
    stdscr.clrtoeol()
    stdscr.addnstr(row, 0, "send: " + "  ".join(options), width - 1)
    stdscr.refresh()
    stdscr.nodelay(False)
    ch = stdscr.get_wch()
    stdscr.nodelay(True)

    if ch == chr(27):
        return

    if ch == "m":
        text = prompt_line(stdscr, "mode (idle|rx): ", "rx")
        if text is None:
            state.log("mode cancelled")
            return
        mode = {"idle": MODE_IDLE, "0": MODE_IDLE,
                "rx": MODE_RX, "1": MODE_RX}.get(text.strip().lower())
        if mode is None:
            state.log(f"mode cancelled: {text!r} (want idle|rx)")
            return
        serial.write(build_mode_command(mode))
        state.log(f"sent MODE_COMMAND {MODE_NAMES[mode]}")
        return

    if ch == "t":
        hopping = prompt_hex(stdscr, "hopping (hex): ", 0)
        if hopping is None:
            state.log("tx cancelled")
            return
        serial_number = prompt_hex(stdscr, "serial (hex): ", 0x0FB58C0C)
        if serial_number is None:
            state.log("tx cancelled")
            return
        button = prompt_hex(stdscr, "button (hex, 0-F): ", 2)
        if button is None or not (0 <= button <= 0xF):
            state.log("tx cancelled: bad button")
            return
        frames = prompt_hex(stdscr, "frames (hex, default 3): ", 3)
        if frames is None or not (0 < frames <= MAX_TX_FRAMES):
            state.log("tx cancelled: bad frame count")
            return
        packet = build_tx_command(hopping, serial_number, button, frames)
        serial.write(packet)
        state.log(
            f"sent TX hopping=0x{hopping:08X} serial=0x{serial_number:07X} "
            f"button=0x{button:X} frames={frames}"
        )
        return

    if isinstance(ch, str):
        code = ord(ch)
        for name, scenario_code in SCENARIOS:
            if code == scenario_code:
                serial.write(build_scenario_command(scenario_code))
                state.log(f"sent SCENARIO_COMMAND '{name}'")
                return
    state.log("send cancelled: unrecognized key")


def draw(stdscr, state: AppState, port: str) -> None:
    height, width = stdscr.getmaxyx()
    stdscr.erase()

    mode_text = ""
    if state.last_status is not None:
        mode_text = f"  mode={state.last_status.get('mode_name', '?')}"
    header = (
        f" rf_console  port={port}  frames={state.frame_count}  "
        f"status={state.status_count}  recording={'ON' if state.recording else 'off'}  "
        f"-> {state.save_path}{mode_text}"
    )
    stdscr.addnstr(0, 0, header.ljust(width), width - 1, curses.A_REVERSE)

    log_area_height = height - 3
    visible = state.log_lines[-log_area_height:] if log_area_height > 0 else []
    for i, line in enumerate(visible):
        stdscr.addnstr(1 + i, 0, line, width - 1)

    footer = " r:record  o:output-path  c:command  b:batch-tx  q:quit "
    stdscr.addnstr(height - 2, 0, footer.ljust(width), width - 1, curses.A_REVERSE)
    stdscr.refresh()


def run(stdscr, port: str, baud: int, initial_save: Path) -> None:
    curses.curs_set(0)
    stdscr.nodelay(True)
    stdscr.timeout(50)

    serial = SerialPort(port, baud)
    stream = PacketStream()
    state = AppState(save_path=initial_save)
    state.log(f"connected to {port} @ {baud} baud")
    state.log("press 'c' to send, 'r' to toggle recording, 'o' to change output path")

    try:
        while True:
            try:
                chunk = serial.read()
            except OSError as error:
                state.log(f"serial read error: {error}")
                chunk = b""
            if chunk:
                try:
                    for encoded in stream.feed(chunk):
                        try:
                            packet = parse_packet(encoded)
                        except PacketError as error:
                            state.log(f"discarded packet: {error}")
                            continue
                        state.handle_packet(packet, encoded)
                except PacketError as error:
                    state.log(f"stream resynchronized: {error}")

            ch = stdscr.getch()
            if ch != -1:
                key = chr(ch) if 0 <= ch < 256 else None
                if key == "q":
                    break
                if key == "r":
                    state.recording = not state.recording
                    if state.recording:
                        state.open_files()
                    else:
                        state.close_files()
                        state.log("recording stopped")
                elif key == "o":
                    was_recording = state.recording
                    if was_recording:
                        state.recording = False
                        state.close_files()
                    text = prompt_line(stdscr, "output path: ", str(state.save_path))
                    if text:
                        state.save_path = Path(text).expanduser()
                        state.log(f"output path set to {state.save_path}")
                    if was_recording:
                        state.recording = True
                        state.open_files()
                elif key == "c":
                    command_menu(stdscr, state, serial)
                elif key == "b":
                    batch_tx_menu(stdscr, state, serial)

            draw(stdscr, state, port)
    finally:
        state.close_files()
        serial.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", required=True, help="serial device, e.g. /dev/ttyACM0")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument(
        "--save", type=Path,
        default=Path(f"captures/session-{datetime.now():%Y%m%d-%H%M%S}.ndjson"),
        help="initial NDJSON output path (changeable from the TUI with 'o'); "
             "a sibling .ndjson.bin raw stream is written alongside it",
    )
    args = parser.parse_args(argv)

    args.save.parent.mkdir(parents=True, exist_ok=True)

    try:
        curses.wrapper(run, args.port, args.baud, args.save)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
