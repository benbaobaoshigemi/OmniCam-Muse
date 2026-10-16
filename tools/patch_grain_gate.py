"""Master 颗粒 for filters without stock grain: patches module/system/odm/lib64/libAlgoInterface.so in place.

Two trampolines in the code segment tail, both keyed on a negative filter_grain_amount (the app's
marker, MasterGrainToggle); a stock capture never sends one, so it runs the original code.

OCamFilterMeisheV2::setMesheFilterParam sets the Meishe grain parameters only for filter types it
knows by name (800t / fuji / ccd_base, portra400*, the master fuji and 800T files,
blackandwhite_HC_* / positive*). Every branch meets again at 0x1ad02a4. The app marks a filter that
should get grain anyway with a negative filter_grain_amount (MasterGrainToggle); the trampoline at
the join turns the amount positive and enters the blackandwhite/positive grain block (0x1acfab4),
which reads every grain parameter from the metadata. Stock grain amounts are positive and pass
through unchanged.

标准 sends an empty filter type. OCamFilterMeisheV2::doFilterProcessV2 skips the filter node when the
type is empty and blacksoft / star light / retro are all off (0x1ad2e64). The second trampoline reads
filter_grain_amount there the way the function reads retro_filter_intensity, and processes the node
without a LUT when it is negative.
"""
from __future__ import annotations

import struct
from pathlib import Path

LIB = Path(__file__).resolve().parent.parent / "module/system/odm/lib64/libAlgoInterface.so"

JOIN = 0x1AD02A4          # ldrb w9, [x23] — first instruction after all grain blocks
GRAIN_BLOCK = 0x1ACFAB4   # blackandwhite_HC_ / positive grain block, after its name check
CAVE = 0x2902EA0          # free space after the palette routines, inside the extended code segment
CAVE_END = 0x2903000

JOIN_ORIGINAL = 0x394002E9  # ldrb w9, [x23]

SKIP_CHECK = 0x1AD2E64    # cbnz w11, PROCESS (blacksoft | star light); then cbnz x8 (type length)
SKIP_ORIGINAL = 0x350008EB
AFTER_SKIP_CHECK = 0x1AD2E6C
PROCESS = 0x1AD2F80
READ_FLOAT = 0x1528F38    # metadata float getter: x0 meta, x1 key (std::string), s0 default
KEY_GRAIN_AMOUNT = 0x1DD8DE  # "filter_grain_amount"
RETRO_READ = 0x1AD2B3C    # the function's own retro_filter_intensity read, copied below


def branch(src: int, dst: int) -> int:
    return 0x14000000 | (((dst - src) >> 2) & 0x3FFFFFF)


def cbnz(src: int, dst: int, rt: int, wide: bool) -> int:
    return (0xB5000000 if wide else 0x35000000) | ((((dst - src) >> 2) & 0x7FFFF) << 5) | rt


def bcond(src: int, dst: int, cond: int) -> int:
    return 0x54000000 | ((((dst - src) >> 2) & 0x7FFFF) << 5) | cond


def adrp(src: int, dst: int, rd: int) -> int:
    pages = (dst >> 12) - (src >> 12)
    return 0x90000000 | ((pages & 3) << 29) | (((pages >> 2) & 0x7FFFF) << 5) | rd


def main() -> None:
    data = bytearray(LIB.read_bytes())
    word = lambda at: struct.unpack_from("<I", data, at)[0]  # noqa: E731
    put = lambda at, value: struct.pack_into("<I", data, at, value)  # noqa: E731

    if word(JOIN) == branch(JOIN, CAVE):
        print("already patched")
        return
    if word(JOIN) != JOIN_ORIGINAL:
        raise SystemExit(f"unexpected instruction at {JOIN:#x}: {word(JOIN):#010x}")

    cave = [
        0xBD4063E0,                        # ldr   s0, [sp, #0x60]   filter_grain_amount
        0x1E202008,                        # fcmp  s0, #0.0
        0x54000064,                        # b.mi  force
        JOIN_ORIGINAL,                     # ldrb  w9, [x23]
        None,                              # b     JOIN + 4
        0x1E214000,                        # force: fneg s0, s0
        0xBD0063E0,                        # str   s0, [sp, #0x60]
        0x9104C3F6,                        # add   x22, sp, #0x130   set by the block's name check
        None,                              # b     GRAIN_BLOCK
    ]
    cave[4] = branch(CAVE + 16, JOIN + 4)
    cave[8] = branch(CAVE + 32, GRAIN_BLOCK)
    if CAVE + 4 * len(cave) > CAVE_END or any(data[CAVE:CAVE + 4 * len(cave)]):
        raise SystemExit("cave space is not free")
    for i, value in enumerate(cave):
        put(CAVE + 4 * i, value)
    put(JOIN, branch(JOIN, CAVE))

    if word(SKIP_CHECK) != SKIP_ORIGINAL:
        raise SystemExit(f"unexpected instruction at {SKIP_CHECK:#x}: {word(SKIP_CHECK):#010x}")
    retro = [word(RETRO_READ + 4 * i) for i in range(8)]
    load_meta, load_key, sso_key, store_size, store_key, zero_default = (retro[i] for i in (0, 1, 4, 5, 6, 7))
    start = CAVE + 4 * len(cave)
    skip = [
        ("cbnz_w11", None),                 # blacksoft | star light set
        ("cbnz_x8", None),                  # filter type not empty
        ("adrp", None),                     # x9 = "filter_grain_amount"
        ("raw", 0x91000000 | (0x8DE << 10) | (9 << 5) | 9),   # add  x9, x9, #0x8de
        ("raw", load_meta),                 # ldr  x0, [x24, #0x110]
        ("raw", load_key),                  # ldr  q0, [x9]
        ("raw", 0xB940112A),                # ldr  w10, [x9, #0x10]
        ("raw", 0x528004C8),                # mov  w8, #0x26          short string, 19 chars
        ("raw", sso_key),                   # add  x1, sp, #0x2c8
        ("raw", store_size),                # strb w8, [sp, #0x2c8]
        ("raw", store_key),                 # stur q0, [x23, #0x51]
        ("raw", zero_default),              # movi d0, #0
        ("raw", 0xB80612EA),                # stur w10, [x23, #0x61]  chars 16..19 with the terminator
        ("bl", None),
        ("raw", 0x1E202008),                # fcmp s0, #0.0
        ("bmi", None),
        ("b_back", None),
        ("b_process", None),
    ]
    process = start + 4 * (len(skip) - 1)
    for i, (kind, value) in enumerate(skip):
        at = start + 4 * i
        if kind == "cbnz_w11":
            value = cbnz(at, process, 11, False)
        elif kind == "cbnz_x8":
            value = cbnz(at, process, 8, True)
        elif kind == "adrp":
            value = adrp(at, KEY_GRAIN_AMOUNT, 9)
        elif kind == "bl":
            value = 0x94000000 | (((READ_FLOAT - at) >> 2) & 0x3FFFFFF)
        elif kind == "bmi":
            value = bcond(at, process, 4)
        elif kind == "b_back":
            value = branch(at, AFTER_SKIP_CHECK)
        elif kind == "b_process":
            value = branch(at, PROCESS)
        if any(data[at:at + 4]):
            raise SystemExit("cave space is not free")
        put(at, value)
    if start + 4 * len(skip) > CAVE_END:
        raise SystemExit("cave overflow")
    put(SKIP_CHECK, branch(SKIP_CHECK, start))
    LIB.write_bytes(data)
    print(f"patched {LIB}")


if __name__ == "__main__":
    main()
