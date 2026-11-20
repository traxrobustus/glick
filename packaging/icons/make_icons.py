"""Draws the glick icon and writes every size the installers and apps need.

Run from the repository root:  python3 packaging/icons/make_icons.py
"""
import math
from PIL import Image, ImageDraw

BACKGROUND = (16, 18, 22, 255)
AMBER = (255, 176, 32, 255)
RED = (255, 90, 79, 255)
S = 4  # supersampling factor


def draw_mark(d, size, scale=1.0):
    """A jog-wheel ring with an accent dot at twelve o'clock and four beats inside."""
    c = size / 2
    r = size * 0.33 * scale
    w = size * 0.075 * scale
    d.arc([c - r, c - r, c + r, c + r], start=-70, end=250, fill=AMBER, width=round(w))
    for a in (-70, 250):  # round the ends of the ring
        x, y = c + (r - w / 2) * math.cos(math.radians(a)), c + (r - w / 2) * math.sin(math.radians(a))
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=AMBER)
    dot = size * 0.062 * scale
    d.ellipse([c - dot, c - r + w / 2 - dot, c + dot, c - r + w / 2 + dot], fill=RED)
    bar_w, gap, heights = size * 0.058 * scale, size * 0.034 * scale, (0.30, 0.19, 0.19, 0.19)
    x = c - (4 * bar_w + 3 * gap) / 2
    for i, h in enumerate(heights):
        hh = size * h * scale
        d.rounded_rectangle([x, c - hh / 2, x + bar_w, c + hh / 2], radius=bar_w / 2, fill=RED if i == 0 else AMBER)
        x += bar_w + gap


def icon(size, rounded=True):
    big = size * S
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if rounded:
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=big * 0.22, fill=BACKGROUND)
    draw_mark(d, big)
    return img.resize((size, size), Image.LANCZOS)


def adaptive_foreground(size=432):
    # Android masks the outer third of an adaptive icon, so the mark is drawn smaller.
    big = size * S
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw_mark(ImageDraw.Draw(img), big, scale=0.62)
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    master = icon(1024)
    master.resize((512, 512), Image.LANCZOS).save("packaging/icons/glick.png")
    master.save("packaging/icons/glick.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    master.resize((256, 256), Image.LANCZOS).save("app/src/desktopMain/resources/icon.png")
    adaptive_foreground().save("androidApp/src/main/res/drawable-nodpi/ic_launcher_foreground.png")
    master.resize((512, 512), Image.LANCZOS).save("packaging/icons/play-store-512.png")
