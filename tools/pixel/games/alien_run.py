"""Writes the Alien Run sheets into app/src/main/res/drawable-nodpi as game_*.png.

Usage (from the repo root): uv run --with pillow python tools/pixel/games/alien_run.py <packs-dir> [scratch-dir]
<packs-dir> holds the Kenney Pixel Platformer pack at <packs-dir>/kenney (CC0, kenney.nl/assets/pixel-platformer).
Star Arrow and Cosmic Worm sheets come from gen2.py / gen3.py the same way.
"""
import os, shutil, sys, tempfile
sys.argv[1:] = sys.argv[1:2] + [sys.argv[2] if len(sys.argv) > 2 else tempfile.mkdtemp()]
import gen, gen2, gen3

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DEST = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
# generated name -> resource name
SHEETS = {"runner_k": "runner", "dino_B_drawn_cacti": "cacti", "dino_B_drawn_ground": "ground", "dino_B_drawn_sky": "sky",
          "dino_B_drawn_clouds": "clouds", "dino_B_drawn_far": "mesas", "dino_B_drawn_near": "dunes", "dino_B_shadow": "shadow",
          "dino_B_dust": "dust", "hearts": "hearts", "ufo": "ufo"}

if __name__ == "__main__":
    gen2.kenney_runner(); gen.drawn("B"); gen3.runner_extras()
    os.makedirs(DEST, exist_ok=True)
    for src, dst in SHEETS.items(): shutil.copy(os.path.join(gen.OUT, src + ".png"), os.path.join(DEST, f"game_{dst}.png"))
    print("wrote", sorted(os.listdir(DEST)))
