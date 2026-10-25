#!/usr/bin/env python3
"""Capture and decode STM32 RF analyzer binary packets.

Also understands the ACK reply (with its raw accepted byte: 0 = rejected/
busy, 1 = complete, 2 = TX aborted by MODE IDLE) and decodes the radio mode
the ESP32-C3/CC1101 firmware carries in STATUS header flags bits 2..3.
"""

from __future__ import annotations

import argparse
import json
import os
import select
import struct
import sys
import termios
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO, Iterator

VERSION = 1
TYPE_FRAME = 1
TYPE_STATUS = 2
TYPE_ACK = 5
HEADER = struct.Struct("<BBHHHIHHBB")
CRC = struct.Struct("<H")
MAX_ENCODED_PACKET = 1024

# STATUS header flags bits 2..3 carry the radio mode (IDLE/RX/TX).
MODE_FLAG_SHIFT = 2
MODE_FLAG_MASK = 0x0003
MODE_IDLE = 0
MODE_RX = 1
MODE_TX = 2
MODE_NAMES = {MODE_IDLE: "idle", MODE_RX: "rx", MODE_TX: "tx"}


class PacketError(ValueError):
    """A framed packet was malformed or corrupt."""


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


def parse_packet(encoded: bytes) -> dict:
    raw = cobs_decode(encoded)
    if len(raw) < 2 + CRC.size:
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
            "version": version,
            "type": "ack",
            "for_type": raw[2],
            # 0 = rejected/busy, 1 = complete, 2 = TX aborted by MODE IDLE
            "accepted": accepted_raw != 0,
            "accepted_raw": accepted_raw,
        }

    if len(raw) < HEADER.size + CRC.size:
        raise PacketError("packet shorter than header")
    (_, _, flags, sequence, packet_length, timestamp_us,
     dropped_frames, pulse_count, initial_level, receiver_id) = HEADER.unpack_from(raw)
    if packet_length != len(raw):
        raise PacketError(f"length field {packet_length} does not match {len(raw)} bytes")
    if packet_length != HEADER.size + pulse_count * 2 + CRC.size:
        raise PacketError("pulse count does not match packet length")

    pulses = list(struct.unpack_from(f"<{pulse_count}H", raw, HEADER.size)) if pulse_count else []
    packet = {
        "version": version,
        "type": "frame" if message_type == TYPE_FRAME else "status" if message_type == TYPE_STATUS else message_type,
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
        if len(pulses) not in (2, 5):
            packet["status_words"] = pulses
    else:
        packet["pulse_count"] = pulse_count
        packet["duration_us"] = sum(pulses)
        packet["min_pulse_us"] = min(pulses) if pulses else None
        packet["max_pulse_us"] = max(pulses) if pulses else None
        packet["pulses_us"] = pulses
    return packet


class PacketStream:
    def __init__(self) -> None:
        self.buffer = bytearray()

    def feed(self, data: bytes) -> Iterator[bytes]:
        self.buffer.extend(data)
        while True:
            try:
                delimiter = self.buffer.index(0)
            except ValueError:
                if len(self.buffer) > MAX_ENCODED_PACKET:
                    self.buffer.clear()
                    raise PacketError("stream exceeded maximum packet size without delimiter")
                return
            encoded = bytes(self.buffer[:delimiter])
            del self.buffer[:delimiter + 1]
            if encoded:
                yield encoded


class SerialInput:
    def __init__(self, port: str, baud: int) -> None:
        if baud != 115200:
            raise ValueError("the standard-library serial backend currently supports 115200 baud")
        self.fd = os.open(port, os.O_RDONLY | os.O_NOCTTY | os.O_NONBLOCK)
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
        termios.tcflush(self.fd, termios.TCIFLUSH)

    def read(self, size: int) -> bytes:
        readable, _, _ = select.select([self.fd], [], [], 0.5)
        return os.read(self.fd, size) if readable else b""

    def close(self) -> None:
        os.close(self.fd)


def output_stream(path: str) -> tuple[BinaryIO, bool]:
    if path == "-":
        return sys.stdout.buffer, False
    return open(path, "wb"), True


def emit_json(stream: BinaryIO, packet: dict) -> None:
    stream.write(json.dumps(packet, separators=(",", ":")).encode() + b"\n")
    stream.flush()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--port", help="serial device, for example /dev/ttyUSB0")
    source.add_argument("--input", type=Path, help="replay a previously saved binary stream")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--save", type=Path, help="save the exact encoded stream")
    parser.add_argument("--json", default="-", help="NDJSON output path; default is stdout")
    parser.add_argument("--max-packets", type=int, default=0, help="exit after this many valid packets")
    args = parser.parse_args(argv)

    serial_input = None
    close_source = False
    if args.port:
        serial_input = SerialInput(args.port, args.baud)
        reader = serial_input
    elif args.input:
        reader = open(args.input, "rb")
        close_source = True
    else:
        reader = sys.stdin.buffer

    json_output, close_json = output_stream(args.json)
    save_output = open(args.save, "ab") if args.save else None
    packet_stream = PacketStream()
    valid_packets = 0

    try:
        while True:
            chunk = reader.read(4096)
            if not chunk:
                if args.port:
                    continue
                break
            if save_output:
                save_output.write(chunk)
                save_output.flush()
            try:
                frames = packet_stream.feed(chunk)
                for encoded in frames:
                    try:
                        packet = parse_packet(encoded)
                    except PacketError as error:
                        print(f"discarded packet: {error}", file=sys.stderr)
                        continue
                    # Host timestamps make deliberate gaps useful as labels in
                    # long multi-remote recording sessions. The monotonic value
                    # is suitable for interval calculations; UTC is convenient
                    # when reviewing the NDJSON manually.
                    packet["host_monotonic_ns"] = time.monotonic_ns()
                    packet["host_time_utc"] = datetime.now(timezone.utc).isoformat()
                    emit_json(json_output, packet)
                    valid_packets += 1
                    if args.max_packets and valid_packets >= args.max_packets:
                        return 0
            except PacketError as error:
                print(f"stream resynchronized: {error}", file=sys.stderr)
    except KeyboardInterrupt:
        return 0
    finally:
        if serial_input:
            serial_input.close()
        elif close_source:
            reader.close()
        if close_json:
            json_output.close()
        if save_output:
            save_output.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
