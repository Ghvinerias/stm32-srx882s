#!/usr/bin/env python3
"""Reference encoder for the OTA wire protocol (see OTA_PROTOCOL.md).

`python3 ota_ref.py vectors > OTA_VECTORS.json` regenerates the golden vectors
that the firmware and the Android app must both reproduce byte-for-byte.
"""
import hashlib, hmac, json, struct, sys

VERSION = 1
T_OTA_CMD, T_OTA_RESP = 6, 7
OP_HELLO, OP_BEGIN, OP_CHUNK, OP_END, OP_ABORT, OP_BOOTLOADER = 1, 2, 3, 4, 5, 6
MAX_CHUNK = 192


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def cobs_encode(data: bytes) -> bytes:
    out = bytearray([0]); code_i = 0; code = 1
    for b in data:
        if b == 0:
            out[code_i] = code; code = 1; code_i = len(out); out.append(0)
        else:
            out.append(b); code += 1
            if code == 0xFF:
                out[code_i] = code; code = 1; code_i = len(out); out.append(0)
    out[code_i] = code
    return bytes(out)


def with_crc(raw: bytes) -> bytes:
    return raw + struct.pack("<H", crc16(raw))


def wire(raw: bytes) -> bytes:
    return cobs_encode(raw) + b"\x00"


def mac(secret: bytes, label: bytes, *parts: bytes) -> bytes:
    return hmac.new(secret, label + b"".join(parts), hashlib.sha256).digest()


def cmd(op: int, payload: bytes = b"") -> bytes:
    return with_crc(bytes([VERSION, T_OTA_CMD, op]) + payload)


def resp(op: int, status: int, payload: bytes = b"") -> bytes:
    return with_crc(bytes([VERSION, T_OTA_RESP, op, status]) + payload)


def begin(secret, nonce, image):
    size = struct.pack("<I", len(image)); sha = hashlib.sha256(image).digest()
    return cmd(OP_BEGIN, size + sha + mac(secret, b"OTA-BEGIN", nonce, size, sha))


def chunk(offset, data):
    assert 0 < len(data) <= MAX_CHUNK
    return cmd(OP_CHUNK, struct.pack("<I", offset) + data)


def bootloader(secret, nonce):
    return cmd(OP_BOOTLOADER, mac(secret, b"OTA-DL", nonce))


def vectors():
    secret = bytes(range(32)); nonce = bytes(range(0xA0, 0xB0))
    image = bytes((i * 7 + 3) & 0xFF for i in range(1000))  # 1000 B -> 6 chunks
    caps = struct.pack("<BHI", 1, MAX_CHUNK, 0x1F0000)
    def pk(raw): return {"raw": raw.hex(), "wire": wire(raw).hex()}
    chunks = [{"offset": o, **pk(chunk(o, image[o:o + MAX_CHUNK]))}
              for o in range(0, len(image), MAX_CHUNK)]
    return {
        "secret_hex": secret.hex(), "nonce_hex": nonce.hex(),
        "image_len": len(image), "image_rule": "image[i] = (i*7+3) & 0xFF",
        "image_sha256": hashlib.sha256(image).hexdigest(),
        "max_chunk": MAX_CHUNK,
        "hello": pk(cmd(OP_HELLO)),
        "hello_resp_ok": pk(resp(OP_HELLO, 0, caps + nonce)),
        "begin": pk(begin(secret, nonce, image)),
        "begin_resp_ok": pk(resp(OP_BEGIN, 0)),
        "chunks": chunks,
        "chunk_resp_first": pk(resp(OP_CHUNK, 0, struct.pack("<I", MAX_CHUNK))),
        "chunk_resp_bad_offset": pk(resp(OP_CHUNK, 7, struct.pack("<I", 192))),
        "end": pk(cmd(OP_END)), "end_resp_ok": pk(resp(OP_END, 0)),
        "abort": pk(cmd(OP_ABORT)),
        "bootloader": pk(bootloader(secret, nonce)),
        "bad_mac_begin": pk(begin(bytes(32), nonce, image)),
    }


if __name__ == "__main__":
    if sys.argv[1:] == ["vectors"]:
        print(json.dumps(vectors(), indent=1))
    else:
        sys.exit(__doc__)
