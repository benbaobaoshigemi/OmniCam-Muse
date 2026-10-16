"""Builds app/src/main/assets/symbols.json from the per-app spec sources (sym_*.py, kept in Python
so they can carry comments), then replays it on the indexed app builds (spec_eval.py).

  python sym.py            build + replay, every symbol listed
  python sym.py -q         build + replay, problems only
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "symbols.json")
sys.path.insert(0, HERE)
import spec_eval  # noqa: E402

STAGE = "com.oplus.camera.protocal.event.message.deviceProtocol.deviceControlRequest.EventMessageCaptureRequestStage"
C = {}
def add(**kw):
    for k, v in kw.items(): C[k.replace("__", ".")] = v
def M(**kw): d = {"kind": "method"}; d.update(kw); return d
def F(**kw): d = {"kind": "field"}; d.update(kw); return d
def K(**kw): d = {"kind": "class"}; d.update(kw); return d
S = "java.lang.String"
for source in ("sym_camera.py", "sym_camera3.py"):
    exec(open(os.path.join(HERE, source), encoding="utf-8").read())
G = {}
exec(open(os.path.join(HERE, "sym_gallery.py"), encoding="utf-8").read())
spec = {"camera": C, "gallery": G}
json.dump(spec, open(SPEC, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
total = 0
for app, tags in (("camera", ("old_cam", "new_cam")), ("gallery", ("old_gal", "new_gal"))):
    print("==", app)
    total += spec_eval.report(spec[app], tags, None, verbose="-q" not in sys.argv)
print("TOTAL problems", total)
sys.exit(1 if total else 0)
