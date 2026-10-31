# Draws the settings menu's six category icons and the menus' SETTINGS icon (64x64 ARGB) into src/game/category_icons.inc.
# 24-unit grid, 2-unit stroke, round caps/joins. White on transparent, except CHEATS (orange #F28C1C).
# Icons are new drawings, not Rangga's gears-with-subject style. Every one is strokes alone (poly, circle, rrect):
# one width, round ends and corners, nothing filled.
# usage: python tools/category_icons.py [preview.png]
import math
import os
import sys

from PIL import Image, ImageDraw

SIZE = 64
SCALE = 8
S = SIZE * SCALE
GRID = 24  # conceptual grid
UNIT = S / GRID
STROKE = 2 * UNIT
WHITE = 255
ORANGE = (0xF2, 0x8C, 0x1C)  # cheats colour
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "game", "category_icons.inc")


def u(v):
    """Grid units to canvas pixels."""
    return v * UNIT


# ---- strokes: every line STROKE wide with round caps and joins, nothing filled ----
def disc(d, x, y):
    """A round cap or join: a disc STROKE across at a canvas point."""
    r = STROKE / 2
    d.ellipse([x - r, y - r, x + r, y + r], fill=WHITE)


def poly(d, pts, closed=False):
    """A stroked polyline through grid points, round caps and joins."""
    cp = [(u(x), u(y)) for x, y in pts]
    if closed:
        cp.append(cp[0])
    for a, b in zip(cp, cp[1:]):
        d.line([a, b], fill=WHITE, width=round(STROKE))
    for x, y in cp:
        disc(d, x, y)


def arc_pts(cx, cy, r, a0, a1, n=48):
    """Grid points along a circle's arc, degrees clockwise on the screen from +x."""
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def circle(d, cx, cy, r):
    poly(d, arc_pts(cx, cy, r, 0, 360, 96), closed=True)


def rrect(d, x0, y0, x1, y1, r):
    """A rounded rectangle's outline."""
    pts = (arc_pts(x1 - r, y0 + r, r, 270, 360, 12) + arc_pts(x1 - r, y1 - r, r, 0, 90, 12)
           + arc_pts(x0 + r, y1 - r, r, 90, 180, 12) + arc_pts(x0 + r, y0 + r, r, 180, 270, 12))
    poly(d, pts, closed=True)


def icon_canvas():
    img = Image.new("L", (S, S), 0)
    return img, ImageDraw.Draw(img)


# ---- Screen: a monitor on its stand ----
def icon_screen():
    img, d = icon_canvas()
    rrect(d, 3, 4, 21, 16, 2)
    poly(d, [(12, 16), (12, 20)])
    poly(d, [(8, 20), (16, 20)])
    return img


# ---- Speed: a gauge, its needle well over ----
def icon_speed():
    img, d = icon_canvas()
    poly(d, arc_pts(12, 15, 9, 180, 360))
    poly(d, [(3, 15), (21, 15)])
    poly(d, [(12, 15), (16.5, 9)])
    return img


# ---- Visuals: a four-point sparkle and a small one ----
def icon_visuals():
    img, d = icon_canvas()
    cx, cy = 10.5, 13.5
    pts = []
    for i in range(8):
        a = math.radians(i * 45 - 90)
        r = 8 if i % 2 == 0 else 2.2
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    poly(d, pts, closed=True)
    poly(d, [(19, 3), (19, 7)])
    poly(d, [(17, 5), (21, 5)])
    return img


# ---- Controls: a gamepad ----
def icon_controls():
    img, d = icon_canvas()
    rrect(d, 2, 7, 22, 18, 4.5)
    poly(d, [(5.5, 12.5), (9.5, 12.5)])
    poly(d, [(7.5, 10.5), (7.5, 14.5)])
    poly(d, [(15.5, 11), (15.5, 11)])
    poly(d, [(18, 13.5), (18, 13.5)])
    return img


# ---- System: a cog ----
def icon_system():
    img, d = icon_canvas()
    circle(d, 12, 12, 6)
    circle(d, 12, 12, 2)
    for i in range(8):
        a = math.radians(i * 45)
        poly(d, [(12 + 6 * math.cos(a), 12 + 6 * math.sin(a)), (12 + 9 * math.cos(a), 12 + 9 * math.sin(a))])
    return img


# ---- Cheats: a lightning bolt (orange) ----
def icon_cheats():
    img, d = icon_canvas()
    poly(d, [(13, 2), (5, 13), (11, 13), (10, 22), (19, 10), (13, 10), (14, 2)], closed=True)
    return img


# ---- Settings (the main and pause menus' icon beside OPTIONS): three sliders ----
def icon_settings():
    img, d = icon_canvas()
    for y, knob in ((5, 15), (12, 8), (19, 13)):
        poly(d, [(3, y), (knob - 2, y)])
        poly(d, [(knob + 2, y), (21, y)])
        circle(d, knob, y, 2)
    return img


# the categories in settings::Category order, then SETTINGS' own (settings_menu.cpp's index kCount)
ICONS = [
    ("Screen", icon_screen, None),
    ("Speed", icon_speed, None),
    ("Visuals", icon_visuals, None),
    ("Controls", icon_controls, None),
    ("System", icon_system, None),
    ("Cheats", icon_cheats, ORANGE),
    ("Settings", icon_settings, None),
]


def to_argb(grey_img, colour=None):
    """Convert greyscale icon to ARGB pixel array. colour=None means white."""
    small = grey_img.resize((SIZE, SIZE), Image.LANCZOS)
    pixels = []
    for y in range(SIZE):
        for x in range(SIZE):
            a = small.getpixel((x, y))
            if colour:
                r, g, b = colour
            else:
                r, g, b = 255, 255, 255
            pixels.append((a << 24) | (r << 16) | (g << 8) | b)
    return pixels


def write_rows(f, pixels, indent):
    for row in range(SIZE):
        vals = ", ".join(f"0x{pixels[row * SIZE + col]:08X}" for col in range(SIZE))
        comma = "," if row < SIZE - 1 else ""
        f.write(f"{indent}{vals}{comma}\n")


def write_inc(icons_data):
    """kIcons: the six categories', in settings::Category order; kSettingsIcon: the menus' SETTINGS."""
    categories, settings = icons_data[:-1], icons_data[-1]
    with open(OUT, "w") as f:
        f.write("// written by tools/category_icons.py, do not edit\n")
        f.write(f"constexpr int kIconCount = {len(categories)};\n")
        f.write(f"constexpr uint32_t kIconSize = {SIZE};\n")
        f.write("constexpr uint32_t kIcons[kIconCount][kIconSize * kIconSize] = {\n")
        for idx, pixels in enumerate(categories):
            f.write("    {\n")
            write_rows(f, pixels, "        ")
            f.write("    },\n" if idx < len(categories) - 1 else "    }\n")
        f.write("};\n")
        f.write("constexpr uint32_t kSettingsIcon[kIconSize * kIconSize] = {\n")
        write_rows(f, settings, "    ")
        f.write("};\n")


def main():
    icons_data = []
    preview_parts = []
    for name, draw_fn, colour in ICONS:
        grey = draw_fn()
        pixels = to_argb(grey, colour)
        icons_data.append(pixels)
        preview_parts.append((name, grey, colour))

    write_inc(icons_data)
    print(f"Wrote {len(icons_data) - 1} category icons and SETTINGS' to {OUT}")

    if len(sys.argv) > 1:
        # write preview
        preview = Image.new("RGBA", (SIZE * len(ICONS), SIZE), (32, 32, 32, 255))
        for i, (name, grey, colour) in enumerate(preview_parts):
            small = grey.resize((SIZE, SIZE), Image.LANCZOS)
            r, g, b = colour if colour else (255, 255, 255)
            rgba = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
            for y in range(SIZE):
                for x in range(SIZE):
                    a = small.getpixel((x, y))
                    rgba.putpixel((x, y), (r, g, b, a))
            preview.paste(rgba, (i * SIZE, 0), rgba)
        preview.save(sys.argv[1])
        print(f"Wrote preview to {sys.argv[1]}")


if __name__ == "__main__":
    main()
