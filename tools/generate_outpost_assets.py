#!/usr/bin/env python3
"""Build original 32px forest-outpost art and static prop resources, without dependencies.

No external images or game art are inputs. Run normally to reproduce the committed
assets, or pass --check to compare every generated PNG/model/blockstate/loot table.
Storage remains in vanilla containers; these props require no ticking block entities.
"""
from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path

from generate_hub_textures import Canvas

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/bobin_craft"
LOOT = ROOT / "src/main/resources/data/bobin_craft/loot_tables/blocks"
BLOCKS = ("tent_fabric", "reinforced_wood", "palisade", "camouflage_net", "sand_bags",
          "ammo_crate", "equipment_locker", "weapon_rack", "radio", "warning_sign")
TEXTURES = ("tent_fabric", "reinforced_wood", "reinforced_wood_end", "palisade", "palisade_end",
            "camouflage_net", "sand_bags", "ammo_crate", "ammo_crate_top", "equipment_locker",
            "locker_side", "rack_wood", "rack_metal", "radio", "radio_side", "warning_sign")


def texture(name):
    seed = 733 + TEXTURES.index(name) * 97
    rng = random.Random(seed)
    if name in ("tent_fabric", "camouflage_net"):
        c = Canvas(seed, (64, 75, 45), 4)
        for _ in range(30):
            x, y = rng.randrange(32), rng.randrange(32)
            color = rng.choice(((40, 53, 35), (88, 94, 56), (69, 68, 41), (36, 45, 33)))
            for dy in range(rng.randrange(3, 7)):
                width = rng.randrange(4, 10)
                for dx in range(width):
                    c.point((x + dx + (dy % 3)) % 32, (y + dy) % 32, color)
        if name == "camouflage_net":
            for y in range(32):
                for x in range(32):
                    # Wide, irregular woven diagonals with actual cutout openings.
                    if (x + y) % 8 > 1 and (x - y) % 8 > 1:
                        c.pixels[y * 32 + x] = (0, 0, 0, 0)
        else:
            c.line(0, 1, 31, 1, (110, 112, 74))
            c.line(0, 2, 31, 2, (34, 44, 30))
            for x in range(1, 32, 4):
                c.point(x, 0, (161, 147, 103))
            for x, y in ((5, 26), (27, 7)):
                c.rect(x, y, x + 2, y + 2, (100, 102, 66))
                c.point(x + 1, y + 1, (36, 44, 32))
    elif name in ("reinforced_wood", "palisade", "rack_wood"):
        c = Canvas(seed, (86, 72, 48), 7)
        for x in range(0, 32, 8):
            c.line(x, 0, x, 31, (40, 38, 27))
            c.line(x + 1, 0, x + 1, 31, (115, 95, 62))
            for _ in range(8):
                sx, sy = x + rng.randrange(2, 8), rng.randrange(32)
                c.line(sx, sy, sx, min(31, sy + rng.randrange(1, 9)), (62, 55, 37))
        if name != "rack_wood":
            for y in (7, 23):
                c.rect(0, y, 31, y + 2, (54, 62, 54))
                c.line(0, y, 31, y, (100, 104, 81))
                for x in (3, 12, 21, 29):
                    c.bolt(x, y + 1)
            for x, y in ((8, 5), (22, 19), (29, 28)):
                c.rect(x, y, x + 1, y + 3, (51, 69, 40))
    elif name in ("reinforced_wood_end", "palisade_end"):
        c = Canvas(seed, (96, 79, 52), 5)
        for y in range(32):
            for x in range(32):
                radius = math.sqrt(((x % 16) - 7.5) ** 2 + ((y % 16) - 7.5) ** 2)
                if int(radius * 1.4) % 4 == 0:
                    c.point(x, y, (58, 53, 35))
        for x in (0, 15, 16, 31):
            c.line(x, 0, x, 31, (47, 42, 29))
        c.line(0, 15, 31, 15, (47, 42, 29))
    elif name == "sand_bags":
        c = Canvas(seed, (130, 122, 83), 8)
        for y in range(2, 30, 3):
            for x in range(2 + y % 2, 30, 3):
                c.point(x, y, (159, 148, 104))
                c.point(x + 1, y + 1, (111, 105, 73))
        for y in (0, 1, 30, 31):
            c.line(0, y, 31, y, (82, 79, 55))
        for x in (0, 1, 30, 31):
            c.line(x, 0, x, 31, (92, 87, 58))
        for x in range(3, 29, 4):
            c.line(x, 2, x + 1, 2, (176, 160, 113))
            c.line(x, 29, x + 1, 29, (176, 160, 113))
        c.line(3, 26, 8, 28, (75, 75, 53))
    elif name in ("ammo_crate", "ammo_crate_top", "equipment_locker", "locker_side", "radio_side", "rack_metal"):
        base = (66, 78, 51) if name.startswith("ammo") else (59, 68, 58)
        if name == "rack_metal":
            base = (45, 49, 46)
        c = Canvas(seed, base, 5)
        for x, y in ((2, 3), (29, 3), (2, 28), (29, 28)):
            c.bolt(x, y)
        for _ in range(12):
            x, y = rng.randrange(3, 28), rng.randrange(3, 29)
            c.line(x, y, min(29, x + rng.randrange(1, 4)), y, (100, 100, 72))
        if name.startswith("ammo"):
            for y in (3, 27):
                c.rect(0, y, 31, y + 2, (40, 48, 34))
            if name == "ammo_crate_top":
                c.rect(12, 11, 20, 18, (32, 39, 31))
                c.rect(13, 12, 19, 16, (81, 89, 57))
            else:
                c.rect(7, 10, 24, 23, (170, 159, 106))
                for x in (10, 15, 20):
                    c.rect(x, 14, x + 1, 20, (59, 65, 40))
                    c.point(x, 13, (59, 65, 40))
                c.rect(14, 2, 17, 7, (119, 115, 80))
        elif name == "equipment_locker":
            c.rect(3, 2, 28, 29, (42, 52, 44))
            c.rect(4, 3, 27, 28, (75, 84, 62))
            for y in (6, 9, 12):
                c.line(7, y, 23, y, (28, 38, 33))
                c.line(7, y + 1, 23, y + 1, (110, 111, 77))
            c.rect(23, 18, 25, 24, (28, 35, 29))
            c.line(24, 19, 24, 22, (168, 162, 120))
            c.rect(8, 19, 15, 24, (160, 150, 107))
            c.line(9, 21, 14, 21, (69, 76, 51))
        elif name == "radio_side":
            for y in range(8, 26, 3):
                c.line(6, y, 25, y, (28, 38, 32))
    elif name == "radio":
        c = Canvas(seed, (55, 65, 48), 4)
        c.rect(2, 3, 17, 20, (24, 31, 27))
        for y in range(5, 19, 3):
            c.line(4, y, 15, y, (86, 90, 61))
        c.rect(20, 4, 29, 12, (20, 39, 31))
        c.line(21, 7, 28, 7, (158, 172, 103))
        for x in (22, 27):
            c.rect(x - 1, 17, x + 1, 21, (23, 30, 25))
            c.point(x, 18, (175, 163, 112))
        c.line(4, 26, 28, 26, (121, 122, 79))
        for x in (6, 13, 20, 27):
            c.line(x, 25, x, 28, (121, 122, 79))
    elif name == "warning_sign":
        c = Canvas(seed, (159, 144, 78), 6)
        for x in range(32):
            for y in range(32):
                if (y < 4 or y > 27) and (x + y) % 12 < 6:
                    c.point(x, y, (39, 43, 32))
        # Original restricted-area symbol, readable without small text.
        for y in range(7, 25):
            half = (y - 7) // 2
            c.line(16 - half, y, 16 + half, y, (43, 49, 33))
        c.rect(15, 12, 17, 18, (194, 171, 92))
        c.rect(15, 21, 17, 22, (194, 171, 92))
        c.bolt(2, 7)
        c.bolt(29, 24)
    else:
        raise ValueError(name)
    return c.png()


def box(start, end, texture_name, **face_textures):
    return {"from": list(start), "to": list(end), "faces": {
        face: {"texture": "#" + face_textures.get(face, texture_name), "uv": [0, 0, 16, 16]}
        for face in ("north", "south", "east", "west", "up", "down")}}


def model(textures, elements):
    return {"parent": "minecraft:block/block", "ambientocclusion": True,
            "textures": {key: f"bobin_craft:block/{value}" for key, value in textures.items()}, "elements": elements}


def all_resources():
    result = {ASSETS / f"textures/block/{name}.png": texture(name) for name in TEXTURES}
    def emit(path, value):
        result[path] = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()
    for name in BLOCKS:
        if name in ("tent_fabric", "reinforced_wood", "palisade"):
            textures = {"all": f"bobin_craft:block/{name}"}
            data = {"parent": "minecraft:block/cube_all", "textures": textures}
            if name != "tent_fabric":
                data = {"parent": "minecraft:block/cube_column", "textures": {
                    "side": f"bobin_craft:block/{name}", "end": f"bobin_craft:block/{name}_end"}}
        elif name == "camouflage_net":
            data = model({"particle": name, "net": name}, [box((0, 15, 0), (16, 16, 16), "net")])
        elif name == "sand_bags":
            elements = []
            for y, ranges in ((0, ((0, 8), (8, 16))), (5, ((0, 5), (5, 12), (12, 16))), (10, ((0, 8), (8, 16)))):
                for x0, x1 in ranges:
                    elements.append(box((x0 + .125, y, .125), (x1 - .125, min(y + 4.5, 14), 15.875), "bag"))
            data = model({"particle": name, "bag": name}, elements)
        elif name == "ammo_crate":
            data = model({"particle": name, "side": name, "lid": name + "_top"}, [
                box((1, 0, 1), (15, 10, 15), "side", up="lid", down="lid"),
                box((1, 10, 1), (15, 12, 15), "side", up="lid", down="lid")])
        elif name == "equipment_locker":
            data = model({"particle": "locker_side", "side": "locker_side", "front": name}, [
                box((1, 0, 1), (15, 16, 15), "side", north="front")])
        elif name == "weapon_rack":
            elements = [box((1, 0, 2), (15, 3, 16), "wood"), box((1, 0, 13), (15, 16, 16), "wood"),
                        box((1, 0, 2), (3, 16, 13), "wood"), box((13, 0, 2), (15, 16, 13), "wood")]
            for x in (5, 10):
                elements += [box((x, 3, 6), (x + 2, 8, 9), "wood"),
                             box((x + .25, 7, 6.5), (x + 1.75, 12, 8.5), "metal"),
                             box((x + .65, 12, 7), (x + 1.35, 16, 8), "metal"),
                             box((x + 1, 7, 8), (x + 2.5, 10, 9), "metal")]
            data = model({"particle": "rack_wood", "wood": "rack_wood", "metal": "rack_metal"}, elements)
        elif name == "radio":
            data = model({"particle": "radio_side", "side": "radio_side", "front": "radio", "metal": "rack_metal"}, [
                box((2, 0, 3), (14, 8, 13), "side", north="front"),
                box((11, 8, 10), (12, 16, 11), "metal")])
        elif name == "warning_sign":
            data = model({"particle": "rack_wood", "wood": "rack_wood", "front": name}, [
                box((7, 0, 6), (9, 8, 8), "wood"), box((1, 8, 6), (15, 15, 8), "wood", north="front", south="front")])
        else:
            raise ValueError(name)
        emit(ASSETS / f"models/block/{name}.json", data)
        directional = name in ("equipment_locker", "weapon_rack", "radio", "warning_sign")
        variants = {f"facing={direction}": {"model": f"bobin_craft:block/{name}", "y": rotation}
                    for direction, rotation in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}
        emit(ASSETS / f"blockstates/{name}.json", {"variants": variants if directional else {"": {"model": f"bobin_craft:block/{name}"}}})
        emit(ASSETS / f"models/item/{name}.json", {"parent": f"bobin_craft:block/{name}"})
        emit(LOOT / f"{name}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
             "entries": [{"type": "minecraft:item", "name": f"bobin_craft:{name}"}],
             "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    options = parser.parse_args()
    resources = all_resources()
    for path, expected in resources.items():
        if options.check:
            if not path.is_file() or path.read_bytes() != expected:
                raise SystemExit(f"Generated outpost asset differs: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    print(f"{'Validated' if options.check else 'Generated'} {len(TEXTURES)} original 32x32 PNGs and {len(BLOCKS) * 4} outpost resources.")
