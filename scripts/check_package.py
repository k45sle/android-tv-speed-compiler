from __future__ import annotations

import sys
import tarfile
import zipfile
from pathlib import Path

required = {
    "tvcompiler/templates/index.html",
    "tvcompiler/static/style.css",
    "tvcompiler/static/app.js",
}
source_required = {f"src/{name}" for name in required} | {"pyproject.toml"}
if len(sys.argv) != 3:
    raise SystemExit("usage: check_package.py WHEEL SDIST")
wheel, sdist = map(Path, sys.argv[1:])
with zipfile.ZipFile(wheel) as archive:
    names = set(archive.namelist())
    missing = required - names
    if missing:
        raise SystemExit(f"wheel is missing: {sorted(missing)}")
    entries = [name for name in names if name.endswith("entry_points.txt")]
    if len(entries) != 1 or "tvcompiler = tvcompiler.web:main" not in archive.read(entries[0]).decode():
        raise SystemExit("wheel does not install the tvcompiler console entry point")
with tarfile.open(sdist, "r:gz") as archive:
    names = {name.split("/", 1)[-1] for name in archive.getnames()}
    missing = source_required - names
    if missing:
        raise SystemExit(f"source archive is missing: {sorted(missing)}")
print(f"package contents ok: {wheel.name}, {sdist.name}")
