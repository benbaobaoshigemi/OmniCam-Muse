"""Packs the data of the on-device HNCS master LMT baker (app/src/main/cpp/master_core.cpp).

Output: <out>/hncs_engine.bin, little endian, format 4
  char[8]    "HNCSENG1"
  u32        4
  f32        K (engine exposure: GREY_SCENE -> display-linear 0.18), f32 GREY_SCENE
  i32 i32    colour map grid width, height
  f32 x3     cbStart, crStart, divFactor
  f32 x2     matrix CCT clamp max, table CCT clamp max
  u32 n      Phocus matrices: n x (f32 temperature, f32[9])
  u32 n      standard-intent colour maps: n x (f32 temperature, f32[h*w*2])
  f32[9]     X2D II 100C ColorMatrix1 (XYZ -> camera)
  u16[65536] film curve (type 6, companding 2)
  f32[1024]  HDRTransform master toneCurve (DAT_00524f8c)
  f32[65536] hybridraw built-in proproc gamma, code (already / 1023)
  f32[65536] proproc_gamma_table.bin, code (already / 1023)
  f32        stock master grey code, f32 proproc cap
  f32[64^3*3] luxIndex > 280 encoding inverse at the LMT nodes, [B][G][R][3] (lux_blend_inverse.py)
Optional reference bakes for cross-checks: <out>/ref_*.f32 (not shipped).
"""
import struct
import sys
from pathlib import Path

import numpy as np

import hncs_engine as E
import master_lmt as M

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)

ks = {cct: E.Engine(cct).k for cct in (2000, 3200, 5500, 8000, 10000)}
k = ks[5500]
assert all(abs(v - k) / k < 1e-4 for v in ks.values()), ks

blob = bytearray(b"HNCSENG1")
blob += struct.pack("<I", 4)
blob += struct.pack("<ff", k, E.GREY_SCENE)
blob += struct.pack("<ii", E.GW, E.GH)
blob += struct.pack("<fff", E.GRID["cbStart"], E.GRID["crStart"], E.GRID["divFactor"])
blob += struct.pack("<ff", 10000.0 if E.VERSION3 else 8000.0, 10000.0 if E.VERSION3 else 6200.0)
blob += struct.pack("<I", len(E.MATRICES))
for t, v in E.MATRICES:
    blob += struct.pack("<f", t) + v.astype("<f4").tobytes()
blob += struct.pack("<I", len(E.TABLES))
for t, v in E.TABLES:
    blob += struct.pack("<f", t) + v.astype("<f4").tobytes()
blob += E.CM.astype("<f4").tobytes()
blob += np.round(E.FILM * 65535).astype("<u2").tobytes()
blob += M.TONECURVE.astype("<f4").tobytes()
blob += M.BUILTIN.astype("<f4").tobytes()
blob += M.PROPROC.astype("<f4").tobytes()
blob += struct.pack("<ff", M.GREY_CODE, M.CAP)
blob += M.LUX_INVERSE.astype("<f4").tobytes()
(out / "hncs_engine.bin").write_bytes(bytes(blob))
print("hncs_engine.bin", len(blob), "bytes, K =", k)

if len(sys.argv) > 2:
    ref = Path(sys.argv[2])
    ref.mkdir(parents=True, exist_ok=True)
    ccm = [1.0703125, -0.1328125, 0.0625, -0.09375, 1.109375, -0.03125, 0.046875, -0.5703125, 1.5234375]
    cases = [("main", 5000, False), ("main", 3600, True), ("thumbnail", 5000, False), ("preview", 4200, False)]
    for path, cct, lux in cases:
        lut = M.bake(path, cct, ccm, lux, E.IPE * 1023 if path == "preview" else None)
        name = f"ref_{path}_{cct}_{int(lux)}.f32"
        (ref / name).write_bytes(lut.astype("<f4").tobytes())
        print("ref", name)
