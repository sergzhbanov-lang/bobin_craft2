# Architecture inspection and integration

## Imported baseline

The ZIP contained the complete project under `Bobin_craft/`; the selected Git checkout held only a one-line README. The original archive is preserved. Its project was imported into the existing checkout without a worktree.

| Area | Actual implementation |
| --- | --- |
| Toolchain | Minecraft1.20.1; Yarn1.20.1+build.10; Fabric Loader0.15.11/API0.92.2; Loom1.7-SNAPSHOT; Java17; Gradle8.8 |
| Registration | Common `BobinCraft` registers items, blocks, worldgen codecs and firearm C2S handlers |
| Client separation | Client entrypoint `BobinCraftClient`; camera/input/HUD/render/model mixins declared client-only |
| Terrain | `WarzoneChunkGenerator` fills finite arenas from `WarzoneTerrain`/`WarzoneNoise`; biome regions share the same terrain plan |
| Plans | `WarzoneBasePlan`: sites, roads, trails, bridges and nested `TacticalSpot`; `PoiPlan`: checkpoints/battlefields; `HideoutPlan`: shelters |
| Structures | `SiteBuilder`, `SiteModules`, `SiteDesign`, `WarzoneStructures`, `BlueprintBlitter`, `ChunkWriter` realize plans and vanilla loot containers |
| Blueprint cache | `WarzoneBlueprints` uses concurrent caches with finite plan identities |
| Exploration | Existing camera, mouse movement/pathfinding, roof cutaway and fog-of-war |
| Combat | Six firearms use client aim/ammunition/tracers and server impact handlers; Siege is separate |
| Original persistence | Vanilla worlds/containers persist; old command return points were static; no raid state or Warzone encounters |
| Loot | Existing supply/armory/command tables already consumed during generation, without chunk-load refills |

These are substantial existing geometry and exploration systems, rather than a completed extraction game. Their runtime behavior could not be demonstrated by the baseline build because dependency access failed before compilation.

## Integrated raid loop

`HubContent` registers the hub generator and five bunker blocks. Data-driven `bobin_craft:warzone` uses `HubChunkGenerator` in the Overworld and the existing combat generator in a separate dimension. The preset appends to the normal preset tag, preserving vanilla choices and Nether/End. A spawn-free biome and lit bunker prevent natural hostile spawning.

`HubBuilder` runs once, controlled by `RaidPersistentState`. Unsupported/corrupt initialization never grants permission to replace an existing bunker or storage. The empty shared chest and separate starter cache are vanilla containers. Player inventory remains vanilla throughout transitions.

`DeploymentPlanner` evaluates safety, clearance, escape corridors, roads and nearby site danger from existing metadata. Seed/rotation tie-breaking yields three distinct roles; fallback search never relaxes hard safety. The fixed landing-pad extraction is separated from offers. Final server block/entity checks cover trees, structures and player modifications absent from pure terrain metadata.

`TacticalMapData` samples96×96 terrain and overlays planned roads/POIs. A four-entry seed/size/version cache bounds retained maps. A dedicated worker receives pure plans; all save/world/network changes return to the server executor. Map opening performs no live chunk scan.

The table sends server-authored metadata to `TacticalMapScreen`. One terrain texture supports coordinate-based markers, zoom and pan. Deployment sends only rotation and choice ID. The server checks a recent session, the physical table/proximity, current choices and absence of active/pending transition. Chunk futures prepare the destination and extraction area before final safety checks and teleportation.

`WarzoneEncounterManager` lazily activates finite `TacticalSpot` defenders near living non-spectator players, once per persisted site. Its independent dimension-tick callback also supports legacy command-based entry. Creative players can inspect spawned defenders but are never targeted. Registered `WarzoneGuardEntity` keeps assigned posts, routes and awareness across serialization. It replaces vanilla wandering/target goals while reusing crossbow combat and rendering. Distance/FOV/LOS checks are staggered; sounds supply investigation positions rather than invisible targets. A world/player throttle coalesces shotgun notifications. Alarms decay and spawn no reinforcements.

`RaidManager` keeps only map sessions, pending requests and countdowns transient. Choices, discoveries, completions and encounter consumption use vanilla persistent data. Extraction teleports the existing player and advances one cycle. Death follows vanilla inventory rules. Seed/size/planner-version changes invalidate offers; malformed choices are replanned.

Five themed tables extend loot metadata in existing `SiteModules`. Vanilla one-time container loot persists without restore or refill hooks.

## Scoped corrections and limits

The guessed seed-zero generator fallback was removed, and noise population now honors its supplied executor. Actual world-preset preview/startup seed ordering remains an explicit Minecraft integration check.

Weapon countdowns and projectiles moved from render frames to client ticks, retaining frame-based aiming/interpolated tracers. An extra vanilla melee packet after gun impact was removed. Rate-limited gunfire notifications include misses; specialized gun impact rates are pellet-aware. Client ammunition/impact trust remains incomplete.

The initial model has one persistent combat map and shared bunker, finite loot/defenders, basic post/patrol/retreat behavior, and known POIs. It does not claim private storage, raid resets, a reinforcement economy, generalized cover/navigation proofs or a fogged planning-map overlay. Pillager inheritance and Peaceful removal remain relevant. Compilation, packets, rendering, teleportation and reload behavior require real Minecraft checks; headless/syntax checks cannot establish them.
