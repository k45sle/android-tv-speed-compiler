"""Writes tools/pixel/bezel.xpm: a pixel-art TV frame for the README clips (colours from the game palette in games/lib.py).

160x103 cells; the transparent 144x81 screen (16:9) starts at (8, 8). render.sh makes the logo green clear and scales it 400% into bezel.png, so a 576x324
clip sits in a 640 px wide frame. Needs only the standard library: python3 tools/pixel/bezel.py
"""
import os

W, H = 160, 103
SX, SY, SW, SH = 8, 8, 144, 81  # the screen hole
BODY_BOTTOM = 97                # last row of the TV body; the stand is below it
COLORS = {  # outline, grey7, grey5, navy and gold from the game palette; "." is the logo green, made clear by render.sh
    ".": "153C29", "k": "0B2017", "b": "4D5560", "h": "8A929B", "s": "1B2147", "g": "E8A33A",
}
grid = [["."] * W for _ in range(H)]


def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1): grid[y][x] = c


def outlined(x0, y0, x1, y1, fill):
    rect(x0, y0, x1, y1, "k"); rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill)


# stand first, so the body overlaps its top
outlined(66, BODY_BOTTOM, 93, BODY_BOTTOM + 3, "b")       # neck
outlined(44, BODY_BOTTOM + 3, 115, H - 1, "b")            # foot
rect(45, H - 2, 114, H - 2, "s")
# body: outline, grey fill, light top-left edge, dark bottom-right edge, rounded corners
outlined(0, 0, W - 1, BODY_BOTTOM, "b")
rect(1, 1, W - 2, 1, "h"); rect(1, 1, 1, BODY_BOTTOM - 1, "h")
rect(2, BODY_BOTTOM - 1, W - 2, BODY_BOTTOM - 1, "s"); rect(W - 2, 2, W - 2, BODY_BOTTOM - 1, "s")
for x, y in ((0, 0), (W - 1, 0), (0, BODY_BOTTOM), (W - 1, BODY_BOTTOM)): grid[y][x] = "."
# the screen: a dark inner ring, then the hole
rect(SX - 1, SY - 1, SX + SW, SY + SH, "k")
rect(SX, SY, SX + SW - 1, SY + SH - 1, ".")
# chin: speaker slots on the left, power LED on the right
for i in range(10):
    rect(14 + i * 3, 93, 14 + i * 3, 95, "s")
rect(146, 92, 148, 94, "k"); rect(147, 93, 147, 93, "g")

lines = ["/* XPM */", "static char *img[] = {", f'"{W} {H} {len(COLORS)} 1",']
lines += [f'"{k} c #{v}",' for k, v in COLORS.items()]
lines += [f'"{"".join(row)}",' for row in grid]
lines[-1] = lines[-1].rstrip(",")
lines.append("};")
open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "bezel.xpm"), "w").write("\n".join(lines) + "\n")
