"""Draws the Kindred MC banner and the crew's pixel portraits from their skins in assets/skins.

Everything is drawn on a small canvas, one cell per Minecraft-style pixel, then scaled up without
smoothing so the pixels stay crisp. Run it from the repository root after changing a skin:

    python3 assets/make_banner.py

It writes assets/banner.png and assets/faces/*.png. Needs Python 3 with Pillow (pip install pillow).
"""
import random
from pathlib import Path
from PIL import Image

ASSETS = Path(__file__).resolve().parent
OUT = ASSETS
W, H, SCALE = 256, 96, 5
rnd = random.Random(1210)

# --- Reading a player skin -------------------------------------------------------------

def slim(skin):
    """Slim (three-pixel) arms leave the skin's pixels just right of the right arm's top unused."""
    return all(skin.getpixel((x, y))[3] == 0 for x in (50, 51) for y in range(16, 20))

def part(skin, box):
    return skin.crop(box)

def figure(path):
    skin = Image.open(path).convert('RGBA')
    w = 3 if slim(skin) else 4
    fig = Image.new('RGBA', (8 + 2 * w, 32), (0, 0, 0, 0))
    def put(base, over, at):
        fig.alpha_composite(part(skin, base), at)
        fig.alpha_composite(part(skin, over), at)
    put((8, 8, 16, 16), (40, 8, 48, 16), (w, 0))            # head and hat
    put((20, 20, 28, 32), (20, 36, 28, 48), (w, 8))         # body and jacket
    put((44, 20, 44 + w, 32), (44, 36, 44 + w, 48), (0, 8))  # right arm (viewer's left)
    put((36, 52, 36 + w, 64), (52, 52, 52 + w, 64), (w + 8, 8))  # left arm
    put((4, 20, 8, 32), (4, 36, 8, 48), (w, 20))            # right leg
    put((20, 52, 24, 64), (4, 52, 8, 64), (w + 4, 20))      # left leg
    return fig

def face(path):
    skin = Image.open(path).convert('RGBA')
    f = skin.crop((8, 8, 16, 16))
    f.alpha_composite(skin.crop((40, 8, 48, 16)))
    return f

# --- Drawing the banner ----------------------------------------------------------------

# A 5x7 block font. Each glyph is seven rows of five characters; '1' is a filled pixel.
FONT = {
    'A': ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    'B': ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    'C': ["01111", "10000", "10000", "10000", "10000", "10000", "01111"],
    'D': ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    'E': ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    'F': ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    'G': ["01111", "10000", "10000", "10011", "10001", "10001", "01111"],
    'H': ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    'I': ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    'J': ["00111", "00001", "00001", "00001", "10001", "10001", "01110"],
    'K': ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    'L': ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    'M': ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    'N': ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    'O': ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    'P': ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    'R': ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    'S': ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    'T': ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    'U': ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    'V': ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    'W': ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    'Y': ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    ' ': ["00000"] * 7,
    '!': ["00100", "00100", "00100", "00100", "00100", "00000", "00100"],
    '-': ["00000", "00000", "00000", "01110", "00000", "00000", "00000"],
}

# Chunky two-pixel-stroke letters for the title, like the game's own logo.
BOLD = {
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


def put(img, x, y, color):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), color)


def jitter(base, spread):
    """A colour a few shades either side of base, like a Minecraft texture."""
    d = rnd.randint(-spread, spread)
    return tuple(max(0, min(255, c + d)) for c in base[:3]) + (255,)


def text_mask(text, scale, font=FONT):
    """The pixels of text in a block font, at the given scale."""
    cells = set()
    x = 0
    for ch in text:
        glyph = font[ch]
        for gy, row in enumerate(glyph):
            for gx, bit in enumerate(row):
                if bit == '1':
                    for sy in range(scale):
                        for sx in range(scale):
                            cells.add((x + gx * scale + sx, gy * scale + sy))
        x += (len(glyph[0]) + 1) * scale
    return cells, x - scale, 7 * scale


def draw_title(img, text, scale, top):
    """Stone-textured letters with a dark outline and drop shadow, centred."""
    cells, width, height = text_mask(text, scale, BOLD)
    left = (img.width - width) // 2
    shadow, outline = (24, 24, 32, 255), (40, 40, 44, 255)
    for (x, y) in cells:
        put(img, left + x + 2, top + y + 2, shadow)
    for (x, y) in cells:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if (x + dx, y + dy) not in cells:
                    put(img, left + x + dx, top + y + dy, outline)
    for (x, y) in cells:
        shade = (176, 176, 176) if y < height * 0.35 else (140, 140, 140) if y < height * 0.7 else (112, 112, 112)
        put(img, left + x, top + y, jitter(shade, 10))


def draw_caption(img, text, top, color=(255, 255, 255, 255)):
    cells, width, _ = text_mask(text, 1)
    left = (img.width - width) // 2
    for (x, y) in cells:
        put(img, left + x + 1, top + y + 1, (48, 48, 64, 255))
    for (x, y) in cells:
        put(img, left + x, top + y, color)


def draw_sky(img, horizon):
    top, bottom = (108, 160, 248), (180, 210, 255)
    for y in range(horizon):
        band = (y // 4) * 4 / max(1, horizon - 1)  # stepped, like a pixel sky
        color = tuple(int(top[i] + (bottom[i] - top[i]) * band) for i in range(3)) + (255,)
        for x in range(img.width):
            img.putpixel((x, y), color)


def draw_cloud(img, x, y, w, h):
    for cy in range(h):
        for cx in range(w):
            put(img, x + cx, y + cy, (250, 250, 255, 255) if cy < h - 1 else (226, 232, 246, 255))


def draw_sun(img, x, y):
    for cy in range(10):
        for cx in range(10):
            edge = cx in (0, 9) or cy in (0, 9)
            put(img, x + cx, y + cy, (255, 236, 140, 255) if edge else (255, 250, 200, 255))


def draw_ground(img, top):
    for x in range(img.width):
        drip = rnd.choice((2, 2, 3, 3, 4))  # grass hangs a little way down the dirt
        for y in range(top, img.height):
            if y < top + drip:
                put(img, x, y, jitter((98, 168, 58), 12))
            elif y > img.height - 5:
                put(img, x, y, jitter((128, 128, 128), 14))  # stone at the bottom
            else:
                put(img, x, y, jitter((134, 96, 67), 12))
        if rnd.random() < 0.18:  # a tuft of grass on top
            put(img, x, top - 1, jitter((110, 184, 66), 10))


def draw_tree(img, x, ground):
    for y in range(ground - 14, ground):
        put(img, x, y, jitter((107, 81, 48), 8))
        put(img, x + 1, y, jitter((96, 72, 42), 8))
    for cy in range(-24, -12):
        width = 5 if cy < -21 else 7
        for cx in range(-width, width + 2):
            if rnd.random() < 0.92:
                put(img, x + cx, ground + cy, jitter((60, 125, 40), 16))


def draw_crafting_table(img, x, y):
    """A 10x10 crafting table: a light grid on top, darker sides with a saw and a hammer."""
    for cy in range(10):
        for cx in range(10):
            if cy < 3:
                color = (88, 58, 32) if cy == 2 or cx in (0, 3, 6, 9) else (176, 128, 74)
            else:
                color = (120, 84, 48) if cx in (0, 9) else (150, 104, 60)
            put(img, x + cx, y + cy, jitter(color, 8))
    for (mx, my) in ((2, 5), (3, 5), (4, 5), (2, 6), (6, 5), (7, 5), (7, 6), (7, 7)):
        put(img, x + mx, y + my, (70, 50, 34, 255))


def draw_chest(img, x, y):
    """A 10x10 chest: a darker lid line across the middle and a grey latch."""
    for cy in range(10):
        for cx in range(10):
            edge = cx in (0, 9) or cy in (0, 9)
            color = (92, 62, 30) if edge or cy == 4 else (168, 116, 56)
            put(img, x + cx, y + cy, jitter(color, 8))
    for (mx, my) in ((4, 3), (5, 3), (4, 4), (5, 4), (4, 5), (5, 5)):
        put(img, x + mx, y + my, (200, 200, 200, 255) if my != 5 else (120, 120, 120, 255))


def main():
    img = Image.new('RGBA', (W, H), (0, 0, 0, 255))
    ground = 78
    draw_sky(img, ground)
    draw_sun(img, 240, 3)
    for cx, cy, cw in ((14, 8, 26), (60, 16, 18), (178, 6, 22), (150, 20, 14)):
        draw_cloud(img, cx, cy, cw, 4)
    draw_ground(img, ground)
    draw_tree(img, 22, ground)
    draw_tree(img, 236, ground)
    # A crafting table and a chest beside the crew.
    draw_crafting_table(img, 50, ground - 10)
    draw_chest(img, 196, ground - 10)

    names = ('Kindred', 'Sol', 'Vesp')
    figures = [figure(ASSETS / 'skins' / f'{n}.png') for n in names]
    gap = 10
    total = sum(f.width for f in figures) + gap * (len(figures) - 1)
    x = (W - total) // 2
    for fig in figures:
        img.alpha_composite(fig, (x, ground - fig.height))
        x += fig.width + gap

    draw_title(img, 'KINDRED MC', 3, 6)
    draw_caption(img, 'AI PLAYERS THAT EARN EVERY BLOCK', 34)

    OUT.mkdir(parents=True, exist_ok=True)
    img.resize((W * SCALE, H * SCALE), Image.NEAREST).convert('RGB').save(OUT / 'banner.png', optimize=True)
    faces = OUT / 'faces'
    faces.mkdir(exist_ok=True)
    for n in names:
        face(ASSETS / 'skins' / f'{n}.png').resize((128, 128), Image.NEAREST).save(faces / f'{n.lower()}.png', optimize=True)
        fig = figure(ASSETS / 'skins' / f'{n}.png')
        fig.resize((fig.width * 8, fig.height * 8), Image.NEAREST).save(faces / f'{n.lower()}-full.png', optimize=True)


main()
