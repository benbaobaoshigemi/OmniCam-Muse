"""Check selection coverage, source compatibility, asset references, and native/reference parity."""
import argparse
import itertools
import json
from pathlib import Path
import re
import struct
import subprocess
import sys

import numpy as np

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
LOOKS = PROJECT / "module/payload/master_color/looks"


def array(source, name):
    m = re.search(r"String\[\] " + name + r" = \{(.*?)\};", source, re.S)
    assert m, name
    return re.findall(r'"([^"\n]*)"', m[1])


def validate():
    catalog = json.loads((HERE / "catalog.json").read_text(encoding="utf-8"))
    source = (PROJECT / "app/src/main/java/local/jiege/settings/MasterLooks.java").read_text(encoding="utf-8")
    assert array(source, "FILM_IDS") == [f["id"] for f in catalog["films"]]
    assert array(source, "PRINT_IDS") == [""] + [p["id"] for p in catalog["prints"]]
    assert array(source, "FILM_DEFAULT_PRINTS") == [f["defaultPrint"] or "" for f in catalog["films"]]
    names = ["hncs", "hncs_standard_reproduction", "hncs_reproduction_standard", "hncs_reproduction_reproduction", "leica_m9", "x8u", "x7u", "agx", "sigmoid", "filmic"]
    for prefix, group in [("lumix_", "LUMIX_IDS"), ("canon_", "CANON_IDS")]:
        names += [prefix + v for v in array(source, group)]
    names += ["basic_" + v for v in array(source, "BASIC_IDS")]
    # These seven assets remain in the module payload; Fuji is no longer a UI family.
    legacy_fuji = ["provia", "velvia", "astia", "classic_chrome", "classic_negative", "nostalgic_neg", "reala_ace"]
    names += ["fuji_" + name for name in legacy_fuji]
    for f in catalog["films"]:
        base = "film_" + f["id"]
        names.append(base)
        if not f["positive"]:
            names += [base + "__" + p["id"] for p in catalog["prints"]]
    references = set()
    for name in names:
        if name == "hncs": continue
        if name == "x8u":
            assert sorted(p.name for p in (LOOKS / "x8u").iterdir()) == ["LMTLut%d" % i for i in range(4)]
            assert all(p.stat().st_size == 3*64**3*4 for p in (LOOKS / "x8u").iterdir())
            continue
        path = LOOKS / (name + (".lmt" if name == "x7u" else ".bin"))
        assert path.is_file(), path
        if name == "x7u":
            assert path.stat().st_size == 3*64**3*4
            continue
        blob = path.read_bytes()
        assert blob[:8] == b"MCOLLOOK", path
        version, kind = struct.unpack_from("<II", blob, 8)
        assert version == 1
        if kind != 8: continue
        at = 24
        stages = []
        for _ in range(2):
            n = struct.unpack_from("<I", blob, at)[0]; at += 4
            stage = blob[at:at+n].decode("ascii"); at += n
            stages.append(stage)
            if stage:
                assert re.fullmatch(r"[a-z0-9_]{1,64}", stage)
                target = LOOKS / "tables" / (stage + ".u16")
                assert target.stat().st_size == 65**3*3*2, target
                references.add(target.name)
        assert at == len(blob) and stages[0], path
    assert references == {p.name for p in (LOOKS / "tables").glob("*.u16")}
    print(f"PASS catalog: {len(names)} looks, {len(references)} lattices", flush=True)
    return names


def parity(names):
    import master_lmt as M
    import looks
    work = PROJECT / "build/mastercolor_check"
    work.mkdir(parents=True, exist_ok=True)
    # Parameter overrides: "<look> p0 p1 p2 p3" (nan = default); the native check writes <look>_p0_p1... .f32
    overrides = ["basic_gamma 1.8", "sigmoid 2.2 0.5", "sigmoid 1.0 0.0", "agx -8 5 2 2", "agx nan nan nan 2.5",
                 "filmic -6 3.5", "filmic -8.5 6", "agx -10 6.5 1.5 3.3"]
    names = names + overrides
    (work / "names.txt").write_text("\n".join(names)+"\n", encoding="ascii")
    ccm = [1.0703125,-0.1328125,0.0625,-0.09375,1.109375,-0.03125,0.046875,-0.5703125,1.5234375]
    # Stable host fixture; the lens profile is the main camera's Adobe Standard data from scene.bin.
    lens = "main23"
    key = b"MCOLKEY8" + struct.pack("<ii9fiiI", 2, 5000, *ccm, 0, 0, 4) + b"hncs" + struct.pack("<6f",0,1,1,0.5,1,1.5)
    key += struct.pack("<I",len(lens)) + lens.encode("ascii") + struct.pack("<4f",*[float("nan")]*4)
    (work / "key.bin").write_bytes(key)
    exe = work / "catalog_check.exe"
    subprocess.run([sys.executable,"-m","ziglang","c++","-O2","-std=c++17",str(HERE/"catalog_check.cpp"),
                    *[str(PROJECT/"app/src/main/cpp"/f) for f in ("master_core.cpp","master_canon.cpp","master_dt.cpp")],"-o",str(exe)],check=True)
    subprocess.run([str(exe),(LOOKS.parent/"hncs_engine.bin").as_posix(),str(work/"names.txt"),str(work),str(work/"key.bin")],check=True)
    nodes = np.array([(r/63,g/63,b/63) for b,g,r in itertools.product([0,8,24,40,63], repeat=3)])
    worst = (0, "")
    for line in names:
        tokens = line.split()
        name, param = tokens[0], [float(t) for t in tokens[1:]] or None
        stem = "_".join(tokens)
        if name in ("x8u", "x7u"): continue
        if name == "hncs" or name.startswith("hncs_"):
            import hncs_engine as E
            engine = E.Engine(5000)
            if name != "hncs":
                _, intent, curve = name.split("_")
                engine.table = E._interp(sorted((t["temperature"], E._values(t)) for t in E.HEADER["tables"] if t["intent"] == intent), 5000).reshape(E.GH,E.GW,2)
                raw = (E.ASSETS / ("filmcurve_type" + ("6" if curve == "standard" else "7") + "_companding2.hcurve")).read_bytes()
                engine.film = np.frombuffer(raw[68:], "<u2").astype(np.float64) / 65535
            ref = M.render(nodes,"main",5000,ccm,engine=engine,lens=lens)
        else:
            ref = M.render_look(nodes,"main",5000,ccm,looks.Packed(LOOKS/(name+".bin")),lens=lens,param=param)
        native = np.fromfile(work/(stem+".f32"),"<f4").reshape(-1,3)
        error = float(np.abs(native-ref).max())
        if error > worst[0]: worst = (error,line)
        # Table quantisation / 10-bit HDR inverse / float-vs-double boundary differences.
        assert error < 0.01, (name,error)
    print(f"PASS native/reference: {len(names)-1} looks × 125 nodes; maximum error {worst[0]:.6f} ({worst[1]})",flush=True)


if __name__ == "__main__":
    p=argparse.ArgumentParser();p.add_argument("--native",action="store_true");args=p.parse_args()
    names=validate()
    if args.native: parity(names)
