"""Writes the arcade picker sheets into app/src/main/res/drawable-nodpi: game_arcade_room.png and game_arcade_<key>.png (off frame, then lit frame).

Usage (from the repo root): uv run --with pillow python tools/pixel/games/arcade.py [scratch-dir]
Needs no downloaded packs.
"""
import os, random, shutil, sys
import lib
from lib import DEST, ellipse, img, put, save, sheet

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
    return im


def room():
    rnd = random.Random(4); W, H = 432, 190; im = img(W, H)
    for y in range(H):
        for x in range(W):
            if y < 160: put(im, x, y, "logo" if (x // 24) % 2 else "outline")
            else: put(im, x, y, "outline")
    for x in range(W): put(im, x, 159, "outline"); put(im, x, 160, "green7")
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
    save(im, "arcade_room.png")


if __name__ == "__main__":
    lib.use_scratch(sys.argv[1] if len(sys.argv) > 1 else None)
    for k in CAB: save(sheet([cabinet(k, lit) for lit in (False, True)]), f"arcade_{k}.png")
    room()
    os.makedirs(DEST, exist_ok=True)
    names = [f for f in os.listdir(lib.OUT) if f.startswith("arcade_")]
    for f in names: shutil.copy(os.path.join(lib.OUT, f), os.path.join(DEST, "game_" + f))
    print("wrote", sorted(names))
