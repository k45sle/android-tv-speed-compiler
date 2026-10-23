"""Shared game-art helpers: the 32-color palette, pixel drawing, grid sprites, sheets. Games call use_scratch() before saving."""
import math, os, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
DEST = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
OUT = None  # scratch dir for every sheet, set by use_scratch


def use_scratch(path=None):
    global OUT
    OUT = path or tempfile.mkdtemp(); os.makedirs(OUT, exist_ok=True)
    return OUT


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


def ellipse(im, cx, cy, rx, ry, c):
    for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
        for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1: put(im, x, y, c)
