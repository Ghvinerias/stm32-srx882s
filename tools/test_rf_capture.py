import struct
import unittest

from rf_capture import (
    CRC,
    HEADER,
    PacketError,
    PacketStream,
    cobs_decode,
    cobs_encode,
    crc16_ccitt,
    parse_packet,
)


def make_packet(pulses=(350, 1050, 700, 700), message_type=1, flags=0):
    raw_length = HEADER.size + len(pulses) * 2 + CRC.size
    raw = bytearray(HEADER.pack(1, message_type, flags, 42, raw_length, 123456,
                                3, len(pulses), 1, 1))
    raw.extend(struct.pack(f"<{len(pulses)}H", *pulses))
    raw.extend(CRC.pack(crc16_ccitt(raw)))
    return cobs_encode(bytes(raw)) + b"\x00"


def make_ack(for_type=3, accepted=1):
    raw = bytearray(4)
    raw[0] = 1
    raw[1] = 5
    raw[2] = for_type
    raw[3] = accepted
    raw.extend(CRC.pack(crc16_ccitt(bytes(raw[:4]))))
    return cobs_encode(bytes(raw)) + b"\x00"


class CaptureProtocolTests(unittest.TestCase):
    def test_crc_known_vector(self):
        self.assertEqual(crc16_ccitt(b"123456789"), 0x29B1)

    def test_cobs_round_trip(self):
        for value in (b"", b"abc", b"\x00", bytes(range(256)), b"a\x00b\x00c"):
            self.assertEqual(cobs_decode(cobs_encode(value)), value)

    def test_parse_frame(self):
        packet = parse_packet(make_packet()[:-1])
        self.assertEqual(packet["type"], "frame")
        self.assertEqual(packet["sequence"], 42)
        self.assertEqual(packet["dropped_frames"], 3)
        self.assertEqual(packet["pulses_us"], [350, 1050, 700, 700])
        self.assertEqual(packet["duration_us"], 2800)

    def test_parse_status(self):
        packet = parse_packet(make_packet((0xA200, 0x044A, 0x3456, 0x0012, 7),
                                          message_type=2)[:-1])
        self.assertEqual(packet["type"], "status")
        self.assertEqual(packet["clock_hz"], 72_000_000)
        self.assertEqual(packet["total_edges"], 0x00123456)
        self.assertEqual(packet["dropped_packets"], 7)

    def test_status_mode_bits(self):
        # bits 2..3 = (flags >> 2) & 3: 0 IDLE, 1 RX, 2 TX
        for flags, mode, name in ((0x0000, 0, "idle"),
                                  (0x0004, 1, "rx"),
                                  (0x0008, 2, "tx")):
            packet = parse_packet(make_packet((0xA200, 0x044A, 0x3456, 0x0012, 7),
                                              message_type=2, flags=flags)[:-1])
            self.assertEqual(packet["mode"], mode)
            self.assertEqual(packet["mode_name"], name)

    def test_status_mode_bits_coexist_with_existing_flags(self):
        # CAPTURE_TRUNCATED (bit 0) + DMA_OVERRUN (bit 1) + CLOCK_FALLBACK
        # (bit 8) must not disturb the mode field.
        packet = parse_packet(make_packet((1, 2, 3, 4, 5), message_type=2,
                                          flags=0x0103 | 0x0008)[:-1])
        self.assertEqual(packet["mode"], 2)
        self.assertEqual(packet["mode_name"], "tx")
        self.assertEqual(packet["flags"] & 0x0003, 0x0003)

    def test_parse_ack_accepted_raw(self):
        for raw, boolean in ((0, False), (1, True), (2, True)):
            packet = parse_packet(make_ack(3, raw)[:-1])
            self.assertEqual(packet["type"], "ack")
            self.assertEqual(packet["for_type"], 3)
            self.assertEqual(packet["accepted_raw"], raw)
            self.assertEqual(packet["accepted"], boolean)

    def test_parse_ack_mode_command(self):
        packet = parse_packet(make_ack(8, 1)[:-1])
        self.assertEqual(packet["for_type"], 8)
        self.assertEqual(packet["accepted_raw"], 1)

    def test_parse_ack_rejects_bad_length(self):
        raw = bytearray([1, 5, 3, 1, 0])          # one byte too long for an ACK
        raw.extend(CRC.pack(crc16_ccitt(bytes(raw))))
        with self.assertRaises(PacketError):
            parse_packet(cobs_encode(bytes(raw)))

    def test_crc_rejects_corruption(self):
        encoded = bytearray(make_packet()[:-1])
        encoded[-1] ^= 0x01
        with self.assertRaises(PacketError):
            parse_packet(bytes(encoded))

    def test_stream_handles_chunking(self):
        wire = make_packet() + make_packet((1, 2))
        stream = PacketStream()
        frames = []
        for offset in range(0, len(wire), 3):
            frames.extend(stream.feed(wire[offset:offset + 3]))
        self.assertEqual(len(frames), 2)
        self.assertEqual(parse_packet(frames[1])["pulses_us"], [1, 2])


if __name__ == "__main__":
    unittest.main()
