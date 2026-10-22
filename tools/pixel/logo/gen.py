"""Logo generator: writes the logo sheets, XPM sources, banner GIF and social preview from the locked L0 design.

Usage (from the repo root): uv run --with pillow python tools/pixel/logo/gen.py, then tools/pixel/render.sh for banner.png and the icons.
Keys: . empty, g gold, b sand (gold in shadow), r brown, y yellow, k outline, - cream dash, ~ sage dash, c cream wordmark.
"""
import math, os
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))

# Subset of the game palette (games/gen.py PAL); bg is the logo base.
PAL = {"bg": "153C29", "c": "F2E6C4", "g": "E8A33A", "b": "C98E45", "r": "9C6330", "y": "F2C14E", "k": "0B2017",
       "-": "F2E6C4", "~": "9DBFA9"}
UNDER = {"g": "b", "b": "r", "y": "b"}  # belly tones while the roll shows the underside
LOOP = 36   # frames in the whole loop: 3 s at 12 fps
ROLL = 16   # roll frames at the start of the loop
P = 12      # stream period: every dash row repeats every 12 px, so the stream wraps seamlessly at the end of LOOP
NOSE, H, C = 21, 13, 6  # nose column, grid height, centre row

# One tile per dash row (rows d = -6, -4, ..., 6 from the centre), each P wide. Dash lengths follow today's logo.
TILES = {-6: "-.--........", -4: "--.--.---...", -2: "--.-------..", 0: "---.--.----.",
         2: ".---.------.", 4: "---.-----...", 6: "..---......."}
assert all(len(t) == P for t in TILES.values())

SIZE = (6, 4)  # locked size M: chevron reach in rows from the centre, band width


def lines(g): return ["".join(r) for r in g]


def hull(reach, w):
    g = [["."] * (NOSE + 1) for _ in range(H)]
    for y in range(H):
        d = abs(y - C)
        if d > reach: continue
        re = NOSE - 2 * d
        for x in range(re - w + 1, re + 1): g[y][x] = "g" if y <= C else "b"
    return lines(g)


def dashes(w, shift):
    """Dash field at stream offset shift: each row runs from x 0 to one clear pixel before the outline."""
    g = [["."] * (NOSE + 1) for _ in range(H)]
    for d, tile in TILES.items():
        y = C + d; end = NOSE - 2 * abs(d) - w - 2
        last = len(tile.rstrip(".")) - 1  # at rest the tile's last dash sits on end, like today's right-aligned dashes
        for x in range(0, end + 1):
            if tile[(x - end + last + shift) % P] == "-":
                g[y][x] = "~" if x < 2 else "-"  # the two left columns fade in, so dashes do not pop out of a wall
    return lines(g)


def roll(rows, k):
    """Frame k of the 16-step barrel roll on the long axis: rows squash by |cos|, belly tones on the far half-turn."""
    co = math.cos(k * 2 * math.pi / ROLL); out = []
    for y in range(H):
        if abs(co) < 1e-6: src = C if y == C else None
        else:
            s = C + (y - C) / co
            src = round(s) if -0.5 < s < H - 0.5 else None
        row = rows[src] if src is not None else "." * len(rows[0])
        out.append("".join(UNDER.get(ch, ch) if co < -1e-6 else ch for ch in row))
    return out


PAD_L, PAD_R = 1, 2


def compose(hull_rows, dash_rows):
    """Hull over dashes, outline around the hull only, padded for the outline."""
    w = NOSE + 1 + PAD_L + PAD_R
    hr = ["." * w] + ["." * PAD_L + r + "." * PAD_R for r in hull_rows] + ["." * w]
    dr = ["." * w] + ["." * PAD_L + r + "." * PAD_R for r in dash_rows] + ["." * w]
    out = []
    for y in range(len(hr)):
        row = []
        for x in range(w):
            if hr[y][x] != ".": row.append(hr[y][x]); continue
            edge = any(0 <= x + dx < w and 0 <= y + dy < len(hr) and hr[y + dy][x + dx] != "."
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            row.append("k" if edge else dr[y][x])
        out.append("".join(row))
    return out


def frames(reach, w, speed):
    assert LOOP * speed % P == 0  # the stream is back at offset 0 when the loop wraps
    hr = hull(reach, w)
    return [compose(roll(hr, t) if t < ROLL else hr, dashes(w, t * speed)) for t in range(LOOP)]


# 3x5 wordmark font, cut from tools/pixel/banner.xpm.
FONT = {
    "S": ["ccc", "c..", "ccc", "..c", "ccc"], "P": ["ccc", "c.c", "ccc", "c..", "c.."],
    "E": ["ccc", "c..", "ccc", "c..", "ccc"], "D": ["cc.", "c.c", "c.c", "c.c", "cc."],
    "C": ["ccc", "c..", "c..", "c..", "ccc"], "O": ["ccc", "c.c", "c.c", "c.c", "ccc"],
    "M": ["c.c", "ccc", "ccc", "c.c", "c.c"], "I": ["ccc", ".c.", ".c.", ".c.", "ccc"],
    "L": ["c..", "c..", "c..", "c..", "ccc"],
}
WORDS = [("SPEED", 0), ("COMPILE", 8)]  # two lines with a 3-row gap, as in today's banner
WM_W, WM_H = 27, 13


def wordmark(t):
    """Streak: a slanted gold band crosses the letters once per half-turn of the roll (two sweeps); still the rest of the loop."""
    g = [["."] * WM_W for _ in range(WM_H)]
    for word, oy in WORDS:
        for j, ch in enumerate(word):
            for ry, row in enumerate(FONT[ch]):
                for rx, c in enumerate(row):
                    if c != ".": g[oy + ry][4 * j + rx] = "c"
    half = ROLL // 2
    if t < ROLL:
        p = round((t % half) * 34 / (half - 1))  # ~5 px a frame; the 5 px band is as wide as a step, so no column is skipped
        for y in range(WM_H):
            for x in range(WM_W):
                off = p + (WM_H - 1 - y) // 3 - x  # band leans forward
                if g[y][x] == "c" and 0 <= off <= 4: g[y][x] = "y" if off == 0 else "g"
    return lines(g)


def banner_layout(key):
    """Logo and wordmark centred as one group on the 64x36 banner, 4 px apart."""
    lw = len(key[0]) - PAD_L - PAD_R
    x0 = (64 - (lw + 4 + WM_W)) // 2
    return {"lx": x0 - PAD_L, "ly": 18 - len(key) // 2, "wx": x0 + lw + 4, "wy": 12}


def icon(key):
    """27x27 icon: key frame cropped to the 18 px an adaptive mask keeps (72 of 108 dp), centred."""
    rows = [r for r in key if r.strip(".")]
    xs = [x for r in rows for x, ch in enumerate(r) if ch != "."]
    x1 = max(xs); x0 = max(min(xs), x1 - 17)
    rows = [r[x0:x1 + 1] for r in rows]
    ox, oy = (27 - len(rows[0])) // 2, (27 - len(rows)) // 2
    g = [["."] * 27 for _ in range(27)]
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            if ch != ".": g[oy + y][ox + x] = ch
    return lines(g)


def banner(t, still=False):
    """Frame t of the 64x36 banner; still uses the key frame and the unanimated wordmark."""
    key = frames(*SIZE, 1)[0]
    lay = banner_layout(key)
    g = [["."] * 64 for _ in range(36)]
    for rows, ox, oy in ((frames(*SIZE, 1)[0 if still else t], lay["lx"], lay["ly"]), (wordmark(LOOP if still else t), lay["wx"], lay["wy"])):
        for y, r in enumerate(rows):
            for x, ch in enumerate(r):
                if ch != ".": g[oy + y][ox + x] = ch
    return lines(g)


def image(rows, scale=1):
    rgb = {k: tuple(int(v[i:i + 2], 16) for i in (0, 2, 4)) for k, v in PAL.items()}
    im = Image.new("RGB", (len(rows[0]), len(rows)))
    im.putdata([rgb["bg"] if ch == "." else rgb[ch] for r in rows for ch in r])
    return im.resize((im.width * scale, im.height * scale), Image.NEAREST)


def xpm(path, rows):
    keys = ["."] + [k for k in PAL if k != "bg" and any(k in r for r in rows)]
    body = [f'"{len(rows[0])} {len(rows)} {len(keys)} 1",'] + [f'"{k} c #{PAL["bg" if k == "." else k]}",' for k in keys]
    body += [f'"{r}",' for r in rows]
    body[-1] = body[-1].rstrip(",")
    with open(path, "w") as f: f.write("/* XPM */\nstatic char *img[] = {\n" + "\n".join(body) + "\n};\n")


def main():
    fr = [banner(t) for t in range(LOOP)]
    still = banner(0, True)
    # One 64x36 banner per frame, side by side (37 x 64 wide): the app draws frame t from x = 64 t; the extra last frame is the
    # still (no streak) shown when stopped, the same image as banner.xpm.
    image(["".join(f[y] for f in fr + [still]) for y in range(36)]).save(f"{ROOT}/app/src/main/res/drawable-nodpi/logo_banner.png")
    key = frames(*SIZE, 1)[0]
    xpm(f"{ROOT}/tools/pixel/icon.xpm", icon(key))
    xpm(f"{ROOT}/tools/pixel/banner.xpm", still)
    ims = [image(f, 10) for f in fr]
    ims[0].save(f"{ROOT}/docs/media/banner.gif", save_all=True, append_images=ims[1:], duration=1000 // 12, loop=0, optimize=False)
    social = Image.new("RGB", (1280, 640), tuple(int(PAL["bg"][i:i + 2], 16) for i in (0, 2, 4)))
    social.paste(image(still, 10), (320, 140))
    social.save(f"{ROOT}/docs/media/social-preview.png")


if __name__ == "__main__":
    main()
