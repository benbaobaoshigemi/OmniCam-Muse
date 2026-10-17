"""Decrypt one of the OPPO camera's encrypted config containers (encrypt 4 of them on PKJ110).

The containers start with the magic ``01 01``, carry 4 bytes of header, AES-ECB ciphertext, and a
4-byte trailer ending in ``FF``. The key lives in the stock ``libOplusSecurity.so``.

This is the same scheme ``tools/patch_25mp.py`` uses to read the X9 Ultra stock configs; it is
exposed here on its own so a PKJ110 file can be inspected without touching a patch script.

Usage:
    python tools/decrypt_oplus_config.py <encrypted-file> <libOplusSecurity.so> [-o out.json]

Notes
-----
* Pull both inputs from the phone:
      adb pull /odm/etc/camera/config/oplus_camera_preview_decision_config.json
      adb pull /odm/lib64/libOplusSecurity.so
* Plaintext output is the camera's own JSON; it parses with plain ``json`` (2-space style).
* The encrypted files on PKJ110 are ``oplus_camera_config`` (70,696 B),
  ``oplus_camera_aps_config`` (65,400 B) and ``oplus_camera_preview_decision_config.json``
  (360,920 B); ``oplus_preview_decision_params.json`` is already plaintext.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

MAGIC = b"\x01\x01"
KEY_OFFSET = 0x7AB
KEY_LENGTH = 32
KEY_XOR = 0x3F


def config_key(security_lib: Path) -> bytes:
    """The camera config AES key: 32 bytes at 0x7AB in libOplusSecurity.so, xor 0x3F, every other byte."""
    blob = security_lib.read_bytes()
    if len(blob) < KEY_OFFSET + KEY_LENGTH:
        raise ValueError(f"{security_lib}: too small to carry the key at 0x{KEY_OFFSET:X}")
    return bytes(value ^ KEY_XOR for value in blob[KEY_OFFSET:KEY_OFFSET + KEY_LENGTH])[::2]


def decrypt(data: bytes, key: bytes) -> bytes:
    """Plaintext of an encrypted container, or the input unchanged when it is not encrypted."""
    if data[:2] != MAGIC:
        return data
    if data[-1] != 0xFF:
        raise ValueError("unexpected container envelope (trailer is not 0xFF)")
    return unpad(AES.new(key, AES.MODE_ECB).decrypt(data[4:-4]), 16)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("encrypted", type=Path, help="config file pulled from /odm/etc/camera/config")
    parser.add_argument("security", type=Path, help="libOplusSecurity.so pulled from /odm/lib64")
    parser.add_argument("-o", "--out", type=Path, help="write here instead of stdout")
    args = parser.parse_args(argv)

    key = config_key(args.security)
    plain = decrypt(args.encrypted.read_bytes(), key)
    if plain[:1] != b"{":
        print(f"warning: plaintext does not start with '{{' (got {plain[:8]!r})", file=sys.stderr)
    if args.out:
        args.out.write_bytes(plain)
        print(f"{args.out}: {len(plain)} bytes")
    else:
        sys.stdout.buffer.write(plain)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
