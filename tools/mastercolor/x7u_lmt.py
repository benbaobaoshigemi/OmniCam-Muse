"""Find X7 Ultra master LMT for the X9U chain: h = HDRT-master inverse of (C16 ODT . C16 LMT).
D = ODT_Photo(LMTLut0(x)) with the X7U tables (sRGB-coded display RGB); T = sRGB decode(D) is the
display-linear target; h = master_lmt.invert_output(T) (toneCurve luma + HLG chroma ratio, the same
inverse the HNCS / Lumix bakers use). Then refines h so the real HDRT forward model (master_lmt.hdrt) returns D (Levenberg-Marquardt per node).
Writes x7u_exact.lmt (64^3 fp32 planar [3][B][G][R]) -> module/payload/master_color/looks/x7u.lmt.
Inputs: X7U C16 odm basictone (LMTLut0, ODT_Photo); the X9U stock ODT_Photo is only a sanity check."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import numpy as np
import master_lmt as M

C = str(refs.ROM / "c16/x/odm/etc/camera/basictone") + "\\"
A = str(refs.ROM / "x9u_stock_20260924/odm/etc/camera/basictone") + "\\"
L = lambda p: np.fromfile(p, "<f4").reshape(3, 64, 64, 64).astype(np.float64)

def samp(lut, rgb):
    p = np.clip(rgb, 0, 1) * 63; i = np.minimum(p.astype(int), 62); f = p - i; out = np.zeros_like(rgb)
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = (f[:, 2] if db else 1 - f[:, 2]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 0] if dr else 1 - f[:, 0])
                out += w[:, None] * lut[:, i[:, 2] + db, i[:, 1] + dg, i[:, 0] + dr].T
    return out

ENC = sys.argv[1] if len(sys.argv) > 1 else "g22"    # how the X7U ODT output is coded: g22 (default), srgb, hlg

def srgb_dec(e):
    e = np.clip(e, 0, 1)
    if ENC == "hlg":
        return np.clip(M.hlg_inv(e), 0, 1)
    if ENC == "srgb":
        return np.where(e < 0.04045, e / 12.92, ((e + 0.055) / 1.055) ** 2.4)
    return e ** 2.2

n = np.arange(64) / 63.0
B, G, R = np.meshgrid(n, n, n, indexing="ij")
x = np.stack([R.ravel(), G.ravel(), B.ravel()], 1)

# sanity of the assumption "ODT output is sRGB-coded display RGB comparable with HDRT's output":
xl, xo = L(A + "lmt/LMTLut0"), L(A + "odt/ODT_Photo")
rng = np.random.default_rng(1); s = rng.random((20000, 3))
h9 = samp(xl, s); a, b = M.hdrt(h9), samp(xo, h9)
d = np.abs(a - b); print("X9U check  hdrt(LMT0) vs ODT_Photo(LMT0): mean %.4f p99 %.4f max %.4f" % (d.mean(), np.percentile(d, 99), d.max()))

D = samp(L(C + "odt/ODT_Photo"), samp(L(C + "lmt/LMTLut0"), x))
# D is the ODT output; under the chosen coding it means display-linear T = srgb_dec(D). The X9U chain codes its
# output with sRGB (HDRT), so the value to reproduce is that coding of T (identity for ENC == "srgb"; the shipped table uses g22).
D = M.srgb_enc(srgb_dec(D))
h = M.invert_output(srgb_dec(D))
back = M.hdrt(h)
r = np.abs(back - D); inr = (D.max(1) < 0.999)
print("round trip hdrt(h) vs D: mean %.5f p99 %.4f max %.4f (in-range nodes %d%%: mean %.5f max %.4f)" % (r.mean(), np.percentile(r, 99), r.max(), 100 * inr.mean(), r[inr].mean(), r[inr].max()))
out = h.reshape(64, 64, 64, 3).transpose(3, 0, 1, 2).astype(np.float32)
out.tofile("x7u_exact_%s_pre.lmt" % ENC)
g = lambda l: [round(float(l[1, i, i, i]), 3) for i in (0, 8, 16, 24, 32, 40, 48, 56, 63)]
print("gray  X9U LMT0 ", g(xl)); print("gray  exact h  ", g(out.astype(np.float64)))
print("gray  HDRT(h)  ", [round(float(v), 3) for v in M.hdrt(np.array([[t] * 3 for t in np.array([0, 8, 16, 24, 32, 40, 48, 56, 63]) / 63.0]))[:, 1]][:0] or "")

# ---- exact refinement: solve hdrt(h) = D per node (3 unknowns, 3 equations) on the smooth model ----
TC = M.TONECURVE
def hdrt_smooth(h):
    h = np.clip(h, 0, 1)
    dark = M.srgb_enc(np.clip(M.hlg_inv(h), 0, 1))
    dy = np.clip(dark @ M.W601, 0, 1)
    gain = dark / (dy[..., None] + 1e-4)
    pos = h * 1023; i0 = np.minimum(pos.astype(int), 1022); f = pos - i0
    tc = TC[i0] * (1 - f) + TC[i0 + 1] * f
    return np.clip((tc @ M.W601)[..., None] * gain, 0, 1)

ok = D.max(1) < 0.995                       # nodes that are not clipped at white
hh = h.copy()
res = np.abs(hdrt_smooth(hh) - D).max(1)
lam = np.full(len(hh), 1e-4)                # Levenberg-Marquardt damping, per node
for it in range(60):
    f0 = hdrt_smooth(hh) - D
    J = np.empty((len(hh), 3, 3)); e = 2e-3
    for c in range(3):
        hp = hh.copy(); hp[:, c] = np.minimum(hp[:, c] + e, 1.0)
        hm = hh.copy(); hm[:, c] = np.maximum(hm[:, c] - e, 0.0)
        J[:, :, c] = (hdrt_smooth(hp) - hdrt_smooth(hm)) / (hp[:, c] - hm[:, c])[:, None]
    JT = np.transpose(J, (0, 2, 1))
    A_ = JT @ J + lam[:, None, None] * np.eye(3)
    step = np.linalg.solve(A_, (JT @ f0[:, :, None]))[:, :, 0]
    cand = np.clip(hh - step, 0, 1)
    rc = np.abs(hdrt_smooth(cand) - D).max(1)
    better = (rc < res) & ok
    hh[better] = cand[better]; res[better] = rc[better]
    lam[better] = np.maximum(lam[better] * 0.3, 1e-7); lam[~better] = np.minimum(lam[~better] * 5, 1e3)
back2 = M.hdrt(hh); r2 = np.abs(back2 - D)
print("refined: hdrt(h) vs D  in-range nodes mean %.5f p99 %.4f max %.4f   (before: mean %.5f max %.4f)" % (r2[ok].mean(), np.percentile(r2[ok], 99), r2[ok].max(), r[ok].mean(), r[ok].max()))
print("nodes with residual > 0.01: %d of %d" % ((r2[ok].max(1) > 0.01).sum(), ok.sum()))
print("refined h moved from invert_output by mean %.4f max %.4f" % (np.abs(hh - h)[ok].mean(), np.abs(hh - h)[ok].max()))
out2 = hh.reshape(64, 64, 64, 3).transpose(3, 0, 1, 2).astype(np.float32)
out2.tofile("x7u_exact.lmt" if ENC == "g22" else "x7u_exact_%s.lmt" % ENC)
print("gray refined", g(out2.astype(np.float64)))
