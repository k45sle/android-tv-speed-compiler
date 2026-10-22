"""Writes the arcade picker sheets into app/src/main/res/drawable-nodpi: game_arcade_room.png and game_arcade_<key>.png (off frame, then lit frame).

Usage (from the repo root): uv run --with pillow python tools/pixel/games/arcade.py [scratch-dir]
Needs no downloaded packs.
"""
import os, shutil, sys, tempfile
from PIL import Image
HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv[1:] = ["-", sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp()]  # gen.py reads <packs-dir> <scratch-dir> at import
sys.path.insert(0, HERE)
import gen, gen3  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
DEST = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

if __name__ == "__main__":
    for k in gen3.CAB:
        for lit in (False, True): gen3.cabinet(k, lit)
        frames = [Image.open(os.path.join(gen.OUT, f"cab_{k}_{s}.png")).convert("RGBA") for s in ("off", "on")]
        gen.save(gen.sheet(frames), f"arcade_{k}.png")
    gen3.room(); shutil.copy(os.path.join(gen.OUT, "room.png"), os.path.join(gen.OUT, "arcade_room.png"))
    os.makedirs(DEST, exist_ok=True)
    names = [f for f in os.listdir(gen.OUT) if f.startswith("arcade_")]
    for f in names: shutil.copy(os.path.join(gen.OUT, f), os.path.join(DEST, "game_" + f))
    print("wrote", sorted(names))
