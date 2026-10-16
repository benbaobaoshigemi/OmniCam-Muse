"""Canon EOS R5 Picture Style engine for the master LMT (reference, numpy int64).

Port of PhotonCamera-bjzhou raw/CanonToneAlgorithm.kt (GLSL canonRecipe, canonYuvToRgb,
applyEngineTone) and CanonIccShader.kt / CanonIccProfile.kt, with the assets and fingerprints of
CanonProfile.load (assets/canon/eos_r5). The GLSL is what PhotonCamera renders with; its integer ops
are 32-bit. `overflow` records whether any intermediate leaves Int32 (if never, int64 equals the
GLSL result exactly).
Input: EOS R5 white-balanced camera RGB (EquivalentCameraCalibration: calibration.json ColorMatrix
at the scene white), nominal white 1 -> 14-bit code 16383. Output: linear sRGB.
Engine exposure: RawRenderingEngine.Canon default 0 EV plus canonExposureCompensationEv -0.5 EV.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402
import hashlib
import json
import struct
from pathlib import Path

import numpy as np

ROOT = refs.PHOTONCAMERA / "app/src/main/assets/canon/eos_r5"
STYLES = ["standard", "portrait", "landscape", "neutral", "faithful", "monochrome"]
ENGINE_EV = 0.0 - 0.5          # Canon default EV + canonExposureCompensationEv default
INT32 = 2 ** 31
overflow = {"max": 0}


def _chk(x):
    m = int(np.max(np.abs(x))) if np.size(x) else 0
    overflow["max"] = max(overflow["max"], m)
    return x


def tdiv(a, b):
    """C / GLSL integer division (truncates toward zero)."""
    a = _chk(np.asarray(a, np.int64))
    b = np.asarray(b, np.int64)
    q = np.abs(a) // np.abs(b)
    return np.where((a < 0) ^ (b < 0), -q, q)


def mul(*xs):
    r = np.asarray(xs[0], np.int64)
    for x in xs[1:]:
        r = _chk(r * np.asarray(x, np.int64))
    return r


def load_style(style):
    d = ROOT / style
    manifest = json.loads((d / "gamma-manifest.json").read_text(encoding="utf-8"))

    def verified(name, size):
        b = (d / name).read_bytes()
        assert len(b) == size, name
        assert hashlib.sha256(b).hexdigest() == manifest["files"][name]["sha256"], name
        return b

    ints = lambda b: np.frombuffer(b, "<i4").astype(np.int64)
    words = ints(verified("kernel_words.i32le", 0x168 * 4))
    y = ints(verified("gamma_y.i32le", 0x20000 * 4))
    c = ints(verified("gamma_c.i32le", 0x20000 * 4))
    uv = ints(verified("gamma_uv.i32le", 0x10000 * 4))
    w = np.frombuffer(verified("chroma_weights.u16le", 4096 * 2), "<u2").astype(np.int64)
    atlas = np.concatenate([y, c, uv, w])
    assert atlas.size == 1024 * 324
    icc_style = "standard" if style == "monochrome" else style
    return dict(style=style, words=words, atlas=atlas, icc=parse_icc(icc_style))


# ---------------------------------------------------------------- ICC (CanonIccProfile.parse)
def parse_icc(style):
    pm = json.loads((ROOT / "profiles/profile_manifest.json").read_text(encoding="utf-8"))
    entry = [e for e in pm["profiles"] if e["style"] == style][0]
    src = (ROOT / "profiles" / entry["icc"]).read_bytes()
    dst = (ROOT / "profiles/output_srgb.icc").read_bytes()
    assert hashlib.sha256(src).hexdigest() == entry["sha256"]
    assert hashlib.sha256(dst).hexdigest() == pm["outputProfileSha256"]

    def tag(raw, name):
        n = struct.unpack(">I", raw[128:132])[0]
        for i in range(n):
            o = 132 + 12 * i
            if raw[o:o + 4] == name.encode():
                return struct.unpack(">II", raw[o + 4:o + 12])
        raise KeyError(name)

    fixed = lambda raw, o: struct.unpack(">i", raw[o:o + 4])[0] / 65536.0
    u16 = lambda raw, o, n: np.frombuffer(raw[o:o + 2 * n], ">u2").astype(np.float64) / 65535.0
    start, _ = tag(src, "A2B0")
    assert src[start:start + 4] == b"mft2" and src[start + 8] == 3 and src[start + 9] == 3
    grid = src[start + 10]
    n, m = struct.unpack(">HH", src[start + 48:start + 52])
    cur = start + 52
    inp = u16(src, cur, 3 * n).reshape(3, n); cur += 6 * n
    clut = u16(src, cur, 3 * grid ** 3).reshape(grid, grid, grid, 3); cur += 6 * grid ** 3
    out = u16(src, cur, 3 * m).reshape(3, m)
    cols = []
    for nm in ("rXYZ", "gXYZ", "bXYZ"):
        o, _ = tag(dst, nm)
        cols.append([fixed(dst, o + 8 + 4 * i) for i in range(3)])
    rgb_to_xyz = np.array(cols).T                      # columns r, g, b
    trcs = []
    for nm in ("rTRC", "gTRC", "bTRC"):
        o, _ = tag(dst, nm)
        cnt = struct.unpack(">I", dst[o + 8:o + 12])[0]
        trcs.append(u16(dst, o + 12, cnt))
    white = np.array([fixed(src, 68 + 4 * i) for i in range(3)])
    return dict(grid=grid, inp=inp, clut=clut, out=out, xyz_to_target=np.linalg.inv(rgb_to_xyz), trc=trcs, white=white)


def icc_curve(v, curve):
    n = len(curve)
    p = np.clip(v, 0, 1) * (n - 1)
    i = np.minimum(p.astype(np.int64), n - 2)
    return curve[i] + (curve[i + 1] - curve[i]) * (p - i)


def icc_tetra(rgb, clut, grid):
    p = np.clip(rgb, 0, 1) * (grid - 1)
    base = np.minimum(p.astype(np.int64), grid - 2)
    f = p - base
    fr, fg, fb = f[..., 0], f[..., 1], f[..., 2]
    corner = lambda d: clut[base[..., 0] + d[0], base[..., 1] + d[1], base[..., 2] + d[2]]
    E = lambda *d: np.array(d)
    cases = [
        ((fr >= fg) & (fg >= fb), (1, 0, 0), (1, 1, 0), (fr, fg, fb)),
        ((fr >= fg) & ~(fg >= fb) & (fr >= fb), (1, 0, 0), (1, 0, 1), (fr, fb, fg)),
        ((fr >= fg) & ~(fg >= fb) & ~(fr >= fb), (0, 0, 1), (1, 0, 1), (fb, fr, fg)),
        (~(fr >= fg) & (fr >= fb), (0, 1, 0), (1, 1, 0), (fg, fr, fb)),
        (~(fr >= fg) & ~(fr >= fb) & (fg >= fb), (0, 1, 0), (0, 1, 1), (fg, fb, fr)),
        (~(fr >= fg) & ~(fr >= fb) & ~(fg >= fb), (0, 0, 1), (0, 1, 1), (fb, fg, fr)),
    ]
    out = np.zeros(rgb.shape)
    c000, c111 = corner((0, 0, 0)), corner((1, 1, 1))
    for mask, a, b, (wx, wy, wz) in cases:
        val = (c000 * (1 - wx)[..., None] + corner(a) * (wx - wy)[..., None]
               + corner(b) * (wy - wz)[..., None] + c111 * wz[..., None])
        out = np.where(mask[..., None], val, out)
    return out


def icc_inverse_trc(v, curve):
    n = len(curve)
    idx = np.clip(np.searchsorted(curve, v, side="right") - 1, 0, n - 2)   # largest lo with curve[lo] <= v
    a, b = curve[idx], curve[idx + 1]
    with np.errstate(divide="ignore", invalid="ignore"):
        fr = np.where(b > a, (v - a) / (b - a), 0.0)
    r = (idx + fr) / (n - 1)
    return np.where(v <= curve[0], 0.0, np.where(v >= curve[-1], 1.0, r))


def icc_to_linear_srgb(rgb, icc):
    shaped = np.stack([icc_curve(rgb[..., c], icc["inp"][c]) for c in range(3)], -1)
    pcs = icc_tetra(shaped, icc["clut"], icc["grid"])
    pcs = np.stack([icc_curve(pcs[..., c], icc["out"][c]) for c in range(3)], -1)
    fy = (pcs[..., 0] * (65535.0 / 65280.0) * 100.0 + 16.0) / 116.0
    fx = fy + (pcs[..., 1] * (65535.0 / 256.0) - 128.0) / 500.0
    fz = fy - (pcs[..., 2] * (65535.0 / 256.0) - 128.0) / 200.0
    li = lambda v: np.where(v > 6.0 / 29.0, v ** 3, (v - 4.0 / 29.0) * (108.0 / 841.0))
    xyz = np.stack([li(fx), li(fy), li(fz)], -1) * icc["white"]
    lin = xyz @ icc["xyz_to_target"].T
    enc = np.stack([icc_inverse_trc(lin[..., c], icc["trc"][c]) for c in range(3)], -1)
    return np.where(enc <= 0.04045, enc / 12.92, ((enc + 0.055) / 1.055) ** 2.4)


# ---------------------------------------------------------------- integer recipe (GLSL canonRecipe)
def recipe(rgb, s):
    W = s["words"]
    A = s["atlas"]
    w = lambda i: W[i]
    look = lambda i: A[np.asarray(i, np.int64)]
    cw = lambda i: W[0x122 + i]
    red, green, blue = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    scale, divisor, pre_limit = w(2), w(3), w(0x84)
    b = np.minimum(tdiv(mul(blue, 2, scale), divisor), pre_limit)
    g = np.minimum(tdiv(mul(green, 2, scale), divisor), pre_limit)
    r = np.minimum(tdiv(mul(red, 2, scale), divisor), pre_limit)
    widx = lambda v, lim: np.minimum(tdiv(mul(v, w(0x76), 4), 4096), lim)
    ri, gi, bi = widx(r, w(0x70)), widx(g, w(0x72)), widx(b, w(0x74))
    li = np.clip(tdiv(gi + bi * 2 + ri, 128), 0, 1023)
    di = tdiv(gi - ri, 32)
    dj = np.clip(tdiv(gi - bi, 32), 0, 1023)
    b = np.minimum(b, w(0x6e))
    g = np.minimum(g, w(0x6c))
    r = np.minimum(r, w(0x6a))
    aux = tdiv(mul(red, 0x4c8) + mul(green, 0x964) + mul(blue, 0x1d4), 4096)
    iu = tdiv(mul(blue, 0x800) - mul(green, 0x54c) - mul(red, 0x2b3), 4096)
    iv = tdiv(mul(red, 0x800) - mul(green, 0x6b2) - mul(blue, 0x14d), 4096)
    rb = tdiv(mul(aux, 0xfff) + 0x800 + mul(iu, 0x1c56), 4096) * 2
    rg = tdiv(mul(aux, 0x1002) - mul(iv, 0xb6d) - mul(iu, 0x584) + 0x800, 4096) * 2
    rr = tdiv((mul(iv, 0xb37) + mul(aux, 0x800) - iu) * 2 + 0x800, 4096) * 2

    def cy(v):
        code = tdiv(mul(v, w(2)), w(3))
        code = np.clip(np.minimum(code, 131071), 0, w(0x68))
        return look(code)

    y = np.clip(tdiv(mul(cy(rr), w(0x2a)) + mul(cy(rg), w(0x2b)) + mul(cy(rb), w(0x2c)) + 0x800, 4096), 0, 65535)
    fd = np.clip(tdiv(mul(w(0x12), b - g) + mul(w(0x10), r - g), 1024), -131072, 131072)
    sd = np.clip(tdiv(mul(w(0x1a), b - g) + mul(w(0x18), r - g), 1024), -131072, 131072)
    dbase = np.where(di >= 0, 329728, 327680)
    weight = np.minimum(look(328704 + li), look(dbase + np.minimum(np.abs(di), 1023)))
    weight = np.minimum(weight, look(330752 + dj))
    fgain = np.where(fd > 0, w(0x25), w(0x26))
    sgain = np.where(sd > 0, w(0x23), w(0x24))
    foff = np.minimum(tdiv(mul(tdiv(mul(weight, fd), 1024), fgain), 128), w(0x88))
    soff = np.minimum(tdiv(mul(tdiv(mul(weight, sd), 1024), sgain), 128), w(0x88))
    base = tdiv(mul(w(0x1d), g) + mul(w(0x1e), b) + mul(w(0x1c), r), 1024)
    c_first = base + foff
    c_third = base + soff
    c_second = tdiv(mul(base * 9 - c_third, 0x600) - mul(c_first, 0x1000), 0x2000)

    def cc(v, mono):
        code = np.clip(v, -131071, 131071)
        if w(0xf) == 1:
            code = code + mono
        neg = 2 * look(131072) - look(131072 - np.minimum(code, 0))
        pos = look(131072 + np.minimum(np.maximum(code, 0), w(0x86)))
        return np.where(code < 0, neg, pos)

    c1, c2, c3 = cc(c_first, w(0x2d)), cc(c_second, w(0x2e)), cc(c_third, w(0x2f))
    d1 = np.clip(tdiv(mul(c3, 0x800) - mul(c2, 0x550) - mul(c1, 0x2b0), 4096), -32768, 32767)
    d2 = np.clip(tdiv(mul(c1, 0x800) - mul(c2, 0x6b0) - mul(c3, 0x150), 4096), -32768, 32767)
    dom = np.where(np.abs(d1) < np.abs(d2), d2, d1)
    gain = look(262144 + dom + 32768)
    first = tdiv(mul(gain, d1), 256)
    second = tdiv(mul(gain, d2), 256)
    k1 = w(0x21) + np.where(second <= 0, w(0x1f), 0)
    k2 = w(0x22) + np.where(first <= 0, w(0x20), 0)
    mf = np.clip(first + tdiv(mul(k1, second), 8192), -32768, 32767)
    ms = np.clip(second + tdiv(mul(k2, first), 8192), -32768, 32767)
    w163, w164 = int(w(0x163)), int(w(0x164))
    lo16 = lambda x: ((x & 0xffff) ^ 0x8000) - 0x8000
    cwt = mul(lo16(w163), c1) + mul(w163 >> 16, c2) + mul(lo16(w164), c3)
    color_luma = (cwt + ((cwt >> 31) & 63)) >> 8
    u, v = correct_chroma(mf >> 1, ms >> 1, color_luma, cw)
    return np.stack([(y >> 2) * 4, u, v], -1)


def _sector(code, first, second):
    quadrant = (code >> 5) & 3
    u = ~first if quadrant < 2 else first
    v = ~second if quadrant in (1, 2) else second
    angle = code & 63
    a = 64 - angle if angle > 31 else angle
    b = angle - 32 if angle > 31 else 32 - angle
    value = mul(a, u) + mul(b, v)
    mode = (code >> 8) & 7
    res = {0: (value * 3) >> 6, 1: value >> 4, 2: (value * 3) >> 5, 3: value >> 3, 4: (value * 3) >> 4,
           5: (((value * 3) >> 1) + value * 3) >> 4, 6: value >> 1}.get(mode, value)
    return np.clip(res, 0, 1023)


def _radial(value, code):
    mode = (code >> 11) & 7
    res = {0: (value * 3) >> 2, 1: value, 2: (value >> 2) + value, 3: (value * 3) >> 1, 4: value * 2,
           5: value * 3, 6: ((value * 3) >> 1) + value * 3}.get(mode, value * 8)
    return np.minimum(1023, res)


def _shift(value, amount):
    return value >> (-amount & 31) if amount < 0 else value << (amount & 31)


def correct_chroma(u, v, luma, cw):
    li = luma >> 4
    delta = li - cw(8)
    threshold = np.full(np.shape(u), int(cw(3)) & 2047, np.int64)
    if cw(4) != 0:
        threshold = np.minimum(2047, np.where(delta < 0, (mul(cw(5), li) >> 7) + threshold,
                                              (mul(cw(6), np.clip(delta, 0, 1023)) >> 7) + cw(7)))
    sa = _sector(int(cw(21)), u >> 4, v >> 4)
    sb = _sector(int(cw(22)), u >> 4, v >> 4)
    sw = np.maximum(sa, sb) if (int(cw(21)) & 128) != 0 else np.minimum(sa, sb)
    ru = np.clip(_shift(u, int(cw(25)) - 4), -1024, 1023)
    rv = np.clip(_shift(v, int(cw(25)) - 4), -1024, 1023)
    ru = np.where(ru < 0, ~ru, ru)
    rv = np.where(rv < 0, ~rv, rv)
    radius = np.maximum(ru + rv * 2, rv + ru * 2)
    inner = _radial(np.clip(radius - threshold * 2, 0, 2047), int(cw(23)))
    outer = _radial(np.clip((int(cw(24)) & 2047) * 2 - radius, 0, 2047), int(cw(24)))
    light = np.clip(_shift(luma, int(cw(28)) - 4), 0, 1023)
    lower = np.minimum(np.clip(light * 2 - (int(cw(26)) & 2047), 0, 1023) << ((int(cw(26)) >> 11) & 3), 1023)
    upper = np.minimum(np.clip((int(cw(27)) & 2047) - light * 2, 0, 1023) << ((int(cw(27)) >> 11) & 3), 1023)
    weight = np.minimum(np.minimum(sw, np.minimum(inner, outer)), np.minimum(lower, upper))
    gu = mul(cw(49), weight) >> 7
    gv = mul(cw(59), weight) >> 7
    ou = np.clip(u + (mul(u, gu) >> 10), -16384, 16383) * 2
    ov = np.clip(v + (mul(v, gv) >> 10), -16384, 16383) * 2
    return ou, ov


def yuv_to_rgb(yuv):
    y, u, v = yuv[..., 0], yuv[..., 1], yuv[..., 2]
    return np.clip(np.stack([
        tdiv((mul(v, 0xb37) + mul(y, 0x800) - u) * 2 + 0x800, 4096),
        tdiv(mul(y, 0x1002) - mul(v, 0xb6d) - mul(u, 0x584) + 0x800, 4096),
        tdiv(mul(y, 0xfff) + mul(u, 0x1c56) + 0x800, 4096)], -1), 0, 65535)


def canon(camera, s):
    """applyEngineTone: R5 WB camera RGB (white 1) -> linear sRGB."""
    code = np.clip(camera * 16383.0, 0.0, 65535.0).astype(np.int64)   # GLSL ivec3(): truncation
    enc = yuv_to_rgb(recipe(code, s)) / 65535.0
    if s["words"][0xf] == 1:
        return np.where(enc < 0.04045, enc / 12.92, ((enc + 0.055) / 1.055) ** 2.4)
    return icc_to_linear_srgb(enc, s["icc"])
