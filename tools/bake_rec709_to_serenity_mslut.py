# Bake a Rec.709 look .cube into a 人文/清冷-style Meishe LUT.
#
# Device path (人文 / 清冷): Display P3 primaries + sRGB EOTF, u8 32^3,
# R-fastest, header cloned from Serenity.cube.rgb.bin.
# Rec.709 look LUT is applied in Rec.709 encoding; this script wraps:
#   P3_sRGB <-> Rec.709_<lut-eotf>  around the look cube, then writes MS-LUT.
#
# Gamut (P3 -> 709, the only lossy leg):
#   css4      W3C CSS Color 4 §14.2.1 Binary Search + Local MINDE
#             (OkLCh constant-L/H chroma reduction, relative colorimetric).
#             This is the published mapping for Display P3 -> sRGB.
#             Rec.709 shares sRGB primaries; 709 -> P3 is a subset, matrix only.
#   clip      per-channel clip after 3x3 (legacy)
#   compress  scale toward 0 so channels fit [0,1] (legacy)
#
# Usage:
#   python bake_rec709_to_serenity_mslut.py look.cube out.cube.rgb.bin
#   python bake_rec709_to_serenity_mslut.py look.cube out.bin --lut-eotf gamma22
#   python bake_rec709_to_serenity_mslut.py --selftest
import argparse
import pathlib
import sys
import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import refs  # noqa: E402

N = 32
HDR_SIZE = 176
PAYLOAD = N * N * N * 3
TOTAL = HDR_SIZE + PAYLOAD

# Linear Rec.709/sRGB -> XYZ (D65), XYZ -> linear Display P3 (D65).
# IEC 61966-2-1 / Apple Display P3. Both white points D65: no CAT.
M_709_TO_XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
], dtype=np.float64)
M_P3_TO_XYZ = np.array([
    [0.4865709, 0.2656677, 0.1982173],
    [0.2289746, 0.6917385, 0.0792869],
    [0.0000000, 0.0451134, 1.0439444],
], dtype=np.float64)
M_XYZ_TO_709 = np.linalg.inv(M_709_TO_XYZ)
M_XYZ_TO_P3 = np.linalg.inv(M_P3_TO_XYZ)
M_P3_TO_709 = M_XYZ_TO_709 @ M_P3_TO_XYZ
M_709_TO_P3 = M_XYZ_TO_P3 @ M_709_TO_XYZ

# CSS Color 4 conversions.js (W3C, 64-bit OKLab, D65 XYZ).
# https://www.w3.org/TR/css-color-4/#color-conversion-code
XYZ_TO_LMS = np.array([
    [0.8190224379967030, 0.3619062600528904, -0.1288737815209879],
    [0.0329836539323885, 0.9292868615863434, 0.0361446663506424],
    [0.0481771893596242, 0.2642395317527308, 0.6335478284694309],
], dtype=np.float64)
LMS_TO_OKLAB = np.array([
    [0.2104542683093140, 0.7936177747023054, -0.0040720430116193],
    [1.9779985324311684, -2.4285922420485799, 0.4505937096174110],
    [0.0259040424655478, 0.7827717124575296, -0.8086757549230774],
], dtype=np.float64)
OKLAB_TO_LMS = np.array([
    [1.0000000000000000, 0.3963377773761749, 0.2158037573099136],
    [1.0000000000000000, -0.1055613458156586, -0.0638541728258133],
    [1.0000000000000000, -0.0894841775298119, -1.2914855480194092],
], dtype=np.float64)
LMS_TO_XYZ = np.array([
    [1.2268798758459243, -0.5578149944602171, 0.2813910456659647],
    [-0.0405757452148008, 1.1122868032803170, -0.0717110580655164],
    [-0.0763729366746601, -0.4214933324022432, 1.5869240198367816],
], dtype=np.float64)

CSS4_JND = 0.02
CSS4_EPS = 1e-4
INGAMUT_SLACK = 1e-6

DEFAULT_HEADER = refs.ROOT / "固件解包/unpacked/fs/odm/etc/camera/meishe_lut/Serenity.cube.rgb.bin"


def srgb_eotf_inv(u):
    u = np.asarray(u, dtype=np.float64)
    return np.where(u <= 0.04045, u / 12.92, ((u + 0.055) / 1.055) ** 2.4)


def srgb_eotf(u):
    u = np.asarray(u, dtype=np.float64)
    return np.where(u <= 0.0031308, 12.92 * u, 1.055 * np.power(np.maximum(u, 0.0), 1.0 / 2.4) - 0.055)


def gamma22_eotf_inv(u):
    return np.power(np.clip(np.asarray(u, dtype=np.float64), 0.0, None), 2.2)


def gamma22_eotf(u):
    return np.power(np.clip(np.asarray(u, dtype=np.float64), 0.0, None), 1.0 / 2.2)


def eotf_pair(name):
    if name == "srgb":
        return srgb_eotf_inv, srgb_eotf
    if name == "gamma22":
        return gamma22_eotf_inv, gamma22_eotf
    raise SystemExit("unknown --lut-eotf %s (srgb|gamma22)" % name)


def xyz_to_oklab(xyz):
    lms = xyz @ XYZ_TO_LMS.T
    return np.cbrt(lms) @ LMS_TO_OKLAB.T


def oklab_to_xyz(oklab):
    lms_p = oklab @ OKLAB_TO_LMS.T
    return (lms_p ** 3) @ LMS_TO_XYZ.T


def _in_unit_cube(rgb, slack=INGAMUT_SLACK):
    return np.all((rgb >= -slack) & (rgb <= 1.0 + slack), axis=-1)


def css4_map_to_rgb(xyz, M_xyz_to_rgb, M_rgb_to_xyz, jnd=CSS4_JND, epsilon=CSS4_EPS):
    """Map XYZ (D65) into dest linear RGB [0,1].

    W3C CSS Color Module Level 4 §14.2.1:
    Binary Search Gamut Mapping with Local MINDE, destination *-linear RGB.
    Relative colorimetric: already-in-gamut colors are the 3x3 result, unchanged.
    """
    xyz = np.asarray(xyz, dtype=np.float64)
    tail = xyz.shape
    xyz = xyz.reshape(-1, 3)
    dest = xyz @ M_xyz_to_rgb.T
    out = dest.copy()
    n = xyz.shape[0]
    if n == 0:
        return out.reshape(tail)

    oklab = xyz_to_oklab(xyz)
    L = oklab[:, 0]
    a = oklab[:, 1]
    b = oklab[:, 2]
    chroma = np.sqrt(a * a + b * b)

    white = L >= 1.0
    black = L <= 0.0
    out[white] = 1.0
    out[black] = 0.0
    in_g = _in_unit_cube(dest)
    need = ~(in_g | white | black)
    if not np.any(need):
        return out.reshape(tail)

    idx = np.flatnonzero(need)
    Lw, aw, bw, Cw = L[idx], a[idx], b[idx], chroma[idx]
    m = idx.size
    min_c = np.zeros(m, dtype=np.float64)
    max_c = Cw.copy()
    min_in_gamut = np.ones(m, dtype=bool)
    still = np.ones(m, dtype=bool)
    mapped = np.zeros((m, 3), dtype=np.float64)

    def rgb_at(c):
        scale = np.divide(c, Cw, out=np.zeros(m, dtype=np.float64), where=Cw > 1e-14)
        lab = np.stack([Lw, aw * scale, bw * scale], axis=1)
        rgb = oklab_to_xyz(lab) @ M_xyz_to_rgb.T
        return lab, rgb

    def clip_delta(lab, rgb):
        clipped = np.clip(rgb, 0.0, 1.0)
        clip_lab = xyz_to_oklab(clipped @ M_rgb_to_xyz.T)
        return clipped, np.linalg.norm(clip_lab - lab, axis=1)

    lab, rgb = rgb_at(Cw)
    clipped, err = clip_delta(lab, rgb)
    early = err < jnd
    mapped[early] = clipped[early]
    still[early] = False

    for _ in range(48):
        active = still & ((max_c - min_c) > epsilon)
        if not np.any(active):
            break
        c = 0.5 * (min_c + max_c)
        lab, rgb = rgb_at(c)
        inside = _in_unit_cube(rgb, slack=0.0)
        take = active & min_in_gamut & inside
        min_c = np.where(take, c, min_c)
        other = active & ~take
        if not np.any(other):
            continue
        clipped, err = clip_delta(lab, rgb)
        close = other & (err < jnd)
        very = close & ((jnd - err) < epsilon)
        mapped[very] = clipped[very]
        still[very] = False
        grow = close & ~very
        min_in_gamut = np.where(grow, False, min_in_gamut)
        min_c = np.where(grow, c, min_c)
        max_c = np.where(other & ~close, c, max_c)

    leftover = still
    if np.any(leftover):
        lab, rgb = rgb_at(0.5 * (min_c + max_c))
        clipped, _ = clip_delta(lab, rgb)
        mapped[leftover] = clipped[leftover]

    out[idx] = np.clip(mapped, 0.0, 1.0)
    return out.reshape(tail)


def parse_cube(path):
    size = None
    domain_min = np.zeros(3, dtype=np.float64)
    domain_max = np.ones(3, dtype=np.float64)
    rows = []
    for raw in pathlib.Path(path).read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key = line.split(None, 1)[0].upper()
        if key == "TITLE":
            continue
        if key == "LUT_3D_SIZE":
            size = int(line.split()[1])
            continue
        if key == "DOMAIN_MIN":
            domain_min = np.array(list(map(float, line.split()[1:4])), dtype=np.float64)
            continue
        if key == "DOMAIN_MAX":
            domain_max = np.array(list(map(float, line.split()[1:4])), dtype=np.float64)
            continue
        if key.startswith("LUT_") or key in ("1D", "3D"):
            continue
        parts = line.replace(",", " ").split()
        if len(parts) >= 3:
            rows.append([float(parts[0]), float(parts[1]), float(parts[2])])
    if size is None:
        raise SystemExit("%s: missing LUT_3D_SIZE" % path)
    expect = size ** 3
    if len(rows) != expect:
        raise SystemExit("%s: got %d RGB rows, expected %d" % (path, len(rows), expect))
    table = np.asarray(rows, dtype=np.float64).reshape(size, size, size, 3)
    # IRIDAS: R fastest, then G, then B -> index [b, g, r]
    return table, domain_min, domain_max


def identity_table(size):
    axis = np.linspace(0.0, 1.0, size)
    bb, gg, rr = np.meshgrid(axis, axis, axis, indexing="ij")
    return np.stack([rr, gg, bb], axis=-1)


def sample_cube(table, rgb):
    n = table.shape[0]
    p = np.clip(rgb, 0.0, 1.0) * (n - 1)
    i0 = np.floor(p).astype(np.int32)
    i1 = np.minimum(i0 + 1, n - 1)
    f = p - i0
    r0, g0, b0 = i0[..., 0], i0[..., 1], i0[..., 2]
    r1, g1, b1 = i1[..., 0], i1[..., 1], i1[..., 2]
    fr, fg, fb = f[..., 0], f[..., 1], f[..., 2]
    c000 = table[b0, g0, r0]
    c100 = table[b0, g0, r1]
    c010 = table[b0, g1, r0]
    c110 = table[b0, g1, r1]
    c001 = table[b1, g0, r0]
    c101 = table[b1, g0, r1]
    c011 = table[b1, g1, r0]
    c111 = table[b1, g1, r1]
    c00 = c000 * (1 - fr)[..., None] + c100 * fr[..., None]
    c10 = c010 * (1 - fr)[..., None] + c110 * fr[..., None]
    c01 = c001 * (1 - fr)[..., None] + c101 * fr[..., None]
    c11 = c011 * (1 - fr)[..., None] + c111 * fr[..., None]
    c0 = c00 * (1 - fg)[..., None] + c10 * fg[..., None]
    c1 = c01 * (1 - fg)[..., None] + c11 * fg[..., None]
    return c0 * (1 - fb)[..., None] + c1 * fb[..., None]


def compress_to_unit(rgb):
    """Keep hue in linear RGB: scale toward 0 so channels fit [0,1]."""
    rgb = np.maximum(rgb, 0.0)
    peak = np.max(rgb, axis=-1, keepdims=True)
    scale = np.ones_like(peak)
    over = peak > 1.0
    scale[over] = 1.0 / peak[over]
    return rgb * scale


def p3_lin_to_709_lin(p3_lin, gamut):
    if gamut == "clip":
        return np.clip(p3_lin @ M_P3_TO_709.T, 0.0, 1.0)
    if gamut == "compress":
        return compress_to_unit(p3_lin @ M_P3_TO_709.T)
    if gamut == "css4":
        return css4_map_to_rgb(p3_lin @ M_P3_TO_XYZ.T, M_XYZ_TO_709, M_709_TO_XYZ)
    raise SystemExit("unknown --gamut %s (css4|clip|compress)" % gamut)


def rec709_lin_to_p3_lin(rec_lin, gamut):
    # Rec.709 is a subset of Display P3: legal 709 is a 3x3, no mapping.
    if gamut == "css4":
        return css4_map_to_rgb(rec_lin @ M_709_TO_XYZ.T, M_XYZ_TO_P3, M_P3_TO_XYZ)
    return np.maximum(rec_lin @ M_709_TO_P3.T, 0.0)


def p3_lattice():
    axis = np.linspace(0.0, 1.0, N)
    rr, gg, bb = np.meshgrid(axis, axis, axis, indexing="ij")
    p3_enc = np.stack([rr, gg, bb], axis=-1)
    return np.transpose(p3_enc, (2, 1, 0, 3)).reshape(-1, 3)


def bake(table, domain_min, domain_max, lut_eotf, gamut):
    lut_decode, lut_encode = eotf_pair(lut_eotf)
    p3_enc = p3_lattice()
    rec_lin = p3_lin_to_709_lin(srgb_eotf_inv(p3_enc), gamut)
    rec_enc = np.clip(lut_encode(rec_lin), 0.0, 1.0)
    rec_enc = domain_min + rec_enc * (domain_max - domain_min)
    look = sample_cube(table, rec_enc)
    look = (look - domain_min) / np.maximum(domain_max - domain_min, 1e-12)
    look_lin = lut_decode(np.clip(look, 0.0, 1.0))
    out_p3_enc = np.clip(srgb_eotf(rec709_lin_to_p3_lin(look_lin, gamut)), 0.0, 1.0)
    return np.rint(out_p3_enc * 255.0).astype(np.uint8)


def load_header(path):
    data = pathlib.Path(path).read_bytes()
    if data[:8] != b".MS-LUT ":
        raise SystemExit("%s: not .MS-LUT" % path)
    if len(data) != TOTAL:
        raise SystemExit("%s: expected 98480 bytes, got %d" % (path, len(data)))
    return data[:HDR_SIZE]


def _oklch_of_p3_enc(rgb):
    lin = srgb_eotf_inv(np.asarray(rgb, dtype=np.float64))
    lab = xyz_to_oklab(lin @ M_P3_TO_XYZ.T)
    c = np.hypot(lab[..., 1], lab[..., 2])
    h = (np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) + 360.0) % 360.0
    return lab[..., 0], c, h


def selftest():
    table = identity_table(32)
    dmin = np.zeros(3)
    dmax = np.ones(3)
    p3_enc = p3_lattice()
    p3_lin = srgb_eotf_inv(p3_enc)
    rec_raw = p3_lin @ M_P3_TO_709.T
    oog = ~_in_unit_cube(rec_raw, slack=1e-9)
    print("P3 32^3 lattice: %d points, %d outside Rec.709 (%.1f%%)"
          % (p3_enc.shape[0], int(oog.sum()), 100.0 * oog.mean()))

    # Rec.709 red, expressed in encoded Display P3 — must round-trip.
    red709_p3 = np.clip(srgb_eotf((M_709_TO_P3 @ np.array([1.0, 0.0, 0.0]))), 0, 1)

    rows = []
    for gamut in ("css4", "clip", "compress"):
        payload = bake(table, dmin, dmax, "srgb", gamut).reshape(-1, 3)
        black = payload[0]
        white = payload[-1]
        # lattice origin/end are (0,0,0) and (1,1,1) in R-fastest
        p3_red_in = np.array([1.0, 0.0, 0.0])
        rec_lin = p3_lin_to_709_lin(srgb_eotf_inv(p3_red_in[None, :]), gamut)[0]
        back = rec709_lin_to_p3_lin(rec_lin[None, :], gamut)[0]
        back_u8 = np.rint(np.clip(srgb_eotf(back), 0, 1) * 255).astype(int)
        L, C, H = _oklch_of_p3_enc(srgb_eotf(back))
        src_L, src_C, src_H = _oklch_of_p3_enc(p3_red_in)
        # in-gamut max error vs identity (u8)
        ig = payload[~oog]
        src_u8 = np.rint(p3_enc[~oog] * 255.0)
        max_ig = np.max(np.abs(ig.astype(np.int16) - src_u8))
        rows.append((gamut, black, white, back_u8, float(H), float(src_H), float(C),
                     float(src_C), int(max_ig), rec_lin))

    red709_back = {}
    for gamut in ("css4", "clip", "compress"):
        rec_lin = p3_lin_to_709_lin(srgb_eotf_inv(red709_p3[None, :]), gamut)[0]
        back = rec709_lin_to_p3_lin(rec_lin[None, :], gamut)[0]
        red709_back[gamut] = (
            rec_lin,
            np.max(np.abs(back - srgb_eotf_inv(red709_p3))),
        )

    print("identity bake  black / white / in-gamut max |u8 err|")
    for gamut, black, white, _, _, _, _, _, max_ig, _ in rows:
        print("  %-8s  black=%s  white=%s  in-gamut maxΔ=%d"
              % (gamut, black.tolist(), white.tolist(), max_ig))
    print("P3 red (1,0,0) -> 709 -> P3   (src OkLCh H=%.2f C=%.4f)"
          % (_oklch_of_p3_enc(np.array([1.0, 0.0, 0.0]))[2],
             _oklch_of_p3_enc(np.array([1.0, 0.0, 0.0]))[1]))
    for gamut, black, white, back_u8, H, src_H, C, src_C, max_ig, rec_lin in rows:
        print("  %-8s  u8=%s  709lin=%s  hueΔ=%+.2f°  C %.4f→%.4f"
              % (gamut, back_u8.tolist(), np.round(rec_lin, 4).tolist(),
                 H - src_H, src_C, C))
    print("Rec.709 red in P3 (should round-trip; linear abs err):")
    print("  p3_enc=", np.round(red709_p3, 6).tolist())
    for gamut, (rec_lin, err) in red709_back.items():
        print("  %-8s  709lin=%s  p3_lin_err=%.3e"
              % (gamut, np.round(rec_lin, 6).tolist(), err))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cube", nargs="?")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--header", default=str(DEFAULT_HEADER))
    ap.add_argument("--lut-eotf", choices=("srgb", "gamma22"), default="srgb",
                    help="encoding the Rec.709 look LUT was built in")
    ap.add_argument("--gamut", choices=("css4", "clip", "compress"), default="css4",
                    help="P3->709: css4 = W3C CSS Color 4 OkLCh (default)")
    ap.add_argument("--selftest", action="store_true",
                    help="identity-LUT checks, no output file")
    args = ap.parse_args()
    if args.selftest:
        selftest()
        return
    if not args.cube or not args.out:
        ap.error("cube and out are required unless --selftest")
    table, dmin, dmax = parse_cube(args.cube)
    payload = bake(table, dmin, dmax, args.lut_eotf, args.gamut)
    if payload.size != PAYLOAD:
        raise SystemExit("payload size %d != %d" % (payload.size, PAYLOAD))
    header = load_header(args.header)
    pathlib.Path(args.out).write_bytes(header + payload.tobytes())
    print("wrote %s  bytes=%d  lut=%dx%dx%d  lut_eotf=%s  gamut=%s"
          % (args.out, TOTAL, table.shape[0], table.shape[1], table.shape[2],
             args.lut_eotf, args.gamut))


if __name__ == "__main__":
    main()
