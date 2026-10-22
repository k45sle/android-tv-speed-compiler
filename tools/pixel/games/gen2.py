"""Round 2: Kenney-based runner frames, logo arrow sheet, Flappy layers and pipe, Snake board and sprites."""
import math, os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen import *  # noqa: palette, img, put, save, grid, ground, clouds, sky_b, strip_fill, periodic, sheet

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


ARROW = [".c.cc............", ".................", "cc.cc.ccc........", ".................", "..cc.cccccccc....",
         "........cccccyy..", "ccc.cc.ccccccyyyy", "........cccccyy..", ".ccc.cccccccc....", ".................",
         "ccc.ccccc........", ".................", "..ccc............"]


def arrow_sheet():
    """5 tilts (sheared columns, nose up to nose down) x 3 trail lengths (bars behind the tip shrink after a flap)."""
    frames = []
    for k in (-0.4, -0.2, 0, 0.2, 0.4):
        for cut in (0, 3, 6):
            rows = [["."] * 19 for _ in range(25)]
            for y, r in enumerate(ARROW):
                for x, ch in enumerate(r):
                    if ch == "." or x < cut: continue
                    rows[y + 6 + round((x - 8) * k)][x + 1] = ch
            frames.append(grid(["".join(r) for r in rows], outline=False))
    save(sheet(frames), "arrow.png")


def flappy_layers():
    im = img(320, 180)
    for y in range(180):
        c = "sky3" if y < 50 else "sky2" if y < 100 else "sky1"
        for x in range(320):
            if y in range(50, 54) or y in range(100, 104):
                c2 = ("sky3" if y < 100 else "sky2") if (x + y) % 2 == 0 else c
                put(im, x, y, c2)
            else: put(im, x, y, c)
    save(im, "fl_sky.png")
    save(strip_fill(56, lambda x: 20 + periodic(x, [(3, 9, .1), (8, 3, .4)]), lambda x, d, t: "grass" if d < 2 else "green3"), "fl_hills.png")
    save(strip_fill(30, lambda x: 12 + periodic(x, [(13, 4, .0), (29, 2, .3)]), lambda x, d, t: "green5" if d < 2 else "green7"), "fl_bush.png")
    save(ground("B").crop((0, 0, 320, 20)), "fl_ground.png")
    p = img(26, 13)  # rows 0-11 cap, row 12 the body slice (stretched vertically when drawn)
    for x in range(26):
        for y in range(12):
            edge = x in (0, 25) or y in (0, 11)
            put(p, x, y, "outline" if edge else "green3" if x < 7 else "green5" if x < 18 else "green7")
        if 2 <= x < 24:
            put(p, x, 12, "outline" if x in (2, 23) else "green3" if x < 8 else "green5" if x < 18 else "green7")
    save(p, "pipe.png")


# ---- Snake, 3/4 top-down. Board 15x7 cells of 20 px inside a fence, HUD strip on top. ----
def snake_board():
    rnd = random.Random(5); im = img(320, 180)
    for y in range(180):
        for x in range(320): put(im, x, y, "logo" if y < 18 else "green5")
    for cy in range(7):
        for cx in range(15):
            x0, y0 = 10 + cx * 20, 30 + cy * 20
            if (cx + cy) % 2:  # every other cell a little lighter: tufts of light grass
                for _ in range(5):
                    tx, ty = x0 + rnd.randrange(2, 17), y0 + rnd.randrange(3, 18)
                    put(im, tx, ty, "green3"); put(im, tx - 1, ty - 1, "green3"); put(im, tx + 1, ty - 1, "green3")
            for _ in range(3): put(im, x0 + rnd.randrange(20), y0 + rnd.randrange(20), "green7")
    for x in range(10, 310):  # shadow of the far fence on the grass
        for y in range(30, 33): put(im, x, y, "green7")
    def fence_face(y0, h):
        for x in range(320):
            for y in range(y0, y0 + h):
                if y in (y0 + 3, y0 + 4, y0 + h - 4, y0 + h - 3): put(im, x, y, "brown")  # two rails
                if y == y0 + 5 or y == y0 + h - 2: put(im, x, y, "brown2")
        for px in range(0, 320, 20):
            for x in range(px, px + 5):
                for y in range(y0, y0 + h): put(im, x, y, "sand1" if y == y0 else "brown2" if x == px + 4 else "brown")
    fence_face(18, 12); fence_face(170, 10)
    for y in range(30, 170):  # side rails seen from above
        for x in list(range(0, 10)) + list(range(310, 320)):
            put(im, x, y, "brown" if x in (3, 4, 5, 6, 313, 314, 315, 316) else "brown2" if x in (7, 317) else "green7" if x in (8, 9) else "green5")
        if (y - 30) % 20 < 4:
            for x in list(range(2, 8)) + list(range(312, 318)): put(im, x, y, "sand1" if (y - 30) % 20 == 0 else "brown")
    save(im, "snake_board.png")


def blob(d, kind="body", eyes=None, tongue=False):
    """A round segment in 3/4 view: light top-left, side band at the bottom, outline. eyes: (dx, dy) direction."""
    n = d + 8; c = n / 2 - .5; r = d / 2
    rows = [["."] * n for _ in range(n)]
    for y in range(n):
        for x in range(n):
            dx, dy = x - c, y - c
            if dx * dx + dy * dy <= r * r:
                side = dy > r * .45
                lit = (dx + r * .3) ** 2 + (dy + r * .45) ** 2 < (r * .45) ** 2
                rows[y][x] = "b" if side else "Y" if lit else "y"
    if eyes:
        ex, ey = eyes
        for s in (-1, 1):
            px = round(c + ex * r * .35 + (-ey) * s * r * .38); py = round(c + ey * r * .35 + ex * s * r * .38 - 1)
            for yy in (py, py + 1):
                for xx in (px, px + 1): rows[yy][xx] = "w"
            rows[py + 1][px + (1 if ex >= 0 else 0)] = "k"
    if tongue and eyes:
        ex, ey = eyes
        for t in range(int(r) + 1, int(r) + 4):
            rows[round(c + ey * t)][round(c + ex * t)] = "r"
    return grid(["".join(r) for r in rows])


def snake_sprites():
    save(blob(14), "sn_body.png")
    heads = []
    for e in ((1, 0), (0, 1), (-1, 0), (0, -1)):
        heads += [blob(16, eyes=e), blob(16, eyes=e, tongue=True)]
    save(sheet(heads), "sn_head.png")
    sh = img(16, 6)
    for x in range(16):
        for y in range(6):
            if ((x - 7.5) / 8) ** 2 + ((y - 2.5) / 3) ** 2 <= 1: put(sh, x, y, "green7")
    save(sh, "sn_shadow.png")
    apple = ["......bb.ee.", ".....b.eee..", "..rrrbrrr...", ".rrRRrrrrr..", "rrRRrrrrrrr.", "rRRrrrrrrrr.",
             "rRrrrrrrrrr.", "rrrrrrrrrrr.", "rrrrrrrrrrr.", ".rrrrrrrrr..", "..rrrrrrr...", "...rr.rr...."]
    KEYS.update({"R": "pink", "e": "green3"})
    save(grid(apple), "sn_apple.png")
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


if __name__ == "__main__":
    kenney_runner(); arrow_sheet(); flappy_layers(); snake_board(); snake_sprites(); print("ok")
