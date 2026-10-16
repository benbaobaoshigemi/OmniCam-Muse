import sys, zipfile, os
from pathlib import Path
src, out = Path(sys.argv[1]), Path(sys.argv[2])
if out.exists(): out.unlink()
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(src.rglob("*")):
        if p.is_file(): z.write(p, p.relative_to(src).as_posix())
with zipfile.ZipFile(out) as z:
    assert z.testzip() is None
    assert all("\\" not in n for n in z.namelist())
print(out, out.stat().st_size)
