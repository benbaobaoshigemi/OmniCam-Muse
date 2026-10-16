"""Bakes the module's master-mode LUTs (Rec.709 .cube -> device MS-LUT) into the module payload.

Sources are in inputs/master_luts (copied from the user's LUT packs). The conversion is the one
used for the Fuji Rec.709 set (bake_rec709_to_serenity_mslut: Display P3 + sRGB EOTF lattice,
32^3 u8, CSS Color 4 gamut mapping, header of LeicaM9.cube.rgb.bin). Output names are the filter
IDs in app/.../filters/MasterFilterDefaults.java and never change once shipped.
"""
from __future__ import annotations

from pathlib import Path

from bake_rec709_to_serenity_mslut import bake, load_header, parse_cube

PROJECT = Path(__file__).resolve().parent.parent

SOURCE = PROJECT / "inputs/master_luts"
OUTPUT = PROJECT / "module/payload/meishe_lut"
HEADER = OUTPUT / "LeicaM9.cube.rgb.bin"

ASSETS = [
    ("Fuji_NN.cube", "JG_FUJI_NOSTALGIC_NEG.cube.rgb.bin"),
    ("理光正片GR Posi.cube", "JG_RICOH_POSITIVE.cube.rgb.bin"),
    ("理光负片GR Neg.cube", "JG_RICOH_NEGATIVE.cube.rgb.bin"),
    ("Green.cube", "JG_RICOH_CINEMA_GREEN.cube.rgb.bin"),
    ("Yellow.cube", "JG_RICOH_CINEMA_YELLOW.cube.rgb.bin"),
    ("GR-Viv_33_Rec709.cube", "JG_RICOH_VIVID.cube.rgb.bin"),
    ("CineStill-50D.cube", "JG_FILM_CINESTILL_50D.cube.rgb.bin"),
    ("Fuji C200.cube", "JG_FILM_FUJI_C200.cube.rgb.bin"),
    ("HASSELBLAD Blue.cube", "JG_FILM_HASSELBLAD_BLUE.cube.rgb.bin"),
    ("Kodak 5203.cube", "JG_FILM_KODAK_5203.cube.rgb.bin"),
    ("Kodak 5207.cube", "JG_FILM_KODAK_5207.cube.rgb.bin"),
    ("Kodak 5219.cube", "JG_FILM_KODAK_5219.cube.rgb.bin"),
    ("Kodak E200.cube", "JG_FILM_KODAK_E200.cube.rgb.bin"),
    ("Kodak Gold200.cube", "JG_FILM_KODAK_GOLD_200.cube.rgb.bin"),
    ("Portra400.cube", "JG_FILM_KODAK_PORTRA_400.cube.rgb.bin"),
    ("Ultramax 400.cube", "JG_FILM_KODAK_ULTRAMAX_400.cube.rgb.bin"),
]


def main() -> None:
    header = load_header(HEADER)
    for source_name, output_name in ASSETS:
        table, domain_min, domain_max = parse_cube(SOURCE / source_name)
        payload = bake(table, domain_min, domain_max, "srgb", "css4")
        (OUTPUT / output_name).write_bytes(header + payload.tobytes())
        print("wrote %s from %s (%d^3)" % (output_name, source_name, table.shape[0]))


if __name__ == "__main__":
    main()
