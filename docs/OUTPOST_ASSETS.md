# Original forest outpost assets

The Update 2 outpost uses 16 original, deterministic 32×32 RGBA textures. They
were drawn procedurally from pixel noise, stitches, wood grain, camouflage
patches, wear, bolts and geometric military symbols. No external images,
commercial game assets, texture packs or fonts were used as inputs.

Reproduce the PNGs, block models, blockstates, item models and block loot tables:

```sh
python3 tools/generate_outpost_assets.py
python3 tools/generate_outpost_assets.py --check
python3 tools/validate_resources.py
```

The older `generate_hub_textures.py` remains reproducible and validates the ten
original bunker/map textures, whose registry IDs remain available to old saves.
The existing interactive planning table now uses the new weathered wooden frame
and legs, retaining its military map and server-side deployment interaction.

| Block ID (`bobin_craft:`) | Geometry and use |
| --- | --- |
| `tent_fabric` | Full block; stitched dark camouflage canvas for camp tents |
| `reinforced_wood` | Full block; weathered timbers with metal braces |
| `palisade` | Full block; timber defensive wall with end-grain top |
| `camouflage_net` | Thin overhead cutout panel; no collision |
| `sand_bags` | Staggered bags, 14/16 block-high collision |
| `ammo_crate` | Low 12/16 block-high box with ammunition stencil |
| `equipment_locker` | Inset metal cabinet with louvres and handle |
| `weapon_rack` | Wooden frame with original static weapon displays |
| `radio` | Low radio case with tall antenna |
| `warning_sign` | Restricted-area symbol on a wooden post |

Lockers, weapon racks, radios and warning signs retain horizontal facing in
their blockstates. Baked models and cached outline/collision shapes rotate
together. The net uses Fabric's cutout render layer. These props contain no
block entities or inventories; persistent storage uses vanilla chests and
barrels, and the supply merchant uses server-owned vanilla trade offers.

Names and tactical-map labels are provided in English, Ukrainian and Russian.
The resource validator checks custom model/texture paths and each block's
registered loot item, as well as deterministic regeneration of every new asset.
