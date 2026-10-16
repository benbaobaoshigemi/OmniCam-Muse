"""AgX, darktable sigmoid and darktable filmic for the master LMT (reference, numpy).

Line-by-line ports of PhotonCamera-bjzhou raw/AgXToneAlgorithm.kt, DarktableSigmoidToneAlgorithm.kt,
DarktableFilmicToneAlgorithm.kt (GLSL applyEngineTone) and RawToneMappingGl.kt (filmic spline
uniforms), with the default RawToneMappingParameters. Engine contract (RawEngineTonePass): input is
linear scene RGB in the engine working space (BT.2020 for all three); output is linear RGB for the
single display encoding downstream. The filmic highlight-reconstruction pre-pass is spatial and RAW
specific, so it is not part of the LMT.
"""
import numpy as np

# ---------------------------------------------------------------- colour spaces
BRADFORD = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])
D50 = np.array([0.96422, 1.0, 0.82521])
D65 = np.array([0.95047, 1.0, 1.08883])


def cat(src, dst):
    s, d = BRADFORD @ src, BRADFORD @ dst
    return np.linalg.inv(BRADFORD) @ np.diag(d / s) @ BRADFORD


def primaries_to_xyz(xr, yr, xg, yg, xb, yb, xw, yw):
    m = np.array([[xr / yr, xg / yg, xb / yb], [1, 1, 1], [(1 - xr - yr) / yr, (1 - xg - yg) / yg, (1 - xb - yb) / yb]])
    w = np.array([xw / yw, 1.0, (1 - xw - yw) / yw])
    return m * (np.linalg.inv(m) @ w)


BT2020_TO_XYZ = primaries_to_xyz(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290)
P3_TO_XYZ = np.array([[0.4865709, 0.2656677, 0.1982173], [0.2289746, 0.6917385, 0.0792869],
                      [0.0, 0.0451134, 1.0439444]])
PROPHOTO_TO_XYZ = np.array([[0.7976749, 0.1351917, 0.0313534], [0.2880402, 0.7118741, 0.0000857],
                            [0.0, 0.0, 0.8252100]])
PROPHOTO_TO_BT2020 = np.linalg.inv(BT2020_TO_XYZ) @ cat(D50, D65) @ PROPHOTO_TO_XYZ
BT2020_TO_P3 = np.linalg.inv(P3_TO_XYZ) @ BT2020_TO_XYZ


def rgb_to_hsv(c):
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    # GLSL branchless variant used by agxRgbToHsv
    k = np.array([0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0])
    sgb = (g >= b)[..., None]
    p = np.where(sgb, np.stack([g, b, np.full_like(g, k[0]), np.full_like(g, k[1])], -1),
                 np.stack([b, g, np.full_like(g, k[3]), np.full_like(g, k[2])], -1))
    srp = (r >= p[..., 0])[..., None]
    q = np.where(srp, np.stack([r, p[..., 1], p[..., 2], p[..., 0]], -1),
                 np.stack([p[..., 0], p[..., 1], p[..., 3], r], -1))
    d = q[..., 0] - np.minimum(q[..., 3], q[..., 1])
    e = 1e-10
    return np.stack([np.abs(q[..., 2] + (q[..., 3] - q[..., 1]) / (6.0 * d + e)), d / (q[..., 0] + e), q[..., 0]], -1)


def hsv_to_rgb(h):
    k = np.array([1.0, 2.0 / 3.0, 1.0 / 3.0])
    x = h[..., 0:1] + k
    p = np.abs((x - np.floor(x)) * 6.0 - 3.0)
    return h[..., 2:3] * ((1 - h[..., 1:2]) * 1.0 + h[..., 1:2] * np.clip(p - 1.0, 0.0, 1.0))


# ---------------------------------------------------------------- darktable sigmoid
SIG_WHITE, SIG_FOG, SIG_PAPER_POW, SIG_TARGET = 1.0, 0.00142637086, 1.0, 0.1845
SIGMOID_DEFAULTS = dict(contrast=1.5, hue=1.0)   # film power, hue preservation


def sigmoid(color, contrast=1.5, hue=1.0):
    """darktable sigmoid. The paper exposure follows from the contrast so that scene mid grey (SIG_TARGET)
    renders as display SIG_TARGET (contrast 1.5 gives the 0.354355423 of the defaults)."""
    paper_exp = (SIG_FOG + SIG_TARGET) ** contrast * (1 / SIG_TARGET - 1)
    avg = np.maximum(color.mean(-1), 0.0)
    mn = color.min(-1)
    with np.errstate(divide="ignore", invalid="ignore"):
        sat = np.where(mn < 0, -avg / (mn - avg), 1.0)
    pos = avg[..., None] + sat[..., None] * (color - avg[..., None])
    film = (SIG_FOG + np.maximum(pos, 0.0)) ** contrast
    per = np.clip(SIG_WHITE * (film / (paper_exp + film)) ** SIG_PAPER_POW, 0.0, SIG_WHITE)
    # preserveSigmoidHueAndEnergy: order by the GLSL tie rules
    r, g, b = pos[..., 0], pos[..., 1], pos[..., 2]
    order = np.empty(pos.shape, np.int64)
    cases = [
        ((r >= g) & (g > b), (2, 1, 0)),
        ((r >= g) & ~(g > b) & (b > r), (1, 0, 2)),
        ((r >= g) & ~(g > b) & ~(b > r) & (b > g), (1, 2, 0)),
        ((r >= g) & ~(g > b) & ~(b > r) & ~(b > g), (2, 1, 0)),
        (~(r >= g) & (r >= b), (2, 0, 1)),
        (~(r >= g) & ~(r >= b) & (b > g), (0, 1, 2)),
        (~(r >= g) & ~(r >= b) & ~(b > g), (0, 2, 1)),
    ]
    for mask, o in cases:
        order[mask] = o
    take = lambda a, i: np.take_along_axis(a, order[..., i:i + 1], -1)[..., 0]
    imin, imid, imax = take(pos, 0), take(pos, 1), take(pos, 2)
    pmin, pmid, pmax = take(per, 0), take(per, 1), take(per, 2)
    chroma = imax - imin
    with np.errstate(divide="ignore", invalid="ignore"):
        mid_scale = np.where(chroma != 0, (imid - imin) / chroma, 0.0)
        full = pmin + (pmax - pmin) * mid_scale
        naive_mid = pmid + (full - pmid) * hue
        per_energy = per.sum(-1)
        naive_energy = pmin + naive_mid + pmax
        mm = imin + imid
        blend = np.where(mm != 0, 2.0 * imin / mm, 0.0)
    target = blend * per_energy + (1 - blend) * naive_energy
    lo = naive_mid <= pmid
    c1 = ((1 - hue) * pmid + hue * (mid_scale * pmax + (1 - mid_scale) * (target - pmax))) / (1 + hue * (1 - mid_scale))
    c2 = ((1 - hue) * pmid + hue * (pmin * (1 - mid_scale) + mid_scale * (target - pmin))) / (1 + hue * mid_scale)
    omin = np.where(lo, target - pmax - c1, pmin)
    omid = np.where(lo, c1, c2)
    omax = np.where(lo, pmax, target - pmin - c2)
    out = np.zeros_like(pos)
    np.put_along_axis(out, order[..., 0:1], omin[..., None], -1)
    np.put_along_axis(out, order[..., 1:2], omid[..., None], -1)
    np.put_along_axis(out, order[..., 2:3], omax[..., None], -1)
    return out


# ---------------------------------------------------------------- AgX (darktable AgX as in PhotonCamera)
AGX_DEFAULTS = dict(black=-10.0, white=6.5, toe=1.5, shoulder=3.3)
AGX_EPS, AGX_RANGE, AGX_MIN_RANGE, AGX_GAMMA, AGX_PIVOT_Y, AGX_SLOPE, AGX_HUE_MIX = 1e-6, 16.5, 0.2, 2.2, 0.4586564469, 3.0, 0.6
AGX_BASE_TO_RENDER = np.array([[0.8535098168, 0.0870498824, 0.0594403008], [0.1209748385, 0.7561015246, 0.1229236368],
                               [0.0964595535, 0.0689548151, 0.8345856314]])
AGX_RENDER_TO_BASE = np.array([[1.1203173359, -0.0999545154, -0.0203628205], [-0.1213527019, 1.1417155224, -0.0203628205],
                               [-0.1213527019, -0.0999545154, 1.2213072173]])
AGX_LUMA = np.array([0.2658180370, 0.5984698605, 0.1357121025])


def agx_compress(c):
    y_in = c @ AGX_LUMA
    mx = c.max(-1)
    opp = mx[..., None] - c
    y_comp = opp.max(-1) - opp @ AGX_LUMA + y_in
    off = np.maximum(-c.min(-1), 0.0)
    ro = c + off[..., None]
    oo = ro.max(-1)[..., None] - ro
    y_new = oo.max(-1) - oo @ AGX_LUMA + ro @ AGX_LUMA
    ratio = np.where((y_new > y_comp) & (y_new > AGX_EPS), y_comp / np.where(y_new == 0, 1, y_new), 1.0)
    return ratio[..., None] * ro


def agx_range(p):
    black = min(p["black"], p["white"] - AGX_MIN_RANGE)
    white = max(p["white"], black + AGX_MIN_RANGE)
    return black, white, max(AGX_MIN_RANGE, white - black)


def agx_log(v, p):
    black, white, rng = agx_range(p)
    rel = np.maximum(AGX_EPS, v / 0.18)
    return np.clip((np.log2(np.maximum(rel, 0.0)) - black) / rng, 0.0, 1.0)


def _agx_sig(v, power):
    return v / (1.0 + v ** power) ** (1.0 / power)


def _agx_scaled(v, scale, slope, power, tx, ty):
    return scale * _agx_sig(slope * (v - tx) / scale, power) + ty


def _agx_scale(lx, ly, tx, ty, slope, power):
    proj = slope * max(AGX_EPS, lx - tx)
    act = max(AGX_EPS, ly - ty)
    base = max(AGX_EPS, act ** -power - proj ** -power)
    return min(1e9, base ** (-1.0 / power))


def agx_curve(v, p):
    black, white, rng = agx_range(p)
    px = min(max(-black / rng, AGX_EPS), 1 - AGX_EPS)
    py = AGX_PIVOT_Y
    slope = AGX_SLOPE * (rng / AGX_RANGE)
    tb, tw = 0.0, 1.0
    tp = max(0.01, p["toe"])
    toe_scale = -_agx_scale(1.0, 1.0 - tb, 1.0 - px, 1.0 - py, slope, tp)
    toe_len = max(AGX_EPS, px)
    toe_dy = max(AGX_EPS, py - tb)
    convex = toe_dy / toe_len > slope
    toe_fp = slope * toe_len / toe_dy
    toe_fc = toe_dy / toe_len ** toe_fp
    intercept = py - slope * px
    sp = max(0.01, p["shoulder"])
    sh_scale = _agx_scale(1.0, tw, px, py, slope, sp)
    sh_len = max(AGX_EPS, 1.0 - px)
    sh_dy = max(AGX_EPS, tw - py)
    concave = sh_dy / sh_len > slope
    sh_fp = slope * sh_len / sh_dy
    sh_fc = sh_dy / sh_len ** sh_fp
    with np.errstate(invalid="ignore", over="ignore", divide="ignore"):
        if convex:
            toe = np.where(v < 0, tb, tb + np.maximum(0.0, toe_fc * np.maximum(v, 0) ** toe_fp))
        else:
            toe = _agx_scaled(v, toe_scale, slope, tp, px, py)
        if concave:
            sh = np.where(v >= 1, tw, tw - np.maximum(0.0, sh_fc * np.maximum(1 - v, 0) ** sh_fp))
        else:
            sh = _agx_scaled(v, sh_scale, slope, sp, px, py)
    res = np.where(v < px, toe, np.where(v <= px, slope * v + intercept, sh))
    return np.clip(res, tb, tw)


def agx(color, p=AGX_DEFAULTS):
    c = np.clip(np.nan_to_num(color), -1e6, 1e6)
    base = agx_compress(c)
    rend = base @ AGX_BASE_TO_RENDER.T
    hsv_before = rgb_to_hsv(rend)
    t = np.maximum(agx_curve(agx_log(rend, p), p), 0.0) ** AGX_GAMMA
    hsv_after = rgb_to_hsv(t)
    d = hsv_after[..., 0] - hsv_before[..., 0]
    shortest = d - np.round(d)   # GLSL round: half away from zero; ties are measure zero here
    mixed = (1 - AGX_HUE_MIX) * shortest + hsv_before[..., 0]
    hsv_after[..., 0] = mixed - np.floor(mixed)
    return hsv_to_rgb(hsv_after) @ AGX_RENDER_TO_BASE.T


# ---------------------------------------------------------------- darktable filmic
FILMIC_GREY_SOURCE, FILMIC_OUTPUT_POWER, FILMIC_DISPLAY_BLACK = 0.1845, 3.614815775, 0.0001517634
FILMIC_DEFAULT_DR, FILMIC_DEFAULT_CONTRAST, FILMIC_LATITUDE, FILMIC_SAFETY = 12.21, 1.433801098, 0.0001, 0.01
FILMIC_DEFAULTS = dict(black=-7.65, white=4.56)
MIN_DR = 0.2


def filmic_uniforms(p=FILMIC_DEFAULTS):
    """RawToneMappingGl.computeFilmicToneCurveUniforms (float32 arithmetic as in Kotlin)."""
    f = np.float32
    black = f(min(p["black"], p["white"] - MIN_DR))
    white = f(max(p["white"], black + MIN_DR))
    dr = f(max(MIN_DR, white - black))
    in_min = f(2.0) ** black * f(FILMIC_GREY_SOURCE)
    in_max = f(2.0) ** white * f(FILMIC_GREY_SOURCE)
    bd = f(FILMIC_DISPLAY_BLACK) ** (f(1) / f(FILMIC_OUTPUT_POWER))
    wd = f(1)
    gd = f(FILMIC_GREY_SOURCE) ** (f(1) / f(FILMIC_OUTPUT_POWER))
    gl = f(min(max(abs(black) / dr, 0.001), 0.999))
    contrast = f(FILMIC_DEFAULT_CONTRAST) * (dr / f(FILMIC_DEFAULT_DR))
    minc = max(f(1), (wd - gd) / max(f(1) - gl, f(1e-5)), (gd - bd) / max(gl, f(1e-5)))
    contrast = f(min(max(contrast, minc + f(FILMIC_SAFETY)), f(100)))
    li = gd - contrast * gl
    drange = wd - bd
    xmin = (bd + f(FILMIC_SAFETY) * drange - li) / contrast
    xmax = (wd - f(FILMIC_SAFETY) * drange - li) / contrast
    toe_log = f(min(max((f(1) - f(FILMIC_LATITUDE)) * gl + f(FILMIC_LATITUDE) * xmin, 0), gl))
    sh_log = f(min(max((f(1) - f(FILMIC_LATITUDE)) * gl + f(FILMIC_LATITUDE) * xmax, gl), 1))
    toe_d = toe_log * contrast + li
    sh_d = sh_log * contrast + li
    tl, sl = float(toe_log), float(sh_log)
    toe = np.linalg.solve(np.array([[0, 0, 0, 0, 1], [0, 0, 0, 1, 0], [tl ** 4, tl ** 3, tl ** 2, tl, 1],
                                    [4 * tl ** 3, 3 * tl ** 2, 2 * tl, 1, 0], [12 * tl ** 2, 6 * tl, 2, 0, 0]], float),
                          np.array([float(bd), 0, float(toe_d), float(contrast), 0]))
    sh = np.linalg.solve(np.array([[1, 1, 1, 1, 1], [4, 3, 2, 1, 0], [sl ** 4, sl ** 3, sl ** 2, sl, 1],
                                   [4 * sl ** 3, 3 * sl ** 2, 2 * sl, 1, 0], [12 * sl ** 2, 6 * sl, 2, 0, 0]], float),
                         np.array([float(wd), 0, float(sh_d), float(contrast), 0]))
    m = np.zeros((5, 3), np.float32)   # rows M1..M5, columns (toe, shoulder, linear)
    m[4, 0], m[3, 0], m[2, 0], m[1, 0], m[0, 0] = toe
    m[4, 1], m[3, 1], m[2, 1], m[1, 1], m[0, 1] = sh
    m[0, 2], m[1, 2] = toe_d - contrast * toe_log, contrast
    return dict(black=float(black), dr=float(dr), in_min=float(max(in_min, f(1e-8))),
                in_max=float(max(in_max, in_min + f(1e-8))), lat_min=tl, lat_max=sl, m=m)


FILMIC_BT2020_TO_LMS = np.array([[0.4067763460, 0.6178051991, 0.0458445893], [0.0677498629, 0.7489671634, 0.1001665160],
                                 [0.0221408642, -0.0153252587, 0.5876294574]])
FILMIC_LMS_TO_BT2020 = np.array([[2.8380181184, -2.3373915374, 0.1770173235], [-0.2415770666, 1.5294941187, -0.2418685622],
                                 [-0.1132319053, 0.1279577808, 1.6887750778]])
FILMIC_SRGB_TO_LMS = np.array([[0.2986531876, 0.7060763220, 0.0656966248], [0.0959000021, 0.7198304285, 0.1011531117],
                               [0.0224644230, 0.0449176289, 0.5270630110]])
FILMIC_LMS_TO_SRGB = np.array([[4.8627131007, -4.7893329888, 0.3130405567], [-0.6262137162, 2.0228185813, -0.3101607577],
                               [-0.1538905312, 0.0317407724, 1.9103966448]])
LMS_TO_FL = np.array([[1.08771930, -0.66666667, 0.02061856], [-0.08771930, 1.66666667, -0.05154639], [0.0, 0.0, 1.03092784]])
FL_TO_LMS = np.array([[0.95, 0.38, 0.0], [0.05, 0.62, 0.03], [0.0, 0.0, 0.97]])
YRG_R, YRG_G, Y1931 = 0.21902143, 0.54371398, 1.05785528
MAXC = 1e20


def _log_enc(v, u):
    return np.clip((np.log2(np.maximum(v, 1.52587890625e-5) / FILMIC_GREY_SOURCE) - u["black"]) / max(u["dr"], 0.2), 0, 1)


def _spline(x, u):
    m = u["m"].astype(np.float64)
    poly = lambda c: m[0, c] + x * (m[1, c] + x * (m[2, c] + x * (m[3, c] + x * m[4, c])))
    return np.where(x < u["lat_min"], poly(0), np.where(x > u["lat_max"], poly(1), m[0, 2] + x * m[1, 2]))


def _lms_to_yrg(lms):
    y = 0.68990272 * lms[..., 0] + 0.34832189 * lms[..., 1]
    s = lms.sum(-1)
    with np.errstate(divide="ignore", invalid="ignore"):
        n = np.where(np.abs(s)[..., None] > 1e-8, lms / s[..., None], 0.0)
    fl = n @ LMS_TO_FL.T
    return np.stack([y, fl[..., 0], fl[..., 1]], -1)


def _yrg_to_lms(yrg):
    fl = np.stack([yrg[..., 1], yrg[..., 2], 1 - yrg[..., 1] - yrg[..., 2]], -1)
    n = fl @ FL_TO_LMS.T
    den = 0.68990272 * n[..., 0] + 0.34832189 * n[..., 1]
    with np.errstate(divide="ignore", invalid="ignore"):
        sc = np.where(np.abs(den) > 1e-8, yrg[..., 0] / den, 0.0)
    return n * sc[..., None]


def _yrg_to_ych(yrg):
    r, g = yrg[..., 1] - YRG_R, yrg[..., 2] - YRG_G
    c = np.hypot(r, g)
    with np.errstate(divide="ignore", invalid="ignore"):
        cos = np.where(c > 0, r / c, 1.0)
        sin = np.where(c > 0, g / c, 0.0)
    return np.stack([yrg[..., 0], c, cos, sin], -1)


def _ych_to_yrg(y):
    return np.stack([y[..., 0], y[..., 1] * y[..., 2] + YRG_R, y[..., 1] * y[..., 3] + YRG_G], -1)


def _rgb_to_ych(c, srgb):
    return _yrg_to_ych(_lms_to_yrg(c @ (FILMIC_SRGB_TO_LMS if srgb else FILMIC_BT2020_TO_LMS).T))


def _ych_to_rgb(y, srgb):
    return _yrg_to_lms(_ych_to_yrg(y)) @ (FILMIC_LMS_TO_SRGB if srgb else FILMIC_LMS_TO_BT2020).T


def _clip_white_raw(co, tw, y, cos, sin):
    dy = co[0] * (0.979381443298969 * cos + 0.391752577319588 * sin) + co[1] * (0.0206185567010309 * cos + 0.608247422680412 * sin) - co[2] * (cos + sin)
    dt = tw * (0.68285981628866 * cos + 0.482137060515464 * sin)
    with np.errstate(divide="ignore", invalid="ignore"):
        asym = dt / dy
        den = y * dy - dt
        num = -0.427506877216495 * (y * (co[0] + 0.856492345150334 * co[1] + 0.554995960637719 * co[2]) - 0.988237752433297 * tw)
        mc = num / den
    res = np.where(mc >= 0, mc, MAXC)
    res = np.where(np.abs(den) <= 1e-8, MAXC, res)
    res = np.where(y <= asym, MAXC, res)
    return np.where(np.abs(dy) <= 1e-8, MAXC, res)


def _clip_white(co, tw, y, cos, sin):
    eps = 0.001
    my = Y1931 * tw
    d = np.maximum(my - y, 0.0)
    small = d / max(eps * my, 1e-8) * _clip_white_raw(co, tw, np.full_like(y, (1 - eps) * my), cos, sin)
    mc = np.where(d < eps, small, _clip_white_raw(co, tw, y, cos, sin))
    return np.where(mc >= 0, mc, MAXC)


def _clip_black(co, cos, sin):
    den = co[0] * (0.979381443298969 * cos + 0.391752577319588 * sin) + co[1] * (0.0206185567010309 * cos + 0.608247422680412 * sin) - co[2] * (cos + sin)
    with np.errstate(divide="ignore", invalid="ignore"):
        mc = -0.427506877216495 * (co[0] + 0.856492345150334 * co[1] + 0.554995960637719 * co[2]) / den
    return np.where(np.abs(den) <= 1e-8, MAXC, np.where(mc >= 0, mc, MAXC))


def _gamut_check_rgb(ych, srgb=True):
    rgb = _ych_to_rgb(ych, srgb)
    rgb = rgb + np.maximum(-rgb.min(-1), 0.0)[..., None]
    yb = _rgb_to_ych(rgb, srgb)
    y = np.clip(0.5 * (ych[..., 0] + yb[..., 0]), Y1931 * FILMIC_DISPLAY_BLACK, Y1931 * 1.0)
    rows = FILMIC_LMS_TO_SRGB if srgb else FILMIC_LMS_TO_BT2020
    cos, sin = ych[..., 2], ych[..., 3]
    cw = np.minimum(np.minimum(_clip_white(rows[0], 1.0, y, cos, sin), _clip_white(rows[1], 1.0, y, cos, sin)), _clip_white(rows[2], 1.0, y, cos, sin))
    cb = np.minimum(np.minimum(_clip_black(rows[0], cos, sin), _clip_black(rows[1], cos, sin)), _clip_black(rows[2], cos, sin))
    nc = np.maximum(np.minimum(np.minimum(ych[..., 1], cw), cb), 0.0)
    return np.clip(_ych_to_rgb(np.stack([y, nc, cos, sin], -1), srgb), 0.0, 1.0)


def filmic(color, u=None):
    u = u or filmic_uniforms()
    naive = np.clip(_spline(_log_enc(np.maximum(color, 1.52587890625e-5), u), u), 0, 1) ** FILMIC_OUTPUT_POWER
    pos = np.maximum(color, 0.0)
    mx = pos.max(-1)
    ratios = pos / np.maximum(mx, u["in_min"])[..., None]
    norm = np.clip(_spline(_log_enc(np.clip(mx, u["in_min"], u["in_max"]), u), u), FILMIC_DISPLAY_BLACK, 1.0) ** FILMIC_OUTPUT_POWER
    toned = 0.5 * naive + 0.5 * ratios * norm[..., None]
    # darktableFilmicGamutMapV5
    yo = _rgb_to_ych(color, False)
    yf = _rgb_to_ych(toned, False)
    yf[..., 1] = np.minimum(yo[..., 1], yf[..., 1])
    yf[..., 2], yf[..., 3] = yo[..., 2], yo[..., 3]
    yf[..., 0] = np.clip(yf[..., 0], Y1931 * FILMIC_DISPLAY_BLACK, Y1931)
    # desaturate v4 with saturation 0: only the "brightens and resaturates" branch acts
    co, cf = yo[..., 1] * yo[..., 0], yf[..., 1] * yf[..., 0]
    cf = np.where((yf[..., 0] > yo[..., 0]) & (co < cf), 0.5 * (co + cf), cf)
    yf[..., 1] = np.maximum(cf / np.maximum(yf[..., 0], 1e-8), 0.0)
    # gamut check yrg
    yrg = _ych_to_yrg(yf)
    mc = np.maximum(yf[..., 1], 0.0)
    cos, sin = yf[..., 2], yf[..., 3]
    with np.errstate(divide="ignore", invalid="ignore"):
        mc = np.where((yrg[..., 1] < 0) & (np.abs(cos) > 1e-8), np.minimum(-YRG_R / cos, mc), mc)
        mc = np.where((yrg[..., 2] < 0) & (np.abs(sin) > 1e-8), np.minimum(-YRG_G / sin, mc), mc)
        mc = np.where((yrg[..., 1] + yrg[..., 2] > 1) & (np.abs(cos + sin) > 1e-8), np.minimum((1 - YRG_R - YRG_G) / (cos + sin), mc), mc)
    yf[..., 1] = np.maximum(mc, 0.0)
    srgb = _gamut_check_rgb(yf, True)
    return (srgb @ FILMIC_SRGB_TO_LMS.T) @ FILMIC_LMS_TO_BT2020.T
