"""Normal-mode 25MP (full-binning QBC, rear main/UW/tele) gate relaxation, generated from the stock ODM files.

What stays gated (the user's decision): the DCG multi-frame gate (aecDolState / sensorSupportDcg / flicker),
lens selection (pipeline, zoom range, HR 2DOL support) and flash. Everything else in the decision tree that
can veto a 25M shot is removed, and the lux/ISO thresholds and the three thermal tables are opened up.
Not touched: the 50M nodes, the high-pixel-mode 25M nodes, the app-side "超清优先" switch
(paramsHolder->full_bining_qbc_open) and the deferred-job requirement.
"""
from __future__ import annotations

import copy
import json
import re
import sys
from pathlib import Path

import refs

PROJECT = Path(__file__).resolve().parent.parent
from Crypto.Cipher import AES  # noqa: E402
from Crypto.Util.Padding import unpad  # noqa: E402

STOCK = refs.STOCK_ODM / "odm/etc/camera/config"
OUTPUT = PROJECT / "module/payload"
OUT = OUTPUT / "unlock25mp"
DECISION = "oplus_camera_preview_decision_config.json"
LENSES = ("main", "wide", "tele", "ultratele")

ISO_OPEN = 999999
LUX_OPEN = 9999
QBC_THERMAL_TABLE_VALUE = 1000  # stock: 1000 up to level 7, then 5, 3, -1 (QBC off from level 10)
TURBORAW_THERMAL_TABLE_VALUE = 24  # stock cool-device value; stock drops to 20/15/6/2/-1 from level 8

TARGET_NODES = (
    "ALGO_HR_REAR_W1X-2X_25M_DCG_3EXP",
    "ALGO_HR_REAR_W1X-2X_25M_DCG",
    "ALGO_HR_REAR_UW0P6X-1X_25M_2DOL",
    "ALGO_HR_REAR_T3X-6X_25M_2DOL",
)
KEEP = {
    # lens selection
    "currentPipeline", "zoomRatio", "logicCameraId", "isSupportHR2Dol",
    # DCG multi-frame gate
    "aecDolState", "sensorSupportDcg", "sensorSupportDcgVL", "oplusMeta->com.oplus.aec.flickermode",
    # flash
    "oplusMeta->com.qti.stats_control.is_flash_snapshot", "meta->ANDROID_FLASH_MODE",
    # kept deliberately (see module docstring)
    "isLatestJobIsDeferJob", "paramsHolder->full_bining_qbc_open", "isHighPixel25MScene", "hrDebugMode",
}
REMOVE = {
    "thermalLevel", "isLimitedQBC",
    "oplusMeta->com.qti.stats_control.motion_detected", "paramsHolder->motion_capture_enable",
    "captureNumsOf25M", "quickShotNumWithFullQBC", "paramsHolder->defer_job_amount",
    "paramsHolder->current_burst_capture_type", "paramsHolder->interval_shooting_open",
    "oplusMeta->com.oplus.asd.ui.value",
    "paramsHolder->livephoto_enable", "paramsHolder->filter_open",
    "paramsHolder->macro_closeup_enable", "paramsHolder->under_water_status",
    "paramsHolder->super_text_capture_enable", "paramsHolder->blacksoft_enable",
}


def config_key() -> bytes:
    """AES key of the OPLUS camera configs, read from the stock libOplusSecurity.so."""
    security = (refs.PULLED_LIBS / "libOplusSecurity.so").read_bytes()
    return bytes(value ^ 0x3F for value in security[0x7AB : 0x7AB + 32])[::2]

POP_BRANCH = {"and3": {
    "eq": ["captureMode", "APS_CAPMODE_REAR_NORMAL"],
    "eq1": ["paramsHolder->retro_camera_mode", True],
}}
# Stock's Ultra HDR branch of SINGLE_ALGO_BASIC_TONE is cut off for every non-POP capture that uses the
# `common` algorithm list, so those photos keep their behaviour while POP gets BasicTone.
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


def insert_into_object(text: str, key_pattern: str, start: int, entry: dict) -> str:
    """Insert entry's members at the top of the object opened by key_pattern, keeping the file's style."""
    m = re.compile(key_pattern + r"(\s*)").search(text, start)
    if not m:
        raise RuntimeError("decision layout changed: " + key_pattern)
    ws = m.group(1)
    indent = ws.split("\n")[-1]
    sep = ": " if '": ' in text[start:start + 60] else ":"
    lines = json.dumps(entry, indent=2, separators=(",", sep)).split("\n")[1:-1]
    return text[:m.end()] + ("\n" + indent).join(line[2:] for line in lines) + "," + ws + text[m.end():]


def pop_basictone(text: str) -> str:
    """POP (retro camera) rear/front captures take BasicTone: a POP branch and a non-POP guard on SINGLE_ALGO_BASIC_TONE."""
    if "paramsHolder->retro_camera_mode" in text:
        raise RuntimeError("decision config already has the POP edits")
    found = list(re.finditer(r'"mode"\s*:\s*"SINGLE_ALGO_BASIC_TONE"', text))
    if len(found) != 1:
        raise RuntimeError("expected one SINGLE_ALGO_BASIC_TONE")
    start = found[0].end()
    text = insert_into_object(text, r'"condition"\s*:\s*\{\s*"or"\s*:\s*\{', start, POP_BRANCH)
    return insert_into_object(text, r'"and2"\s*:\s*\{', start, NON_POP_GUARD)


def decrypt(path: Path) -> bytes:
    data = path.read_bytes()
    if data[:2] == b"\x01\x01":
        if data[-1] != 0xFF:
            raise RuntimeError(f"Unexpected encrypted config envelope: {path}")
        data = unpad(AES.new(config_key(), AES.MODE_ECB).decrypt(data[4:-4]), 16)
    return data


def _base(key: str) -> str:
    return re.sub(r"\d+$", "", key)


def strip_condition(condition: dict, seen: dict[str, int]) -> dict:
    result = _strip_top(condition, seen)
    if result is None:
        raise RuntimeError("Condition became empty")
    return result


def _strip_top(node: dict, seen: dict[str, int]) -> dict | None:
    # The tree is {"and": {...}} / {"or": {...}}; operate on the group bodies with the right semantics.
    out: dict = {}
    for key, body in node.items():
        op = _base(key)
        if op not in ("and", "or"):
            raise RuntimeError(f"Unexpected top-level operator {key}")
        group = _strip_group(op, body, seen)
        if group is not None:
            out[key] = group
    return out or None


def _strip_group(op: str, body: dict, seen: dict[str, int]) -> dict | None:
    kept: dict = {}
    always_true = False
    for key, value in body.items():
        if isinstance(value, dict):
            inner = _strip_top({key: value}, seen)
            if inner is None:
                always_true = True
            else:
                kept.update(inner)
            continue
        name = value[0]
        if name in REMOVE:
            seen[name] = seen.get(name, 0) + 1
            always_true = True
            continue
        if name not in KEEP:
            raise RuntimeError(f"Unclassified term in 25M node: {name}")
        kept[key] = value
    if op == "or" and always_true:
        return None  # an OR with a removed disjunct can no longer veto
    return kept or None


def renumber(node):
    """Keep operator keys contiguous (eq, eq1, eq2, ...) so a sequential reader still sees every term."""
    if not isinstance(node, dict):
        return node
    counts: dict[str, int] = {}
    out = {}
    for key, value in node.items():
        base = _base(key)
        index = counts.get(base, 0)
        counts[base] = index + 1
        out[base if index == 0 else f"{base}{index}"] = renumber(value)
    return out


def patched_decision() -> tuple[dict, dict]:
    stock = json.loads(decrypt(STOCK / DECISION).decode("utf-8"), strict=False)
    merged = copy.deepcopy(stock)
    seen: dict[str, int] = {}
    changed = []
    for node in merged["multiAlgo"]["algo"]:
        if node["mode"] in TARGET_NODES:
            node["condition"] = renumber(strip_condition(node["condition"], seen))
            changed.append(node["mode"])
    if sorted(changed) != sorted(TARGET_NODES):
        raise RuntimeError(f"25M nodes changed: {changed}")
    return stock, merged


def unlock_decision(merged: dict) -> bytes:
    """The relaxed decision config as shipped: the 25M edits plus the POP BasicTone edits (pop_basictone)."""
    text = json.dumps(merged, ensure_ascii=False, indent=2)
    return (pop_basictone(text) + "\n").encode("utf-8")


def patched_aps(data: bytes) -> bytes:
    text = data.decode("utf-8")

    def table(tag: str, value: int, text: str) -> str:
        pattern = re.compile(r'("VendorTag":\s*"' + re.escape(tag) + r'",[^{}]*?"Value":\s*")([^"]*)(")', re.S)
        match = pattern.search(text)
        if not match or len(pattern.findall(text)) != 1:
            raise RuntimeError(f"APS tag not found once: {tag}")
        count = len(match.group(2).split(","))
        if count != 21:
            raise RuntimeError(f"Unexpected table length {count} for {tag}")
        return text[: match.start(2)] + ", ".join([str(value)] * count) + text[match.end(2):]

    text = table("com.oplus.aps.params.qbc.thermallevel", QBC_THERMAL_TABLE_VALUE, text)
    text = table("com.oplus.aps.params.turboraw.thermallevel", TURBORAW_THERMAL_TABLE_VALUE, text)
    return text.encode("utf-8")


def patched_lighthouse(data: bytes) -> bytes:
    text = data.decode("utf-8")
    for key, value in (("HIGHT_PIXEL_25M_ISO_TRRIGGER", ISO_OPEN), ("HIGHT_PIXEL_25M_LUX_TRRIGGER", LUX_OPEN)):
        pattern = re.compile(r'("Key":\s*"' + key + r'",[^{}]*?"Value":\s*")(\d+)(")', re.S)
        text, count = pattern.subn(lambda m: f"{m.group(1)}{value}{m.group(3)}", text)
        if count != 1:
            raise RuntimeError(f"{key} matched {count} times")
    return text.encode("utf-8")


def run() -> None:
    """Writes the relaxed copies under payload/unlock25mp; the payload's own aps config stays the base one.

    service.sh stages the directory into the camera's files, and the native path hook serves it while
    the settings switch is on (unlock25mp.cpp), so nothing here is bound over /odm.
    """
    OUT.mkdir(exist_ok=True)
    (OUT / "oplus_camera_aps_config").write_bytes(patched_aps((OUTPUT / "oplus_camera_aps_config").read_bytes()))
    for lens in LENSES:
        name = f"lighthouse{lens}"
        (OUT / name).write_bytes(patched_lighthouse((STOCK / name).read_bytes()))
    _, merged = patched_decision()
    (OUT / DECISION).write_bytes(unlock_decision(merged))
    print("25MP unlock: 4 lighthouse files, 2 APS thermal tables, 4 decision nodes")


if __name__ == "__main__":
    run()
