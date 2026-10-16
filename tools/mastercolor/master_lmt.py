"""Reference baker for the master-mode HNCS LMT (PMA110, camera 7.006.103, ODM 17.0.0.105).

Every stage below is a node of the stock master pipeline, verified from library code and
bit-exact dumps (see the memory note "master-lmt-input-encoding"):

LMT input (per path)
  main       hybridraw ProProcCl: code = builtin_gamma[ccm . awb . cam * 65535] / 1023,
             capped at 3178/4095 (libOPAlgoCamHybridRaw .data 0x1365ffc; used because master
             frames carry no com.oplus.bps.gamma.info). If trunc(luxindex) > 280 the kernel blends a
             pow(1/2.2) chroma (proproc_rgb_p010_pixel_u16); its inverse at the LMT nodes is frame
             independent and precomputed by lux_blend_inverse.py.
  thumbnail  ProProcLiteCl: code = proproc_gamma_table.bin[lin * 65535] / 1023, pure lookup.
  preview    same encoding as main without the lux blend (the stock preview reads the same LMTLut;
             com.oplus.ipe.gamma.info acts on ISP tone-mapped data and is not a scene-linear transfer).
  YUV is BT.601 full on both sides of BasicTone, so the LMT sees these RGB codes directly.
Colour     scene_front.front: linear (post CCM) -> inverse CCM -> white-balanced camera RGB -> Adobe Standard
           ForwardMatrix and HueSatMap of the lens at the frame CCT -> scene-linear ProPhoto D50.
Exposure   the code 0.3613 (stock master mid grey through the real HDRTransform) is 18 % grey.
Engine     Hasselblad HNCS (PhotonCamera HncsToneShader port, hncs_engine.Engine).
Output     the LMT output h feeds HDRTransform master: luma = BT.601 of the per-channel toneCurve,
           chroma ratio = sRGB(HLG->linear(h)) / its luma. h = HLG_OETF(s * T) with s solved so the
           toneCurve luma equals the target luma (the ratio of s*T equals that of T).
There is no ODT in the master LMT pass (ODT_Photo equals the HDRT toneCurve; HDRT is enabled).
"""
from pathlib import Path

import numpy as np

import hncs_engine as E
import looks
import scene_front as S

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
N = 64

BUILTIN = np.load(DATA / "x9u_proproc_builtin_gamma.npy").astype(np.float64) / 1023.0   # 65536
PROPROC = np.frombuffer((DATA / "proproc_gamma_table.bin").read_bytes(), "<u2").astype(np.float64) / 1023.0
TONECURVE = E.TONECURVE                    # HDRTransform master luma curve, 1024 entries
CAP = 3178.0 / 4095.0                      # proproc gammaVec clamp
X65536 = np.arange(65536) / 65535.0
GREY_CODE = 0.3613                         # stock master mid grey (HDRT toneCurve path)
LUX_BLEND_THRESHOLD = 280                  # kernel: (luxIndex > 280) ? mix(...) : gammaOri

HLG_A, HLG_B, HLG_C = 0.17883277, 0.28466892, 0.55991073
W601 = np.array([0.299, 0.587, 0.114])
P3_LUMA = E.P3_TO_XYZ[1]
P3_TO_XYZ = E.P3_TO_XYZ
XYZ_TO_P3 = E.XYZ_TO_P3
PP_TO_P3 = XYZ_TO_P3 @ E.cat(E.D50, E.D65) @ S.PROPHOTO_TO_XYZ


def hlg_inv(x):
    x = np.clip(x, 0, 1)
    return np.where(x <= 0.5, x * x / 3.0, (np.exp((x - HLG_C) / HLG_A) + HLG_B) / 12.0)


def hlg_oetf(e):
    e = np.clip(e, 0, None)
    return np.where(e <= 1 / 12, np.sqrt(3 * e), HLG_A * np.log(np.maximum(12 * e - HLG_B, 1e-12)) + HLG_C)


def srgb_enc(x):
    x = np.clip(x, 0, None)
    return np.where(x < 0.0030412825601275209, 12.92 * x, 1.0550107189475866 * x ** (1 / 2.4) - 0.0550107189475866)


# ---------------------------------------------------------------- LMT input encodings
def lookup(table, lin):
    """gammaTable[convert_int(lin * 65535)] as in the kernels (truncating index)."""
    return table[np.clip((np.clip(lin, 0, 1) * 65535.0).astype(np.int64), 0, 65535)]


def encode_main(lin, lux_blend):
    """proproc_rgb_p010_pixel_u16 gamma stage; lin = clamped CCM output (per channel)."""
    lin = np.clip(lin, 0, 1)
    ori = lookup(BUILTIN, lin)
    if not lux_blend:
        return np.minimum(ori, CAP)
    s = np.sort(lin, -1)
    mn, md = s[..., 0], s[..., 1]
    d2 = 1 - np.clip(0.01 - lin, 0, 0.01) * 90
    d2 = np.clip(d2 ** 3, 0, 1)
    wgt = (np.clip(md - mn, 0, 1)[..., None] + 1e-9) ** d2
    gray = lin @ W601
    g1 = lookup(BUILTIN, gray)
    gv = np.clip(lin ** (1 / 2.2) * (g1 / np.clip(gray ** (1 / 2.2), 1e-9, 1))[..., None], 0, 1)
    return np.minimum(ori * (1 - wgt) + gv * wgt, CAP)


def invert_table(table, code):
    return np.interp(np.clip(code, table[0], table[-1]), table, X65536)


LUX_INVERSE = np.load(DATA / "x9u_lux_blend_inverse.npy").astype(np.float64)   # [B][G][R][3]


def trilinear(table, code):
    """Trilinear lookup of a [B][G][R][3] node table at codes (..., 3) in [0, 1]."""
    n = table.shape[0]
    p = np.clip(code, 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(p).astype(np.int64), n - 2)
    f = p - i0
    out = np.zeros(code.shape)
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = ((f[..., 0] if dr else 1 - f[..., 0]) * (f[..., 1] if dg else 1 - f[..., 1])
                     * (f[..., 2] if db else 1 - f[..., 2]))
                out += w[..., None] * table[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out


def decode_main(code, lux_blend):
    if not lux_blend:
        return invert_table(BUILTIN, np.minimum(code, CAP))
    # Frame-independent inverse of the blended encoding at the LMT nodes (lux_blend_inverse.py).
    return trilinear(LUX_INVERSE, code)


def decode_thumbnail(code):
    return invert_table(PROPROC, code)


def decode_preview(code, ipe_gamma):
    """ipe_gamma: com.oplus.ipe.gamma.info, 257 values (code * 1023) at linear i/256."""
    t = np.maximum.accumulate(np.asarray(ipe_gamma, np.float64) / 1023.0)
    x = np.arange(257) / 256.0
    return np.interp(np.clip(code, t[0], t[-1]), t, x)


# ---------------------------------------------------------------- colour
def cct_white(t):
    return E.white_xyz(t)


def scene_p3(linear, ccm, cct, lens):
    """Post-CCM linear -> D65 P3 scene linear (the HNCS scene domain)."""
    return S.front(linear, ccm, cct, lens) @ PP_TO_P3.T


# ---------------------------------------------------------------- output
def invert_output(T):
    """Display-linear P3 target -> LMT output h through the verified HDRT master model."""
    T = np.clip(T, 0, 1)
    target = srgb_enc(T) @ W601
    lo = np.zeros(T.shape[:-1])
    hi = np.full(T.shape[:-1], 8.0)
    for _ in range(40):
        s = (lo + hi) / 2
        h = np.clip(hlg_oetf(s[..., None] * T), 0, 1)
        y = TONECURVE[np.round(h * 1023).astype(np.int64)] @ W601
        up = y < target
        lo = np.where(up, s, lo)
        hi = np.where(up, hi, s)
    return np.clip(hlg_oetf(((lo + hi) / 2)[..., None] * T), 0, 1)


def hdrt(h):
    """HDRTransform master (pointwise part): LMT output -> SDR display RGB (sRGB-coded, P3)."""
    h = np.clip(h, 0, 1)
    dark = srgb_enc(np.clip(hlg_inv(h), 0, 1))
    dy = np.clip(dark @ W601, 0, 1)
    gain = dark / (dy[..., None] + 1e-4)
    return np.clip((TONECURVE[np.round(h * 1023).astype(np.int64)] @ W601)[..., None] * gain, 0, 1)


# ---------------------------------------------------------------- bake
def decode(code, path, lux_blend=False, ipe_gamma=None):
    if path == "main":
        return decode_main(code, lux_blend)
    if path == "thumbnail":
        return decode_thumbnail(code)
    if path == "preview":
        # Preview shares the stock LMTLut with capture: its codes carry the capture encoding.
        return decode_main(code, False)
    raise ValueError(path)


def render(code, path, cct, ccm, lux_blend=False, ipe_gamma=None, exposure_ev=0.0, engine=None, lens=None):
    """LMT input codes (..., 3) -> LMT output codes (..., 3)."""
    grey_lin = decode(np.full((1, 3), GREY_CODE), path, lux_blend, ipe_gamma)
    grey_y = float(scene_p3(grey_lin, ccm, cct, lens)[0] @ P3_LUMA)
    engine = E.Engine(cct) if engine is None else engine
    scene = scene_p3(decode(code, path, lux_blend, ipe_gamma), ccm, cct, lens) * (E.GREY_SCENE / grey_y) * 2.0 ** exposure_ev
    return invert_output(engine.render(scene * engine.k))


def render_look(code, path, cct, ccm, look, lux_blend=False, exposure_ev=0.0, lens=None, param=None):
    """Lumix / film / basic (looks.py): scene ProPhoto D50 with 18 % grey = 0.18 -> look -> HDRT inverse."""
    grey = float(S.front(decode(np.full((1, 3), GREY_CODE), path, lux_blend), ccm, cct, lens)[0] @ S.PROPHOTO_TO_XYZ[1])
    scene = S.front(decode(code, path, lux_blend), ccm, cct, lens) * (0.18 / grey) * 2.0 ** exposure_ev
    return invert_output(look.render(scene, cct) if param is None else look.render(scene, cct, param))


def bake(path, cct, ccm, lux_blend=False, ipe_gamma=None, look=None, exposure_ev=0.0, lens=None):
    """Planar float32 [R, G, B], each [B][G][R] (BasicTone / CombineLut LMTLut layout).
    look: None for HNCS, else a looks.Packed / looks.Lumix / looks.Film."""
    axis = np.linspace(0, 1, N)
    b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")
    nodes = np.stack([r, g, b], -1).reshape(-1, 3)
    out = (render(nodes, path, cct, ccm, lux_blend, ipe_gamma, exposure_ev, lens=lens) if look is None
           else render_look(nodes, path, cct, ccm, look, lux_blend, exposure_ev, lens)).reshape(N, N, N, 3)
    return np.stack([out[..., 0], out[..., 1], out[..., 2]]).astype(np.float32)
