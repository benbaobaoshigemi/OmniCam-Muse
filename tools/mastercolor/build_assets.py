"""Build every PhotonCamera film/print combination and the omitted Lumix styles.

Runs the upstream spectral C++ generator on the host. Film lattices are stored once;
print lattices belong to a film/print pair (density domains depend on the film).
Usage: python build_assets.py --photon <PhotonCamera checkout> --out <looks directory>
"""
import argparse
import gzip
import json
from pathlib import Path
import struct
import subprocess
import sys
import zlib

import numpy as np

HERE = Path(__file__).resolve().parent


def table(path):
    data = np.fromfile(path, "<f4").reshape(65, 65, 65, 4)
    assert np.all(np.isfinite(data)) and np.all(data[..., 3] == 1), path
    rgb = data[..., :3].astype(np.float64)
    assert np.all((rgb >= 0) & (rgb <= 1)), path
    return np.round(rgb * 65535).astype("<u2").tobytes()


def descriptor(film, paper):
    # kind 8: scales, length-prefixed table basenames; empty print means positive.
    b = bytearray(b"MCOLLOOK") + struct.pack("<IIff", 1, 8, 1 / 2.88, 1 / 1.5)
    for name in (film + "_film", film + "__" + paper + "_print" if paper else ""):
        n = name.encode("ascii")
        b += struct.pack("<I", len(n)) + n
    return bytes(b)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photon", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    assets = args.photon / "app/src/main/assets"
    manifest = json.loads((assets / "spektrafilm/manifest.json").read_text())
    catalog = json.loads((HERE / "catalog.json").read_text(encoding="utf-8"))
    assert {f["id"] for f in catalog["films"]} == set(manifest["films"])
    assert {p["id"] for p in catalog["prints"]} == set(manifest["prints"])
    work = HERE.parents[1] / "build/mastercolor_assets"
    models, generated = work / "models", work / "generated"
    for p in (models, generated, args.out / "tables"):
        p.mkdir(parents=True, exist_ok=True)
    for name, entry in [("shared", manifest["shared"])] + [
        (name, entry["model"]) for group in ("films", "prints") for name, entry in manifest[group].items()
    ]:
        decoded = gzip.decompress((assets / "spektrafilm" / entry["path"]).read_bytes())
        assert len(decoded) == entry["uncompressedBytes"], name
        (models / (name + ".bin")).write_bytes(decoded)
    cpp = args.photon / "app/src/main/cpp/spektrafilm"
    exe = work / "spektrafilm_generator.exe"
    subprocess.run([sys.executable, "-m", "ziglang", "c++", "-O2", "-std=c++17", "-I" + str(cpp),
                    str(HERE / "spektrafilm_driver.cpp"),
                    *[str(cpp / ("spectral_" + part + ".cpp")) for part in ("data", "filming", "gamut", "generator", "printing")],
                    "-o", str(exe)], check=True)
    combinations = 0
    for film in catalog["films"]:
        name = film["id"]
        positive = manifest["films"][name]["type"] == "positive"
        assert positive == film["positive"]
        papers = [] if positive else [p["id"] for p in catalog["prints"]]
        required = [generated / (name + "_film.f32")] + [generated / (name + "__" + p + "_print.f32") for p in papers]
        if not all(p.is_file() and p.stat().st_size == 65**3 * 4 * 4 for p in required):
            subprocess.run([str(exe), str(models), name, str(generated), *papers], check=True)
        for raw in required:
            (args.out / "tables" / (raw.stem + ".u16")).write_bytes(table(raw))
        default = film["defaultPrint"]
        (args.out / ("film_" + name + ".bin")).write_bytes(descriptor(name, default))
        for paper in papers:
            (args.out / ("film_" + name + "__" + paper + ".bin")).write_bytes(descriptor(name, paper))
        combinations += len(papers) if papers else 1
        print(f"{name}: {len(papers) if papers else 1} selections ready", flush=True)
    import firmware_engines
    firmware_engines.pack(assets, args.out)
    import looks
    looks.PHOTON = assets
    grey = looks.lumix_grey()
    for style in looks.LUMIX_STYLES:
        (args.out / ("lumix_" + style + ".bin")).write_bytes(looks.pack_lumix(style, grey))
    # HNCS has two independent choices: render intent and film curve (four combinations).
    raw = (assets / "hncs/LUTTable100MP3.hncs").read_bytes()
    header_length = struct.unpack_from("<i", raw, 8)[0]
    header = json.loads(raw[12:12 + header_length])
    payload = np.frombuffer(zlib.decompress(raw[12 + header_length:]), "<f4")
    for intent in ("standard", "reproduction"):
        for curve in ("standard", "reproduction"):
            if intent == curve == "standard": continue  # existing hncs_engine.bin
            curve_blob = (assets / ("hncs/filmcurve_type" + ("6" if curve == "standard" else "7") + "_companding2.hcurve")).read_bytes()
            assert curve_blob[:8] == b"HNCURV1\0" and len(curve_blob) == 68 + 65536 * 2
            tables = sorted((t for t in header["tables"] if t["intent"] == intent), key=lambda t: t["temperature"])
            b = bytearray(b"MCOLLOOK") + struct.pack("<II",1,9) + curve_blob[68:] + struct.pack("<I",len(tables))
            for t in tables:
                v = t["values"]
                b += struct.pack("<f",t["temperature"]) + payload[v["offset"]:v["offset"]+v["count"]].tobytes()
            (args.out / f"hncs_{intent}_{curve}.bin").write_bytes(b)
    print(f"Complete: {combinations} film selections, {len(looks.LUMIX_STYLES)} Lumix styles", flush=True)


if __name__ == "__main__":
    main()
