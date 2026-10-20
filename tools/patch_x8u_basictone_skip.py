"""X8U (PKJ110) BasicTone "all-zero skip" NOP.

Root cause of the POP SDR capture producing full-sensor RAW in the main JPEG:

POP capture runs `aps_algo_hybridraw`, which feeds BasicTone a 10-bit P010 buffer.
BasicTone's job on that path is to convert P010 -> 8-bit before the encoder. Its
precheck in basicToneProcessV1 reads the four master sliders (light sense / natural
saturation / colour temperature / tint) via rectifyRefRegister into w20..w23, ORs them
together and, when they are ALL ZERO (the user never touched the sliders), `cbz` jumps
to a face-rectify fallback that skips the real BasicTone conversion entirely. The P010
then goes straight to the encoder, which serialises it as raw pixels in front of the
real JPEG -> gallery thumbnail shows, main image is blank.

On X9U the identical branch was NOP-ed at libAlgoInterface.so 0x2040688. X8U's 60 MB
stock so was never patched because the decision-tree injection (door #2) was deemed
"enough" -- it is not: it brings the basic_tone node into the chain, which then hits
this all-zero skip. This is the missing door #4 (see docs/MODULE.md section 32/34).

The X8U stock libAlgoInterface.so is NOT in module/system/ (metamount would overlay it
unconditionally) and is intentionally NOT bound on PKJ110. It is patched in place in
module/payload_pkj110/lib64/libAlgoInterface.so and bound by post-mount.sh's PKJ110
branch.

Function layout (X8U stock so, vaddr == file offset for .text):
  basicToneProcessV1           0x1e7a954  (0x318)
  <anonymous body>             0x1e7ac6c  reads the four sliders + all-zero skip
  basicToneLmtProcessV1        0x1e7ae88

Patch site:
  0x1e7ae00  cbz w8, 0x1e7ae3c   (0x340001e8)  ->  nop  (0xd503201f)
"""
from __future__ import annotations

import struct
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "port-x8u/_pristine/libAlgoInterface_x8u_stock.so"
DST = Path(__file__).resolve().parent.parent / "module/payload_pkj110/lib64/libAlgoInterface.so"

SKIP = 0x1E7AE00
CBZ_ORIGINAL = 0x340001E8  # cbz w8, +0x3c
NOP = 0xD503201F


def main() -> None:
    data = bytearray(SRC.read_bytes())
    word = struct.unpack_from("<I", data, SKIP)[0]
    if word == NOP:
        print("already patched")
    elif word != CBZ_ORIGINAL:
        raise SystemExit(f"unexpected instruction at {SKIP:#x}: {word:#010x}")
    else:
        struct.pack_into("<I", data, SKIP, NOP)
        DST.parent.mkdir(parents=True, exist_ok=True)
        DST.write_bytes(data)
        print(f"patched {DST} (nop at {SKIP:#x})")
    print(f"size={len(data)} md5={__import__('hashlib').md5(data).hexdigest()}")


if __name__ == "__main__":
    main()
