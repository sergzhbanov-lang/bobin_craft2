#!/usr/bin/env python3
"""Validate forest resources without starting Minecraft or installing packages."""

from pathlib import Path
import json
import re
import struct
import subprocess
import sys
import zlib

sys.dont_write_bytecode = True
from generate_forest_textures import textures, png_bytes


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
ASSETS = RESOURCES / "assets/bobin_craft"
DATA = RESOURCES / "data"
SOILS = ("dark_forest_soil", "pine_needle_ground", "mossy_forest_soil", "forest_litter")
LOGS = ("weathered_bark", "dark_pine_wood", "rotten_wood")
PLANTS = ("dry_undergrowth", "forest_grass", "pale_sedge", "low_forest_bush", "needle_undergrowth")
RIVER_ROCKS = ("river_stone", "layered_slate", "lichen_rock", "river_pebbles")
BLOCKS = (*SOILS, *LOGS, "moss_covered_rock", *PLANTS, *RIVER_ROCKS, "river_reeds")


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def check_model(identifier):
    namespace, name = identifier.split(":", 1)
    if namespace == "minecraft":
        return
    assert namespace == "bobin_craft", identifier
    model = read(ASSETS / "models" / (name + ".json"))
    if "parent" in model:
        check_model(model["parent"])
    for texture in model.get("textures", {}).values():
        if texture.startswith("#"):
            continue
        texture_namespace, texture_name = texture.split(":", 1)
        if texture_namespace == "bobin_craft":
            assert (ASSETS / "textures" / (texture_name + ".png")).is_file(), texture


def check_png(name, expected):
    path = ASSETS / "textures/block" / (name + ".png")
    raw = path.read_bytes()
    assert raw == png_bytes(expected), f"Non-reproducible or stale texture: {name}"
    assert raw[:8] == b"\x89PNG\r\n\x1a\n", name
    width, height, bit_depth, color_type = struct.unpack(">IIBB", raw[16:26])
    assert (width, height, bit_depth) == (16, 16, 8), name
    assert color_type == (6 if name in PLANTS else 2), name
    offset = 8
    while offset < len(raw):
        size = struct.unpack(">I", raw[offset:offset + 4])[0]
        chunk = raw[offset + 4:offset + 8 + size]
        crc = struct.unpack(">I", raw[offset + 8 + size:offset + 12 + size])[0]
        assert zlib.crc32(chunk) == crc, f"Invalid PNG chunk: {name}"
        offset += size + 12
    if name in PLANTS:
        alpha = {color[3] for row in expected for color in row}
        assert alpha == {0, 255}, f"{name} must have a cutout silhouette"


def main():
    source = (ROOT / "src/main/java/com/bobincraft/warzone/ForestBlocks.java").read_text()
    registered = set(re.findall(r'register\("([a-z_]+)",', source))
    assert registered == set(BLOCKS), "Registered blocks and asset checks diverge"

    for block in BLOCKS:
        states = read(ASSETS / "blockstates" / (block + ".json"))["variants"]
        if block in LOGS:
            assert set(states) == {"axis=x", "axis=y", "axis=z"}, block
            assert states["axis=x"].get("x") == 90 and states["axis=x"].get("y") == 90, block
            assert states["axis=z"].get("x") == 90, block
        elif block == "river_pebbles":
            assert set(states) == {f"variant={variant},waterlogged={wet}"
                                   for variant in range(3) for wet in ("false", "true")}, block
        elif block == "river_reeds":
            assert set(states) == {"half=lower", "half=upper"}, block
        else:
            assert set(states) == {""}, block
        for variant in states.values():
            for model in variant if isinstance(variant, list) else [variant]:
                check_model(model["model"])
        check_model("bobin_craft:item/" + block)
        loot = read(DATA / "bobin_craft/loot_tables/blocks" / (block + ".json"))
        assert loot["pools"][0]["entries"][0]["name"] == "bobin_craft:" + block, block
        for locale in ("en_us", "uk_ua", "ru_ru"):
            assert read(ASSETS / "lang" / (locale + ".json")).get("block.bobin_craft." + block), (locale, block)

    for tag, blocks in {"dirt": SOILS, "logs": LOGS, "logs_that_burn": LOGS,
                        "mineable/shovel": SOILS, "mineable/axe": LOGS,
                        "mineable/pickaxe": ("moss_covered_rock", *RIVER_ROCKS)}.items():
        resource = read(DATA / "minecraft/tags/blocks" / (tag + ".json"))
        assert resource["replace"] is False, tag
        assert {"bobin_craft:" + block for block in blocks} <= set(resource["values"]), tag

    generated = textures()
    assert len(generated) == 20
    for name, canvas in generated.items():
        check_png(name, canvas)
    subprocess.run([sys.executable, str(ROOT / "tools/assets/generate_river_rocks.py"), "--check"], check=True)
    print("Forest resources valid: 18 blocks, 26 reproducible PNGs, models, drops, tags, 3 locales.")


if __name__ == "__main__":
    main()
