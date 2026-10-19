#!/usr/bin/env python3
"""Rebuild the original 16x16 Warzone forest art with Python's standard library.

Every mark, palette and random seed is defined here; no external images are used.
Run from any directory. PNGs are deliberately unfiltered and use a fixed zlib level.
"""

from pathlib import Path
import math
import random
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/bobin_craft/textures/block"
SIZE = 16


def image(color):
    return [[tuple(color) for _ in range(SIZE)] for _ in range(SIZE)]


def pixel(canvas, x, y, color):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        canvas[y][x] = tuple(color)


def line(canvas, x0, y0, x1, y1, color):
    length = max(abs(x1 - x0), abs(y1 - y0), 1)
    for step in range(length + 1):
        pixel(canvas, round(x0 + (x1 - x0) * step / length),
              round(y0 + (y1 - y0) * step / length), color)


def jitter(color, amount):
    return tuple(max(0, min(255, channel + amount)) for channel in color)


def earth(seed):
    rng = random.Random(seed)
    canvas = image((62, 51, 42))
    for y in range(SIZE):
        for x in range(SIZE):
            clod = ((x // 3 * 11 + y // 2 * 7) % 13) - 6
            canvas[y][x] = jitter((62, 51, 42), rng.choice((-6, -3, 0, 2, 4)) + clod)
    for _ in range(10):
        x, y = rng.randrange(16), rng.randrange(16)
        pixel(canvas, x, y, (39, 37, 31))
        pixel(canvas, x + 1, y, (48, 42, 35))
    return canvas


def ground(kind, seed):
    canvas = earth(seed)
    rng = random.Random(seed + 100)
    if kind == "dark_forest_soil":
        for _ in range(9):
            x, y = rng.randrange(16), rng.randrange(16)
            line(canvas, x, y, x + 2, y + 1, (81, 66, 48))
    elif kind == "pine_needle_ground":
        for _ in range(40):
            x, y = rng.randrange(16), rng.randrange(16)
            dx, dy = rng.choice(((2, 1), (-1, 2), (2, -1), (0, 3)))
            line(canvas, x, y, x + dx, y + dy,
                 rng.choice(((91, 75, 46), (113, 92, 55), (71, 65, 43), (89, 86, 55))))
    elif kind == "mossy_forest_soil":
        for y in range(16):
            for x in range(16):
                patch = math.sin(x * 0.55) + math.sin(y * 0.73 + x * 0.16)
                if patch > -0.32 + rng.random() * 0.9:
                    pixel(canvas, x, y, rng.choice(((57, 65, 38), (68, 76, 43),
                                                   (77, 83, 48), (48, 59, 37))))
    elif kind == "forest_litter":
        for _ in range(19):
            x, y = rng.randrange(16), rng.randrange(16)
            shade = rng.choice(((98, 74, 46), (114, 89, 54), (80, 70, 45), (71, 69, 43)))
            for dx, dy in ((0, 0), (1, 0), (0, 1), (-1, 1), (0, 2)):
                pixel(canvas, x + dx, y + dy, shade)
            pixel(canvas, x, y + 1, jitter(shade, -18))
    return canvas


def side(top, seed):
    canvas = earth(seed)
    rng = random.Random(seed)
    for x in range(16):
        depth = 2 + rng.randrange(3)
        for y in range(depth):
            canvas[y][x] = top[y][x]
        pixel(canvas, x, depth, (41, 37, 30))
    for _ in range(8):
        x, y = rng.randrange(16), rng.randrange(5, 15)
        line(canvas, x, y, x + 2, y - 1, (78, 64, 46))
    return canvas


def bark(kind, seed):
    rng = random.Random(seed)
    base = {"weathered_bark": (74, 71, 61), "dark_pine_wood": (58, 49, 40),
            "rotten_wood": (72, 63, 43)}[kind]
    canvas = image(base)
    for x in range(16):
        column = rng.choice((-18, -10, -3, 4, 10, 16))
        for y in range(16):
            pixel(canvas, x, y, jitter(base, column + rng.choice((-4, 0, 2))))
    for _ in range(12):
        x, y = rng.randrange(16), rng.randrange(16)
        line(canvas, x, y, x, min(15, y + rng.randrange(2, 6)), jitter(base, -22))
        pixel(canvas, x + 1, y, jitter(base, 14))
    if kind == "rotten_wood":
        for x, y in ((3, 4), (10, 9), (6, 14)):
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1), (0, 2)):
                pixel(canvas, x + dx, y + dy, (33, 34, 26))
    return canvas


def endgrain(kind, seed):
    rng = random.Random(seed)
    base = {"weathered_bark": (108, 99, 75), "dark_pine_wood": (111, 91, 62),
            "rotten_wood": (92, 83, 54)}[kind]
    canvas = image(base)
    for y in range(16):
        for x in range(16):
            ring = int(math.sqrt((x - 7) ** 2 + (y - 8) ** 2) * 1.15) % 3
            delta = (-15, 4, 9)[ring] + rng.choice((-2, 0, 2))
            pixel(canvas, x, y, jitter(base, delta))
            if x in (0, 15) or y in (0, 15):
                pixel(canvas, x, y, jitter(base, -35))
    line(canvas, 7, 8, 12, 14, jitter(base, -33))
    if kind == "rotten_wood":
        for y in range(6, 11):
            for x in range(5, 10):
                if (x - 7) ** 2 + (y - 8) ** 2 < 8:
                    pixel(canvas, x, y, (37, 37, 27))
    return canvas


def rock():
    rng = random.Random(931)
    canvas = image((81, 84, 75))
    for y in range(16):
        for x in range(16):
            grain = (x // 4 * 5 + y // 3 * 7) % 19 - 9
            pixel(canvas, x, y, jitter((81, 84, 75), grain + rng.randrange(-5, 6)))
            moss = math.sin(x * 0.5 + 0.8) + math.cos(y * 0.48 - x * 0.1)
            if moss > 0.75 + rng.random() * 0.7:
                pixel(canvas, x, y, rng.choice(((55, 66, 39), (66, 76, 45), (77, 82, 48))))
    line(canvas, 0, 10, 5, 6, (48, 53, 48))
    line(canvas, 5, 6, 8, 7, (54, 58, 52))
    line(canvas, 8, 7, 10, 3, (49, 55, 48))
    return canvas


def undergrowth():
    canvas = image((0, 0, 0, 0))
    stems = ((7, 15, 4, 6), (8, 15, 10, 2), (8, 15, 13, 7),
             (6, 15, 2, 10), (9, 15, 14, 11), (7, 14, 7, 4))
    colors = ((113, 104, 67, 255), (132, 120, 77, 255), (90, 87, 57, 255))
    for index, (x0, y0, x1, y1) in enumerate(stems):
        color = colors[index % 3]
        line(canvas, x0, y0, x1, y1, color)
        line(canvas, x1, y1 + 2, x1 - 2, y1, color)
        line(canvas, x1, y1 + 3, x1 + 2, y1 + 1, color)
    for x, y in ((4, 7), (10, 3), (13, 8), (7, 5)):
        pixel(canvas, x, y, (145, 129, 84, 255))
    return canvas


def forest_grass():
    canvas = image((0, 0, 0, 0))
    blades = (
        (2, 15, 1, 7, (49, 63, 39, 255)),
        (4, 15, 3, 4, (61, 76, 46, 255)),
        (6, 15, 5, 6, (42, 55, 35, 255)),
        (7, 15, 8, 3, (71, 86, 52, 255)),
        (9, 15, 11, 5, (55, 70, 43, 255)),
        (11, 15, 12, 8, (46, 59, 37, 255)),
        (13, 15, 14, 6, (65, 79, 48, 255)),
    )
    for x0, y0, x1, y1, color in blades:
        line(canvas, x0, y0, x1, y1, color)
        pixel(canvas, x0, 15, (38, 47, 31, 255))
        pixel(canvas, x1, y1, (82, 95, 59, 255))
    return canvas


def pale_sedge():
    canvas = image((0, 0, 0, 0))
    stalks = (
        (3, 15, 1, 5, (108, 109, 78, 255)),
        (5, 15, 4, 3, (124, 121, 88, 255)),
        (7, 15, 6, 6, (92, 95, 69, 255)),
        (8, 15, 9, 2, (131, 126, 92, 255)),
        (10, 15, 12, 5, (112, 111, 81, 255)),
        (12, 15, 14, 7, (96, 98, 72, 255)),
    )
    for x0, y0, x1, y1, color in stalks:
        line(canvas, x0, y0, x1, y1, color)
        line(canvas, x1, y1, x1 + (1 if x1 >= 8 else -1), y1 + 1, (144, 137, 101, 255))
        pixel(canvas, x0, 15, (68, 66, 49, 255))
    return canvas


def low_forest_bush():
    rng = random.Random(1207)
    canvas = image((0, 0, 0, 0))
    for x0, y0, x1, y1 in ((7, 15, 4, 9), (8, 15, 8, 7), (8, 15, 12, 9), (6, 13, 2, 11), (9, 13, 13, 11)):
        line(canvas, x0, y0, x1, y1, (64, 52, 38, 255))
    palette = ((38, 52, 34, 255), (49, 65, 41, 255), (59, 75, 46, 255), (71, 84, 51, 255))
    for y in range(4, 15):
        for x in range(1, 15):
            d = ((x - 7.5) / 6.5) ** 2 + ((y - 9.5) / 5.2) ** 2
            if d <= 1.0 and (d < 0.62 or rng.random() < 0.72):
                shade = palette[(x + y + rng.randrange(2)) % len(palette)]
                pixel(canvas, x, y, shade)
    for x, y in ((5, 8), (10, 7), (7, 11), (11, 10)):
        pixel(canvas, x, y, (85, 73, 52, 255))
    return canvas


def needle_undergrowth():
    canvas = image((0, 0, 0, 0))
    runners = ((2, 15, 6, 11), (6, 15, 8, 9), (9, 15, 7, 10), (13, 15, 10, 11), (4, 15, 12, 13))
    for x0, y0, x1, y1 in runners:
        line(canvas, x0, y0, x1, y1, (73, 60, 42, 255))
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (-1, -1), (1, -1)):
            pixel(canvas, x1 + dx, y1 + dy, (54, 69, 42, 255))
        pixel(canvas, x1, y1 - 1, (74, 88, 53, 255))
    for x in range(2, 14):
        if x % 2 == 0:
            pixel(canvas, x, 14, (45, 58, 36, 255))
            pixel(canvas, x, 15, (61, 53, 39, 255))
    return canvas


def png_bytes(canvas):
    alpha = len(canvas[0][0]) == 4
    raw = b"".join(b"\0" + bytes(channel for color in row for channel in color) for row in canvas)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6 if alpha else 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def textures():
    result = {}
    for seed, name in enumerate(("dark_forest_soil", "pine_needle_ground", "mossy_forest_soil", "forest_litter"), 410):
        top = ground(name, seed)
        result[name + "_top"] = top
        result[name + "_side"] = side(top, seed + 29)
    for seed, name in enumerate(("weathered_bark", "dark_pine_wood", "rotten_wood"), 710):
        result[name] = bark(name, seed)
        result[name + "_top"] = endgrain(name, seed + 40)
    result["moss_covered_rock"] = rock()
    result["dry_undergrowth"] = undergrowth()
    result["forest_grass"] = forest_grass()
    result["pale_sedge"] = pale_sedge()
    result["low_forest_bush"] = low_forest_bush()
    result["needle_undergrowth"] = needle_undergrowth()
    return result


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, canvas in textures().items():
        (OUT / (name + ".png")).write_bytes(png_bytes(canvas))
    print(f"Generated {len(textures())} original 16x16 forest textures.")
