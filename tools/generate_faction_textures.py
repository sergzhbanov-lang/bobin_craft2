#!/usr/bin/env python3
"""Create/check 21 original UV-matched 64 x 64 faction NPC textures (stdlib only).

All pixels are authored here. No vanilla skin, downloaded art, or image input is used.
Run `python3 tools/generate_faction_textures.py --check` to check committed resources.
The box dimensions below intentionally match WarzoneGuardModel, including its rig,
shoulder pads, boots and optional support pack rather than an illager texture layout.
"""
from __future__ import annotations

import argparse
import random
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "src/main/resources/assets/bobin_craft/textures/entity/faction"
SIZE = 64
SKIN = [(177, 127, 91), (116, 80, 59), (213, 168, 127), (151, 104, 74)]
FACTIONS = {
    # name: cloth, camouflage, armor, identifying accent, visor, bandit
    "regular_army": ((81, 98, 59), (51, 65, 39), (119, 111, 73), (223, 189, 83), (129, 167, 146), False),
    "iron_wolves": ((69, 77, 84), (43, 48, 55), (91, 101, 110), (187, 56, 43), (211, 140, 74), False),
    "glass_company": ((41, 57, 69), (25, 37, 49), (160, 145, 108), (61, 196, 204), (78, 195, 210), False),
    "frost_corps": ((211, 220, 219), (147, 169, 177), (97, 125, 146), (123, 211, 224), (83, 185, 218), False),
    "ash_raiders": ((111, 77, 57), (58, 43, 35), (71, 51, 43), (206, 76, 39), (212, 161, 73), True),
    "black_crows": ((46, 44, 53), (24, 26, 32), (67, 61, 73), (189, 173, 192), (150, 122, 167), True),
    "marsh_reavers": ((75, 91, 55), (42, 57, 38), (95, 77, 50), (190, 175, 66), (165, 172, 87), True),
}
# (UV origin, width, height, depth). Every region is disjoint.
BOXES = {
    "head": ((0, 0), 8, 8, 8),
    "helmet": ((32, 0), 8, 8, 8),
    "body": ((16, 16), 8, 12, 4),
    "right_arm": ((40, 16), 4, 12, 4),
    "left_arm": ((32, 48), 4, 12, 4),
    "right_leg": ((0, 16), 4, 12, 4),
    "left_leg": ((16, 48), 4, 12, 4),
    "rig": ((16, 32), 8, 10, 4),
    "right_pad": ((40, 32), 4, 4, 4),
    "left_pad": ((48, 48), 4, 4, 4),
    "right_boot": ((0, 32), 4, 5, 4),
    "left_boot": ((0, 48), 4, 5, 4),
    "pack": ((40, 40), 4, 6, 2),
}


def tone(color, change):
    return tuple(max(0, min(255, component + change)) for component in color[:3])


class Canvas:
    def __init__(self):
        self.pixels = [(0, 0, 0, 0)] * (SIZE * SIZE)

    def point(self, x, y, color):
        if not (0 <= x < SIZE and 0 <= y < SIZE):
            raise ValueError(f"Out-of-atlas pixel {x},{y}")
        self.pixels[y * SIZE + x] = tuple(color) if len(color) == 4 else tuple(color) + (255,)

    def png(self):
        def chunk(kind, data):
            return (struct.pack(">I", len(data)) + kind + data
                    + struct.pack(">I", zlib.crc32(kind + data)))
        raw = b"".join(b"\0" + bytes(component for pixel in self.pixels[y * SIZE:(y + 1) * SIZE]
                                       for component in pixel) for y in range(SIZE))
        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def faces(box):
    (u, v), width, height, depth = box
    return {
        "top": (u + depth, v, width, depth),
        "bottom": (u + depth + width, v, width, depth),
        "right": (u, v + depth, depth, height),
        "front": (u + depth, v + depth, width, height),
        "left": (u + depth + width, v + depth, depth, height),
        "back": (u + depth + width + depth, v + depth, width, height),
    }


def verify_atlas():
    occupied = {}
    for part, box in BOXES.items():
        for face, (u, v, width, height) in faces(box).items():
            for y in range(v, v + height):
                for x in range(u, u + width):
                    assert 0 <= x < SIZE and 0 <= y < SIZE, (part, face, x, y)
                    assert (x, y) not in occupied, (part, face, occupied.get((x, y)))
                    occupied[x, y] = part, face


def texture(name, variant):
    cloth, camo, armor, accent, visor, bandit = FACTIONS[name]
    seed = list(FACTIONS).index(name) * 701 + variant * 119
    skin = SKIN[(seed // 7) % len(SKIN)]
    canvas = Canvas()
    dark = (25, 27, 26)
    metal = (151, 159, 154)

    for part, box in BOXES.items():
        for face, (u, v, width, height) in faces(box).items():
            # A private face seed keeps generation reproducible if more parts are added.
            rng = random.Random(f"{name}/{variant}/{part}/{face}")
            for y in range(height):
                for x in range(width):
                    noise = rng.randint(-5, 5)
                    shade = {"top": 10, "bottom": -17, "right": -8, "left": -5, "back": -3}.get(face, 0)
                    color = tone(cloth, noise + shade)
                    pattern = ((x // 2 + y // 3 + seed) * 7 + y // 2) % 9
                    if pattern in (0, 1):
                        color = tone(camo, noise + shade)

                    if part == "head":
                        color = tone(skin, noise + shade)
                        if face in ("top", "back") or (face in ("right", "left") and y < 2):
                            color = tone((51, 39, 31), noise)
                        if face == "front":
                            if y == 2 and x in (1, 2, 5, 6):
                                color = (55, 44, 35)
                            if y == 3 and x in (1, 2, 5, 6):
                                color = (29, 38, 35) if x in (2, 5) else (221, 213, 187)
                            if y == 5 and x in (3, 4):
                                color = tone(skin, -29)
                            if y == 6 and x in (2, 3, 4, 5):
                                color = tone(skin, -43)
                            if bandit and y >= 4:
                                color = tone(camo, noise - 12)
                                if y == 5 and (x + seed) % 3 == 0:
                                    color = tone(accent, -65)
                            if variant == 1 and y in (2, 3, 4):
                                color = visor if 1 <= x <= 6 and y == 3 else dark
                        elif bandit and y >= 4:
                            color = tone(camo, noise - 10)

                    elif part == "helmet":
                        color = tone(armor if not bandit else cloth, noise + shade)
                        if face == "front":
                            # Helmet brim / hood opening. Transparent pixels reveal the face.
                            visible = y <= 1 or (bandit and (x == 0 or x == 7))
                            color = color if visible else (0, 0, 0, 0)
                            if y == 1 and 2 <= x <= 5:
                                color = tone(accent, -40)
                            if variant == 2 and y == 0 and x in (3, 4):
                                color = metal
                        elif face in ("left", "right"):
                            if y > (5 if bandit else 3):
                                color = (0, 0, 0, 0)
                            elif y == 2 and x in (3, 4):
                                color = dark
                        elif face == "back" and y > (6 if bandit else 4):
                            color = (0, 0, 0, 0)
                        if name == "ash_raiders" and y < 2:
                            color = tone(accent, noise - 20)
                        if name == "black_crows" and face == "top" and (x + y) % 7 == 0:
                            color = tone(accent, -55)

                    elif part == "body":
                        if face == "front" and x in (1, 6):
                            color = tone(armor, -20)
                        if y >= height - 2 and face not in ("top", "bottom"):
                            color = dark if y == height - 2 else tone(armor, -30)
                            if face == "front" and x in (3, 4):
                                color = metal

                    elif part.endswith("arm"):
                        if face not in ("top", "bottom") and y >= 9:
                            color = tone(dark, noise)
                            if y == 9:
                                color = tone(armor, -25)
                        if face in ("front", "back") and y == 6:
                            color = tone(camo, -20)
                        if bandit and part == "left_arm" and y in (4, 5):
                            color = tone(accent, -20 + noise)

                    elif part.endswith("leg"):
                        if face == "front" and y in (5, 6, 7) and x in (1, 2):
                            color = tone(armor, noise - 18)
                        if face in ("right", "left") and y in (2, 3, 4):
                            color = tone(armor, noise - 8)

                    elif part == "rig":
                        color = tone(armor, noise + shade)
                        if face == "front":
                            if variant == 1:  # Light scout harness, visible cloth between straps.
                                color = tone(cloth, noise)
                                if x in (1, 6) or y == 6:
                                    color = tone(armor, -25)
                            elif x == 0 or x == 7 or y == 0:
                                color = tone(armor, -28)
                            elif 4 <= y <= 7 and x in (1, 2, 4, 5, 6):
                                color = tone(armor, -20 if y == 4 or x in (2, 5) else 15)
                            if y == 2 and 4 <= x <= 6:
                                color = accent
                            if y == 3 and x == 5:
                                color = accent
                            if variant == 2 and 1 <= x <= 3 and y <= 2:
                                color = tone(dark, 6)
                                if x == 2 and y == 1:
                                    color = metal
                            if bandit and (x + y + seed) % 11 == 0:
                                color = tone(accent, -40)
                        if face == "back":
                            if x in (1, 6):
                                color = tone(armor, -25)
                            if y in (2, 3) and 2 <= x <= 5:
                                color = tone(accent, -50)

                    elif part.endswith("pad"):
                        color = tone(armor, noise + shade - 8)
                        if face in ("left", "right", "front"):
                            if y == 0:
                                color = dark
                            if y in (1, 2) and x in (1, 2):
                                color = accent
                            if y == 3 and x == 1:
                                color = tone(accent, -25)

                    elif part.endswith("boot"):
                        color = tone(dark, noise + shade)
                        if face == "front" and x in (1, 2) and y in (0, 2):
                            color = tone(armor, -20)
                        if face not in ("top", "bottom") and y == height - 1:
                            color = (17, 19, 19)
                        if bandit and y == 0 and face != "bottom":
                            color = tone(accent, -65)

                    elif part == "pack":
                        color = tone(armor, noise + shade - 12)
                        if face in ("front", "back"):
                            if x in (0, width - 1):
                                color = dark
                            if y == 2:
                                color = tone(armor, 18)
                            if y == 4 and x in (1, 2):
                                color = accent

                    canvas.point(u + x, v + y, color)
    return canvas.png()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="compare committed art to deterministic original pixels")
    args = parser.parse_args()
    verify_atlas()
    expected = set()
    errors = []
    for name in FACTIONS:
        for variant in range(3):
            path = DESTINATION / f"{name}_{variant}.png"
            expected.add(path.name)
            data = texture(name, variant)
            if args.check:
                if not path.is_file() or path.read_bytes() != data:
                    errors.append(str(path.relative_to(ROOT)))
            else:
                DESTINATION.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if args.check:
        errors.extend(str(path.relative_to(ROOT)) for path in DESTINATION.glob("*.png") if path.name not in expected)
        if errors:
            raise SystemExit("Faction texture mismatch: " + ", ".join(errors))
    print(f"{'Validated' if args.check else 'Generated'} 21 original faction textures; 64x64 RGBA; UV faces disjoint.")


if __name__ == "__main__":
    main()
