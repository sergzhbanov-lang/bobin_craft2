# Warzone Update 2 — executed validation

Verified on 2026-10-18 with Temurin JDK 17, Gradle 8.8, Fabric Loom 1.7.4, Minecraft 1.20.1/Yarn 1.20.1+build.10, Loader 0.15.11 and Fabric API 0.92.2+1.20.1.

## Actual results

| Check | Result |
| --- | --- |
| Full Gradle `build` (compile, JUnit, remapped release JAR and sources JAR) | PASS; final source build completed in 43 s. |
| JUnit state/map/layout suite | 13 tests, 0 failures, 0 skipped. Covers old/new hub metadata, save state isolation, map packet bounds and round trips, no field-map deployment authorization, discovery filtering, persistent discovery IDs, six connected zones and perimeter/tower clearance. |
| `tools/verify-headless.sh` | PASS for resources, existing guard/terrain/blueprint/POI/flora and deployment logic. |
| Pure guard/fire-control checks | 25 awareness/throttle checks; 10,055 tick-based firearm/reload/finite-ammunition/persistence checks passed. |
| Resource checks | 108 JSON files, 24 loot tables, 61 reviewed item IDs; 16 new original 32×32 prop textures and 40 outpost resource files verified. Existing 10 hub textures and 15 baseline hub resources also validated. |
| Faction checks | All 7 JSON profiles refer to existing firearms; 21 original 64×64 RGBA textures reproduce byte for byte and use disjoint valid UV faces. |
| Real Fabric dedicated server, fresh world | PASS: new forest hub generated, six zones, complete five-block-high solid perimeter, valid table/storage and safe spawn/NPC anchors, exactly 7 friendly guards and 1 quartermaster with 9 registered native trades. 2,181 world/NPC/state assertions passed. |
| Real Fabric dedicated server, same-world restart | PASS: 2,191 world/NPC/state assertions; customized block, consumed starter rifle, merchant and guard UUIDs, and used trade preserved. No hub rebuild, stock refill or duplicate NPCs. |
| Real Fabric forest generation checks (both server runs) | 528,351 assertions per run passed: finite/level supported compound, uneven forest ring, cleared wall buffer, deterministic positive/negative chunk seams and order, and matching column/height queries. |
| Hostile NPC NBT under real Fabric | PASS: faction, appearance, registered firearm, bounded ammunition and cooldown survive two NBT round trips. |
| Release JAR/ZIP integrity | CRC verification performed during final packaging; smoke harness classes are absent from the release JAR. |

The supplied baseline could not previously build because dependencies were unavailable. This session fetched the real allowed dependencies, compiled the actual source and produced `bobin_craft-1.0.12.jar`. Initial compilation exposed an incorrect Yarn method name; it was fixed to `setAbleToJoinRaid`. Guard entity registration now uses FabricEntityTypeBuilder, avoiding the earlier vanilla missing-datafixer lookup log.

Registry-dependent forest checks cannot run in an ordinary JUnit JVM, which lacks Fabric's package-access transforms. They run through the reproducible **real Fabric** harness in `tools/server-smoke/`, rather than being disabled or represented as passed unit tests.

## Measured scope and performance

The headless run checked 3 seeds each for bridges, other POIs and flora. It reported 74 built bridges, 61 towers and 12 hideouts; flora checks reported 1,856 plants and no orphan/rock-embedded log failures. Deployment checks sampled 2 seeds × 4 arena sizes (8 maps), with 976 assertions; every tested map had risk spread and changed choices across rotations. The measured deployment run took 21.20 s, bridge run 14.47 s and other POI run 8.22 s on this shared host.

Minecraft reported fresh-world startup `Done (7.825s)` and restart `Done (9.595s)`. These are single-run server bootstrap observations on a shared host, not a benchmark of client FPS or loaded multiplayer NPC combat. No render/FPS, long-session memory trend, map opening latency or multiplayer tick-cost benchmark has been collected.

The smoke server bound only to localhost in an isolated disposable directory and ran without online authentication. Its expected authentication-key fetch failure reflects this runtime's restricted network, not a generation or gameplay assertion failure. Online authentication was not tested. Test worlds, EULA files, logs, Gradle caches and compiled smoke harness artifacts are excluded from the delivered project ZIP.

## Reproduce

```sh
./gradlew --no-daemon --max-workers=4 build
bash tools/verify-headless.sh
bash tools/server-smoke/build.sh
```

Follow `tools/server-smoke/README.md` to launch two isolated Loom server runs and inspect `BOBIN_SMOKE_INITIAL_PASS`, `BOBIN_SMOKE_RESTART_PASS`, `BOBIN_SMOKE_FOREST_PASS`, `BOBIN_SMOKE_NPC_PERSISTENCE_PASS` and `BOBIN_SMOKE_PASS`. The harness deliberately edits the disposable test world and stops the server; never install it in a real player's world.

## Unverified gameplay and limits

A Minecraft dedicated server was launched and tested. A graphical Minecraft client was **not** launched: this runtime has no display/Xvfb. Consequently these remain manual checks:

- M in first person and Bobin camera, remapped controls, GUI scales 2–4, dragging/zoom, key release/close behavior and absence of tracking indicators.
- Faction uniforms/held gun alignment in motion, night lighting, roof/net/foliage appearance and disconnect/dimension transitions.
- Actual player merchant transactions (including concurrent clients), top-down approach/interaction, safe hub PVP protection and Peaceful friendly guards.
- All three live player deployments, location discovery, target detection/spread/bursts/reloads, solid-wall and ally interception, cover/navigation behavior and combat balance.
- Loot/extraction/death/reconnect cycle with players and multiple clients; normal world types, single-player world creation and existing customized bunker worlds in the graphical client.

Known intentional limits: merchant stock is finite and shared without automatic restocking; ammo props are scenery because the original firearm system has no separate ammunition item; map geography is a strategic overview without a saved explored-tile mask; NPC ballistics are authoritative hitscan while player bullet animation/impact reporting retain the supplied trust limitations. Encounters/loot are shared and finite, without automatic reset. Existing old hubs retain their original architecture; migration does not replace customized blocks.
