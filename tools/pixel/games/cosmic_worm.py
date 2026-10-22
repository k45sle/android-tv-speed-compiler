"""Writes the Cosmic Worm sheets into app/src/main/res/drawable-nodpi as game_*.png.

Usage (from the repo root): uv run --with pillow python tools/pixel/games/cosmic_worm.py <packs-dir> [scratch-dir]
<packs-dir> is the same Kenney pack folder alien_run.py takes.
"""
import os, shutil, sys, tempfile
sys.argv[1:] = sys.argv[1:2] + [sys.argv[2] if len(sys.argv) > 2 else tempfile.mkdtemp()]
import gen, gen2, gen3

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DEST = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
# generated name -> resource name
SHEETS = {"cw_board_space": "worm_board", "cw_star": "worm_star", "sn_sparkle": "worm_sparkle", "cg_pad": "worm_pad",
          "cg_portal_w": "worm_portal_w", "cg_portal_n": "worm_portal_n"}

if __name__ == "__main__":
    gen3.conga_board(); gen3.board_space(); gen3.star_food(); gen3.portals(); gen3.crew(); gen2.snake_sprites()
    os.makedirs(DEST, exist_ok=True)
    for src, dst in SHEETS.items(): shutil.copy(os.path.join(gen.OUT, src + ".png"), os.path.join(DEST, f"game_{dst}.png"))
    print("wrote", sorted(f for f in os.listdir(DEST) if "worm" in f))
