"""Writes the Alien Run sheets into app/src/main/res/drawable-nodpi as game_*.png.

Usage (from the repo root): uv run --with pillow python tools/pixel/games/alien_run.py [scratch-dir]
The runner (stored below as text) and hearts come from the Kenney Pixel Platformer pack; the hearts read its tilemap in kenney/ (CC0, kenney.nl/assets/pixel-platformer).
"""
import math, os, random, shutil, sys
from PIL import Image
import lib
from lib import DEST, KEYS, RGB, grid, img, periodic, put, remap, save, sheet, strip_fill

K = os.path.join(lib.HERE, "kenney")

# Kenney Pixel Platformer green character (CC0), remapped and flipped, as text. Keys below.
KM = {"a": "sky1", "b": "white", "c": "green7", "d": "sky2", "e": "green3", "f": "grey7", "g": "green5",
      "x": "grey5", "r": "red"}
HELMET = [
    "......aaaaaaaaaaaa......", "....aaabbbbbbbbbbaaa....", "...aabbaaaaaaaaaabbaa...", "...abaaaaaaaaaaaaaaba...",
    "..aabaaaaaaaaaaaaaabaa..", "..abaaaccccccccccaaaba..", "..abaaccccccccccccaaba..", "..abacccddddddddcccaba..",
    "..abaccddddddddddccaba..", "..abaccdeeeeeeeedccaba..", "..abacceeeeeeeeeeccaba..", "..abacceeeeeeeeeeccaba..",
    "..abacceeeeeeeeeeccaba..", "..abaaceeeeeeeeeecaaba..", "..aabaceeeeeeeeeecabaa..", "...abaaaeeeeeeeeaaaba...",
    "...aabbaaaaaaaaaabbaa...", "....aaabbbbbbbbbbaaa....",
]
LEGS_A = [".....caaaaaaaaaaaac.....", ".....cceeeeeeeeeecc.....", "......ccgggccgggccc.....", "......cceeecceeecc......", ".......cceecceecc......."]
LEGS_B = [".....caaaaaaaaaaaac.....", ".....ccgeeeeeeeegcc.....", ".....ceegccccccgeec.....", ".....ceecccccccceec.....", ".....cccc......cccc....."]


def face(rows, eyes="right"):
    """Eyes (2x2) and mouth on the visor; 'right' shifts the look one pixel toward the run direction."""
    r = [list(x) for x in rows]
    if eyes == "x":
        for ex in (9, 14):
            for dx, dy in ((0, 0), (2, 0), (1, 1), (0, 2), (2, 2)): r[11 + dy][ex + dx] = "f"
        r[15][12] = r[15][13] = "f"; r[14][12] = r[14][13] = "r"
        for i, (x, y) in enumerate(((6, 3), (7, 4), (7, 5), (8, 6))): r[y][x] = "x"  # helmet crack
    else:
        dy = {"right": 0, "up": -1, "down": 1}[eyes]
        for ex in (11, 15):
            for xx in (ex, ex + 1):
                for yy in (12 + dy, 13 + dy): r[yy][xx] = "f"
        r[15][13] = r[15][14] = "f"
    return ["".join(x) for x in r]


def kframe(helmet, legs, hy=1, ly=19):
    c = [["."] * 24 for _ in range(24)]
    for rows, y0 in ((legs, ly), (helmet, hy)):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch != "." and 0 <= y0 + y < 24: c[y0 + y][x] = ch
    im = img(24, 24)
    for y in range(24):
        for x in range(24):
            if c[y][x] != ".": put(im, x, y, KM[c[y][x]])
    return im


def kenney_runner():
    h = face(HELMET); hu = face(HELMET, "up"); hd = face(HELMET, "down"); hx = face(HELMET, "x")
    squash = h[:9] + h[11:]
    frames = [kframe(h, LEGS_A, 1), kframe(h, LEGS_A, 2), kframe(h, LEGS_B, 1), kframe(h, LEGS_B, 2),  # run x4
              kframe(hu, LEGS_B[:4], 0, 18),  # jump: legs tucked
              kframe(hd, LEGS_A, 1),  # fall
              kframe(squash, LEGS_B, 3),  # land squash
              kframe(hx, LEGS_B, 1)]  # crash
    save(sheet(frames), "runner_k.png")


CACTI = [
    ["...gg...", "..gggg..", "..gggg..", "g.gggg..", "gggggg.g", "gggggggg", ".ggggggg", "..gggg..",
     "..gggg..", "..gggg..", "..gggg..", "..gggg..", "..gggg..", "..gggg.."],
    ["....ggg....", "...ggggg...", "...ggggg...", "...ggggg.gg", "gg.ggggg.gg", "gg.ggggg.gg", "gg.ggggggg.",
     "gg.gggggg..", "ggggggggg..", ".gggggggg..", "...ggggg...", "...ggggg...", "...ggggg...", "...ggggg...",
     "...ggggg...", "...ggggg...", "...ggggg...", "...ggggg...", "...ggggg..."],
    ["..gg.......gg..", ".gggg.....gggg.", ".gggg.g...gggg.", ".gggggg...gggg.", "gggggg..g.gggg.",
     "g.gggg..ggggggg", "..gggg...gggggg", "..gggg....gggg.", "..gggg....gggg.", "..gggg....gggg.",
     "..gggg....gggg.", "..gggg....gggg."],
]


def spiked(rows):  # cream spikes on the cactus body, every few cells
    out = []
    for y, r in enumerate(rows):
        r = list(r)
        for x in range(len(r)):
            if r[x] == "g" and (x * 3 + y * 5) % 11 == 0: r[x] = "c"
        out.append("".join(r))
    return out


def pad(rows, n=1):
    w = len(rows[0]) + 2 * n
    return ["." * w] * n + ["." * n + r + "." * n for r in rows] + ["." * w] * n


def sky_b():
    im = img(320, 180); bands = ["sky3", "sky2", "sky2", "sky1", "sky1"]
    edges = [0, 36, 60, 96, 120, 180]
    for y in range(180):
        b = next(i for i in range(5) if y < edges[i + 1])
        for x in range(320):
            c = bands[b]
            if b > 0 and y - edges[b] < 4 and (x + y) % 2 == 0: c = bands[b - 1]  # 4-row dither between bands
            put(im, x, y, c)
    for y in range(-13, 14):  # sun
        for x in range(-13, 14):
            d = math.hypot(x, y)
            if d <= 12.5: put(im, 250 + x, 40 + y, "cream2" if d < 8 else "yellow")
    return im


def clouds():
    im = img(320, 40); rnd = random.Random(3)
    for cx, cy, w in ((40, 14, 34), (150, 24, 24), (235, 10, 40)):
        for x in range(-w // 2, w // 2):
            hgt = int(6 + 5 * math.cos(x / w * math.pi) + rnd.choice([0, 0, 1]))
            for y in range(hgt):
                put(im, (cx + x) % 320, cy - y + 4, "white" if y > 1 else "grey2")
    return im


def mesas():
    def top(x):  # flat-topped plateaus: quantised sine sum
        v = periodic(x, [(2, 16, .1), (5, 7, .3), (11, 3, .7)])
        return 26 - (round(v / 6) * 6 if v > -4 else -12)
    def col(x, d, t):
        if d < 2: return "mesa1"
        lit = periodic(x + 2, [(2, 16, .1), (5, 7, .3), (11, 3, .7)]) - periodic(x, [(2, 16, .1), (5, 7, .3), (11, 3, .7)])
        return "mesa3" if lit > 0.6 else "mesa2"
    return strip_fill(64, top, col)


def dunes():
    top = lambda x: 12 + periodic(x, [(3, 6, .2), (7, 2, .5)])
    return strip_fill(32, top, lambda x, d, t: "sand1" if d < 2 else ("sand2" if d < 14 else "sand3"))


def dust():
    shapes = [["..cc..", ".cwwc."], [".c..c.", "cw..wc", ".c..c."], ["c....c", "......", "w....w"]]
    return sheet([grid(s, outline=False) for s in shapes])


def shadow(w):
    im = img(w, 3)
    for x in range(w):
        for y in range(3):
            if (y == 1) or (2 < x < w - 3): put(im, x, y, "sand3")
    return im


def ground():
    rnd = random.Random(7); im = img(320, 32)
    for x in range(320):
        for y in range(32): put(im, x, y, "sand1" if y < 3 else "sand2" if y < 18 else "sand3")
        put(im, x, 0, "brown")
    for _ in range(70):
        x, y = rnd.randrange(320), rnd.randrange(4, 30)
        c = rnd.choice(["sand3", "brown", "sand1"])
        put(im, x, y, c)
        if rnd.random() < .4: put(im, (x + 1) % 320, y, c)
    for x in range(0, 320, 2): put(im, x, 18, "sand3")  # a darker band line for depth
    return im


def desert():
    save(sheet([grid(pad(spiked(c), 0), shade=True) for c in CACTI]), "dino_B_drawn_cacti.png")
    save(ground(), "dino_B_drawn_ground.png")
    save(sky_b(), "dino_B_drawn_sky.png"); save(clouds(), "dino_B_drawn_clouds.png")
    save(mesas(), "dino_B_drawn_far.png"); save(dunes(), "dino_B_drawn_near.png")
    save(dust(), "dino_B_dust.png"); save(sheet([shadow(18), shadow(13), shadow(8)]), "dino_B_shadow.png")


def hearts_ufo():
    tiles = Image.open(os.path.join(K, "tilemap_packed.png")).convert("RGBA")
    # full and empty heart, recolored green (owner, 2026-10-21): warm fill -> brand greens, dark rim -> outline
    swap = {RGB[a]: RGB[b] for a, b in (("mesa1", "green3"), ("pink", "green3"), ("red", "green5"), ("mesa2", "green7"),
                                         ("mesa3", "outline"), ("brown2", "outline"), ("brown", "green7"))}
    hearts = []
    for x in (72, 108):
        h = remap(tiles.crop((x, 36, x + 18, 54)))
        for yy in range(h.height):
            for xx in range(h.width):
                p = h.getpixel((xx, yy))
                if p[3] and p[:3] in swap: h.putpixel((xx, yy), swap[p[:3]] + (255,))
        hearts.append(h)
    save(sheet(hearts), "hearts.png")
    ufo = ["....ccc.....", "...cbbbc....", "..yyyyyyyy..", ".ggggggggggg", "gwgggwgggwgg", ".ggggggggggg", "...y..y..y.."]
    KEYS.update({"b": "sky2"})
    frames = []
    for on in range(2):  # lights blink
        rows = [r.replace("w", "Y" if on else "w") for r in ufo]
        frames.append(grid(rows))
    KEYS["b"] = "brown"
    save(sheet(frames), "ufo.png")


# generated name -> resource name
SHEETS = {"runner_k": "runner", "dino_B_drawn_cacti": "cacti", "dino_B_drawn_ground": "ground", "dino_B_drawn_sky": "sky",
          "dino_B_drawn_clouds": "clouds", "dino_B_drawn_far": "mesas", "dino_B_drawn_near": "dunes", "dino_B_shadow": "shadow",
          "dino_B_dust": "dust", "hearts": "hearts", "ufo": "ufo"}

if __name__ == "__main__":
    lib.use_scratch(sys.argv[1] if len(sys.argv) > 1 else None)
    kenney_runner(); desert(); hearts_ufo()
    os.makedirs(DEST, exist_ok=True)
    for src, dst in SHEETS.items(): shutil.copy(os.path.join(lib.OUT, src + ".png"), os.path.join(DEST, f"game_{dst}.png"))
    print("wrote", sorted(os.listdir(DEST)))
