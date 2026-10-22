"""Game art generator: the 32-color game palette, drawn desert scenery, sprite helpers. Run via alien_run.py."""
import math, os, random, sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
CC0 = sys.argv[1]  # downloaded packs (Kenney Pixel Platformer in <dir>/kenney), read as image data only
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "out")  # scratch dir for every sheet
os.makedirs(OUT, exist_ok=True)

# 32-color game palette. Brand colors first (logo base, cream, gold, UI greens).
PAL = {
    "outline": "0B2017", "navy": "1B2147", "logo": "153C29", "indigo": "34306A", "green7": "2E6B4A",
    "green5": "4F9A5E", "green3": "6FD08C", "sage": "9DBFA9", "grass": "A8D45A", "cream": "F2E6C4",
    "cream2": "FFF6DC", "gold": "E8A33A", "yellow": "F2C14E", "white": "FFFFFF", "grey2": "C7CCD1",
    "grey5": "8A929B", "grey7": "4D5560", "sky1": "BDE6EE", "sky2": "8CCDE6", "sky3": "5AA8D6",
    "blue": "3B6FA8", "sand1": "F5D58A", "sand2": "E4B464", "sand3": "C98E45", "brown": "9C6330",
    "brown2": "6B3F22", "mesa1": "E07A4E", "mesa2": "B5543A", "mesa3": "7E3A30", "red": "D8403A",
    "pink": "FF9585", "plum": "5A4A7A",
}
assert len(PAL) == 32 and len(set(PAL.values())) == 32
RGB = {k: tuple(int(v[i:i + 2], 16) for i in (0, 2, 4)) for k, v in PAL.items()}
PALSET = set(RGB.values())


def img(w, h): return Image.new("RGBA", (w, h), (0, 0, 0, 0))
def put(im, x, y, c):
    if 0 <= x < im.width and 0 <= y < im.height: im.putpixel((x, y), RGB[c] + (255,))


def save(im, name):
    for p in im.getdata():
        assert p[3] in (0, 255) and (p[3] == 0 or p[:3] in PALSET), (name, p)
    im.save(os.path.join(OUT, name))


def remap(im):
    """Nearest palette color (redmean distance), alpha cut at 128."""
    im = im.convert("RGBA"); out = img(*im.size); cache = {}
    def near(c):
        if c not in cache:
            def d(p):
                r = (c[0] + p[0]) / 2
                return (2 + r / 256) * (c[0] - p[0]) ** 2 + 4 * (c[1] - p[1]) ** 2 + (2 + (255 - r) / 256) * (c[2] - p[2]) ** 2
            cache[c] = min(PALSET, key=d)
        return cache[c]
    for y in range(im.height):
        for x in range(im.width):
            p = im.getpixel((x, y))
            if p[3] >= 128: out.putpixel((x, y), near(p[:3]) + (255,))
    return out


# ---- grid sprites: fill letters, then auto shade (tier B) and auto outline ----
KEYS = {"g": "green5", "l": "green3", "d": "green7", "c": "cream", "w": "white", "k": "outline", "y": "gold",
        "Y": "yellow", "r": "red", "o": "outline", "s": "sand3", "b": "brown", "B": "brown2"}


def grid(rows, shade=False, outline=True, flat=None):
    h, w = len(rows), max(len(r) for r in rows)
    cells = [[(r[x] if x < len(r) else ".") for x in range(w)] for r in rows]
    if flat:
        cells = [[flat.get(c, c) for c in row] for row in cells]
    if shade:  # rim light on top edges, shadow on bottom edges of the green fill
        src = [row[:] for row in cells]
        for y in range(h):
            for x in range(w):
                if src[y][x] == "g":
                    if y == 0 or src[y - 1][x] == ".": cells[y][x] = "l"
                    elif y == h - 1 or src[y + 1][x] == ".": cells[y][x] = "d"
    im = img(w, h)
    for y in range(h):
        for x in range(w):
            if cells[y][x] != ".": put(im, x, y, KEYS[cells[y][x]])
    if outline:
        fill = [[cells[y][x] != "." for x in range(w)] for y in range(h)]
        for y in range(h):
            for x in range(w):
                if not fill[y][x] and any(0 <= x + dx < w and 0 <= y + dy < h and fill[y + dy][x + dx]
                                          for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    put(im, x, y, "outline")
    return im


# Dino body, 24 wide, rows 0-15 (legs added per frame). Faces right.
BODY = [
    "........................",
    "............gggggggg....",
    "...........gggggggggg...",
    "...........gggggwwgggg..",
    "...........gggggwkggggg.",
    "...........gggggggggggg.",
    "...........gggggggg.....",
    "..........yyyyyyyy......",
    ".g.......gyyyyyyyy......",
    ".gg.....ggggggggccg.....",
    "..gg...gggggggggcccgg...",
    "..gggggggggggggcccccg...",
    "...ggggggggggggcccccg...",
    "....gggggggggggccccg....",
    ".....gggggggggggggg.....",
    "......gggggggggggg......",
]


def dino(legs, bob=0, eye=None, squash=False, scarf=0):
    rows = [list(r) for r in BODY]
    if eye == "x":
        rows[3][16], rows[3][17], rows[4][16], rows[4][17] = "k", "w", "w", "k"
        rows[6] = list("...........ggggggg......")
        rows[6][18] = "r"
    if scarf:  # scarf tail flutters behind the neck
        rows[7 + scarf][8] = "y"; rows[7][9] = "y"
    if squash:
        rows = rows[:12] + rows[13:]
        rows.insert(0, ["."] * 24)
    body = ["".join(r) for r in rows]
    canvas = [["."] * 24 for _ in range(24)]
    for y, r in enumerate(body):
        yy = y + 1 - bob
        if 0 <= yy < 24:
            for x, ch in enumerate(r):
                if ch != ".": canvas[yy][x] = ch
    for hip, dx, lift in legs:  # leg: 2px column from the hip, foot 3px toward +x
        top = 16 - bob + (1 if squash else 0); foot_y = 21 - lift
        for y in range(top, foot_y + 1):
            t = (y - top) / max(1, foot_y - top)
            x = hip + round(dx * t)
            for xx in (x, x + 1):
                if 0 <= xx < 24 and 0 <= y < 24: canvas[y][xx] = "g"
        fx = hip + dx
        for xx in (fx, fx + 1, fx + 2):
            if 0 <= xx < 24 and 0 <= foot_y < 24: canvas[foot_y][xx] = "g"
    return ["".join(r) for r in canvas]


def dino_frames(tier):
    if tier == "A":
        run = [dino([(7, -1, 0), (13, 1, 2)]), dino([(7, 1, 2), (13, -1, 0)])]
        return {"run": run, "jump": [dino([(7, -1, 2), (13, 1, 2)])], "fall": [dino([(7, -1, 2), (13, 1, 2)])],
                "land": [run[0]], "crash": [dino([(7, -1, 0), (13, 1, 0)], eye="x")]}
    # 6-frame run: back and front leg half a cycle apart, foot lifts while it swings forward, body bobs
    cyc = [(-3, 0), (-1, 0), (1, 0), (3, 1), (1, 3), (-1, 2)]
    run = []
    for i in range(6):
        b = cyc[i]; f = cyc[(i + 3) % 6]
        run.append(dino([(7, b[0], b[1]), (13, f[0], f[1])], bob=1 if i in (1, 4) else 0, scarf=1 if i % 3 == 0 else 2))
    return {"run": run, "jump": [dino([(7, -2, 3), (13, 2, 1)], scarf=2)],
            "fall": [dino([(7, -1, 0), (13, 1, 0)], bob=1, scarf=1)],
            "land": [dino([(6, -2, 0), (13, 2, 0)], squash=True, scarf=2)],
            "crash": [dino([(7, -2, 0), (13, 2, 0)], eye="x", scarf=2)]}


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


def sheet(frames):
    w = max(f.width for f in frames); h = max(f.height for f in frames)
    s = img(w * len(frames), h)
    for i, f in enumerate(frames): s.paste(f, (i * w + (w - f.width) // 2, h - f.height))
    return s


def periodic(x, terms):  # tileable over 320 px: integer periods only
    return sum(a * math.sin(2 * math.pi * (k * x / 320 + ph)) for k, a, ph in terms)


def strip_fill(h, top_at, colors_by_depth):
    im = img(320, h)
    for x in range(320):
        t = int(top_at(x))
        for y in range(max(0, t), h): put(im, x, y, colors_by_depth(x, y - t, t))
    return im


def ground(tier):
    rnd = random.Random(7); im = img(320, 32)
    for x in range(320):
        for y in range(32):
            put(im, x, y, "sand2" if tier == "A" else ("sand1" if y < 3 else "sand2" if y < 18 else "sand3"))
        put(im, x, 0, "brown")
    for _ in range(26 if tier == "A" else 70):
        x, y = rnd.randrange(320), rnd.randrange(4, 30)
        c = "brown" if tier == "A" else rnd.choice(["sand3", "brown", "sand1"])
        put(im, x, y, c)
        if tier == "B" and rnd.random() < 0.4: put(im, (x + 1) % 320, y, c)
    if tier == "B":  # a darker band line for depth
        for x in range(0, 320, 2): put(im, x, 18, "sand3")
    return im


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
        base = "mesa3" if lit > 0.6 else "mesa2"
        return base
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


def drawn(tier):
    fr = dino_frames(tier)
    flat = {"l": "g", "d": "g", "w": "c"} if tier == "A" else None
    order = fr["run"] + fr["jump"] + fr["fall"] + fr["land"] + fr["crash"]
    save(sheet([grid(f, shade=tier == "B", flat=flat) for f in order]), f"dino_{tier}_drawn_runner.png")
    save(sheet([grid(pad(spiked(c) if tier == "B" else c, 0), shade=tier == "B") for c in CACTI]), f"dino_{tier}_drawn_cacti.png")
    save(ground(tier), f"dino_{tier}_drawn_ground.png")
    if tier == "B":
        save(sky_b(), "dino_B_drawn_sky.png"); save(clouds(), "dino_B_drawn_clouds.png")
        save(mesas(), "dino_B_drawn_far.png"); save(dunes(), "dino_B_drawn_near.png")
        save(dust(), "dino_B_dust.png"); save(sheet([shadow(18), shadow(13), shadow(8)]), "dino_B_shadow.png")
    else:
        save(clouds(), "dino_A_drawn_clouds.png")
    return len(fr["run"])


def cc0(tier):
    k = os.path.join(CC0, "kenney", "Tilemap")
    chars = Image.open(os.path.join(k, "tilemap-characters_packed.png")).convert("RGBA")
    tiles = Image.open(os.path.join(k, "tilemap_packed.png")).convert("RGBA")
    # Kenney's green character: 2 walk frames only; flip to face right; the crash frame is frame 0 shifted (no art for it)
    f = [remap(chars.crop((i * 24, 0, i * 24 + 24, 24))).transpose(Image.FLIP_LEFT_RIGHT) for i in (0, 1)]
    save(sheet(f + [f[1], f[0], f[0], f[0]]), f"dino_{tier}_cc0_runner.png")
    cactus = remap(tiles.crop((126, 108, 144, 126)))
    tall = img(18, 27); tall.paste(cactus, (0, 0)); tall.paste(remap(tiles.crop((126, 117, 144, 126))), (0, 18))
    save(sheet([cactus, tall, sheet([cactus, cactus])]), f"dino_{tier}_cc0_cacti.png")
    top = remap(tiles.crop((36, 36, 54, 54))); fill = remap(tiles.crop((72, 18, 90, 36)))
    g = img(320, 32)
    for x in range(0, 320, 18):
        g.paste(top, (x, 0)); g.paste(fill, (x, 18))
    save(g, f"dino_{tier}_cc0_ground.png")
    if tier == "B":
        L = os.path.join(CC0, "desert", "PNG", "bg_desert_layers")
        def layer(n, h): return remap(Image.open(os.path.join(L, f"bg_desert_{n}.png")).convert("RGBA").resize((320, h), Image.BOX))
        save(layer(1, 180), "dino_B_cc0_sky.png"); save(layer(2, 84), "dino_B_cc0_far.png"); save(layer(3, 68), "dino_B_cc0_near.png")


# ---- Flappy frame: bird vs logo arrow ----
BIRD = ["......yyyy......", "....yyyyyywww...", "...yyyyyyywkw...", "..yyyyyyyywww...", "cccyyyyyyyyrrrr.",
        "ccccyyyyyyyrrr..", "cccccyyyyyyyy...", ".cccyyyyyyyyy...", "..YYyyyyyyyy....", "...YYYyyyyy....."]
LOGO = ["c.cc............", "................", "cc.cc.ccc........", "..................", "..cc.cccccccc....",
        "........cccccyy..", "ccc.cc.ccccccyyyy", "........cccccyy..", ".ccc.cccccccc....", "...............",
        "ccc.ccccc........", "...............", "..ccc............"]


def flappy(flyer):
    im = img(320, 180)
    for y in range(180):
        c = "sky3" if y < 50 else "sky2" if y < 100 else "sky1"
        for x in range(320): put(im, x, y, c)
    im.alpha_composite(clouds(), (0, 20))
    hills = strip_fill(50, lambda x: 20 + periodic(x, [(3, 9, .1), (8, 3, .4)]), lambda x, d, t: "grass" if d < 2 else "green3")
    im.alpha_composite(hills, (0, 104))
    bush = strip_fill(30, lambda x: 12 + periodic(x, [(13, 4, .0), (29, 2, .3)]), lambda x, d, t: "green5" if d < 2 else "green7")
    im.alpha_composite(bush, (0, 130))
    for px, gap in ((150, 52), (250, 96)):  # pipe pairs: cap 26 wide, body 22, gap 56
        for x in range(px, px + 26):
            for y in range(0, 160):
                inside = gap - 28 <= y < gap + 28
                cap = (gap - 40 <= y < gap - 28) or (gap + 28 <= y < gap + 40)
                body = not inside and not cap and px + 2 <= x < px + 24
                if cap or body:
                    edge = x in (px, px + 25) or (not cap and x in (px + 2, px + 23)) or (cap and y in (gap - 40, gap + 39))
                    c = "outline" if edge else "green3" if x < px + 7 else "green5" if x < px + 18 else "green7"
                    put(im, x, y, c)
    g = ground("B"); im.alpha_composite(g.crop((0, 0, 320, 20)), (0, 160))
    if flyer == "bird":
        b = grid(BIRD); im.alpha_composite(b, (70, 70))
    else:
        a = img(19, 15); a.alpha_composite(grid(LOGO, outline=True).crop((0, 0, 19, 15)))
        im.alpha_composite(a, (66, 68))
    save(im, f"flappy_{flyer}.png")


if __name__ == "__main__":
    pal = img(32, 1)
    for i, v in enumerate(PAL.values()): pal.putpixel((i, 0), tuple(int(v[j:j + 2], 16) for j in (0, 2, 4)) + (255,))
    pal.save(os.path.join(OUT, "palette.png"))
    for t in "AB":
        print(t, "run frames", drawn(t)); cc0(t)
    flappy("bird"); flappy("arrow")
    print("ok", sorted(os.listdir(OUT)))
