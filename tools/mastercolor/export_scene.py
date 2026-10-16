"""Packs the scene front stage data of the master LMT baker: one Adobe Standard profile per rear lens.

Output: <out>/scene.bin, little endian
  char[8]    "MCSCENE1"
  u32        1
  u32 n      lenses
  per lens   u32 length + ASCII name, f32 35 mm equivalent focal length (mm),
             f32 temperature of table 1, f32 temperature of table 2 (Kelvin, the DCP calibration illuminants),
             f32[9] ForwardMatrix1, f32[9] ForwardMatrix2 (white balanced camera RGB -> XYZ D50, row major),
             u32 hue, u32 sat, u32 val divisions, f32[val*hue*sat*3] HueSatMap1, same for HueSatMap2
             (hue shift in degrees, saturation scale, value scale)
Usage: python export_scene.py <out directory>
"""
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402

LENSES = [  # name, equivalent focal length, DCP file
    ("uw14", 14.0, "OPPO Find X9 Ultra ultra wide camera 14mm f2.0 Adobe Standard.dcp"),
    ("main23", 23.0, "OPPO Find X9 Ultra wide camera 23mm f1.5 Adobe Standard.dcp"),
    ("tele70", 70.0, "OPPO Find X9 Ultra tele camera 70mm f2.2 Adobe Standard.dcp"),
    ("tele230", 230.0, "OPPO Find X9 Ultra tele camera 230mm f3.5 Adobe Standard.dcp"),
]
ILLUMINANT_KELVIN = {17: 2856.0, 21: 6504.0}


def read_dcp(path):
    """Tags of a DCP file (TIFF structure with the IIRC magic)."""
    b = Path(path).read_bytes()
    assert b[:4] == b"IIRC", path
    off = struct.unpack("<I", b[4:8])[0]
    n = struct.unpack("<H", b[off:off + 2])[0]
    size = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4}
    fmt = {1: "B", 3: "H", 4: "I", 7: "B", 8: "h", 9: "i", 11: "f"}
    tags = {}
    for i in range(n):
        tag, ty, c, v = struct.unpack("<HHII", b[off + 2 + i * 12:off + 14 + i * 12])
        s = size[ty] * c
        d = b[off + 10 + i * 12:off + 14 + i * 12] if s <= 4 else b[v:v + s]
        if ty == 2:
            tags[tag] = d.rstrip(b"\x00").decode("latin1")
        elif ty in (5, 10):
            a = struct.unpack("<%d%s" % (2 * c, "I" if ty == 5 else "i"), d)
            tags[tag] = np.asarray(a[0::2], np.float64) / np.asarray(a[1::2], np.float64)
        else:
            tags[tag] = np.asarray(struct.unpack("<%d%s" % (c, fmt[ty]), d[:s]))
    return tags


def pack_lens(name, focal, path):
    d = read_dcp(path)
    il1, il2 = int(d[50778][0]), int(d[50779][0])
    assert (il1, il2) == (17, 21), (path, il1, il2)
    assert 50964 in d and 50965 in d, f"{path}: ForwardMatrix missing"
    assert not any(t in d for t in (50940, 51107, 51109)), f"{path}: tone curve / encoding / baseline offset not handled"
    hue, sat, val = (int(x) for x in d[50937])
    assert val == 1 and d[50938].size == hue * sat * 3 == d[50939].size, path
    nb = name.encode("ascii")
    b = struct.pack("<I", len(nb)) + nb + struct.pack("<3f", focal, ILLUMINANT_KELVIN[il1], ILLUMINANT_KELVIN[il2])
    b += d[50964].astype("<f4").tobytes() + d[50965].astype("<f4").tobytes()
    b += struct.pack("<3I", hue, sat, val) + d[50938].astype("<f4").tobytes() + d[50939].astype("<f4").tobytes()
    return b


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    blob = b"MCSCENE1" + struct.pack("<II", 1, len(LENSES))
    for name, focal, file in LENSES:
        blob += pack_lens(name, focal, refs.ADOBE_DCP / file)
    (out / "scene.bin").write_bytes(blob)
    print("scene.bin", len(blob))


if __name__ == "__main__":
    main()
