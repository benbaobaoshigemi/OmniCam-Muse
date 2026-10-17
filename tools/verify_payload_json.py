#!/usr/bin/env python3
"""Detect the "pair-list" JSON deformation in the port's payload configs.

Background (X8U port, 2026-10-13)
--------------------------------
`camera_unit_config` is read by `com.oplus.ocs.camera.configure.JsonParser`
(lives in /product/framework/com.oplus.camera.unit.sdk.jar). Its
`parseUsecaseInfo()` does, for every entry of `usecase_info`:

    JSONArray arr = usecase.getJSONArray(key);
    for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);   // each element MUST be an object
        ...
    }

and the whole method is wrapped in one try/catch that returns whatever it has
built so far. So a single element in the wrong shape aborts the parse and the
app ends up with a *partial* usecase table. Entering POP then dies with:

    RuntimeException: getUseCaseValues, retro_camera_case's usecase_info are not configured!

The port's payload generator emitted the entries it added as Python
`list(dict.items())` instead of real objects, i.e.

    "retro_camera_case": [ [["capture_meta","rear_sat"]], ... ]   # WRONG
    "retro_camera_case": [ {"capture_meta":"rear_sat"}, ... ]     # RIGHT

That shape (`[[k, v], ...]`) never occurs in the device's own config, so any
node matching it is a bug. This script finds them.

Usage:
    python tools/verify_payload_json.py                 # scan module/payload*/ recursively
    python tools/verify_payload_json.py <file.json> ... # scan specific files

Exit code is 1 when a deformation is found.
"""

import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ROOTS = [
    os.path.join(REPO, "module", "payload"),
    os.path.join(REPO, "module", "payload_pkj110"),
    # The delivered copy of the PKJ110 payload. It used to be maintained by hand and
    # silently kept the deformed version long after module/payload_pkj110/ was fixed,
    # so it is scanned too. See port-x8u/README.md §4.8.
    os.path.join(REPO, "port-x8u", "payload"),
]


def is_pair_list(value):
    """True for [[k, v], ...] where every key is a string (the deformed map)."""
    return (
        isinstance(value, list)
        and len(value) > 0
        and all(
            isinstance(item, list) and len(item) == 2 and isinstance(item[0], str)
            for item in value
        )
    )


def scan(node, path, found):
    if isinstance(node, dict):
        for key, value in node.items():
            scan(value, "%s/%s" % (path, key), found)
    elif isinstance(node, list):
        if is_pair_list(node):
            found.append(path)
            return
        for index, item in enumerate(node):
            scan(item, "%s[%d]" % (path, index), found)


def scan_file(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as error:
        print("  SKIP  %s (%s)" % (path, error))
        return 0
    found = []
    scan(data, "", found)
    if found:
        print("  BAD   %s -- %d deformed node(s)" % (path, len(found)))
        for where in found[:10]:
            print("          %s" % where)
        if len(found) > 10:
            print("          ... and %d more" % (len(found) - 10))
    else:
        print("  ok    %s" % path)
    return len(found)


def collect(targets):
    files = []
    for target in targets or DEFAULT_ROOTS:
        if os.path.isfile(target):
            files.append(target)
            continue
        for root, _dirs, names in os.walk(target):
            for name in names:
                if name.endswith(".json") or name in (
                    "camera_unit_config",
                    "oplus_camera_config",
                ):
                    files.append(os.path.join(root, name))
    return sorted(files)


def main(argv):
    files = collect(argv[1:])
    if not files:
        print("no payload JSON found")
        return 0
    print("scanning %d file(s) for pair-list deformation" % len(files))
    total = sum(scan_file(path) for path in files)
    print()
    if total:
        print("FAIL: %d deformed node(s). Repair with the inverse transform:" % total)
        print("      [[k, v], ...]  ->  {k: v, ...}   (recursively)")
        print("      Deformed nodes also abort parseUsecaseInfo() for *every* entry")
        print("      that has not been visited yet, so unrelated modes break too.")
        return 1
    print("PASS: no pair-list deformation")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
