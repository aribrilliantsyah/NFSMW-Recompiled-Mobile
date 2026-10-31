# Draws the settings menu's six category icons (64x64 ARGB) and writes src/game/category_icons.inc.
# 24-unit grid, 2-unit stroke, round caps/joins. White on transparent, except CHEATS (orange #F28C1C).
# Icons are new drawings, not Rangga's gears-with-subject style.
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


def canvas():
    return Image.new("L", (S, S), 0), None


def draw_on(img):
    return ImageDraw.Draw(img)


def u(v):
    """Grid units to canvas pixels."""
    return v * UNIT


def ux(*vals):
    """Multiple grid values to canvas list."""
    return [v * UNIT for v in vals]


# ---- Screen: monitor outline ----
def icon_screen():
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    w = STROKE
    # monitor body
    d.rounded_rectangle(ux(4, 5, 20, 16), radius=u(1.5), outline=WHITE, width=round(w))
    # stand neck
    d.line(ux(12, 16, 12, 18.5), fill=WHITE, width=round(w))
    # stand base
    d.line(ux(8, 18.5, 16, 18.5), fill=WHITE, width=round(w))
    # screen inner content: small play triangle
    d.polygon([ux(10.5, 9), ux(10.5, 13), ux(14, 11)], fill=WHITE)
    return img


# ---- Speed: speedometer/gauge ----
def icon_speed():
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    w = STROKE
    cx, cy, r = u(12), u(13), u(7)
    # semicircle arc (bottom half open)
    bbox = [cx - r, cy - r, cx + r, cy + r]
    d.arc(bbox, 200, 340, fill=WHITE, width=round(w))
    # tick marks at 220, 260, 300, 340 degrees
    for angle_deg in (210, 240, 270, 300, 330):
        a = math.radians(angle_deg)
        x1, y1 = cx + (r - u(1.5)) * math.cos(a), cy + (r - u(1.5)) * math.sin(a)
        x2, y2 = cx + (r - u(0.2)) * math.cos(a), cy + (r - u(0.2)) * math.sin(a)
        d.line([x1, y1, x2, y2], fill=WHITE, width=round(w * 0.7))
    # needle pointing to ~310 degrees (fast)
    needle_a = math.radians(310)
    nx, ny = cx + (r - u(3)) * math.cos(needle_a), cy + (r - u(3)) * math.sin(needle_a)
    d.line([cx, cy, nx, ny], fill=WHITE, width=round(w * 0.9))
    # centre dot
    d.ellipse([cx - u(1), cy - u(1), cx + u(1), cy + u(1)], fill=WHITE)
    return img


# ---- Visuals: sparkle/star ----
def icon_visuals():
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    cx, cy = u(12), u(12)
    # four-point star
    points = []
    outer_r, inner_r = u(8), u(2.5)
    for i in range(8):
        angle = math.radians(i * 45 - 90)
        r = outer_r if i % 2 == 0 else inner_r
        points.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
    d.polygon(points, fill=WHITE)
    # small sparkle dots
    for dx, dy in [(5.5, 5.5), (-5.5, -5.5), (5.5, -5.5), (-5.5, 5.5)]:
        d.ellipse([cx + u(dx) - u(0.7), cy + u(dy) - u(0.7),
                   cx + u(dx) + u(0.7), cy + u(dy) + u(0.7)], fill=WHITE)
    return img


# ---- Controls: gamepad ----
def icon_controls():
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    w = STROKE
    # controller body outline
    d.rounded_rectangle(ux(3, 8, 21, 17), radius=u(3), outline=WHITE, width=round(w))
    # grips (small bumps at bottom)
    d.arc(ux(3, 13, 9, 20), 0, 180, fill=WHITE, width=round(w))
    d.arc(ux(15, 13, 21, 20), 0, 180, fill=WHITE, width=round(w))
    # left d-pad cross
    lx, ly = u(8), u(12)
    hs = u(1)
    d.line([lx - u(1.5), ly, lx + u(1.5), ly], fill=WHITE, width=round(w * 0.8))
    d.line([lx, ly - u(1.5), lx, ly + u(1.5)], fill=WHITE, width=round(w * 0.8))
    # right face buttons (4 dots)
    rx, ry = u(16), u(12)
    for ddx, ddy in [(0, -1.2), (0, 1.2), (-1.2, 0), (1.2, 0)]:
        d.ellipse([rx + u(ddx) - u(0.5), ry + u(ddy) - u(0.5),
                   rx + u(ddx) + u(0.5), ry + u(ddy) + u(0.5)], fill=WHITE)
    return img


# ---- System: cog/gear ----
def icon_system():
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    cx, cy = u(12), u(12)
    teeth = 8
    outer_r, inner_r = u(8.5), u(6.5)
    hole_r = u(3.5)
    # draw gear with teeth
    points = []
    for i in range(teeth * 2):
        angle = math.radians(i * 360 / (teeth * 2) - 90)
        r = outer_r if i % 2 == 0 else inner_r
        points.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
    d.polygon(points, fill=WHITE)
    # hole in centre
    d.ellipse([cx - hole_r, cy - hole_r, cx + hole_r, cy + hole_r], fill=0)
    # inner circle ring
    ring_r = u(2)
    d.ellipse([cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r], fill=WHITE)
    return img


# ---- Cheats: lightning bolt (orange) ----
def icon_cheats():
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    # lightning bolt shape
    bolt = [ux(13, 3), ux(7, 13), ux(11, 13), ux(10, 21), ux(17, 10), ux(13, 10), ux(15, 3)]
    d.polygon([p for p in bolt], fill=WHITE)
    return img

# ---- Settings (the main and pause menus' icon beside OPTIONS): three sliders ----
def icon_settings():
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    w = round(STROKE)
    for y, knob in ((6, 15), (12, 8), (18, 13)):
        d.line(ux(4, y, 20, y), fill=WHITE, width=w)
        d.ellipse([u(knob) - u(2.2), u(y) - u(2.2), u(knob) + u(2.2), u(y) + u(2.2)], fill=WHITE)
        d.ellipse([u(knob) - u(1), u(y) - u(1), u(knob) + u(1), u(y) + u(1)], fill=0)
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


def write_inc(icons_data):
    with open(OUT, "w") as f:
        f.write("// written by tools/category_icons.py, do not edit\n")
        f.write(f"constexpr int kIconCount = {len(icons_data)};\n")
        f.write(f"constexpr uint32_t kIconSize = {SIZE};\n")
        f.write(f"constexpr uint32_t kIcons[kIconCount][kIconSize * kIconSize] = {{\n")
        for idx, pixels in enumerate(icons_data):
            f.write("    {\n")
            for row in range(SIZE):
                vals = ", ".join(f"0x{pixels[row * SIZE + col]:08X}" for col in range(SIZE))
                comma = "," if row < SIZE - 1 else ""
                f.write(f"        {vals}{comma}\n")
            f.write("    },\n" if idx < len(icons_data) - 1 else "    }\n")
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
    print(f"Wrote {len(icons_data)} icons to {OUT}")

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
