"""Exposure alignment table: EV offsets that put each master look at the stock master look's exposure.

The stock reference is this phone's LMTLut3 through the real HDRT (neutral ramp, main path, no lux blend). For every look the
neutral scene ramp (18 % grey = 0 EV, up to the main-path sensor clip) is rendered and four offsets are solved. An offset s is the
exposure compensation that makes the look match the stock curve in the following sense:
  grey    the output at 0 EV equals the stock output at 0 EV
  mid     the mean CIE L* over -3..+1 EV equals the stock's
  high    the mean CIE L* over +1 EV..sensor clip equals the stock's
  white   the output at +2.32 EV (90 % diffuse white) equals the stock's
A look that cannot reach the stock value with any exposure gets NaN (its alignment is not offered).
Writes app/src/main/java/local/jiege/settings/MasterAlignTable.java.
Usage: python align_table.py [--check]   (--check prints the table instead of writing it)
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hncs_engine as E  # noqa: E402
import looks  # noqa: E402
import master_lmt as M  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
LOOKS = ROOT / "module/payload/master_color/looks"
OUT = ROOT / "app/src/main/java/local/jiege/settings/MasterAlignTable.java"
CCT = float(next((a[6:] for a in sys.argv if a.startswith("--cct=")), 5500.0))
WHITE_EV = float(np.log2(0.9 / 0.18))
HEAD_EV = float(np.log2(M.decode_main(np.ones((1, 3)), False)[0, 1] / M.decode_main(np.full((1, 3), M.GREY_CODE), False)[0, 1]))
GRID = np.linspace(-14.0, HEAD_EV + 8.0, 1400)
SEGMENTS = {"mid": (-3.0, 1.0), "high": (1.0, HEAD_EV)}
STOCK_LUT = refs.STOCK_ODM / "odm/etc/camera/basictone/lmt/LMTLut3"
SHIFT_LIMIT = 4.0   # master_color.cpp accepts |ev| <= 4 (user compensation included)


def lstar(y):
    y = np.clip(y, 0, 1)
    return np.where(y > 216 / 24389, 116 * np.cbrt(y) - 16, 24389 / 27 * y)


def sample(lut, rgb):
    p = np.clip(rgb, 0, 1) * 63
    i = np.minimum(p.astype(int), 62)
    f = p - i
    out = np.zeros_like(rgb)
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = (f[:, 2] if db else 1 - f[:, 2]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 0] if dr else 1 - f[:, 0])
                out += w[:, None] * lut[:, i[:, 2] + db, i[:, 1] + dg, i[:, 0] + dr].T
    return out


def stock_curve():
    """Stock master chain on the neutral ramp: linear -> built-in gamma code -> LMTLut3 -> HDRT -> display-linear."""
    lut = np.fromfile(STOCK_LUT, "<f4").reshape(3, 64, 64, 64).astype(np.float64)
    grey = float(np.interp(M.GREY_CODE, M.BUILTIN, M.X65536))
    lin = np.minimum(grey * 2.0 ** GRID, 1.0)
    code = np.minimum(M.BUILTIN[np.clip((lin * 65535).astype(np.int64), 0, 65535)], M.CAP)
    display = M.hdrt(sample(lut, np.repeat(code[:, None], 3, 1)))
    return np.where(display < 0.04045, display / 12.92, ((display + 0.055) / 1.055) ** 2.4)[:, 1]


def look_curve(render):
    return render(np.repeat((0.18 * 2.0 ** GRID)[:, None], 3, 1))[:, 1]


def segment_mean(curve, shift, lo, hi):
    evs = np.linspace(lo, hi, 160)
    return float(lstar(np.interp(evs + shift, GRID, curve)).mean())


def solve_monotone(fn, target):
    """Shift s with fn(s) == target for a non-decreasing fn on [-SHIFT_LIMIT, SHIFT_LIMIT]; NaN if out of reach."""
    lo, hi = -SHIFT_LIMIT, SHIFT_LIMIT
    if fn(lo) > target or fn(hi) < target:
        return float("nan")
    for _ in range(50):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if fn(mid) < target else (lo, mid)
    return (lo + hi) / 2


def offsets(curve, stock):
    out = {}
    out["grey"] = solve_monotone(lambda s: float(np.interp(s, GRID, curve)), float(np.interp(0.0, GRID, stock)))
    for name, (lo, hi) in SEGMENTS.items():
        out[name] = solve_monotone(lambda s: segment_mean(curve, s, lo, hi), segment_mean(stock, 0.0, lo, hi))
    out["white"] = solve_monotone(lambda s: float(np.interp(WHITE_EV + s, GRID, curve)), float(np.interp(WHITE_EV, GRID, stock)))
    return out


def main():
    stock = stock_curve()
    engine = E.Engine(CCT)
    renders = {"hncs": lambda s: engine.render(s / 0.18 * E.GREY_SCENE * engine.k)}
    for path in sorted(LOOKS.glob("*.bin")):
        if path.stem.startswith("hncs_"):
            continue   # intent / film-curve variants: tables not covered by the reference renderer
        packed = looks.Packed(path)
        renders[path.stem] = lambda s, packed=packed: packed.render(s, CCT)
    table = {name: offsets(look_curve(render), stock) for name, render in renders.items()}
    if "--check" in sys.argv:
        for name, value in table.items():
            print(f"{name:48s}", " ".join(f"{k} {value[k]:+.2f}" for k in ("grey", "mid", "high", "white")))
        return
    rows = []
    for name, value in table.items():
        cells = ", ".join("Float.NaN" if np.isnan(value[k]) else f"{value[k]:.2f}f" for k in ("grey", "mid", "high", "white"))
        rows.append(f'        put("{name}", {cells});')
    OUT.write_text(
        "package local.jiege.settings;\n\n"
        "import java.util.HashMap;\nimport java.util.Map;\n\n"
        "/** Generated by tools/mastercolor/align_table.py; do not edit. EV offsets {grey, mid, high, white}, NaN = not reachable. */\n"
        "final class MasterAlignTable {\n"
        "    static final Map<String, float[]> OFFSETS = new HashMap<>();\n\n"
        "    private static void put(String look, float grey, float mid, float high, float white) {\n"
        "        OFFSETS.put(look, new float[] {grey, mid, high, white});\n    }\n\n"
        "    static {\n" + "\n".join(rows) + "\n    }\n\n    private MasterAlignTable() {}\n}\n",
        encoding="utf-8", newline="\n")
    print(f"{len(table)} looks -> {OUT}")


if __name__ == "__main__":
    main()
