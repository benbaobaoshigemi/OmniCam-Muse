"""Where the reference material outside this repository lives.

The root defaults to F:/理光 and can be moved with the MUSE_REFS environment variable.
"""
import os
from pathlib import Path

ROOT = Path(os.environ.get("MUSE_REFS", "F:/理光"))
PHOTONCAMERA = ROOT / "第三方/PhotonCamera-bjzhou"        # https://github.com/bjzhou/PhotonCamera
STOCK_ODM = ROOT / "固件解包/target_x9ultra"               # Find X9 Ultra stock /odm, /vendor, /product
PULLED_LIBS = ROOT / "固件解包/拉取的库"                    # libOplusSecurity.so (config key) and other pulled libraries
ROM = ROOT / "固件解包/_rom"                               # selected extractions (c16, x8u, x9u_stock_20260924)
SYMBOL_INDEX = ROOT / "研究/符号索引/apk_17.0.0.105"       # dexdump indexes for tools/symbols
ADOBE_DCP = ROOT / "第三方/Adobe-Standard-X9U"            # Adobe Standard profiles of the four rear lenses, from the phone's Lightroom cache
SOFT_LIGHT = ROOT / "实验/柔光替换"                           # physical HDF / black mist simulation and its GPU pyramid weights (pyramid_weights.json)
