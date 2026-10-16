"""Hasselblad HNCS (Phocus LUTTable100MP3) baked into X9U master-mode LMT tables.

Per 64^3 node h (the master BasicTone input: Display P3 encoded with the ISP IPE gamma; the
master json gamma is a pass-through, so this is what the LMT receives):
  inverse IPE gamma -> scene-linear P3 (exposure anchored to the stock LMT: the input code the
  stock master renders as SDR grey 0.46 is 18 % grey) -> x K -> XYZ(D65) -> CAT to the scene white
  -> X2D II 100C white-balanced camera RGB (DNG ColorMatrix1) -> Phocus matrix at CCT (-> XYZ D50)
  -> HNCS RGB -> Phocus 2D colour map at CCT -> film curve (type 6, companding 2) -> ^2.2
  -> XYZ D50 -> D65 -> Display P3 display-linear T
  -> HLG OETF per channel: the master SDR base of large areas is sRGB(HLG inverse OETF) per channel
     (HDRTransform dark rendition; fitted on a stock / identity-LMT photo pair, rms 0.05), so the
     target display-linear value is written as its HLG code.
Engine sources: PhotonCamera-bjzhou HncsToneShader / HncsProfile / HncsCameraDomain (Apache-2.0).
Projection sources: X9U libOPAlgoCamHDRTransformCamera.so (trans.cl, tmc_downtransform_pro_*),
tone curve DAT_00524f8c saved as x9u_master_tonecurve.npy.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402
import json
import struct
import zlib
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ASSETS = refs.PHOTONCAMERA / "app/src/main/assets/hncs"
N = 64

# ---- HLG / sRGB exactly as X9U trans.cl ----
A, B, C = 0.17883277, 0.28466892, 0.55991073
L2020 = np.array([0.2627, 0.678, 0.0593])
W601 = np.array([0.299, 0.587, 0.114])


def hlg_inv_oetf(x):
    x = np.clip(x, 0, 1)
    return np.where(x <= 0.5, x * x / 3.0, (np.exp((x - C) / A) + B) / 12.0)


def hlg_oetf(e):
    e = np.clip(e, 0, None)
    return np.where(e <= 1 / 12, np.sqrt(3 * e), A * np.log(np.maximum(12 * e - B, 1e-12)) + C)


def ootf_inv(d):
    y = np.maximum((d * L2020).sum(-1, keepdims=True), 1e-12)
    return d * y ** (-1 / 6)


def srgb_enc(x):
    x = np.clip(x, 0, None)
    return np.where(x < 0.0030412825601275209, x * 12.92,
                    1.0550107189475866 * x ** (1 / 2.4) - 0.0550107189475866)


# ---- colour science ----
P3_TO_XYZ = np.array([[0.4865709, 0.2656677, 0.1982173],
                      [0.2289746, 0.6917385, 0.0792869],
                      [0.0000000, 0.0451134, 1.0439444]])
XYZ_TO_P3 = np.linalg.inv(P3_TO_XYZ)
BRADFORD = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])
D65 = np.array([0.95047, 1.0, 1.08883])
D50 = np.array([0.96422, 1.0, 0.82521])


def cat(src, dst):
    s, d = BRADFORD @ src, BRADFORD @ dst
    return np.linalg.inv(BRADFORD) @ np.diag(d / s) @ BRADFORD


def white_xyz(t):
    """Scene white for a CCT: Planckian locus below 4000 K, CIE daylight locus from 4000 K."""
    if t < 4000:
        x = -0.2661239e9 / t**3 - 0.2343589e6 / t**2 + 0.8776956e3 / t + 0.179910
        y = -1.1063814 * x**3 - 1.34811020 * x**2 + 2.18555832 * x - 0.20219683 if t < 2222 else \
            -0.9549476 * x**3 - 1.37418593 * x**2 + 2.09137015 * x - 0.16748867
    else:
        x = (-4.6070e9 / t**3 + 2.9678e6 / t**2 + 0.09911e3 / t + 0.244063) if t <= 7000 else \
            (-2.0064e9 / t**3 + 1.9018e6 / t**2 + 0.24748e3 / t + 0.237040)
        y = -3.0 * x * x + 2.87 * x - 0.275
    return np.array([x / y, 1.0, (1 - x - y) / y])


HNCS_RGB_TO_XYZ_D50 = np.array([[0.79767, 0.13519, 0.03134], [0.28804, 0.71188, 0.00009], [0.0, 0.0, 0.82491]])
KR, KG, KB = 0.28804, 0.71188, 0.00009
RGB_TO_YCC = np.array([[KR, KG, KB],
                       [-KR / (2 * (1 - KB)), -KG / (2 * (1 - KB)), 0.5],
                       [0.5, -KG / (2 * (1 - KR)), -KB / (2 * (1 - KR))]])
YCC_TO_RGB = np.linalg.inv(RGB_TO_YCC)
HNCS_TO_P3 = XYZ_TO_P3 @ cat(D50, D65) @ HNCS_RGB_TO_XYZ_D50

# ---- HNCS assets ----
_raw = (ASSETS / "LUTTable100MP3.hncs").read_bytes()
assert _raw[:8] == b"HNCSMAP1"
_hl = struct.unpack("<i", _raw[8:12])[0]
HEADER = json.loads(_raw[12:12 + _hl])
_payload = np.frombuffer(zlib.decompress(_raw[12 + _hl:]), "<f4")
assert _payload.size == HEADER["payload"]["floatCount"]
GRID = HEADER["grid"]
GW, GH = GRID["width"], GRID["height"]
VERSION3 = HEADER.get("phocusVersion") == "3.0"


def _values(entry):
    ref = entry["values"]
    return _payload[ref["offset"]:ref["offset"] + ref["count"]].astype(np.float64)


MATRICES = sorted((m["temperature"], _values(m)) for m in HEADER["matrices"])
TABLES = sorted((t["temperature"], _values(t)) for t in HEADER["tables"] if t["intent"] == "standard")
CM = np.array(json.loads((ASSETS / "x2d_ii_100c_calibration.json").read_text())["colorMatrix1"]).reshape(3, 3)
_curve = (ASSETS / "filmcurve_type6_companding2.hcurve").read_bytes()
assert _curve[:8] == b"HNCURV1\0"
FILM = np.frombuffer(_curve[68:], "<u2").astype(np.float64) / 65535.0
assert FILM.size == 65536
TONECURVE = np.load(HERE / "data" / "x9u_master_tonecurve.npy").astype(np.float64)
assert TONECURVE.size == 1024
# com.oplus.ipe.gamma.info of the master session (257 nodes, linear i/256 -> code), median of 54
# preview frames; per-frame spread <= 0.016. Strictly increasing (flat 10-bit steps nudged).
IPE = np.load(HERE / "data" / "x9u_master_ipe_gamma.npy").astype(np.float64)
assert IPE.size == 257 and np.all(np.diff(IPE) > 0)
IPE_X = np.arange(257) / 256.0
# Stock master LMT (LMTLut3) + the per-channel dark-rendition SDR base render code 0.4237 as SDR 0.46.
STOCK_GREY_CODE = 0.4237


def ipe_decode(code):
    """IPE-coded value -> ISP linear; above the last node the last segment's slope continues."""
    code = np.clip(code, 0, None)
    lin = np.interp(code, IPE, IPE_X)
    slope = (IPE_X[-1] - IPE_X[-2]) / (IPE[-1] - IPE[-2])
    return np.where(code > IPE[-1], 1.0 + (code - IPE[-1]) * slope, lin)


GREY_SCENE = float(hlg_inv_oetf(np.array(0.75))) * 0.18   # engine scene unit: 18 % grey
INPUT_SCALE = GREY_SCENE / float(ipe_decode(np.array(STOCK_GREY_CODE)))


def decode_input(code):
    return ipe_decode(code) * INPUT_SCALE


def _interp(anchors, t):
    if t <= anchors[0][0]:
        return anchors[0][1]
    if t >= anchors[-1][0]:
        return anchors[-1][1]
    for (t0, v0), (t1, v1) in zip(anchors, anchors[1:]):
        if t0 <= t <= t1:
            w = (t - t0) / (t1 - t0)
            return v0 * (1 - w) + v1 * w


class Engine:
    def __init__(self, cct):
        self.film = FILM
        self.cct = float(cct)
        tmat = min(max(self.cct, 2000.0), 10000.0 if VERSION3 else 8000.0)
        ttab = min(max(self.cct, 2000.0), 10000.0 if VERSION3 else 6200.0)
        # CXMLLut::CalculateLUT / CalculateMatrix take an integer Kelvin value.
        self.cam_to_xyz_d50 = _interp(MATRICES, float(int(tmat))).reshape(3, 3)
        self.table = _interp(TABLES, float(int(ttab))).reshape(GH, GW, 2)
        white = white_xyz(self.cct)
        p3_to_scene = cat(D65, white) @ P3_TO_XYZ
        p3_to_cam_wb = np.diag(1 / (CM @ white)) @ CM @ p3_to_scene
        self.p3_to_hncs = np.linalg.inv(HNCS_RGB_TO_XYZ_D50) @ self.cam_to_xyz_d50 @ p3_to_cam_wb
        self.k = self._exposure()

    def _colormap(self, rgb):
        ycc = rgb @ RGB_TO_YCC.T
        y = ycc[..., 0]
        ok = y > 1e-6
        safe = np.where(ok, y, 1.0)
        pos = GRID["divFactor"] * ycc[..., 1:] / safe[..., None] - np.array([GRID["cbStart"], GRID["crStart"]])
        pos = np.clip(pos, 0, [GW - 1, GH - 1])
        lo = np.clip(np.floor(pos).astype(int), 0, [GW - 2, GH - 2])
        f = pos - lo
        x0, y0 = lo[..., 0], lo[..., 1]
        t = self.table
        r0 = t[y0, x0] * (1 - f[..., :1]) + t[y0, x0 + 1] * f[..., :1]
        r1 = t[y0 + 1, x0] * (1 - f[..., :1]) + t[y0 + 1, x0 + 1] * f[..., :1]
        mapped = r0 * (1 - f[..., 1:]) + r1 * f[..., 1:]
        chroma = mapped * y[..., None] / GRID["divFactor"]
        out = np.concatenate([y[..., None], chroma], -1) @ YCC_TO_RGB.T
        return np.where(ok[..., None], out, rgb)

    def render(self, scene_p3):
        """Scene-linear P3 (already exposure-scaled) -> display-linear P3, clipped to [0, 1]."""
        hncs = self._colormap(scene_p3 @ self.p3_to_hncs.T)
        index = np.clip(np.floor(np.clip(hncs, 0, None) * 65536.0), 0, 65535).astype(np.int64)
        return np.clip((self.film[index] ** 2.2) @ HNCS_TO_P3.T, 0, 1)

    def _exposure(self):
        # Photographic anchor: scene 18 % grey -> display-linear 0.18 (sRGB ~0.46).
        grey = GREY_SCENE
        lo, hi = 1e-3, 100.0
        for _ in range(60):
            k = (lo * hi) ** 0.5
            lo, hi = (k, hi) if self.render(np.full((1, 3), grey * k))[0, 1] < 0.18 else (lo, k)
        return (lo * hi) ** 0.5


def invert_projection(target):
    """Display-linear P3 target -> master LMT output code (per channel HLG OETF)."""
    return np.clip(hlg_oetf(target), 0, 1)


def bake(cct):
    """Returns the LMT as float32 planes [R,G,B], each indexed [B][G][R] (BasicTone layout)."""
    engine = Engine(cct)
    axis = np.linspace(0, 1, N)
    b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")
    nodes = np.stack([r, g, b], -1).reshape(-1, 3)
    out = invert_projection(engine.render(decode_input(nodes) * engine.k)).reshape(N, N, N, 3)
    return np.stack([out[..., 0], out[..., 1], out[..., 2]]).astype(np.float32), engine
