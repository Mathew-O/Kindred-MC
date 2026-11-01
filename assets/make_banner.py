"""Draws the Kindred MC logo banner and the starter players' head icons.

The banner is just the name, in stone block letters with depth and a dark outline, the way the
Minecraft logo looks. Everything is drawn on a small canvas, one cell per texture pixel, then
scaled up without smoothing so the pixels stay crisp. Run it from the repository root:

    python3 assets/make_banner.py

It writes assets/banner.png (transparent background) and assets/faces/*.png (the heads of the
starter skins in assets/skins). Needs Python 3 with Pillow (pip install pillow).
"""
import random
from pathlib import Path
from PIL import Image

ASSETS = Path(__file__).resolve().parent
SCALE = 4      # screen pixels per texture pixel
CELL = 4       # texture pixels per font cell
DEPTH = 7      # how far the letters' sides reach down, in texture pixels
OUTLINE = 2    # the dark outline's width, in texture pixels
MARGIN = 8
GAP = 2        # font cells between letters, so the outlines never join them
rnd = random.Random(1210)

# Chunky letters, seven cells high with two-cell strokes, like the game's own logo.
FONT = {
    'K': ["110011", "110110", "111100", "111000", "111100", "110110", "110011"],
    'I': ["111111", "001100", "001100", "001100", "001100", "001100", "111111"],
    'N': ["110011", "111011", "111111", "110111", "110011", "110011", "110011"],
    'D': ["111110", "110011", "110011", "110011", "110011", "110011", "111110"],
    'R': ["111110", "110011", "110011", "111110", "111100", "110110", "110011"],
    'E': ["111111", "110000", "110000", "111110", "110000", "110000", "111111"],
    'M': ["1100011", "1110111", "1111111", "1101011", "1100011", "1100011", "1100011"],
    'C': ["011111", "110000", "110000", "110000", "110000", "110000", "011111"],
    ' ': ["000"] * 7,
}


def letters(text):
    """The texture pixels covered by the letters' front faces, and the text's size."""
    face, x = set(), 0
    for ch in text:
        glyph = FONT[ch]
        for gy, row in enumerate(glyph):
            for gx, bit in enumerate(row):
                if bit == '1':
                    for sy in range(CELL):
                        for sx in range(CELL):
                            face.add((x + gx * CELL + sx, gy * CELL + sy))
        x += (len(glyph[0]) + GAP) * CELL
    return face, x - GAP * CELL, 7 * CELL


def shade(color, amount):
    return tuple(max(0, min(255, c + amount)) for c in color) + (255,)


def stone(x, y, base):
    """Stone texture: each texture pixel a few shades off base, with the odd darker fleck."""
    noise = rnd.choice((-14, -8, -4, 0, 0, 4, 8, 12))
    if rnd.random() < 0.06:
        noise -= 26
    return shade(base, noise)


def logo(text):
    face, width, height = letters(text)
    sides = {(x, y + d) for (x, y) in face for d in range(1, DEPTH + 1)} - face
    solid = face | sides
    outline = {(x + dx, y + dy) for (x, y) in solid
               for dx in range(-OUTLINE, OUTLINE + 1) for dy in range(-OUTLINE, OUTLINE + 1)} - solid

    img = Image.new('RGBA', (width + 2 * MARGIN, height + DEPTH + 2 * MARGIN), (0, 0, 0, 0))
    def put(x, y, color):
        img.putpixel((x + MARGIN, y + MARGIN), color)

    for (x, y) in outline | solid:  # a soft shadow under everything, down and to the right
        sx, sy = x + 3, y + 3
        if (sx, sy) not in outline | solid and 0 <= sx + MARGIN < img.width and 0 <= sy + MARGIN < img.height:
            put(sx, sy, (0, 0, 0, 90))
    for (x, y) in outline:
        put(x, y, (18, 18, 22, 255))
    for (x, y) in sides:  # the letters' sides: darker the further down
        depth = min(range(1, DEPTH + 1), key=lambda d: 0 if (x, y - d) in face else d + DEPTH)
        base = (84, 84, 90) if depth <= DEPTH // 2 else (60, 60, 66)
        put(x, y, stone(x, y, base))
    for (x, y) in face:  # the fronts: light stone, a touch darker towards the bottom
        band = y * 3 // height
        base = ((214, 214, 214), (184, 184, 184), (154, 154, 154))[band]
        color = stone(x, y, base)
        if (x, y - 1) not in face or (x - 1, y) not in face:
            color = shade(color[:3], 34)    # lit top and left edges
        elif (x + 1, y) not in face:
            color = shade(color[:3], -30)   # shaded right edge
        put(x, y, color)
    return img


def face_of(path):
    skin = Image.open(path).convert('RGBA')
    head = skin.crop((8, 8, 16, 16))
    head.alpha_composite(skin.crop((40, 8, 48, 16)))  # the hat layer
    return head


def main():
    banner = logo('KINDRED MC')
    banner.resize((banner.width * SCALE, banner.height * SCALE), Image.NEAREST).save(ASSETS / 'banner.png', optimize=True)
    faces = ASSETS / 'faces'
    faces.mkdir(exist_ok=True)
    for skin in sorted((ASSETS / 'skins').glob('*.png')):
        face_of(skin).resize((128, 128), Image.NEAREST).save(faces / f'{skin.stem.lower()}.png', optimize=True)


main()
