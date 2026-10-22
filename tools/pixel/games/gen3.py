"""Round 3: hearts and UFO for the runner, space theme for the arrow, Alien Conga (snake) board and crew, arcade picker."""
import math, os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen import *  # noqa
from gen2 import KM, HELMET, LEGS_A, LEGS_B, face, kframe

K = os.path.join(CC0, "kenney", "Tilemap")

FONT = {"0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111", "5": "111100111001111",
        "A": "010101111101101", "B": "110101110101110", "C": "111100100100111", "D": "110101101101110", "E": "111100110100111",
        "G": "111100101101111", "H": "101101111101101", "I": "111010010010111", "K": "101101110101101", "L": "100100100100111", "M": "101111111101101",
        "N": "110101101101101", "O": "111101101101111", "P": "111101111100100", "R": "110101110101101", "S": "111100111001111",
        "T": "111010010010010", "U": "101101101101111", "W": "101101111111101", "Y": "101101010010010", " ": "0" * 15}


def text(im, s, x, y, c, k=1, shadow=None, adv=None):
    for ch in s:
        f = FONT[ch]
        for i in range(15):
            if f[i] == "1":
                for dx in range(k):
                    for dy in range(k):
                        if shadow: put(im, x + (i % 3) * k + dx + 1, y + (i // 3) * k + dy + 1, shadow)
        for i in range(15):
            if f[i] == "1":
                for dx in range(k):
                    for dy in range(k): put(im, x + (i % 3) * k + dx, y + (i // 3) * k + dy, c)
        x += adv or 4 * k


def tw(s, k=1, adv=None): return len(s) * (adv or 4 * k) - (adv and adv - 3 * k or k)


def ellipse(im, cx, cy, rx, ry, c):
    for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
        for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1: put(im, x, y, c)


# ---------- Runner extras ----------
def runner_extras():
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


# ---------- Star Arrow (flappy) in space ----------
def space():
    rnd = random.Random(11); im = img(320, 180)
    bands = [(0, "navy"), (120, "indigo"), (150, "plum")]
    for y in range(180):
        c = [b for b in bands if y >= b[0]][-1][1]
        for x in range(320):
            cc = c
            for i, (y0, col) in enumerate(bands[1:], 1):  # 6-row dither into each band
                if y0 - 6 <= y < y0 and (x + y) % 2 == 0 and (y - (y0 - 6)) % 3 != 0: cc = col
            put(im, x, y, cc)
    for _ in range(90): put(im, rnd.randrange(320), rnd.randrange(150), rnd.choice(["grey5", "grey7", "grey5"]))
    save(im, "sp_sky.png")
    st = img(320, 150)
    for _ in range(40): put(st, rnd.randrange(320), rnd.randrange(150), rnd.choice(["white", "grey2", "cream"]))
    for _ in range(7):  # twinkle crosses
        x, y = rnd.randrange(4, 316), rnd.randrange(4, 140)
        put(st, x, y, "white")
        for d in ((1, 0), (-1, 0), (0, 1), (0, -1)): put(st, x + d[0], y + d[1], "grey2")
    save(st, "sp_stars.png")
    pl = img(320, 90)  # ringed planet and a small moon
    cx, cy, r = 230, 46, 26
    for y in range(90):
        for x in range(320):
            d = math.hypot(x - cx, y - cy)
            if d <= r:
                band = int((y - cy + r) / 6) % 3
                lit = math.hypot(x - cx + 9, y - cy + 9) < r * .8
                put(pl, x, y, ["mesa1", "mesa2", "sand3"][band] if lit else ["mesa2", "mesa3", "brown"][band])
    for x in range(cx - 44, cx + 45):  # ring in front of the lower half
        for y in range(cy - 12, cy + 13):
            v = ((x - cx) / 44) ** 2 + ((y - cy) / 9) ** 2
            if .72 <= v <= 1 and (y >= cy - 2 or math.hypot(x - cx, y - cy) > r): put(pl, x, y, "gold" if v < .86 else "yellow")
    for y in range(90):
        for x in range(320):
            if math.hypot(x - 70, y - 22) <= 7: put(pl, x, y, "grey2" if math.hypot(x - 68, y - 20) < 5 else "grey5")
    put(pl, 71, 24, "grey7"); put(pl, 66, 19, "grey7")
    save(pl, "sp_planet.png")
    g = img(320, 20)  # moon surface
    for x in range(320):
        top = 2 + round(periodic(x, [(9, 1.2, .1), (23, .8, .4)]))
        for y in range(top, 20): put(g, x, y, "grey2" if y == top else "grey5" if y < 12 else "grey7")
    for _ in range(14):
        x, y, rr = rnd.randrange(320), rnd.randrange(7, 17), rnd.choice([2, 3, 4])
        for dx in range(-rr, rr + 1):
            put(g, (x + dx) % 320, y, "grey7"); put(g, (x + dx) % 320, y - 1, "grey2" if abs(dx) < rr else "grey5")
    save(g, "sp_ground.png")
    p = img(26, 21)  # rows 0-11 cap (docking ring with lights), rows 12-19 an 8-row body slice, row 20 unused
    for x in range(26):
        for y in range(12):
            edge = x in (0, 25) or y in (0, 11)
            put(p, x, y, "outline" if edge else "yellow" if y in (2, 3) else "gold" if y < 9 else "brown")
        for y in (5, 6):
            for lx in (5, 12, 19): put(p, lx, y, "red"); put(p, lx + 1, y, "red")
        if 2 <= x < 24:
            for y in range(12, 20):
                c = "outline" if x in (2, 23) else "grey2" if x < 7 else "grey5" if x < 18 else "grey7"
                if y == 19: c = "grey7" if c != "outline" else c
                if y == 15 and 9 <= x <= 15: c = "sky2"
                put(p, x, y, c)
    save(p, "sp_pylon.png")
    fl = []
    for f in range(3):  # exhaust flame behind the arrow
        im2 = img(9, 7)
        L = [7, 5, 3][f]
        for x in range(L):
            h = 3 - abs(x - L // 3) // 2 if x < L else 0
            for y in range(3 - max(0, h - 1) // 1, 4 + max(0, h - 1)):
                put(im2, 8 - x, y, "yellow" if x < L // 3 + 1 else "gold" if x < L - 1 else "red")
        fl.append(im2)
    save(sheet(fl), "sp_flame.png")


# ---------- Alien Conga (snake) ----------
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


def crew():
    chars = Image.open(os.path.join(K, "tilemap-characters_packed.png")).convert("RGBA")
    out = []
    for i in (2, 4, 6):  # blue, pink, yellow crew: 2 walk frames each
        out += [remap(chars.crop((i * 24, 0, i * 24 + 24, 24))), remap(chars.crop(((i + 1) * 24, 0, (i + 1) * 24 + 24, 24)))]
    save(sheet(out), "cg_crew.png")
    lead = []
    def face4(d):
        if d == "left":
            r = [list(x) for x in face(HELMET)]
            for y in (12, 13):
                for x in range(9, 18):
                    if r[y][x] == "f": r[y][x] = "e"
                for ex in (9, 13): r[y][ex] = r[y][ex + 1] = "f"
            r[15][13] = r[15][14] = "e"; r[15][11] = r[15][12] = "f"
            return ["".join(x) for x in r]
        return face(HELMET, d)
    for d in ("right", "down", "left", "up"):
        h = face4(d)
        lead += [kframe(h, LEGS_A, 1), kframe(h, LEGS_B, 2)]
    lead.append(kframe(face(HELMET, "x"), LEGS_B, 1))
    save(sheet(lead), "cg_lead.png")
    pad = img(22, 8)
    ellipse(pad, 10.5, 3.5, 10.5, 3.5, "gold"); ellipse(pad, 10.5, 3.5, 7.5, 2.2, "yellow"); ellipse(pad, 10.5, 3.5, 4, 1.2, "cream2")
    save(pad, "cg_pad.png")
    sh = img(16, 5)
    ellipse(sh, 7.5, 2, 7.5, 2, "outline")
    save(sh, "cg_shadow.png")


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


def rocks():
    """Asteroids in 3 sizes, 8 rotation frames each, drawn from a lumpy radius function (no bitmap rotation)."""
    for name, r, seed in (("ast_s", 5, 1), ("ast_m", 7, 2), ("ast_l", 10, 3)):
        rnd = random.Random(seed); ph = [rnd.random() * 6.28 for _ in range(3)]
        craters = [(rnd.uniform(.2, .6) * r, rnd.random() * 6.28, rnd.choice([1.2, 1.8])) for _ in range(2 + r // 4)]
        n = 2 * r + 5; frames = []
        for f in range(8):
            im = img(n, n); c = (n - 1) / 2; rot = f / 8 * 2 * math.pi
            def R(t): return r * (1 + .16 * math.sin(3 * (t - rot) + ph[0]) + .09 * math.sin(5 * (t - rot) + ph[1]))
            inside = lambda x, y: math.hypot(x - c, y - c) <= R(math.atan2(y - c, x - c))
            for y in range(n):
                for x in range(n):
                    if not inside(x, y): continue
                    dx, dy = x - c, y - c
                    col = "sand3" if dx + dy < -r * .55 else "brown2" if dx + dy > r * .7 else "brown"
                    for cr, ct, cs in craters:
                        kx, ky = c + cr * math.cos(ct + rot), c + cr * math.sin(ct + rot)
                        if math.hypot(x - kx, y - ky) <= cs: col = "brown2"
                    put(im, x, y, col)
            for y in range(n):  # outline
                for x in range(n):
                    if im.getpixel((x, y))[3] == 0 and any(0 <= x + a < n and 0 <= y + b < n and im.getpixel((x + a, y + b))[3] and im.getpixel((x + a, y + b))[:3] != RGB["outline"]
                                                       for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        put(im, x, y, "outline")
            frames.append(im)
        save(sheet(frames), name + ".png")
    rnd = random.Random(8); belt = img(320, 40)  # far belt: specks of rock in a soft band
    for _ in range(140):
        x = rnd.randrange(320); y = int(20 + rnd.gauss(0, 7)) % 40
        put(belt, x, y, rnd.choice(["brown2", "brown2", "grey7", "brown"]))
        if rnd.random() < .25: put(belt, (x + 1) % 320, y, "brown2")
    save(belt, "sp_belt.png")


def star_food():
    rows = ["....y....", "....y....", "...yYy...", "yyyyYyyyy", ".yyYYYyy.", "..yYYYy..", "..yy.yy..", ".yy...yy.", ".y.....y."]
    KEYS["Y"] = "yellow"; KEYS["y"] = "gold"
    a = grid([r.replace("Y", "w") if False else r for r in rows])
    b = grid([r.replace("Y", "c") for r in rows])  # twinkle frame
    save(sheet([a, b]), "cw_star.png")


def board_space():
    """Alternative floor for the worm: a nebula field instead of grass, same force field and gates."""
    im = Image.open(os.path.join(OUT, "cg_board.png")).convert("RGBA"); rnd = random.Random(31)
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


# ---------- Arcade picker ----------
CAB = {"run": ("ALIEN RUN", "mesa2", "mesa3", "mesa1", "sand1", "mesa3"),
       "arrow": ("STAR ARROW", "indigo", "navy", "plum", "navy", "yellow"),
       "worm": ("COSMIC WORM", "blue", "navy", "sky2", "sky1", "outline")}


def cabinet(key, lit):
    """96x150 front plus a 6 px side face. Screen hole at (8, 32), 80x45 (a 160x90 dp preview at 2 dp per art pixel)."""
    title, body, dark, light, mq, mqtext = CAB[key]
    W, H = 102, 150; im = img(W, H)
    for y in range(H):
        for x in range(96):
            put(im, x, y, body)
        for x in range(96, 102):
            if y >= (x - 96): put(im, x, y, dark)
    for y in range(H):
        put(im, 0, y, "outline"); put(im, 95, y, "outline"); put(im, 101, y, "outline")
        for x in range(2, 5): put(im, x, y, light)  # front edge stripe
    for x in range(102): put(im, x, 0, "outline"); put(im, x, H - 1, "outline")
    # marquee
    for y in range(4, 24):
        for x in range(6, 90): put(im, x, y, (mq if lit else dark) if 5 < y < 23 and 6 < x < 89 else "outline")
    tc = mqtext if lit else body
    text(im, title, 48 - tw(title, 2, 7) // 2, 9, tc if lit else light, 2, adv=7)
    # bezel and screen hole
    for y in range(26, 82):
        for x in range(4, 92): put(im, x, y, "outline" if y in (26, 81) or x in (4, 91) else "navy")
    for y in range(32, 77):
        for x in range(8, 88): im.putpixel((x, y), (0, 0, 0, 0))
    # control panel, slanted top face
    for y in range(84, 100):
        for x in range(4, 92): put(im, x, y, "grey2" if y < 86 else "grey5" if y < 97 else "grey7")
    for x in range(4, 92): put(im, x, 84, "outline"); put(im, x, 100, "outline")
    for y in range(86, 92): put(im, 26, y, "outline"); put(im, 27, y, "grey7")  # joystick
    ellipse(im, 26.5, 86, 3, 2.5, "red"); put(im, 25, 85, "pink")
    ellipse(im, 26.5, 93, 4, 1.5, "outline")
    for bx, c in ((56, "gold"), (68, "red"), (80, "sky2")):
        ellipse(im, bx, 92, 3.5, 2, "outline"); ellipse(im, bx, 91.5, 2.5, 1.2, c)
    # coin door
    for y in range(108, 138):
        for x in range(34, 62): put(im, x, y, "outline" if y in (108, 137) or x in (34, 61) else "grey7")
    for sx in (40, 52):
        for y in range(114, 124):
            for x in range(sx, sx + 5): put(im, x, y, "outline" if x in (sx, sx + 4) or y in (114, 123) else ("yellow" if lit else "brown2"))
        for y in range(116, 122): put(im, sx + 2, y, "outline")
    for x in range(38, 58): put(im, x, 130, "grey5")
    for y in range(141, 149):
        for x in range(1, 95): put(im, x, y, dark)
    save(im, f"cab_{key}_{'on' if lit else 'off'}.png")


def room():
    rnd = random.Random(4); W, H = 432, 190; im = img(W, H)
    for y in range(H):
        for x in range(W):
            if y < 160: put(im, x, y, "indigo" if (x // 24) % 2 else "navy")
            else: put(im, x, y, "navy")
    for x in range(W): put(im, x, 159, "outline"); put(im, x, 160, "plum")
    for _ in range(70):  # arcade carpet: small shapes in bright colors
        x, y, c = rnd.randrange(W), rnd.randrange(163, 188), rnd.choice(["pink", "yellow", "sky2", "green3"])
        kind = rnd.randrange(3)
        pts = [(0, 0), (1, 0), (2, 1)] if kind == 0 else [(0, 0), (1, 1), (0, 2)] if kind == 1 else [(1, 0), (0, 1), (2, 1), (1, 2)]
        for dx, dy in pts: put(im, x + dx, y + dy, c)
    # sign board
    sx, sy, sw, sh = 116, 3, 200, 24
    for y in range(sy, sy + sh):
        for x in range(sx, sx + sw): put(im, x, y, "brown2" if y in (sy, sy + sh - 1) or x in (sx, sx + sw - 1) else "outline")
    t = "PLAY WHILE YOU WAIT"
    text(im, t, sx + (sw - tw(t, 2)) // 2, sy + 7, "yellow", 2, shadow="brown")
    save(im, "room.png")


if __name__ == "__main__":
    runner_extras(); space(); conga_board(); crew(); portals(); rocks(); star_food(); board_space()
    for k in CAB:
        cabinet(k, True); cabinet(k, False)
    room(); print("ok")
