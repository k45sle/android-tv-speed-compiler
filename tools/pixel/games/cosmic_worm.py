"""Writes the Cosmic Worm sheets into app/src/main/res/drawable-nodpi as game_*.png.

Usage (from the repo root): uv run --with pillow python tools/pixel/games/cosmic_worm.py [scratch-dir]
Needs no downloaded packs.
"""
import math, os, random, shutil, sys
from PIL import Image
import lib
from lib import DEST, ellipse, grid, img, periodic, put, save, sheet

GAPS_X = (4, 10)  # columns open in the top and bottom fence
GAPS_Y = (1, 5)  # rows open in the left and right fence


def conga_board():
    rnd = random.Random(21); im = img(320, 180)
    for y in range(180):
        for x in range(320): put(im, x, y, "navy" if y < 18 else "logo")
    for _ in range(40): put(im, rnd.randrange(320), rnd.randrange(16), rnd.choice(["grey5", "grey2", "white"]))
    for y in range(-5, 6):
        for x in range(-5, 6):
            if x * x + y * y <= 25 and (x + 2) ** 2 + (y - 1) ** 2 > 16: put(im, 160 + x, 9 + y, "cream2")
    for cy in range(7):
        for cx in range(15):
            x0, y0 = 10 + cx * 20, 30 + cy * 20
            for _ in range(5 if (cx + cy) % 2 else 2):
                tx, ty = x0 + rnd.randrange(2, 17), y0 + rnd.randrange(3, 18)
                put(im, tx, ty, "green7"); put(im, tx - 1, ty - 1, "green7"); put(im, tx + 1, ty - 1, "green7")
            if rnd.random() < .25: put(im, x0 + rnd.randrange(20), y0 + rnd.randrange(20), "green5")
    for ang in range(0, 3600):  # crop circle: two flattened rings
        a = ang / 1800 * math.pi
        for rr in (34, 35, 22):
            put(im, round(160 + rr * 1.6 * math.cos(a)), round(100 + rr * .9 * math.sin(a)), "green7")
    for x in range(10, 310):
        for y in range(30, 33): put(im, x, y, "outline" if y == 30 else "green7")
    # force-field barrier (owner: no wooden fence): emitter posts, a two-row laser between them, open gates in the gaps
    def post(px, y0, h, gate):
        for x in range(px - 2, px + 3):
            for y in range(y0, y0 + h):
                put(im, x, y, "outline" if x in (px - 2, px + 2) else "grey2" if x == px - 1 else "grey5" if x == px else "grey7")
        for x in range(px - 1, px + 2):
            put(im, x, y0, "green3" if gate else "yellow"); put(im, x, y0 + 1, "green5" if gate else "gold")
    def barrier_face(y0, h, gaps):
        for x in range(10, 310):
            if (x - 10) // 20 in gaps: continue
            for y, c in ((y0 + 4, "pink"), (y0 + 5, "red"), (y0 + h - 5, "pink"), (y0 + h - 4, "red")): put(im, x, y, c)
        for i in range(16):
            px = 10 + i * 20
            post(px, y0, h, (i - 1) in gaps or i in gaps)
    barrier_face(18, 12, GAPS_X); barrier_face(170, 10, GAPS_X)
    for y in range(30, 170):
        row = (y - 30) // 20
        if row in GAPS_Y: continue
        for xs in ((4, 5), (314, 315)):
            put(im, xs[0], y, "pink"); put(im, xs[1], y, "red")
    for i in range(8):  # side emitters seen from above
        py = 30 + i * 20
        gate = (i - 1) in GAPS_Y or i in GAPS_Y
        for cx in (4, 315):
            for dx in range(-2, 3):
                for dy in range(-2, 3):
                    put(im, cx + dx, py + dy, "outline" if max(abs(dx), abs(dy)) == 2 else ("green3" if gate else "yellow") if dx == dy == 0 else "grey5")
    save(im, "cg_board.png")


def star_food():
    rows = ["....y....", "....y....", "...yYy...", "yyyyYyyyy", ".yyYYYyy.", "..yYYYy..", "..yy.yy..", ".yy...yy.", ".y.....y."]
    a = grid(rows)
    b = grid([r.replace("Y", "c") for r in rows])  # twinkle frame
    save(sheet([a, b]), "cw_star.png")


def board_space():
    """Alternative floor for the worm: a nebula field instead of grass, same force field and gates."""
    im = Image.open(os.path.join(lib.OUT, "cg_board.png")).convert("RGBA"); rnd = random.Random(31)
    for y in range(30, 170):
        for x in range(10, 310):
            v = periodic(x, [(2, 1, .1), (5, .6, .4)]) + math.sin(y / 17 + x / 41) * .9 + math.sin(y / 9 - x / 23) * .5
            c = "navy" if v < .4 else "indigo" if v < 1.3 else "plum"
            if .3 < v < .5 or 1.2 < v < 1.4: c = ("indigo" if v < 1 else "plum") if (x + y) % 2 else c  # dithered cloud edges
            put(im, x, y, c)
    for _ in range(120): put(im, rnd.randrange(10, 310), rnd.randrange(33, 170), rnd.choice(["grey5", "grey2", "white", "grey7"]))
    for x in range(10, 310):
        for y in range(30, 33): put(im, x, y, "outline" if y == 30 else "navy")
    save(im, "cw_board_space.png")


PORTAL_COLORS = [("blue", "sky2", "white"), ("plum", "pink", "cream2"), ("brown", "gold", "yellow"), ("indigo", "sky1", "white")]


def portals():
    """Swirling portals for the force-field gates: 4 pair colors x 4 frames, wide (top/bottom gates) and narrow (side gates)."""
    for name, (w, h) in (("cg_portal_w", (13, 12)), ("cg_portal_n", (10, 13))):  # odd span: centred in the 15 px gap between posts
        frames = []
        for cols in PORTAL_COLORS:
            for f in range(4):
                im = img(w, h); cx, cy = (w - 1) / 2, (h - 1) / 2
                for y in range(h):
                    for x in range(w):
                        dx, dy = (x - cx) / (w / 2), (y - cy) / (h / 2); r = math.hypot(dx, dy)
                        if r > 1: continue
                        if r > .82: put(im, x, y, "outline"); continue
                        if r < .22: put(im, x, y, cols[2]); continue
                        s = (math.atan2(dy, dx) / (2 * math.pi) + r * 1.4 - f / 4) % 1
                        put(im, x, y, cols[0] if s < .4 else cols[1] if s < .8 else cols[2])
                frames.append(im)
        save(sheet(frames), name + ".png")


def pad():
    im = img(22, 8)
    ellipse(im, 10.5, 3.5, 10.5, 3.5, "gold"); ellipse(im, 10.5, 3.5, 7.5, 2.2, "yellow"); ellipse(im, 10.5, 3.5, 4, 1.2, "cream2")
    save(im, "cg_pad.png")


def sparkle():
    sp = []
    for s in range(4):
        rows = [["."] * 11 for _ in range(11)]
        for t in range(-s - 1, s + 2):
            if abs(t) <= s + 1:
                rows[5][5 + t] = "w" if abs(t) < 2 else "c"; rows[5 + t][5] = "w" if abs(t) < 2 else "c"
        if s >= 2:
            for d in (-s + 1, s - 1): rows[5 + d][5 + d] = "c"; rows[5 + d][5 - d] = "c"
        sp.append(grid(["".join(r) for r in rows], outline=False))
    save(sheet(sp), "sn_sparkle.png")


# generated name -> resource name
SHEETS = {"cw_board_space": "worm_board", "cw_star": "worm_star", "sn_sparkle": "worm_sparkle", "cg_pad": "worm_pad",
          "cg_portal_w": "worm_portal_w", "cg_portal_n": "worm_portal_n"}

if __name__ == "__main__":
    lib.use_scratch(sys.argv[1] if len(sys.argv) > 1 else None)
    conga_board(); board_space(); star_food(); portals(); pad(); sparkle()  # the board_space floor is painted over cg_board.png
    os.makedirs(DEST, exist_ok=True)
    for src, dst in SHEETS.items(): shutil.copy(os.path.join(lib.OUT, src + ".png"), os.path.join(DEST, f"game_{dst}.png"))
    print("wrote", sorted(f for f in os.listdir(DEST) if "worm" in f))
