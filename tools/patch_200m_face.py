"""哈苏超清 200MP without the big-face gate: the decision-tree copies with isBigFaceScene removed.

Stock takes the real 200M full-pixel readout (ALGO_HR_HP_W1XF_200M / ALGO_HR_HP_T3XF_200M) only when a big
face is in the frame and upscales 50M otherwise. Dropping that one term from the two nodes leaves every
other gate (lens, zoom 1.0, thermal, light / HDR state). Two copies, one per decision file the camera can
read: the payload's base one and the 25MP unlock one. face200m.cpp serves them while the switch is on.
"""
from __future__ import annotations

import json
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
PAYLOAD = PROJECT / "module/payload"
OUT = PAYLOAD / "face200m"
DECISION = "oplus_camera_preview_decision_config.json"
NODES = ("ALGO_HR_HP_W1XF_200M", "ALGO_HR_HP_T3XF_200M")
TERM = ["isBigFaceScene", True]


def drop_face_gate(group: dict) -> bool:
    """Removes the face term from the AND group that holds it; its key is the last of its operator, so the keys stay contiguous."""
    for key, value in list(group.items()):
        if value == TERM:
            base = key.rstrip("0123456789")
            index = int(key[len(base):] or 0)
            if f"{base}{index + 1}" in group:
                raise RuntimeError("face term is not the last of its operator")
            del group[key]
            return True
        if isinstance(value, dict) and drop_face_gate(value):
            return True
    return False


def patched(source: Path) -> bytes:
    tree = json.loads(source.read_text(encoding="utf-8"), strict=False)
    changed = [node["mode"] for node in tree["multiAlgo"]["algo"]
               if node["mode"] in NODES and drop_face_gate(node["condition"])]
    if sorted(changed) != sorted(NODES):
        raise RuntimeError(f"200M nodes changed: {changed}")
    return (json.dumps(tree, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def run() -> None:
    (OUT / "unlock25mp").mkdir(parents=True, exist_ok=True)
    (OUT / DECISION).write_bytes(patched(PAYLOAD / DECISION))
    (OUT / "unlock25mp" / DECISION).write_bytes(patched(PAYLOAD / "unlock25mp" / DECISION))
    print("200M face gate: 2 nodes in 2 decision copies")


if __name__ == "__main__":
    run()
