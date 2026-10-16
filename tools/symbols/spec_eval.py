"""Offline replay of the hook symbol spec (assets/symbols.json), same semantics as the runtime
resolver (SymbolResolver.java, DexKit-backed).

Symbol kinds and keys (all constraints must hold; a symbol must match exactly one target unless
"multi" is set, then it must match at least one):
  class : name | strings (used by the class) | super | ifaces | fieldTypes
          | superOf @C | interfaceOf @C | typeOf @F | returnOf @M | paramOf [@M, i]
  method: in (class, optional = whole dex), name, params (null = any), ret, static, abstract,
          strings, invokes (@M or descriptor), fields (@F or descriptor; read or written),
          calledBy (@M list) | overrideOf @M (same name/proto in "in")
          | implOf @M (every implementation of an interface/abstract method; multi)
  field : in, name, type, static, readBy, writtenBy (@M lists)
Types: Java names ("int", "java.lang.String", "a.B[]") or "@Id" (class, or the class of a symbol).
"""
import json, re, sys
import dx

PRIM = {"int": "I", "boolean": "Z", "float": "F", "long": "J", "double": "D", "void": "V", "byte": "B", "char": "C", "short": "S"}
ACC_STATIC, ACC_ABSTRACT = 0x8, 0x400
ARG = re.compile(r"\[*(?:L[^;]+;|[ZBSCIJFDV])")


class Unresolved(Exception):
    pass


class Replay:
    def __init__(self, tag, spec):
        self.C = dx.load(tag)
        self.spec = spec
        self.res = {}
        self._callers = None

    # --- references -------------------------------------------------------------------------
    def sym(self, v):
        k = v[1:]
        if k not in self.res:
            raise Unresolved(k)
        return self.res[k]

    def cls_of(self, v):
        """Class descriptor for a class name or @symbol (a member symbol gives its type/declaring class)."""
        if v.startswith("@"):
            r = self.sym(v)
            if isinstance(r, list):
                raise ValueError("multi symbol used as class: " + v)
            return r["class"]
        return dx.D(v)

    def tdesc(self, t):
        arr = 0
        while t.endswith("[]"):
            t, arr = t[:-2], arr + 1
        d = PRIM.get(t) or self.cls_of(t)
        return "[" * arr + d

    def mdesc(self, v):
        if not v.startswith("@"):
            return v
        r = self.sym(v)
        return f"{r['class']}->{r['name']}{r['proto']}"

    def fdesc(self, v):
        if not v.startswith("@"):
            return v
        r = self.sym(v)
        return f"{r['class']}->{r['name']}:{r['type']}"

    # --- dex facts --------------------------------------------------------------------------
    def method_obj(self, desc):
        cls, rest = desc.split("->")
        name, proto = rest.split("(", 1)
        for m in self.C[cls]["methods"]:
            if m["name"] == name and m["type"] == "(" + proto:
                return m
        raise KeyError(desc)

    def callers(self):
        if self._callers is None:
            idx = {}
            for d, c in self.C.items():
                for m in c["methods"]:
                    me = f"{d}->{m['name']}{m['type']}"
                    for o, n, p in m["calls"]:
                        idx.setdefault(f"{o}->{n}{p}", set()).add(me)
            self._callers = idx
        return self._callers

    def assignable(self, sub, sup):
        seen, todo = set(), [sub]
        while todo:
            d = todo.pop()
            if d == sup:
                return True
            if d in seen or d not in self.C:
                continue
            seen.add(d)
            c = self.C[d]
            if c["super"]:
                todo.append(c["super"])
            todo.extend(c["ifaces"])
        return False

    # --- matching ---------------------------------------------------------------------------
    def find(self, s):
        return getattr(self, "find_" + s["kind"])(s)

    def find_class(self, s):
        if "name" in s:
            return [{"class": dx.D(s["name"])}] if dx.D(s["name"]) in self.C else []
        if "superOf" in s:
            return [{"class": self.C[self.cls_of(s["superOf"])]["super"]}]
        if "interfaceOf" in s:
            return [{"class": i} for i in self.C[self.cls_of(s["interfaceOf"])]["ifaces"]]
        if "typeOf" in s:
            r = self.sym(s["typeOf"])
            return [{"class": r["type"]}]
        if "ownerOf" in s:
            return [{"class": self.sym(s["ownerOf"])["class"]}]
        if "returnOf" in s:
            r = self.sym(s["returnOf"])
            return [{"class": r["proto"].split(")")[1]}]
        if "paramOf" in s:
            r = self.sym(s["paramOf"][0])
            return [{"class": ARG.findall(r["proto"].split(")")[0][1:])[s["paramOf"][1]]}]
        want = set(s.get("strings", []))
        clinit = set(s.get("clinitStrings", []))
        sup = self.cls_of(s["super"]) if "super" in s else None
        ifaces = [self.cls_of(i) for i in s.get("ifaces", [])]
        ftypes = [self.tdesc(t) for t in s.get("fieldTypes", [])]
        hits = []
        for d, c in self.C.items():
            if sup and c["super"] != sup:
                continue
            if any(i not in c["ifaces"] for i in ifaces):
                continue
            if want and not want <= {x for m in c["methods"] for x in m["strings"]}:
                continue
            if clinit and not any(m["name"] == "<clinit>" and clinit <= set(m["strings"]) for m in c["methods"]):
                continue
            sft = [self.tdesc(t) for t in s.get("staticFieldTypes", [])]
            if sft and any(t not in [f["type"] for f in c["sfields"]] for t in sft):
                continue
            if ftypes:
                have = [f["type"] for f in c["sfields"] + c["ifields"]]
                if any(t not in have for t in ftypes):
                    continue
            hits.append({"class": d})
        return hits

    def find_method(self, s):
        if "overrideOf" in s:
            base = self.sym(s["overrideOf"])
            owner = self.cls_of(s["in"])
            return [{"class": owner, "name": m["name"], "proto": m["type"]} for m in self.C[owner]["methods"]
                    if m["name"] == base["name"] and m["type"] == base["proto"]]
        if "implOf" in s:
            base = self.sym(s["implOf"])
            return [{"class": d, "name": m["name"], "proto": m["type"]} for d, c in self.C.items()
                    if d != base["class"] and self.assignable(d, base["class"])
                    for m in c["methods"] if m["name"] == base["name"] and m["type"] == base["proto"]
                    and not m.get("access", 0) & ACC_ABSTRACT]
        owners = [self.cls_of(s["in"])] if "in" in s else list(self.C)
        pl = [None if p is None else self.tdesc(p) for p in s["params"]] if "params" in s else None
        rd = self.tdesc(s["ret"]) if "ret" in s else None
        inv = [self.mdesc(i) for i in s.get("invokes", [])]
        flds = [self.fdesc(f) for f in s.get("fields", [])]
        callers = [self.mdesc(c) for c in s.get("calledBy", [])]
        callee_sets = [set(f"{o}->{n}{p}" for o, n, p in self.method_obj(c)["calls"]) for c in callers]
        hits = []
        for od in owners:
            c = self.C.get(od)
            if c is None:
                continue
            for m in c["methods"]:
                if m["name"] in ("<init>", "<clinit>"):
                    continue
                if "name" in s and m["name"] != s["name"]:
                    continue
                args, ret = m["type"][1:].split(")")
                if pl is not None:
                    a = ARG.findall(args)
                    if len(a) != len(pl) or any(w is not None and w != x for w, x in zip(pl, a)):
                        continue
                if rd is not None and ret != rd:
                    continue
                acc = m.get("access", 0)
                if "static" in s and bool(acc & ACC_STATIC) != s["static"]:
                    continue
                if "abstract" in s and bool(acc & ACC_ABSTRACT) != s["abstract"]:
                    continue
                if not set(s.get("numbers", [])) <= set(m["ints"]):
                    continue
                if not set(s.get("strings", [])) <= set(m["strings"]):
                    continue
                me = f"{od}->{m['name']}{m['type']}"
                if inv:
                    called = {f"{o}->{n}{p}" for o, n, p in m["calls"]}
                    if any(i not in called for i in inv):
                        continue
                if s.get("notInvokes"):
                    called = {f"{o}->{n}{p}" for o, n, p in m["calls"]}
                    if any(self.mdesc(i) in called for i in s["notInvokes"]):
                        continue
                if flds:
                    used = {f"{o}->{n}:{t}" for _, o, n, t in m["fields"]}
                    if any(f not in used for f in flds):
                        continue
                if any(me not in cs for cs in callee_sets):
                    continue
                hits.append({"class": od, "name": m["name"], "proto": m["type"]})
        return hits

    def find_field(self, s):
        if "in" not in s:
            hits = []
            for od in self.C:
                hits += self.find_field(dict(s, **{"in": "@@" + od}))
            return hits
        od = s["in"][2:] if s["in"].startswith("@@") else self.cls_of(s["in"])
        c = self.C.get(od)
        if c is None:
            return []
        td = self.tdesc(s["type"]) if "type" in s else None
        access = [(self.mdesc(m), "get") for m in s.get("readBy", [])] + [(self.mdesc(m), "put") for m in s.get("writtenBy", [])]
        excluded = [self.mdesc(m) for m in s.get("notWrittenBy", [])]
        hits = []
        for group, static in ((c["sfields"], True), (c["ifields"], False)):
            for f in group:
                if "name" in s and f["name"] != s["name"]:
                    continue
                if td and f["type"] != td:
                    continue
                if "static" in s and s["static"] != static:
                    continue
                fd = f"{od}->{f['name']}:{f['type']}"
                if all(any(op[1:].startswith(kind) and f"{o}->{n}:{t}" == fd for op, o, n, t in self.method_obj(md)["fields"])
                       for md, kind in access) and not any(
                           any(op[1:].startswith("put") and f"{o}->{n}:{t}" == fd for op, o, n, t in self.method_obj(md)["fields"])
                           for md in excluded):
                    hits.append({"class": od, "name": f["name"], "type": f["type"]})
        return hits

    def run(self):
        out, pending, progress = {}, dict(self.spec), True
        while pending and progress:
            progress = False
            for sid, s in list(pending.items()):
                try:
                    hits = self.find(s)
                except Unresolved:
                    continue
                del pending[sid]
                progress = True
                out[sid] = hits
                ok = (len(hits) >= 1) if s.get("multi") else (len(hits) == 1)
                if ok:
                    self.res[sid] = hits if s.get("multi") else hits[0]
        for sid in pending:
            out[sid] = "unresolved dependency"
        return out


def label(h):
    return dx.J(h["class"]) + ("." + h["name"] if "name" in h else "")


def report(spec, tags, expect=None, verbose=True):
    results = {t: Replay(t, spec).run() for t in tags}
    bad = 0
    for sid, s in spec.items():
        cells = []
        for t in tags:
            h = results[t][sid]
            if isinstance(h, str):
                cells.append("!! " + h); bad += 1; continue
            if s.get("multi"):
                if not h:
                    cells.append("!! none"); bad += 1
                else:
                    cells.append("{" + ", ".join(label(x) for x in h) + "}")
                continue
            if len(h) != 1:
                cells.append(f"!! {len(h)} hits {[label(x) for x in h[:4]]}"); bad += 1; continue
            cells.append(label(h[0]))
        exp = (expect or {}).get(sid)
        if exp and not s.get("multi") and not cells[0].startswith("!!") and cells[0] != exp:
            cells[0] += f"  !! expected {exp}"; bad += 1
        if verbose or any("!!" in c for c in cells):
            print(f"{sid:34s} " + " | ".join(cells))
    print(f"{len(spec)} symbols, problems: {bad}")
    return bad


if __name__ == "__main__":
    spec = json.load(open(sys.argv[1], encoding="utf-8"))
    expect = json.load(open(sys.argv[2], encoding="utf-8")) if len(sys.argv) > 2 else {}
    total = 0
    for app, tags in (("camera", ("old_cam", "new_cam")), ("gallery", ("old_gal", "new_gal"))):
        print(f"== {app}: {tags}")
        total += report(spec[app], tags, expect.get(app))
    sys.exit(1 if total else 0)
