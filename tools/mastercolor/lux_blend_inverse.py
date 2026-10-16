"""Offline inverse of the luxIndex > 280 proproc encoding at the 64^3 LMT input nodes.

The blended encoding (master_lmt.encode_main(..., lux_blend=True), from the proproc_rgb_p010 kernel)
depends only on the built-in gamma table, so its inverse at the fixed LMT nodes is the same for
every frame. It is solved once here and shipped; the device only looks it up.

1. Dense forward sampling (uniform in pure-table code space): each encoded sample is a candidate
   for its nearest node; the closest candidate per node is kept.
2. Damped Newton refinement on a smooth (interpolated) version of the kernel lookup.
3. Nodes the encoder never reaches (a channel above the 3178/4095 cap) take the solution of the
   nearest reachable node; the kernel clamps every code to the cap, so these only feed interpolation.

End to end on random scene colours (kernel encode -> baked 64^3 LUT -> HNCS output) this path has
the same error as the pure-table path; the remainder is the 64^3 interpolation shared by both.
Output: numpy float32 [B][G][R][3], linear post-CCM RGB.
"""
import sys

import numpy as np
from scipy.spatial import cKDTree

import master_lmt as M

N = M.N


def smooth_encode(lin):
    lookup = lambda v: np.interp(np.clip(v, 0, 1), M.X65536, M.BUILTIN)
    lin = np.clip(lin, 0, 1)
    ori = lookup(lin)
    s = np.sort(lin, -1)
    d2 = np.clip((1 - np.clip(0.01 - lin, 0, 0.01) * 90) ** 3, 0, 1)
    w = (np.clip(s[..., 1] - s[..., 0], 0, 1)[..., None] + 1e-9) ** d2
    gray = lin @ M.W601
    gv = np.clip(lin ** (1 / 2.2) * (lookup(gray) / np.clip(gray ** (1 / 2.2), 1e-9, 1))[..., None], 0, 1)
    return np.minimum(ori * (1 - w) + gv * w, M.CAP)


def refine(target, lin, iters=15):
    f = smooth_encode(lin)
    r = np.abs(f - target).max(-1)
    for _ in range(iters):
        J = np.empty(lin.shape + (3,))
        for k in range(3):
            d = np.zeros(3)
            d[k] = 1e-6
            J[..., k] = (smooth_encode(np.clip(lin + d, 0, 1)) - f) / 1e-6
        step = np.linalg.solve(J + 1e-9 * np.eye(3), (target - f)[..., None])[..., 0]
        t = np.ones(len(lin))
        done = np.zeros(len(lin), bool)
        for _ in range(10):
            cand = np.clip(lin + t[:, None] * step, 0, 1)
            fc = smooth_encode(cand)
            rc = np.abs(fc - target).max(-1)
            better = (rc < r) & ~done
            lin[better], f[better], r[better] = cand[better], fc[better], rc[better]
            done |= better
            t = np.where(done, t, t * 0.5)
    return lin


def build(samples_per_axis=320, chunk=4_000_000):
    lin_axis = M.invert_table(M.BUILTIN, np.linspace(0, 1, samples_per_axis) * M.CAP)
    best = np.full(N ** 3, np.inf)
    sol = np.zeros((N ** 3, 3))
    idx = np.stack(np.meshgrid(*[np.arange(samples_per_axis)] * 3, indexing="ij"), -1).reshape(-1, 3)
    for s in range(0, len(idx), chunk):
        lin = lin_axis[idx[s:s + chunk]]
        code = M.encode_main(lin, True)
        node = np.rint(code * (N - 1)).astype(np.int64)
        err = np.abs(code * (N - 1) - node).max(1)
        flat = (node[:, 2] * N + node[:, 1]) * N + node[:, 0]
        order = np.lexsort((err, flat))
        flat, err, lin = flat[order], err[order], lin[order]
        first = np.r_[True, flat[1:] != flat[:-1]]
        f, e, l = flat[first], err[first], lin[first]
        take = e < best[f]
        best[f[take]], sol[f[take]] = e[take], l[take]
    axis = np.arange(N) / (N - 1)
    b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")
    codes = np.stack([r, g, b], -1).reshape(-1, 3)
    reached = np.isfinite(best)
    sol[reached] = refine(np.minimum(codes[reached], M.CAP), sol[reached].copy())
    tree = cKDTree(codes[reached])
    _, near = tree.query(np.minimum(codes[~reached], M.CAP))
    sol[~reached] = sol[reached][near]
    return sol.reshape(N, N, N, 3), reached.mean()


if __name__ == "__main__":
    table, reached = build()
    np.save(sys.argv[1], table.astype(np.float32))
    print("lux blend inverse:", sys.argv[1], "reached", round(float(reached), 4))
