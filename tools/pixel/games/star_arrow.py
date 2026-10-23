"""Writes the Star Arrow sheets into app/src/main/res/drawable-nodpi as game_arrow_*.png.

Usage (from the repo root): uv run --with pillow python tools/pixel/games/star_arrow.py [scratch-dir]
The ship is the chevron from tools/pixel/logo/gen.py (hull, dashes and the barrel roll). Needs no downloaded packs.
"""
import importlib.util, math, os, random, shutil, sys
import lib
from lib import DEST, ROOT, img, periodic, put, save, sheet

spec = importlib.util.spec_from_file_location("logo_gen", os.path.join(ROOT, "tools", "pixel", "logo", "gen.py"))
logo = importlib.util.module_from_spec(spec); spec.loader.exec_module(logo)

SHIP_FRAMES = 28  # frames 0-15 are the barrel roll (the flap pose), 16-27 one seamless loop of the dash stream
SHIP_KEYS = {"k": "outline", "g": "gold", "b": "sand3", "r": "brown", "y": "yellow", "-": "cream", "~": "sage"}


def ship():
    frames = []
    for rows in logo.frames(*logo.SIZE, 1)[:SHIP_FRAMES]:
        im = img(len(rows[0]), len(rows))
        for y, r in enumerate(rows):
            for x, ch in enumerate(r):
                if ch != ".": put(im, x, y, SHIP_KEYS[ch])
        frames.append(im)
    save(sheet(frames), "arrow_ship.png")


def sky():
    im = img(320, 180); rnd = random.Random(11)
    bands = [(0, "navy"), (120, "indigo"), (150, "plum")]
    for y in range(180):
        c = [b for b in bands if y >= b[0]][-1][1]
        for x in range(320):
            for y0, col in bands[1:]:  # a 6-row dither into each band
                if y0 - 6 <= y < y0 and (x + y) % 2 == 0 and (y - (y0 - 6)) % 3 != 0: c = col
            put(im, x, y, c)
    for _ in range(90): put(im, rnd.randrange(320), rnd.randrange(150), rnd.choice(["grey5", "grey7", "grey5"]))
    save(im, "arrow_sky.png")


def stars():
    rnd = random.Random(12); im = img(320, 150)
    for _ in range(40): put(im, rnd.randrange(320), rnd.randrange(150), rnd.choice(["white", "grey2", "cream"]))
    for _ in range(7):  # twinkle crosses
        x, y = rnd.randrange(4, 316), rnd.randrange(4, 140)
        put(im, x, y, "white")
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)): put(im, x + dx, y + dy, "grey2")
    save(im, "arrow_stars.png")


def planet():
    """Ringed planet and a small grey moon on a 320x90 strip."""
    im = img(320, 90); cx, cy, r = 230, 46, 26
    for y in range(90):
        for x in range(320):
            if math.hypot(x - cx, y - cy) <= r:
                band = int((y - cy + r) / 6) % 3
                lit = math.hypot(x - cx + 9, y - cy + 9) < r * .8
                put(im, x, y, ["mesa1", "mesa2", "sand3"][band] if lit else ["mesa2", "mesa3", "brown"][band])
            if math.hypot(x - 70, y - 22) <= 7: put(im, x, y, "grey2" if math.hypot(x - 68, y - 20) < 5 else "grey5")
    for x in range(cx - 44, cx + 45):  # the ring passes in front of the lower half
        for y in range(cy - 12, cy + 13):
            v = ((x - cx) / 44) ** 2 + ((y - cy) / 9) ** 2
            if .72 <= v <= 1 and (y >= cy - 2 or math.hypot(x - cx, y - cy) > r): put(im, x, y, "gold" if v < .86 else "yellow")
    put(im, 71, 24, "grey7"); put(im, 66, 19, "grey7")
    save(im, "arrow_planet.png")


def moon():
    im = img(320, 20); rnd = random.Random(13)
    for x in range(320):
        top = 2 + round(periodic(x, [(9, 1.2, .1), (23, .8, .4)]))
        for y in range(top, 20): put(im, x, y, "grey2" if y == top else "grey5" if y < 12 else "grey7")
    for _ in range(14):  # craters
        x, y, rr = rnd.randrange(320), rnd.randrange(7, 17), rnd.choice([2, 3, 4])
        for dx in range(-rr, rr + 1):
            put(im, (x + dx) % 320, y, "grey7"); put(im, (x + dx) % 320, y - 1, "grey2" if abs(dx) < rr else "grey5")
    save(im, "arrow_ground.png")


def belt():
    rnd = random.Random(8); im = img(320, 40)  # far asteroid belt: specks of rock in a soft band
    for _ in range(140):
        x = rnd.randrange(320); y = int(20 + rnd.gauss(0, 7)) % 40
        put(im, x, y, rnd.choice(["brown2", "brown2", "grey7", "brown"]))
        if rnd.random() < .25: put(im, (x + 1) % 320, y, "brown2")
    save(im, "arrow_belt.png")


def flame():
    frames = []
    for length in (7, 5, 3):  # exhaust behind the ship, shrinking over three frames
        im = img(9, 7)
        for x in range(length):
            h = max(0, 3 - abs(x - length // 3) // 2)
            for y in range(3 - max(0, h - 1), 4 + max(0, h - 1)):
                put(im, 8 - x, y, "yellow" if x < length // 3 + 1 else "gold" if x < length - 1 else "red")
        frames.append(im)
    save(sheet(frames), "arrow_flame.png")


def rocks():
    """Asteroids in 3 sizes, 8 rotation frames each, drawn from a lumpy radius function (no bitmap rotation)."""
    for name, r, seed in (("s", 5, 1), ("m", 7, 2), ("l", 10, 3)):
        rnd = random.Random(seed); ph = [rnd.random() * 6.28 for _ in range(3)]
        craters = [(rnd.uniform(.2, .6) * r, rnd.random() * 6.28, rnd.choice([1.2, 1.8])) for _ in range(2 + r // 4)]
        n = 2 * r + 5; c = (n - 1) / 2; frames = []
        for f in range(8):
            im = img(n, n); rot = f / 8 * 2 * math.pi
            def edge(t): return r * (1 + .16 * math.sin(3 * (t - rot) + ph[0]) + .09 * math.sin(5 * (t - rot) + ph[1]))
            for y in range(n):
                for x in range(n):
                    dx, dy = x - c, y - c
                    if math.hypot(dx, dy) > edge(math.atan2(dy, dx)): continue
                    col = "sand3" if dx + dy < -r * .55 else "brown2" if dx + dy > r * .7 else "brown"
                    for cr, ct, cs in craters:
                        if math.hypot(x - c - cr * math.cos(ct + rot), y - c - cr * math.sin(ct + rot)) <= cs: col = "brown2"
                    put(im, x, y, col)
            solid = {(x, y) for y in range(n) for x in range(n) if im.getpixel((x, y))[3]}
            for y in range(n):
                for x in range(n):
                    if (x, y) not in solid and any((x + a, y + b) in solid for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))): put(im, x, y, "outline")
            frames.append(im)
        save(sheet(frames), f"arrow_rock_{name}.png")


if __name__ == "__main__":
    lib.use_scratch(sys.argv[1] if len(sys.argv) > 1 else None)
    for f in (ship, sky, stars, planet, moon, belt, flame, rocks): f()
    os.makedirs(DEST, exist_ok=True)
    names = [f for f in os.listdir(lib.OUT) if f.startswith("arrow_")]
    for f in names: shutil.copy(os.path.join(lib.OUT, f), os.path.join(DEST, "game_" + f))
    print("wrote", sorted(names))
