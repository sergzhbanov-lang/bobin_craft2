#!/usr/bin/env python3
"""Validate Bobin Craft resource JSON, registered loot items and bunker art offline.

The vanilla allowlist deliberately contains the reviewed Minecraft 1.20.1 loot
palette used by this project, rather than guessing that arbitrary IDs exist.
New items require an explicit registry review before this check accepts them.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
ASSETS = RESOURCES / "assets/bobin_craft"
JAVA = ROOT / "src/main/java"
VANILLA_LOOT_ITEMS = {
    "apple", "arrow", "baked_potato", "bread", "carrot", "chainmail_chestplate",
    "clock", "coal", "comparator", "compass", "cooked_beef", "copper_ingot",
    "diamond", "dried_kelp", "emerald", "glass_bottle", "gold_nugget",
    "golden_apple", "golden_carrot", "gunpowder", "honey_bottle", "iron_chestplate",
    "iron_helmet", "iron_ingot", "iron_pickaxe", "leather", "lever", "paper",
    "potion", "quartz", "redstone", "repeater", "shield", "slime_ball", "spyglass",
    "string", "sugar", "torch", "white_wool",
}
CUSTOM_LOOT_ITEMS = {
    "ak47", "sniper_rifle", "breacher", "spotter", "cryo_carbine", "medic_gun",
    "icicle", "map_table", "supply_crate", "metal_floor", "bunker_wall", "equipment_panel",
    "tent_fabric", "reinforced_wood", "palisade", "camouflage_net", "sand_bags",
    "ammo_crate", "equipment_locker", "weapon_rack", "radio", "warning_sign",
}
POTIONS = {"healing", "regeneration"}
THEMES = {"CIVILIAN": "civilian", "BANDIT": "bandit", "MEDICAL": "medical",
          "TECHNICAL": "technical", "BUNKER": "bunker"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def resource_reference(reference, kind, suffix, source):
    if reference.startswith("bobin_craft:"):
        target = ASSETS / kind / (reference.split(":", 1)[1] + suffix)
        require(target.is_file(), f"{source}: missing referenced resource {target}")


def main():
    resources = {}
    for path in sorted(RESOURCES.rglob("*.json")):
        # Duplicate keys silently shadow translations/resource entries, so reject them.
        def unique_object(pairs):
            result = {}
            for key, value in pairs:
                require(key not in result, f"{path}: duplicate JSON key {key}")
                result[key] = value
            return result
        resources[path] = json.loads(path.read_text(), object_pairs_hook=unique_object)

    loot_items = set()
    loot_tables = 0
    for path, data in resources.items():
        if "loot_tables" in path.parts:
            loot_tables += 1
            for node in walk(data):
                if node.get("type") == "minecraft:item":
                    item_id = node["name"]
                    namespace, name = item_id.split(":", 1)
                    known = ((namespace == "minecraft" and name in VANILLA_LOOT_ITEMS)
                             or (namespace == "bobin_craft" and name in CUSTOM_LOOT_ITEMS))
                    require(known, f"{path}: unreviewed/unknown Minecraft 1.20.1 item {item_id}")
                    loot_items.add(item_id)
                if node.get("function") == "minecraft:set_potion":
                    require(node.get("id") in {f"minecraft:{name}" for name in POTIONS},
                            f"{path}: unknown potion {node.get('id')}")
                if node.get("type") == "minecraft:loot_table":
                    namespace, name = node["name"].split(":", 1)
                    if namespace == "bobin_craft":
                        target = RESOURCES / "data/bobin_craft/loot_tables" / f"{name}.json"
                        require(target.is_file(), f"{path}: missing nested loot table {target}")
        if "models" in path.parts or "blockstates" in path.parts:
            for node in walk(data):
                for key in ("parent", "model"):
                    if key in node and isinstance(node[key], str):
                        resource_reference(node[key], "models", ".json", path)
                for reference in node.get("textures", {}).values():
                    resource_reference(reference, "textures", ".png", path)

    source = "\n".join(path.read_text() for path in JAVA.rglob("*.java"))
    for reference in re.findall(r'"(bobin_craft:chests/[a-z_]+)"', source):
        name = reference.split(":", 1)[1]
        require((RESOURCES / "data/bobin_craft/loot_tables" / f"{name}.json").is_file(),
                f"Java references missing loot table {reference}")
    for constant, theme in THEMES.items():
        require(len(re.findall(rf"\bLOOT_{constant}\b", source)) >= 2,
                f"Thematic loot table {theme} lacks a blueprint consumer")
        require((RESOURCES / f"data/bobin_craft/loot_tables/chests/warzone_{theme}.json").is_file(),
                f"Missing thematic loot table {theme}")
    # Verify that custom loot item IDs are actually present in the registration source.
    registrars = "\n".join((JAVA / name).read_text() for name in (
        "com/bobincraft/BobinCraft.java", "com/bobincraft/weapon/GunType.java", "com/bobincraft/raid/HubContent.java"))
    for item_id in loot_items:
        if item_id.startswith("bobin_craft:"):
            require(f'"{item_id.split(":", 1)[1]}"' in registrars, f"Unregistered custom loot item {item_id}")

    en = resources[ASSETS / "lang/en_us.json"]
    uk = resources[ASSETS / "lang/uk_ua.json"]
    require(en.keys() <= uk.keys(), f"Missing Ukrainian translations: {sorted(en.keys() - uk.keys())}")
    for key in en:
        require(en[key].count("%s") == uk[key].count("%s"), f"Translation placeholder mismatch: {key}")
    subprocess.run([sys.executable, str(ROOT / "tools/generate_hub_textures.py"), "--check"], check=True)
    subprocess.run([sys.executable, str(ROOT / "tools/generate_outpost_assets.py"), "--check"], check=True)
    print(f"Validated {len(resources)} JSON files, {loot_tables} loot tables and {len(loot_items)} reviewed item IDs.")
    print("All five thematic loot tables have blueprint consumers; English/Ukrainian keys and placeholders agree.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError) as error:
        raise SystemExit(str(error)) from error
