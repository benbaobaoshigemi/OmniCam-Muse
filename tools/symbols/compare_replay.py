"""Compares the DexKit replay (module SymbolSearch on the JVM) with the Python replay, per symbol."""
import os
SPEC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "symbols.json")
import json, sys
import dx, spec_eval

OUT = r"\\wsl.localhost\Ubuntu-24.04\home\zjy\dkbuild\out"
spec = json.load(open(SPEC, encoding="utf-8"))
jobs = [("camera", "old_cam", "OplusCamera-current"), ("camera", "new_cam", "cam_7.006.103"),
        ("gallery", "old_gal", "OppoGallery2-x9u"), ("gallery", "new_gal", "gallery_17.10.7")]

def py_entry(h):
    cls = dx.J(h["class"])
    if "proto" in h:
        args, ret = h["proto"][1:].split(")")
        types = [jt(a) for a in spec_eval.ARG.findall(args)]
        return "\t".join(["m", cls, h["name"], jt(ret)] + types)
    if "type" in h:
        return "\t".join(["f", cls, h["name"], jt(h["type"])])
    return "\t".join(["c", cls])

PRIM = {v: k for k, v in spec_eval.PRIM.items()}
def jt(d):
    arr = 0
    while d.startswith("["):
        d, arr = d[1:], arr + 1
    base = PRIM.get(d) or dx.J(d)
    return base + "[]" * arr

bad = 0
for section, tag, name in jobs:
    kit = json.load(open(f"{OUT}\\{section}-{name}.json", encoding="utf-8"))
    rp = spec_eval.Replay(tag, spec[section]); out = rp.run()
    for sid in spec[section]:
        k = sorted(kit["resolved"].get(sid, []))
        p = sorted(py_entry(h) for h in out[sid]) if not isinstance(out[sid], str) and (len(out[sid]) == 1 or spec[section][sid].get("multi")) else []
        if k != p:
            bad += 1
            print(f"{section}/{tag} {sid}\n   dexkit={k}\n   python={p}")
    print(f"{section}/{tag}: compared {len(spec[section])} symbols, dexkit {kit['millis']} ms")
print("differences:", bad)
sys.exit(1 if bad else 0)
