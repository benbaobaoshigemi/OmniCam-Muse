"""Scene front stage of the master LMT baker (reference for master_core.cpp sceneStage).

The LMT input is the ISP's post-CCM linear RGB. The front stage returns scene-linear ProPhoto (D50):
  inverse frame CCM -> white balanced camera RGB -> Adobe Standard ForwardMatrix at the frame CCT (-> XYZ D50)
  -> ProPhoto -> Adobe Standard HueSatMap at the frame CCT (HSV, hue shift / saturation scale / value scale).
Profile data per rear lens: scene.bin (export_scene.py).
"""
import struct
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
SCENE_BIN = HERE.parents[1] / "module/payload/master_color/scene.bin"
PROPHOTO_TO_XYZ = np.array([[0.7976749, 0.1351917, 0.0313534], [0.2880402, 0.7118741, 0.0000857],
                            [0.0, 0.0, 0.8252100]])


class Lens:
    def __init__(self, name, focal, t1, t2, fm1, fm2, hs1, hs2):
        self.name, self.focal, self.t1, self.t2 = name, focal, t1, t2
        self.fm1, self.fm2, self.hs1, self.hs2 = fm1, fm2, hs1, hs2     # hs: [val][hue][sat][3]

    def weight(self, cct):
        """Weight of table 2 at the frame CCT (interpolation in 1/T between the calibration illuminants)."""
        return float(np.clip((1 / cct - 1 / self.t1) / (1 / self.t2 - 1 / self.t1), 0, 1))


def load(path=SCENE_BIN):
    b = Path(path).read_bytes()
    assert b[:8] == b"MCSCENE1"
    version, n = struct.unpack_from("<II", b, 8)
    assert version == 1
    at, lenses = 16, {}
    for _ in range(n):
        length = struct.unpack_from("<I", b, at)[0]; at += 4
        name = b[at:at + length].decode("ascii"); at += length
        focal, t1, t2 = struct.unpack_from("<3f", b, at); at += 12
        f = lambda k: np.frombuffer(b[at:at + 4 * k], "<f4").astype(np.float64)
        fm1 = f(9).reshape(3, 3); at += 36
        fm2 = f(9).reshape(3, 3); at += 36
        hd, sd, vd = struct.unpack_from("<3I", b, at); at += 12
        k = vd * hd * sd * 3
        hs1 = f(k).reshape(vd, hd, sd, 3); at += 4 * k
        hs2 = f(k).reshape(vd, hd, sd, 3); at += 4 * k
        lenses[name] = Lens(name, focal, t1, t2, fm1, fm2, hs1, hs2)
    assert at == len(b)
    return lenses


LENSES = load()


def lens_for(equivalent_focal):
    """Rear lens whose 35 mm equivalent focal length is closest (ratio), or None beyond 25 %."""
    best = min(LENSES.values(), key=lambda l: abs(np.log(equivalent_focal / l.focal)))
    return best.name if abs(np.log(equivalent_focal / best.focal)) <= np.log(1.25) else None


def rgb_to_hsv(c):
    mx, mn = c.max(-1), c.min(-1)
    d = mx - mn
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    ds = np.where(d > 1e-6, d, 1.0)
    h = np.where(mx == r, ((g - b) / ds) % 6.0, np.where(mx == g, (b - r) / ds + 2.0, (r - g) / ds + 4.0))
    h = np.where(d > 1e-6, h, 0.0)
    s = np.where(mx > 1e-6, d / np.where(mx > 1e-6, mx, 1.0), 0.0)
    return h, s, mx


def hsv_to_rgb(h, s, v):
    h = h % 6.0
    c = v * s
    x = c * (1.0 - np.abs(h % 2.0 - 1.0))
    m = v - c
    i = np.floor(h).astype(int)
    r = np.choose(i, [c, x, 0 * c, 0 * c, x, c])
    g = np.choose(i, [x, c, c, x, 0 * c, 0 * c])
    b = np.choose(i, [0 * c, 0 * c, x, c, c, x])
    return np.stack([r + m, g + m, b + m], -1)


def hue_sat(pp, table):
    """Adobe HueSatMap (one value division) applied to linear ProPhoto; table [1][hue][sat][3]."""
    _, hd, sd, _ = table.shape
    h, s, v = rgb_to_hsv(pp)
    hs, ss = h * hd / 6.0, s * (sd - 1)
    h0 = np.floor(hs).astype(int)
    hf = hs - h0
    h1 = np.where(h0 >= hd - 1, 0, h0 + 1)
    h0 = np.minimum(h0, hd - 1)
    s0 = np.minimum(np.floor(ss).astype(int), sd - 2)
    sf = ss - s0
    t = table[0]
    m = ((t[h0, s0] * (1 - hf)[..., None] + t[h1, s0] * hf[..., None]) * (1 - sf)[..., None]
         + (t[h0, s0 + 1] * (1 - hf)[..., None] + t[h1, s0 + 1] * hf[..., None]) * sf[..., None])
    return hsv_to_rgb(h + m[..., 0] * 6.0 / 360.0, np.clip(s * m[..., 1], 0, 1), np.maximum(v * m[..., 2], 0))


def front(linear, ccm, cct, lens):
    """Post-CCM linear RGB (..., 3) -> scene-linear ProPhoto D50 (no exposure scaling)."""
    lens = LENSES[lens]
    w = lens.weight(cct)
    fm = lens.fm1 * (1 - w) + lens.fm2 * w
    pp = np.maximum(np.asarray(linear, np.float64) @ (np.linalg.inv(PROPHOTO_TO_XYZ) @ fm @ np.linalg.inv(np.reshape(ccm, (3, 3)))).T, 0)
    return hue_sat(pp, lens.hs1 * (1 - w) + lens.hs2 * w)
