#!/usr/bin/env python3
"""Generate Bobin Craft's original 32 px bunker textures, using only Python's stdlib.

No external images, game files, fonts or copyrighted texture packs are inputs.
Run from any directory; --check validates the committed art and resource references.
"""
from __future__ import annotations

import argparse
import json
import random
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/bobin_craft"
TEXTURES = ASSETS / "textures/block"
SIZE = 32


class Canvas:
    def __init__(self, seed: int, color: tuple[int, int, int], grain: int = 4):
        rng = random.Random(seed)
        self.pixels = []
        for _ in range(SIZE * SIZE):
            change = rng.randint(-grain, grain)
            self.pixels.append(tuple(max(0, min(255, c + change)) for c in color) + (255,))

    def point(self, x, y, color):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.pixels[y * SIZE + x] = tuple(color[:3]) + (255,)

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.point(x, y, color)

    def line(self, x0, y0, x1, y1, color):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = 1 if x0 < x1 else -1, 1 if y0 < y1 else -1
        error = dx + dy
        while True:
            self.point(x0, y0, color)
            if (x0, y0) == (x1, y1):
                break
            twice = 2 * error
            if twice >= dy:
                error += dy
                x0 += sx
            if twice <= dx:
                error += dx
                y0 += sy

    def bolt(self, x, y):
        self.rect(x - 1, y - 1, x + 1, y + 1, (30, 37, 40))
        self.point(x, y, (164, 172, 167))

    def png(self):
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        raw = b"".join(b"\0" + bytes(c for p in self.pixels[y * SIZE:(y + 1) * SIZE] for c in p)
                       for y in range(SIZE))
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def texture(name):
    if name == "metal_floor":
        c = Canvas(11, (69, 82, 84))
        for i in range(0, 32, 8):
            c.line(i, 0, i, 31, (35, 48, 52))
            c.line(0, i, 31, i, (35, 48, 52))
            for j in range(4, 32, 8):
                c.line(i + 2, j - 1, i + 4, j + 1, (101, 113, 111))
        for x, y in ((2, 2), (29, 2), (2, 29), (29, 29)):
            c.bolt(x, y)
    elif name in ("bunker_wall", "table_leg", "table_edge"):
        c = Canvas(23, (88, 98, 94) if name == "bunker_wall" else (61, 73, 72))
        for y in (0, 15, 31):
            c.line(0, y, 31, y, (30, 40, 43))
            c.line(0, min(31, y + 1), 31, min(31, y + 1), (119, 128, 118))
        for x in (2, 29):
            c.bolt(x, 4)
            c.bolt(x, 27)
        c.line(10, 8, 18, 8, (112, 119, 106))
        c.line(21, 24, 25, 24, (60, 69, 67))
    elif name == "equipment_panel":
        c = texture("bunker_wall")
        c.rect(3, 4, 28, 23, (24, 33, 35))
        c.rect(5, 6, 24, 18, (22, 57, 46))
        for x in range(6, 24, 4):
            c.line(x, 7, x, 17, (38, 78, 59))
        c.line(6, 15, 11, 11, (133, 187, 128))
        c.line(11, 11, 15, 14, (133, 187, 128))
        c.line(15, 14, 22, 8, (133, 187, 128))
        for x, color in ((7, (117, 177, 118)), (15, (218, 157, 66)), (23, (147, 67, 57))):
            c.rect(x - 1, 26, x + 1, 28, color)
    elif name.startswith("supply_crate"):
        c = Canvas(37, (87, 98, 60))
        for y in (0, 8, 16, 24, 31):
            c.line(0, y, 31, y, (42, 53, 36))
            if y < 31:
                c.line(0, y + 1, 31, y + 1, (112, 121, 77))
        for x in (3, 25):
            c.rect(x, 0, x + 3, 31, (157, 133, 73))
            c.line(x, 0, x, 31, (70, 69, 47))
            c.bolt(x + 2, 3)
            c.bolt(x + 2, 28)
        if name.endswith("side"):
            c.rect(10, 10, 21, 21, (193, 184, 137))
            c.line(12, 14, 19, 14, (57, 66, 44))
            c.line(12, 17, 18, 17, (57, 66, 44))
            c.rect(14, 11, 16, 19, (57, 66, 44))
        else:
            c.line(8, 3, 23, 28, (129, 136, 83))
            c.line(8, 28, 23, 3, (129, 136, 83))
    elif name in ("map_unfolded", "map_folded"):
        c = Canvas(53, (202, 190, 145), 3)
        c.rect(0, 0, 31, 1, (87, 94, 75))
        c.rect(0, 30, 31, 31, (87, 94, 75))
        c.rect(0, 0, 1, 31, (87, 94, 75))
        c.rect(30, 0, 31, 31, (87, 94, 75))
        for y in range(5, 29, 5):
            for x in range(3, 29):
                curve = ((x // 4 + y // 5) % 3) - 1
                c.point(x, y + curve, (145, 155, 109))
        for x in (10, 21):
            c.line(x, 2, x, 29, (173, 164, 120))
        c.line(2, 16, 29, 16, (173, 164, 120))
        for a, b in (((4, 25), (12, 20)), ((12, 20), (16, 12)), ((16, 12), (27, 6))):
            c.line(*a, *b, (107, 111, 92))
        c.line(24, 4, 22, 14, (102, 148, 150))
        c.line(22, 14, 26, 27, (102, 148, 150))
        for x, y, color in ((8, 23, (61, 123, 75)), (15, 13, (162, 72, 57)), (25, 7, (162, 72, 57))):
            c.rect(x - 1, y - 1, x + 1, y + 1, color)
            c.point(x, y, (231, 221, 177))
        if name == "map_folded":
            c.rect(15, 2, 16, 29, (148, 140, 106))
            c.line(17, 2, 17, 29, (224, 213, 167))
            c.line(2, 28, 29, 28, (132, 131, 99))
    else:
        raise ValueError(name)
    return c


NAMES = ("metal_floor", "bunker_wall", "table_leg", "table_edge", "equipment_panel",
         "supply_crate_side", "supply_crate_top", "supply_crate_bottom", "map_unfolded", "map_folded")


def validate():
    for name in NAMES:
        path = TEXTURES / f"{name}.png"
        if path.read_bytes() != texture(name).png():
            raise SystemExit(f"Texture differs from original deterministic source: {path}")
    def check_reference(value, directory, suffix, source):
        if value.startswith("bobin_craft:"):
            target = ASSETS / directory / (value.split(":", 1)[1] + suffix)
            if not target.is_file():
                raise SystemExit(f"Missing resource {target} referenced by {source}")

    # Catch misspelled custom references in every new bunker blockstate/item/block model.
    for kind in ("blockstates", "models/block", "models/item"):
        for name in ("map_table", "supply_crate", "metal_floor", "bunker_wall", "equipment_panel"):
            path = ASSETS / kind / f"{name}.json"
            model = json.loads(path.read_text())
            if "parent" in model:
                check_reference(model["parent"], "models", ".json", path)
            for variant in model.get("variants", {}).values():
                check_reference(variant["model"], "models", ".json", path)
            for value in model.get("textures", {}).values():
                check_reference(value, "textures", ".png", path)
    print(f"Validated {len(NAMES)} original 32x32 PNGs and 15 bunker resource files.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    options = parser.parse_args()
    if not options.check:
        TEXTURES.mkdir(parents=True, exist_ok=True)
        for name in NAMES:
            (TEXTURES / f"{name}.png").write_bytes(texture(name).png())
        print(f"Generated {len(NAMES)} original bunker textures in {TEXTURES}")
    else:
        validate()
