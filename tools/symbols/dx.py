"""Helpers over the dexdump pickles: class/method matching between two app versions."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402
import os
# Directory with the dexdump indexes idx_{old,new}_{cam,gal}.pkl (index_dump.py)
WORK = os.environ.get("SYMBOLS_WORK", str(refs.SYMBOL_INDEX))
import pickle, re, collections
_cache = {}
def load(tag):
    if tag not in _cache:
        _cache[tag] = pickle.load(open(os.path.join(WORK, f"idx_{tag}.pkl"), "rb"))
    return _cache[tag]
def D(n):  # java name -> descriptor
    return n if n.startswith("L") else "L" + n.replace(".", "/") + ";"
def J(d): return d[1:-1].replace("/", ".")
OBF = re.compile(r"^[A-Za-z][A-Za-z0-9]{0,3}$")
def is_obf(desc):
    if not desc.startswith("L"): return False
    simple = desc[1:-1].split("/")[-1]
    parts = simple.split("$")
    return any(OBF.match(p) and not p.isdigit() for p in parts) and not desc.startswith(("Ljava/", "Landroid/", "Lkotlin/L"))
def norm_type(t):
    arr = len(t) - len(t.lstrip("["))
    base = t[arr:]
    if base.startswith("L") and is_obf(base): base = "O"
    return "[" * arr + base
def norm_proto(p):
    m = re.match(r"\((.*)\)(.*)", p)
    args = re.findall(r"\[*(?:L[^;]+;|[ZBSCIJFDV])", m.group(1))
    return "(" + ",".join(norm_type(a) for a in args) + ")" + norm_type(m.group(2))
def cls_strings(c): return set(s for m in c["methods"] for s in m["strings"])
def cls_shape(c):
    return (collections.Counter(norm_type(f["type"]) for f in c["sfields"] + c["ifields"]),
            collections.Counter(norm_proto(m["type"]) for m in c["methods"]))
def ext_calls(m):
    return set((o, n, p) for o, n, p in m["calls"] if not is_obf(o) and not OBF.match(n) or n in ("<init>",) and not is_obf(o))
def jacc(a, b):
    if not a and not b: return 1.0
    return len(a & b) / max(1, len(a | b))
def cnt_sim(a, b):
    inter = sum((a & b).values()); tot = sum((a | b).values())
    return inter / tot if tot else 1.0
_sindex = {}
def string_index(tag):
    if tag not in _sindex:
        idx = collections.defaultdict(set)
        for n, c in load(tag).items():
            for s in cls_strings(c): idx[s].add(n)
        _sindex[tag] = idx
    return _sindex[tag]
def class_score(a, b):
    sa, sb = cls_strings(a), cls_strings(b)
    fa, ma = cls_shape(a); fb, mb = cls_shape(b)
    sup = 1.0 if norm_type(a["super"] or "") == norm_type(b["super"] or "") else 0.0
    ea = set(x for m in a["methods"] for x in ext_calls(m)); eb = set(x for m in b["methods"] for x in ext_calls(m))
    return round(0.4 * jacc(sa, sb) + 0.2 * cnt_sim(ma, mb) + 0.15 * cnt_sim(fa, fb) + 0.15 * jacc(ea, eb) + 0.1 * sup, 4)
def match_class(old_name, otag="old_cam", ntag="new_cam", top=3):
    old, new = load(otag), load(ntag)
    a = old[D(old_name)]
    cands = set()
    idx = string_index(ntag)
    strs = sorted(cls_strings(a), key=lambda s: len(idx.get(s, ())))
    for s in strs[:12]:
        if len(idx.get(s, ())) < 200: cands |= idx.get(s, set())
    if not cands or not strs:
        # structural fallback: same package depth & same super normalized
        sup = norm_type(a["super"] or "")
        cands = {n for n, c in new.items() if norm_type(c["super"] or "") == sup and abs(len(c["methods"]) - len(a["methods"])) <= max(3, len(a["methods"]) // 5)}
    scored = sorted(((class_score(a, new[n]), J(n)) for n in cands), reverse=True)[:top]
    return scored
def method_sig(m):
    return (norm_proto(m["type"]), frozenset(m["strings"]), frozenset(ext_calls(m)), len(m["calls"]))
def match_method(old_cls, new_cls, name, proto=None, otag="old_cam", ntag="new_cam", top=3):
    a = load(otag)[D(old_cls)]; b = load(ntag)[D(new_cls)]
    ms = [m for m in a["methods"] if m["name"] == name and (proto is None or m["type"] == proto)]
    out = []
    for m in ms:
        np_ = norm_proto(m["type"])
        sc = []
        for x in b["methods"]:
            s = (1.0 if norm_proto(x["type"]) == np_ else 0) * 0.4 + 0.3 * jacc(set(m["strings"]), set(x["strings"])) + 0.2 * jacc(ext_calls(m), ext_calls(x)) + 0.1 * (1 - abs(len(m["calls"]) - len(x["calls"])) / max(1, len(m["calls"]), len(x["calls"])))
            sc.append((round(s, 3), x["name"], x["type"]))
        out.append((m["name"], m["type"], sorted(sc, reverse=True)[:top]))
    return out
def show(tag, name, members=None, maxs=6):
    c = load(tag)[D(name)]
    print(f"== {tag} {name} super={J(c['super']) if c['super'] else None} ifaces={[J(i) for i in c['ifaces']]} dex={c['dex']}")
    for m in c["methods"]:
        if members and m["name"] not in members: continue
        print(f"  m {m['name']}{m['type']} acc={m.get('access',0):#x} strs={m['strings'][:maxs]} calls={len(m['calls'])}")
    for f in c["sfields"] + c["ifields"]:
        if members and f["name"] not in members: continue
        print(f"  f {f['name']}:{f['type']} acc={f.get('access',0):#x}")
def method(tag, cls, name, proto=None):
    c = load(tag)[D(cls)]
    return [m for m in c["methods"] if m["name"] == name and (proto is None or m["type"] == proto)]
def dump_method(tag, cls, name, proto=None):
    for m in method(tag, cls, name, proto):
        print(f"--- {tag} {cls}.{name}{m['type']}")
        print("  strings:", m["strings"]); print("  ints:", m["ints"][:20])
        for c in m["calls"]: print("  call", J(c[0]), c[1], c[2])
        for f in m["fields"]: print("  fld", f[0], J(f[1]), f[2], f[3])
def unique_strings(old_cls, new_cls, otag="old_cam", ntag="new_cam", n=8):
    """Strings used by this class only (in both versions)."""
    io, inn = string_index(otag), string_index(ntag)
    so = cls_strings(load(otag)[D(old_cls)]); sn = cls_strings(load(ntag)[D(new_cls)])
    both = [s for s in so & sn if io[s] == {D(old_cls)} and inn[s] == {D(new_cls)}]
    return sorted(both, key=len)[:n]
def unique_method_strings(old_cls, name, proto, new_cls, new_name, otag="old_cam", ntag="new_cam"):
    mo = method(otag, old_cls, name, proto)[0]; mn = [m for m in load(ntag)[D(new_cls)]["methods"] if m["name"] == new_name][0]
    common = set(mo["strings"]) & set(mn["strings"])
    # count methods using each string, per version
    def users(tag, s):
        return sum(1 for c in load(tag).values() for m in c["methods"] if s in m["strings"])
    return sorted(((users(otag, s), users(ntag, s), s) for s in common))[:8]
def feats(m):
    s = {("s", x) for x in m["strings"]}
    s |= {("i", f"{o}->{n}{p}") for o, n, p in m["calls"] if not is_obf(o) and not (OBF.match(n) and o.startswith("Lcom/oplus"))}
    return s
def distinguish(ocls, oname, oproto, ncls, nname, otag="old_cam", ntag="new_cam"):
    """Features of the target that no same-proto sibling has, in both versions."""
    out = []
    for tag, cls, name in ((otag, ocls, oname), (ntag, ncls, nname)):
        c = load(tag)[D(cls)]
        tgt = [m for m in c["methods"] if m["name"] == name and (m["type"] == oproto or tag == ntag)][0]
        sib = [m for m in c["methods"] if m is not tgt and norm_proto(m["type"]) == norm_proto(tgt["type"])]
        others = set().union(*[feats(m) for m in sib]) if sib else set()
        out.append(feats(tgt) - others)
    return sorted(out[0] & out[1])
