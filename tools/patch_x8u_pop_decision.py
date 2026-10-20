"""Inject the POP BasicTone decision branches into the X8U (PKJ110) plaintext decision config.

The X8U ``oplus_camera_preview_decision_config.json`` is an encrypted container on /odm (header
``01 01``, AES-ECB, key in ``libOplusSecurity.so``). tools/decrypt_oplus_config.py unwraps it; this
script takes that plaintext and adds the POP edits so the capture decision routes retro-camera
(POP) shots through BasicTone, which is what makes the POP 光感/自然饱和度/色温/色调 sliders
reach the capture (docs/MODULE.md, "POP 成片的滑杆与 10X", gate 2).

What is injected (mirrors the X9U ``payload/oplus_camera_preview_decision_config.json``, which the
original OmniCam Muse 4.0 ships already patched):

  * ``and3`` -- rear POP: captureMode == APS_CAPMODE_REAR_NORMAL && retro_camera_mode
  * ``and4`` -- front POP: captureMode == APS_CAPMODE_FRONT_NORMAL && retro_camera_mode
  * ``not``  -- non-POP guard on the ``common`` modes so they keep their stock behaviour

tools/patch_25mp.py::pop_basictone() only emits the rear branch + guard; the front branch here is
the piece it omits but the X9U reference carries, so it is included explicitly.

Usage:
    python tools/patch_x8u_pop_decision.py <plaintext-decision.json> [-o out.json]

The input is the decrypted plaintext (e.g. .workbuddy/x8u_cfg/x8u_decision.plain.json). The output
stays plaintext: it is staged into the camera's private files and served by the native path hook
(pop_path_remap.cpp), never written back over /odm.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REAR_POP_BRANCH = {"and3": {
    "eq": ["captureMode", "APS_CAPMODE_REAR_NORMAL"],
    "eq1": ["paramsHolder->retro_camera_mode", True],
}}

FRONT_POP_BRANCH = {"and4": {
    "eq": ["captureMode", "APS_CAPMODE_FRONT_NORMAL"],
    "eq1": ["paramsHolder->retro_camera_mode", True],
}}

# Non-POP guard: keep the stock behaviour for every capture that uses the `common` algorithm list
# but is not retro camera (rear/front normal/bokeh/night, AI high pixel).
NON_POP_GUARD = {"not": {"and": {
    "eq": ["paramsHolder->capture_mode", "common"],
    "not_eq": ["paramsHolder->retro_camera_mode", True],
    "or": {
        "eq": ["captureMode", "APS_CAPMODE_REAR_NORMAL"],
        "eq1": ["captureMode", "APS_CAPMODE_REAR_BOKEH"],
        "eq2": ["captureMode", "APS_CAPMODE_REAR_NIGHT"],
        "eq3": ["captureMode", "APS_CAPMODE_AI_HIGH_PIXEL"],
        "eq4": ["captureMode", "APS_CAPMODE_FRONT_NORMAL"],
        "eq5": ["captureMode", "APS_CAPMODE_FRONT_BOKEH"],
        "eq6": ["captureMode", "APS_CAPMODE_FRONT_NIGHT"],
    },
}}}


def _insert_into_object(text: str, key_pattern: str, start: int, entry: dict) -> str:
    """Insert ``entry``'s members at the top of the object opened by ``key_pattern``.

    This is a byte-preserving edit: only the inserted text is new, every other byte of the file is
    left untouched, so the rest of the decision config keeps its exact formatting and value order.
    """
    m = re.compile(key_pattern + r"(\s*)").search(text, start)
    if not m:
        raise RuntimeError("decision layout changed: " + key_pattern)
    ws = m.group(1)
    indent = ws.split("\n")[-1]
    sep = ": " if '": ' in text[start:start + 60] else ":"
    lines = json.dumps(entry, indent=2, separators=(",", sep)).split("\n")[1:-1]
    return text[:m.end()] + ("\n" + indent).join(line[2:] for line in lines) + "," + ws + text[m.end():]


def inject_pop(text: str) -> str:
    if "paramsHolder->retro_camera_mode" in text:
        raise RuntimeError("decision already has the POP edits")
    found = list(re.finditer(r'"mode"\s*:\s*"SINGLE_ALGO_BASIC_TONE"', text))
    if len(found) != 1:
        raise RuntimeError(f"expected exactly one SINGLE_ALGO_BASIC_TONE, found {len(found)}")
    start = found[0].end()
    # Rear + front POP branches go at the top of condition.or (and3, and4).
    text = _insert_into_object(text, r'"condition"\s*:\s*\{\s*"or"\s*:\s*\{', start, REAR_POP_BRANCH)
    text = _insert_into_object(text, r'"condition"\s*:\s*\{\s*"or"\s*:\s*\{', start, FRONT_POP_BRANCH)
    # The non-POP guard goes at the top of and2 (the `common` modes' ultra-hdr branch).
    return _insert_into_object(text, r'"and2"\s*:\s*\{', start, NON_POP_GUARD)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("plaintext", type=Path, help="decrypted plaintext decision config")
    parser.add_argument("-o", "--out", type=Path, help="write here instead of <plaintext>.pop.json")
    args = parser.parse_args(argv)

    text = args.plaintext.read_bytes().decode("utf-8")
    patched = inject_pop(text)
    # Sanity: the result must still parse and now carry the retro term.
    json.loads(patched, strict=False)
    out = args.out or args.plaintext.with_name(args.plaintext.stem + ".pop.json")
    out.write_bytes(patched.encode("utf-8"))
    print(f"{out}: {len(patched.encode('utf-8'))} bytes (retro term x{patched.count('retro_camera_mode')})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
