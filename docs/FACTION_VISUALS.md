# Faction NPC visuals

`WarzoneGuardRenderer` renders the shared `WarzoneGuardEntity` with an original
humanoid model, faction textures and the actual equipped firearm. The entity's
tracked faction and appearance variant select a texture; names and vanilla armor
items do not control faction identity. The safe hub uses Regular Army variant 0.

All 21 RGBA textures are original pixel art authored by
`tools/generate_faction_textures.py`. There are no external image inputs, vanilla
skin recolors, downloaded skins, or additional texture-pack dependencies.

## Recognizing each faction

| Faction / resource prefix | Clothing and equipment | Identification |
| --- | --- | --- |
| `REGULAR_ARMY` / `regular_army` | Olive camouflage, khaki plate carrier, field helmet, dark gloves and boots | Ochre rank bars and shoulder patches; orderly field uniform |
| `IRON_WOLVES` / `iron_wolves` | Slate camouflage, steel-gray armored rig and helmet | Red shoulder/chest markings; amber scout optics |
| `GLASS_COMPANY` / `glass_company` | Navy fatigues, sand-colored carrier and helmet | Bright cyan company patches and scout visor |
| `FROST_CORPS` / `frost_corps` | Snow-white camouflage, blue-gray winter rig and helmet | Pale cyan unit patches, blue snow goggles |
| `ASH_RAIDERS` / `ash_raiders` | Soot/brown patched layers, scorched leather rig, orange head wrap | Burnt-orange wraps, asymmetrical arm band and cloth face mask |
| `BLACK_CROWS` / `black_crows` | Charcoal/plum scavenged clothing, dark hood and leather rig | Bone-lilac patches, stitched mask and contrasting arm band |
| `MARSH_REAVERS` / `marsh_reavers` | Moss/brown wetland camouflage, swamp hood, worn harness | Yellow-green wraps, masked face, muddy boot cuffs |

The first four factions expose human faces beneath field helmets. The last
three use hood/head-wrap silhouettes and lower-face cloth masks. Shoulder
patches and chest tabs repeat the faction accent so the identity remains visible
from the side and at a distance.

## Appearance variants

Every faction has three distinct resources, named `<prefix>_0.png` through
`<prefix>_2.png`. These are visual loadout variants; the server chooses actual
weapons and tactical roles independently.

| Variant | Appearance |
| --- | --- |
| `0` | Rifleman: full field rig, chest magazine pouches, standard faction headgear |
| `1` | Scout: lighter two-strap harness and dark goggles with a faction-colored lens |
| `2` | Support: reinforced full rig, chest radio, helmet hardware and a visible small backpack |

The renderer uses `HeldItemFeatureRenderer`, so rifles and other equipped custom
firearms retain their item models. Guards hold them in a two-handed ready pose
while idle and aim with head pitch/yaw while the synchronized attacking flag is
set. The support pack is hidden for variants 0 and 1.

## Geometry and UV contract

`WarzoneGuardModel` supplies an explicit human biped rather than an illager model.
Every resource is **64 x 64 pixels**, and all cuboid face regions below are
disjoint. UV coordinates are the top-left corner of each cuboid's unfolded box.
The overlay geometry uses its own dimensions, which are also used by the
generator; it does not assume that a ten-pixel rig has a twelve-pixel skin layout.

| Part | UV origin | Width x height x depth |
| --- | --- | --- |
| Head | `0,0` | `8 x 8 x 8` |
| Helmet/hood | `32,0` | `8 x 8 x 8`, expanded by `0.35` |
| Torso | `16,16` | `8 x 12 x 4` |
| Right arm | `40,16` | `4 x 12 x 4` |
| Left arm | `32,48` | `4 x 12 x 4` |
| Right leg | `0,16` | `4 x 12 x 4` |
| Left leg | `16,48` | `4 x 12 x 4` |
| Carrier/harness | `16,32` | `8 x 10 x 4`, expanded by `0.30` |
| Right shoulder pad | `40,32` | `4 x 4 x 4`, expanded by `0.20` |
| Left shoulder pad | `48,48` | `4 x 4 x 4`, expanded by `0.20` |
| Right boot | `0,32` | `4 x 5 x 4`, expanded by `0.12` |
| Left boot | `0,48` | `4 x 5 x 4`, expanded by `0.12` |
| Support backpack | `40,40` | `4 x 6 x 2` |

Transparent helmet-front pixels expose the face, and transparent lower side/back
pixels produce a helmet brim or hood opening. The rig, shoulder pads and boots
use small physical expansions so they read as equipment rather than flat marks.

## Reproducible validation

```sh
python3 tools/generate_faction_textures.py
python3 tools/generate_faction_textures.py --check
```

Generation and validation require only Python's standard library. The check
verifies that every cuboid face fits within the atlas without overlap, then
compares all 21 committed PNGs byte for byte against their generated originals.
Minecraft client inspection is still needed to judge animation, lighting and
held-item alignment in motion; this offline check makes no claim to cover them.
