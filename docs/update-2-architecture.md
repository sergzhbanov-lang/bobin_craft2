# Warzone Update 2 — dependency map and implementation

The supplied project remains Minecraft 1.20.1 / Yarn 1.20.1+build.10 / Fabric Loader 0.15.11 / API 0.92.2+1.20.1 / Java 17. Build configuration pins Loom 1.7.4 and Gradle 8.8. Release version is 1.0.12.

## Reused systems

| Existing system | Update 2 connection |
| --- | --- |
| `WarzoneDimension`, `WarzoneChunkGenerator`, `WarzoneTerrain`, `WarzoneBasePlan`, `HideoutPlan`, `PoiPlan` | Dimension/terrain registration and procedural geography remain. Both maps sample these immutable plans; no map opening scans world blocks. |
| `DeploymentPlanner`, `RaidManager`, `RaidNetworking` | Existing three-point selection, saved rotation and table/proximity/server teleport validation remain. Field-map request/response are separate channels and grant no deployment session. |
| `TacticalMapData` | Shared 96×96 terrain/road metadata, four-entry seed/size/generation cache, relief/forest shading, server-side discovered landmark filtering. |
| `RaidPersistentState.PlayerRaid.discovered` | Saves discovery IDs independently of current position; existing positive base IDs preserved, reserved negative IDs add other POIs. No player trail is recorded. |
| `FogOfWarManager` | Existing local visual visibility remains separate from persistent cartographic discoveries. The strategic terrain overview has no tile exploration mask. |
| `BobinCraftClient`, `BobinScreen`, `BobinMovementController` | Fabric-remappable M binding works through the existing screen-based camera; merchant interaction approaches and uses vanilla server-validated entity interaction. |
| `BuildingCutawayManager`, rendering mixins, `RaycastHelper` | Existing roof rendering/click-through mechanism also recognizes canvas/net roofs and bounded camera-corridor foliage. Caches reset across worlds/disconnect, independent of the open screen. |
| `WarzoneEncounterManager`, `GuardBrain`, `WarzoneGuardEntity` | Finite shared encounter activation, throttled perception/pathing, assigned posts, patrols, gunfire investigation and ally alerts remain. |
| `GunType`, registered weapon items, `BulletPenetration`, `GunServer`, `ShotServer` | NPC adapter uses existing items/caliber traversal and shared damage/effects, with authoritative server hitscan and tick-controlled finite magazines. `WeaponStats` shares rifle constants. |
| Native Minecraft inventories, villagers and merchant handlers | Hub containers and shared merchant stock persist through Minecraft; no custom inventory duplication/grant packets. Existing emerald loot supplies currency. |

## New hub construction and compatibility

`HubChunkGenerator` generates a finite 209×209 terrain island. Its 105×105 outpost clearing is level; a dense irregular spruce/oak forest ring uses uneven terrain, vegetation, logs and rocks. `HubForestDecorator` clips writes to the owning chunk, so generation order and seams are deterministic.

`HubLayout` reserves six service areas and connected dirt paths, guard/merchant anchors and wall clearance. `HubBuilder` places static structures, original props, warm lighting, shared containers, tents, beds, kitchen, a broad deployment planning table, quartermaster stall, checkpoint and four watchtowers. Every perimeter column and every NPC/spawn anchor is checked before NPC creation. `HubNpcs` spawns seven passive soldiers and one weaponsmith quartermaster once, using native saved equipment/offers.

`HubSafety` prevents player damage and hostile entity loads in the managed hub, prevents bed interaction from replacing raid respawn behavior, and returns survival players who cross the closed checkpoint/perimeter boundary. Creative/spectator inspection is allowed. It does not disable the normal interaction/placement/storage workflow inside the compound.

Only the existing first-initialization path builds the outpost. `HubDesign=2` is optional save metadata; old initialized hubs keep design 0 and are never rebuilt. The legacy spawn, table, starter chest and storage coordinates are unchanged. Unknown save versions and missing/corrupt initialized save data keep the existing fail-closed behavior. Existing hostile guard NBT without new firearm metadata is migrated to the faction at its saved site; new tracked faction/variant and weapon state are then saved.

## NPC configuration and synchronization

The existing seven `Faction` values are the complete faction set. Three deterministic uniform variants per faction use one original humanoid geometry and preallocated texture identifiers. DataTracker synchronizes faction, appearance variant and friendliness. Vanilla equipment synchronization carries registered firearm stacks. NBT preserves faction, variant, site, post, patrol state, weapon, magazine, reserve, cooldown and reload progress. Reloading a chunk requires reacquiring aim and never refills saved ammunition.

`FactionLoadouts` reads the bounded bundled JSON at startup; site type/region and role select danger/equipment. `NpcFireControl` is pure tick-based simulation. `NpcWeaponAdapter` checks target eligibility, territory, held item, range and direct collision geometry, applies spread, lets intervening living entities block fire, and reuses damage/effect code. Default loadouts avoid the existing very high-damage sniper. Friendly hub sentries are no-AI/invulnerable, cannot join vanilla raids, and survive Peaceful.

## Important changed files

- `raid/RaidManager.java`, `RaidNetworking.java`, `RaidPersistentState.java`, `TacticalMapData.java`: field-map authority/discoveries and safe optional hub metadata.
- `raid/client/RaidClient.java`, `TacticalMapScreen.java`, `RaidTacticalMapScreen.java`, `TacticalMapTextureCache.java`: controls, separate read-only screen and reusable client texture.
- `raid/HubBuilder.java`, `HubLayout.java`, `HubChunkGenerator.java`, `HubForestDecorator.java`, `HubContent.java`, `HubNpcs.java`, `HubSafety.java`: outpost geometry, generation, assets, traders and safety.
- `raid/guard/WarzoneGuardEntity.java`, `FactionLoadouts.java`, `NpcWeapon.java`, `NpcFireControl.java`, `NpcWeaponAdapter.java`: persistent identities, loadouts and server combat.
- `raid/client/WarzoneGuardModel.java`, `WarzoneGuardRenderer.java`, `weapon/WeaponStats.java`, `GunServer.java`, `ShotServer.java`, `BobinWeaponHandler.java`: shared rendering/weapon integration.
- `render/BuildingCutawayManager.java`, `FoliageCutawayManager.java`, `BobinCraftClient.java`, `screen/BobinScreen.java`, `movement/BobinMovementController.java`: visibility lifecycle and top-down merchant interaction.
- Resource folders contain original outpost blocks/models/textures, 21 faction textures, configuration and en/uk/ru localization. Asset generators and validators live in `tools/`.
- `src/test/`, `tools/headless/`, `tools/server-smoke/`: packet/discovery/state/layout tests, pure terrain/AI tests and actual Fabric server generation/restart checks. The smoke harness is excluded from the release JAR.

See `changed-files.md` for the full archive-relative inventory, and `validation.md` for actual executed evidence and unverified behavior.
