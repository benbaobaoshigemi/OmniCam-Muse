"""Checks the blend tables written by blend_check.cpp (master_core.cpp blendWithStock).

  python blend_check.py <out dir of blend_check.exe> <stock lmt dir>
Properties (e = HLG inverse OETF of the table output, chromaticity = e / sum(e)):
  1/1 is the look, 0/0 the stock table; a colour weight of 0 keeps the stock chromaticity, of 1 the look's;
  the tone weight never changes the chromaticity; on the neutral axis the output is the geometric blend.
"""
import sys
from pathlib import Path

import numpy as np

out, stock = Path(sys.argv[1]), Path(sys.argv[2])
N = 64
A, B, C = 0.17883277, 0.28466892, 0.55991073


def load(path):
    return np.fromfile(path, dtype="<f4").reshape(3, N, N, N)


def e_of(h):
    h = np.clip(h.astype(np.float64), 0, 1)
    return np.where(h <= 0.5, h * h / 3, (np.exp((h - C) / A) + B) / 12)


def chroma(t):
    e = e_of(t)
    return e / np.maximum(e.sum(0), 1e-9), e


look = load(out / "look.f32")
st = [load(stock / f"LMTLut{i}") for i in range(6)]
bl = lambda wt, wc, i: load(out / f"blend_{int(wt*100)}_{int(wc*100)}_{i}.f32")
ok = True


def check(name, value, limit):
    global ok
    good = value <= limit
    ok &= good
    print(f"{'PASS' if good else 'FAIL'}  {name}: {value:.6f} (limit {limit})")


check("1/1 equals the look (max |diff|)", np.abs(bl(1, 1, 0) - look).max(), 1e-6)
check("0/0 equals stock tables (max |diff|)", max(np.abs(bl(0, 0, i) - st[i]).max() for i in range(6)), 1e-6)

for i in (0, 3, 5):
    c_look, e_look = chroma(look)
    c_stock, e_stock = chroma(st[i])
    valid = (e_stock.min(0) > 1e-3) & (e_look.min(0) > 1e-3) & (e_stock.max(0) < 0.9) & (e_look.max(0) < 0.9)
    for wt, wc, ref, label in ((1, 0, c_stock, "stock"), (0, 1, c_look, "look")):
        o = bl(wt, wc, i)
        c_out, e_out = chroma(o)
        m = valid & (e_out.max(0) < 0.98)
        check(f"table {i}: {wt:g}/{wc:g} chromaticity = {label}'s (max, {m.sum()} nodes)", np.abs(c_out - ref)[:, m].max(), 2e-3)
    cs = [chroma(bl(wt, 0.5, i)) for wt in (0.25, 0.5, 0.75)]
    m = valid & (cs[0][1].max(0) < 0.98) & (cs[1][1].max(0) < 0.98) & (cs[2][1].max(0) < 0.98)
    check(f"table {i}: tone weight leaves chromaticity alone (max spread over 0.25/0.5/0.75)",
          max(np.abs(cs[0][0] - cs[1][0])[:, m].max(), np.abs(cs[2][0] - cs[1][0])[:, m].max()), 2e-3)

idx = np.arange(N)
for i in (0, 3):
    _, e_s = chroma(st[i])
    _, e_l = chroma(look)
    for wt in (0.25, 0.5, 0.75):
        _, e_o = chroma(bl(wt, 0.5, i))
        g = lambda e: e[:, idx, idx, idx].mean(0)
        gs, gl, go = g(e_s), g(e_l), g(e_o)
        expect = np.exp((1 - wt) * np.log(np.maximum(gs, 1e-5)) + wt * np.log(np.maximum(gl, 1e-5)))
        sel = (expect > 1e-3) & (expect < 0.9)
        check(f"table {i}: neutral axis at wt={wt} is the geometric blend (max rel. err)", (np.abs(go - expect) / expect)[sel].max(), 0.08)

print("PASS" if ok else "FAIL", "overall")
sys.exit(0 if ok else 1)
