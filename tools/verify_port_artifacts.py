#!/usr/bin/env python3
"""Release gate for the Find X8 Ultra (PKJ110) port payloads.

Runs entirely offline. Checks, in order:

  1. Pinned hashes    -- the PKJ110/PMA110 payload files are the reviewed bytes.
  2. Mirror equality   -- port-x8u/payload/ (the delivered copy) == module/payload_pkj110/.
                          This file used to be updated by hand and kept serving the deformed
                          camera_unit_config long after the module payload was repaired, so it
                          is asserted here. See port-x8u/README.md section 4.8.
  3. JSON shape        -- tools/verify_payload_json.py (pair-list deformation, exit 0).
  4. Semantic diff     -- the PKJ110 camera_unit_config is X8U's stock config plus exactly the
                          nine expected X9U-derived entries; nothing native is changed or dropped.
                          The same group rebuilds oplus_camera_algo_switch_config and requires it
                          to be the device's stock text plus the two retroCamera appends, and
                          that both the module payload and the delivered mirror hold those bytes.
  5. Native fingerprint-- libAlgoInterface.so still matches the getSettingInt prologue used by
                          app/src/main/cpp/gr_basictone.cpp (guards the GR native hook).
  6. Module archive    -- the KernelSU zip carries the same payload bytes, the same
                          post-fs-data.sh, and no leftover *.bak.

Usage:
    python tools/verify_port_artifacts.py
    python tools/verify_port_artifacts.py --skip-zip      # before the archive is rebuilt

Exit code 1 on any failure.
"""

import collections
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------------------
# Pinned artifacts. Update these deliberately, together with the README.
# ---------------------------------------------------------------------------
PKJ110_PAYLOAD = {
    "camera_unit_config": (
        32454,
        "6b6c77aa8694bd55d2eaaacaec5a659a",
    ),
    "camera_unit_feature_config.protobuf": (
        62330,
        "b0ee50c1a3bbc9e1dcc20f2391a490c2",
    ),
    # Not a copy of PMA110's file: the device's own decoded stock text with the retroCamera block
    # appended and the palette capture node added to common/portrait/aiHighPixel/master, so the
    # payload stays "stock plus additions" like every other ported config.
    # Rebuilt by tools/build_x8u_algo_switch_config.py; see README sections 4.12 and 4.14.
    "oplus_camera_algo_switch_config": (
        141831,
        "7aa941be81cf7cffa82553317a469a82",
    ),
    # The POP BasicTone decision tree: X8U's own decrypted decision config with the retro-camera
    # branches (and3 rear / and4 front) and the non-POP guard injected, so POP captures route
    # through BasicTone and the 光感/饱和度/色温/色调 sliders reach the shot. Plaintext: it is
    # staged into the camera's files and served by the native path hook, never bound over /odm
    # (the stock /odm copy is an encrypted container). Rebuilt by tools/patch_x8u_pop_decision.py
    # from the decrypted X8U decision (tools/decrypt_oplus_config.py).
    "pop_decision.json": (
        362758,
        "b41dd01f42ea50f8a8d9f60a4e2e3657",
    ),
    # X8U stock libAlgoInterface.so with the BasicTone "all-zero skip" NOP-ed at 0x1e7ae00
    # (door #4, docs/MODULE.md §32/34). Built by tools/patch_x8u_basictone_skip.py from
    # port-x8u/_pristine/libAlgoInterface_x8u_stock.so. Bound/overwritten on PKJ110 only by
    # post-mount.sh; never placed in system/ (metamount overlays it unconditionally).
    "lib64/libAlgoInterface.so": (
        60867488,
        "55e78c21a7554b8e7ec7abe452044436",
    ),
}
# X9U payload must stay untouched by PKJ110 work.
PMA110_PAYLOAD = {
    "camera_unit_config": (
        40869,
        "6b2fa71707a7acf57600f4c14d68e6b8",
    ),
    "camera_unit_feature_config.protobuf": (
        65773,
        "ba2fadd444f14833597427e049f1c6ba",
    ),
}
MODULE_SCRIPTS = {
    "post-fs-data.sh": (19975, "f5415ec4be66cc699b1d7880f319d2ea"),
    "service.sh": (11630, "221461f2f505c3ac13b3c31f438dfe55"),
    "post-mount.sh": (10975, "87f84b5ccf2005fd908a4c943bf874bc"),
    "x8u-fix-pop.sh": (9181, "2964db9cccf9d7197ece95eab99af699"),
    "x8u-pop-diag.sh": (7485, "6fc6c9749a3b15c21e06d08b62acf96a"),
    "x8u-fix-vibrant.sh": (8919, "c1592457cb521e1016a13c68c51bf513"),
    "x8u-config-revert.sh": (10650, "3ad53dd4378c1aeafc1dd0fb29711460"),
    "x8u-repair.sh": (9793, "0669167d3ec52f2f918cd53a4d58f5d8"),
}

# The POP "Vibrant" filter -- camera_filter_retro_vibrant_dsc ("Positive" / 浓郁, film hint
# "Positive Film") -- hands meicam the colour-LUT file names in
# FilterConstant.STYLE_FILTER_TYPE_POSITIVE_HDR_PATH and friends. X8U's ODM ships the whole
# portra400_* / blackandwhite_* update family but *no* positive_* member at all (stock F.21
# odm.img meishe_lut: 111 entries, zero positive_*), so every request fails with
# "meicam: Failed to open lut file .../positive_hdr_normal_a_1.bin" and the filter silently does
# nothing. The port supplies the device's own Positive-film LUT (gr.posi.rgba.bin, present in
# X8U's ODM) under all eight names the APK can ask for, so the filter renders.
# See port-x8u/README.md section 4.9.
POSITIVE_LUT_SIZE = 24128
POSITIVE_LUT_MD5 = "ec7eee635f70dbf299d6c49e750739b7"
POSITIVE_LUTS = (
    "positive_normal.bin",
    "positive_master.bin",
    "positive_sdr_gen_a_1.bin",
    "positive_sdr_gen_d_1.bin",
    "positive_hdr_normal_a_1.bin",
    "positive_hdr_normal_d_1.bin",
    "positive_hdr_master_a_1.bin",
    "positive_hdr_master_d_1.bin",
)

# The four HLG/HDR colour-conversion tables the camera asks for by name and PKJ110's
# /odm/etc/camera/filters_lut does not have. They are missing from the C.75 / C.73 / F.21 builds
# as well (37 entries each, none of them these), so the video page has never brought its BasicTone
# environment up on this model: the camera requests it, the read fails, and it continues without -
# which the palette made visible, because Lut3dDataManager.p() bails out on its first line while
# the environment is down. Supplied from PMA110 (X9U) 17.0.0.106's odm; 17-cube ASCII Adobe .cube,
# same shape as the other filters_lut entries. See port-x8u/README.md section 4.18.
HLG_LUTS = {
    "bt709_srgb_to_p3_srgb_3dlut_light.cube": (131424, "93d1e7b231446e6c6643b47c49cf414d"),
    "bt709_srgb_to_bt2020_hlg_3dlut_light.cube": (131250, "7e2cbae1cb821c06d143c8b767404320"),
    "p3_srgb_to_bt2020_hlg_3dlut_light.cube": (131134, "c9ccb4d87f40e1a7c007b73b611f0d18"),
    "bt2020_hlg_to_p3_srgb_3dlut_light.cube": (107325, "5c718fa6d64c557011888d21352a7a6f"),
}

def _module_version():
    with open(os.path.join(REPO, "module", "module.prop"), encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("version="):
                # Muse's version line reads "Muse <v>"; build.ps1 strips the prefix before naming
                # the archive, so the check has to strip it the same way.
                return re.sub(r"^Muse\s+", "", line.partition("=")[2].strip())
    raise SystemExit("module/module.prop has no version=")


ARCHIVE_NAME = "OmniCam-Muse-%s-KSU.zip" % _module_version()

# Exactly the entries the port adds on top of X8U's own config. Anything else new, or anything
# missing, is a generator regression rather than a deliberate change.
EXPECTED_ADDITIONS = {
    "/mode_operation_mode/gr_mode",
    "/mode_operation_mode/retro_camera_mode",
    "/usecase_info/gr_mode_case",
    "/usecase_info/gr_mode_hq_raw_case",
    "/usecase_info/retro_camera_case",
    "/capture_stream_number/tilt_shift_mode",
    "/capture_stream_number/gr_mode",
    "/capture_stream_number/high_pixel_mode",
    "/capture_stream_number/retro_camera_mode",
}
# Arrays that grew must grow by pure append, with the appended items coming from X9U.
EXPECTED_APPENDS = {
    "/mode_type_list/rear_wide": 10,
    "/mode_type_list/rear_main": 14,
    "/mode_type_list/rear_tele": 6,
    "/mode_type_list/rear_ultra_tele": 9,
    "/mode_type_list/front_main": 8,
    "/mode_type_list/rear_sat": 8,
}

# getSettingInt prologue for the PKJ110 libAlgoInterface.so, as compiled into gr_basictone.cpp.
SETTING_INT_PROLOGUE = bytes(
    (0x3F, 0x23, 0x03, 0xD5, 0xFF, 0x43, 0x02, 0xD1, 0xFD, 0x7B, 0x06, 0xA9, 0xF6, 0x57, 0x07, 0xA9)
)
SETTING_INT_OFFSET = 0x13F46D4
SETTING_INT_LIB = os.path.join("G:", os.sep, "ColorOS17", "x8u_extract", "odm_lib64", "libAlgoInterface.so")

FAILURES = []
NOTES = []


def check(label, condition, detail=""):
    if condition:
        print("  ok    %s" % label)
    else:
        print("  FAIL  %s %s" % (label, detail))
        FAILURES.append(label)


def note(text):
    NOTES.append(text)
    print("  note  %s" % text)


def digest(path):
    with open(path, "rb") as handle:
        return hashlib.md5(handle.read()).hexdigest()


def size_of(path):
    return os.path.getsize(path)


# ---------------------------------------------------------------------------
# 1/2. payload hashes and the mirror
# ---------------------------------------------------------------------------
def check_payloads():
    print("[1/6] pinned payload bytes")
    module_dir = os.path.join(REPO, "module", "payload_pkj110")
    port_dir = os.path.join(REPO, "port-x8u", "payload")

    for name, (want_size, want_md5) in PKJ110_PAYLOAD.items():
        for base in (module_dir, port_dir):
            path = os.path.join(base, name)
            if not os.path.isfile(path):
                check("exists %s" % os.path.relpath(path, REPO), False)
                continue
            check(
                "%-38s %s" % (name, os.path.relpath(base, REPO)),
                size_of(path) == want_size and digest(path) == want_md5,
                "(size=%d md5=%s, want %d/%s)" % (size_of(path), digest(path), want_size, want_md5),
            )

    print("[2/6] mirror: port-x8u/payload == module/payload_pkj110")
    for name in PKJ110_PAYLOAD:
        a = os.path.join(module_dir, name)
        b = os.path.join(port_dir, name)
        if os.path.isfile(a) and os.path.isfile(b):
            check("mirror %s" % name, digest(a) == digest(b),
                  "(module=%s port=%s)" % (digest(a), digest(b)))

    print("      (PMA110 payload must be untouched)")
    for name, (want_size, want_md5) in PMA110_PAYLOAD.items():
        path = os.path.join(REPO, "module", "payload", name)
        if not os.path.isfile(path):
            check("exists module/payload/%s" % name, False)
            continue
        check("pma110 %s" % name, size_of(path) == want_size and digest(path) == want_md5,
              "(size=%d md5=%s)" % (size_of(path), digest(path)))

    print("      (positive_* LUT stand-ins for the POP Vibrant filter)")
    lut_dir = os.path.join(REPO, "module", "payload", "meishe_lut")
    for name in POSITIVE_LUTS:
        path = os.path.join(lut_dir, name)
        if not os.path.isfile(path):
            check("exists payload/meishe_lut/%s" % name, False)
            continue
        check(
            "positive LUT %-32s" % name,
            size_of(path) == POSITIVE_LUT_SIZE and digest(path) == POSITIVE_LUT_MD5,
            "(size=%d md5=%s, want %d/%s)" % (size_of(path), digest(path),
                                              POSITIVE_LUT_SIZE, POSITIVE_LUT_MD5),
        )

    print("      (HLG/HDR conversion tables the video page needs)")
    hlg_dir = os.path.join(REPO, "module", "payload", "filters_lut")
    for name, (want_size, want_md5) in sorted(HLG_LUTS.items()):
        path = os.path.join(hlg_dir, name)
        if not os.path.isfile(path):
            check("exists payload/filters_lut/%s" % name, False)
            continue
        check(
            "hlg LUT %-34s" % name,
            size_of(path) == want_size and digest(path) == want_md5,
            "(size=%d md5=%s, want %d/%s)" % (size_of(path), digest(path),
                                              want_size, want_md5),
        )


# ---------------------------------------------------------------------------
# 3. JSON shape
# ---------------------------------------------------------------------------
def check_shape():
    print("[3/6] pair-list deformation")
    script = os.path.join(REPO, "tools", "verify_payload_json.py")
    proc = subprocess.run([sys.executable, script], capture_output=True, text=True)
    check("tools/verify_payload_json.py exits 0", proc.returncode == 0,
          "\n" + (proc.stdout or "")[-800:])

    # The guard must actually be able to see the deformity it was written for. The archived bad
    # build lives outside module/ so that it can never be shipped by a naive repack.
    bak = os.path.join(REPO, "port-x8u", "broken", "camera_unit_config.broken.bak")
    if os.path.isfile(bak):
        proc = subprocess.run([sys.executable, script, bak], capture_output=True, text=True)
        check("guard still detects the archived broken build", proc.returncode == 1)
        check("archived broken build is the pair-list version",
              digest(bak) == "17290f6029609c7379ee50e5b7c7c02b", digest(bak))
    else:
        note("port-x8u/broken/camera_unit_config.broken.bak absent; negative test skipped")


# ---------------------------------------------------------------------------
# 4. semantic diff against X8U stock and the X9U template
# ---------------------------------------------------------------------------
def load(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=collections.OrderedDict)


def walk(port, stock, template, path, added, changed, removed):
    if isinstance(stock, dict):
        port = port if isinstance(port, dict) else {}
        for key in stock:
            if key not in port:
                removed.append("%s/%s" % (path, key))
            else:
                walk(port[key], stock[key],
                     template.get(key) if isinstance(template, dict) else None,
                     "%s/%s" % (path, key), added, changed, removed)
        for key in port:
            if key not in stock:
                added.append(("%s/%s" % (path, key), port[key],
                              template.get(key) if isinstance(template, dict) else None))
    elif isinstance(stock, list):
        port = port if isinstance(port, list) else []
        if len(port) < len(stock):
            changed.append((path, "shrunk", len(stock), len(port)))
            return
        if port[:len(stock)] != stock:
            changed.append((path, "prefix differs", len(stock), len(port)))
            return
        if len(port) > len(stock):
            tail = port[len(stock):]
            pool = template if isinstance(template, list) else []
            if not all(item in pool for item in tail):
                changed.append((path, "append not from X9U", tail, None))
            else:
                changed.append((path, "append", len(stock), len(port)))
    else:
        if port != stock:
            changed.append((path, "value", stock, port))


def check_semantics():
    print("[4/6] semantic diff of the ported configs")
    port = load(os.path.join(REPO, "module", "payload_pkj110", "camera_unit_config"))
    stock = load(os.path.join(REPO, "port-x8u", "_pristine", "x8u-pristine-config", "camera_unit_config"))
    template = load(os.path.join(REPO, "module", "payload", "camera_unit_config"))

    added, changed, removed = [], [], []
    walk(port, stock, template, "", added, changed, removed)

    check("no stock node dropped", not removed, str(removed[:8]))

    grown = {path: (a, b) for path, kind, a, b in changed if kind == "append"}
    bad = [c for c in changed if c[1] != "append"]
    check("no stock value/type changed", not bad, str(bad[:8]))

    expect_grown = {k: (v, len(port["mode_type_list"][k.split("/")[-1]]))
                    for k, v in EXPECTED_APPENDS.items()}
    check("array growth is exactly the expected pure appends",
          all(grown.get(k) == v for k, v in expect_grown.items()) and len(grown) == len(expect_grown),
          "got %s" % grown)

    added_paths = {p for p, _v, _t in added}
    check("additions are exactly the nine ported entries",
          added_paths == EXPECTED_ADDITIONS,
          "extra=%s missing=%s" % (sorted(added_paths - EXPECTED_ADDITIONS),
                                   sorted(EXPECTED_ADDITIONS - added_paths)))
    mismatch = [p for p, value, tpl in added if tpl is None or value != tpl]
    check("every addition is byte-equal to the X9U template", not mismatch, str(mismatch[:8]))

    for key in ("gr_mode_case", "gr_mode_hq_raw_case", "retro_camera_case"):
        entry = port["usecase_info"][key]
        check("usecase_info/%s is a list of objects" % key,
              isinstance(entry, list) and entry
              and all(isinstance(e, dict) and len(e) == 1 for e in entry))
    check("usecase_info/retro_camera_case reaches the parser last (branch order preserved)",
          list(port["usecase_info"])[-1] == "retro_camera_case",
          "last=%s" % list(port["usecase_info"])[-1])

    check_algo_config()


# ---------------------------------------------------------------------------
# 4b. oplus_camera_algo_switch_config -- stock text plus pure appends
# ---------------------------------------------------------------------------
def load_builder():
    import importlib.util

    path = os.path.join(REPO, "tools", "build_x8u_algo_switch_config.py")
    spec = importlib.util.spec_from_file_location("_x8u_algo_builder", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_algo_config():
    print("      (oplus_camera_algo_switch_config: stock text + two retroCamera appends "
          "+ the palette capture nodes)")
    builder = load_builder()

    text, problems, notes, palette_added = builder.build_text()
    check("rebuild deviates from the stock base in nothing but the appends and palette nodes",
          not problems, str(problems[:4]))
    for section, kept, pruned in notes:
        if pruned:
            note("%s kept %d keys per entity; pruned X9U-only %s"
                 % (section, kept, ", ".join(pruned)))

    rebuilt = text.encode("utf-8")
    for base in ("module/payload_pkj110", "port-x8u/payload"):
        path = os.path.join(REPO, base, "oplus_camera_algo_switch_config")
        if not os.path.isfile(path):
            check("exists %s/oplus_camera_algo_switch_config" % base, False)
            continue
        with open(path, "rb") as handle:
            check("algo config %s == rebuild" % base, handle.read() == rebuilt)

    stock_text = builder.load_stock()[0].encode("utf-8")
    with open(os.path.join(REPO, "module", "payload_pkj110",
                           "oplus_camera_algo_switch_config"), "rb") as handle:
        payload = handle.read()
    # The insertions sit inside mode blocks -- the retroCamera appends at the end of each section
    # and the palette nodes inside the capture entities -- so the file can no longer be split at a
    # single anchor. The whole-file equality above is the strong check; these pin down the byte
    # level properties a re-serialisation would have destroyed.
    palette_line = b'\n\n                    "aps_algo_color_palette" : 1,'
    preview_at = payload.index(b'"aps_preview_configs"')
    check("palette node in every capture entity (%d), none in preview" % palette_added,
          payload.count(palette_line) == palette_added
          and payload.rindex(palette_line) < preview_at,
          "%d occurrences" % payload.count(palette_line))
    check("stock head survives verbatim",
          payload.startswith(stock_text[: stock_text.index(b'"enable"')]))
    check("stock tail is byte-identical", payload.endswith(b"\n    ]\n}\n"))
    check("exactly two mode blocks were appended",
          payload.count(b'"mode"') == stock_text.count(b'"mode"') + 2,
          "%d vs %d" % (payload.count(b'"mode"'), stock_text.count(b'"mode"') + 2))


# ---------------------------------------------------------------------------
# 5. native fingerprint
# ---------------------------------------------------------------------------
def check_native():
    print("[5/6] libAlgoInterface.so getSettingInt fingerprint")
    if not os.path.isfile(SETTING_INT_LIB):
        note("libAlgoInterface.so not present at %s; skipped" % SETTING_INT_LIB)
        return
    with open(SETTING_INT_LIB, "rb") as handle:
        handle.seek(SETTING_INT_OFFSET)
        got = handle.read(len(SETTING_INT_PROLOGUE))
    check("prologue at %#x matches gr_basictone.cpp" % SETTING_INT_OFFSET,
          got == SETTING_INT_PROLOGUE,
          "(got %s want %s)" % (got.hex(" "), SETTING_INT_PROLOGUE.hex(" ")))
    note("the fingerprint keys on libAlgoInterface.so (ODM firmware), not on the camera APK "
         "version, so 7.006.94 vs 7.006.123 is not a difference. Re-run on a new ODM image.")


# ---------------------------------------------------------------------------
# 6. module archive
# ---------------------------------------------------------------------------
def check_zip():
    print("[6/6] KernelSU archive")
    archive = os.path.join(REPO, ARCHIVE_NAME)
    if not os.path.isfile(archive):
        check("archive exists", False)
        return
    with zipfile.ZipFile(archive) as bundle:
        check("archive CRC", bundle.testzip() is None)
        names = bundle.namelist()

        def bundle_md5(name):
            return hashlib.md5(bundle.read(name)).hexdigest()

        for name, (_size, want) in PKJ110_PAYLOAD.items():
            entry = "payload_pkj110/" + name
            check("archive has %s" % entry, entry in names)
            if entry in names:
                check("archive %s == pinned bytes" % name, bundle_md5(entry) == want,
                      bundle_md5(entry))

        for name in MODULE_SCRIPTS:
            entry = name
            if entry not in names:
                check("archive has %s" % entry, False)
                continue
            disk = os.path.join(REPO, "module", name)
            same = bundle_md5(entry) == digest(disk)
            check("archive %s == disk" % name, same)
        for name, (_size, want) in MODULE_SCRIPTS.items():
            if name in names:
                check("archive %s is the reviewed build" % name, bundle_md5(name) == want,
                      bundle_md5(name))

        leftovers = [n for n in names if n.endswith(".bak")]
        check("no *.bak shipped in the module", not leftovers, str(leftovers))

        for name in POSITIVE_LUTS:
            entry = "payload/meishe_lut/" + name
            if entry not in names:
                check("archive has %s" % entry, False)
                continue
            check("archive positive LUT %s" % name, bundle_md5(entry) == POSITIVE_LUT_MD5,
                  bundle_md5(entry))

        for name, (_size, want_md5) in sorted(HLG_LUTS.items()):
            entry = "payload/filters_lut/" + name
            if entry not in names:
                check("archive has %s" % entry, False)
                continue
            check("archive hlg LUT %s" % name, bundle_md5(entry) == want_md5, bundle_md5(entry))


def main(argv):
    skip_zip = "--skip-zip" in argv
    print("verify_port_artifacts -- repo %s" % REPO)
    check_payloads()
    check_shape()
    check_semantics()
    check_native()
    if skip_zip:
        print("[6/6] archive check skipped (--skip-zip)")
    else:
        check_zip()

    print()
    if FAILURES:
        print("FAIL: %d check(s) failed" % len(FAILURES))
        for name in FAILURES:
            print("  - %s" % name)
        return 1
    print("PASS: all checks green")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
