# Landscape and controls update 1.0.15

Generation profile 4 adds finite, connected river reaches whose planned water
levels never rise downstream. Main and tributary sources have matching mountain
terrain. The wider main river has asymmetric banks, deep pools, sediment shelves
and broad shallow fords. River and pond carving are coordinated so lowland ponds
do not break elevated banks. Water blocks remain vanilla sources: the planned
current direction controls sparse surface particles rather than a new fluid
simulation.

Road routing checks the ground grade and carriageway edges, joins shared paths
at the same centerline and connects suitable future locations. Only the existing
landing pad receives a terrain blend. The profile generates no new buildings.

New rock blocks are river stone, layered slate and lichen rock. Small river
pebbles have three collision/model shapes and can be waterlogged. Boulder shapes
include flatter outcrops and irregular mounds. New double-height reeds, extra
tall grass, ferns and underwater plants use supported substrates; placement checks
both halves before writing a tall plant.

In ordinary view, left mouse shoots through the same weapon handler and server
path used by the tactical camera. Holding fires automatic weapons, while
semiautomatic weapons require another click. R reloads and X toggles sniper aim.
Normal-view ammunition/reload status appears above the hotbar. Controls can be
remapped in Minecraft Controls. Tactical mode remains available on G.

## Compatibility

Saved generation versions 1, 2 and 3 retain their terrain, reservations, routes and
generation settings. New world JSON presets explicitly request version
4. Missing saved codec version fields still decode to version 1. The client water
appearance applies to the Warzone dimension, including existing Warzone saves.
The normal Overworld uses its previous water renderer.

## Validation

- Java 17 / Gradle 8.8 release build: **98 JUnit tests passed**, zero failures or skips.
- Pristine terrain, reservation, route and metadata fixtures verify saved
  generation profiles 1, 2 and 3.
- Five default-size seeds retain dry landings and connect all nine eligible
  destinations. Route checks also cover 1024- and 4096-block arenas.
- Full 2048 × 2048 hydrology scans for seeds 1 and 42 found no dry channel cores,
  uphill current steps, floating banks or water-surface jumps above one block.
- Headless Fabric client loading transformed the attack, sniper FOV and water
  reload mixins. Exported bytecode confirms the custom water callback runs after
  Fabric's vanilla handler reset.
- Resource, texture, loot, faction, legacy blueprint, flora and deployment
  validators passed. The new rock/reed assets are reproducible from bundled scripts.

- Fresh Minecraft 1.20.1 / Fabric dedicated-server smoke run passed: generated
  river/route chunks, 56 terrain chunks and seam/query checks, 528,351 forest
  assertions, new registrations, full tall-plant pairs, hub services and NPC
  persistence. The server shut down cleanly after all PASS markers.

The seed-42 lower main river usually spans 20–33 blocks. Water surfaces range
from level 86 at headwaters to 62 in the lowland lake across the sampled seeds.
The attached `natural-terrain-v4.png` is a numerical diagnostic, not an in-game
screenshot. Client appearance, mouse feel and combat balance still require
interactive playtesting.

## Repository integration

The repository build preserves the existing camera movement, continuous scope zoom,
tick-based weapon cooldowns and fortified camp from the previous repository commit.
The packaged JAR is rebuilt from this combined source rather than copied from the
standalone ZIP.
