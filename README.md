# Bobin Craft — Camera, Movement and Forest Camp Update

Minecraft **1.20.1**, Yarn **1.20.1+build.10**, Fabric Loader **0.15.11**, Fabric API **0.92.2+1.20.1**, **Java 17**, Gradle **8.8**, Fabric Loom **1.7.4**. Source version **1.0.13**. This updates the supplied project and preserves its procedural Warzone, deployment, loot, extraction, camera, fog, and Siege systems. See [current changes and validation](docs/controls-update.md); install the newly built 1.0.13 JAR for these changes.

## Play

1. Install `dist/bobin_craft-1.0.13.jar` or build the new non-sources JAR in `build/libs/` alongside matching Fabric Loader/API on every client and server. Any supplied `dist/bobin_craft-1.0.12.jar` is the unchanged baseline.
2. Create a **new Warzone world**. For a dedicated server use `level-type=bobin_craft:warzone` in `server.properties`. New hubs are 105×105 fortified forest outposts with six connected areas: gathering, sleeping tents, field kitchen, deployment, trading, and entrance security. Existing initialized bunkers and player storage remain intact; there is no automatic destructive migration.
3. Use **G** for the existing top-down camera. **WASD** moves the player continuously relative to the camera; moving the cursor toward any screen edge pans the camera, including diagonals. **Q/E**, wheel zoom, **Shift/Ctrl** height, and **Space/C** recenter remain available. Space also jumps with the default jump binding. Right click interacts/places/uses items within native reach and never moves the player. **H** roof modes also govern nearby foliage visibility. Roof and foliage adjustments are client rendering only. Existing first-person movement remains vanilla.
4. Shared storage and the one-time starter kit remain beside the planning table. The kit includes a rifle, supplies and 16 emeralds. Food storage is in the kitchen. Quartermaster stock uses the native server-managed merchant screen; emeralds also occur in existing raid loot. Stock and usage are shared and saved, with no automatic restocking. Ammo props are scenery; existing guns use their original magazine/reload systems rather than invented ammunition items.
5. Right-click the **center map-table block** in the broad planning table. Select exactly one of three server-authored deployments by marker or **1–3**, inspect its risk, and press **Deploy**. The surrounding tabletop is decorative.
6. During an active raid, press **M** for the separate tactical field map. It shows generated terrain, roads, water, forest shading, relief, discovered landmarks and the extraction revealed during briefing. It has **no player/other-player/enemy marker, current coordinates, automatic centering or teleport action**. The key can be remapped in Minecraft Controls; it also works in top-down mode.
7. Drag to pan; use the wheel or **+/−** to zoom. **WASD** pans, **R** restores the strategic overview; **Escape** or the configured map key closes the field map. Manual field-map views are remembered during the connection. Unvisited secret landmark labels are not transmitted. Terrain geography itself remains a strategic overview, without a saved tile-by-tile exploration mask.
8. Fight the seven existing factions, collect persistent loot, then reach the briefing map’s **E** extraction. Remain on the ground within five blocks for five seconds. Damage, leaving or nearby hostiles interrupts extraction. Success returns the same inventory to the hub.

## Factions and firearms

Every existing faction has three original 64×64 uniform variants, a shared humanoid model and persistent tracked identity. Factions are REGULAR_ARMY, IRON_WOLVES, GLASS_COMPANY, FROST_CORPS, ASH_RAIDERS, BLACK_CROWS and MARSH_REAVERS. `src/main/resources/data/bobin_craft/warzone/faction_loadouts.json` configures primary/sentry/elite weapons, reaction, spread, burst timing, reserve magazines, detection and armor. This is a bundled configuration read at startup; edit it and rebuild (it is not a live datapack reload hook).

NPCs equip actual registered guns. Server-tick fire control uses bounded ammunition, reloads, reaction delays, burst pauses, range and collision checks. Damage/effects reuse the firearm server code and caliber rules. NPC shots use server hitscan rather than the client’s animated player bullet simulation; solid direct obstructions prevent firing. NPCs cannot damage friendly hub guards or merchants, and allies intercept bullets. No sniper loadout is enabled by default because its existing damage is high. Existing patrol, investigation, ally-alert and defensive-post behavior remains.

Player firearms use the same firing, ammunition, reload and impact systems in both camera modes. Left click fires, **R** reloads, and **X** toggles sniper ADS; reload/scope can be remapped in Controls. Projectile velocities now use explicit metres per second converted once to game ticks, and sniper convergence takes 1 second after its existing half-second entry delay. Damage, magazines, range, reload times and firing intervals remain unchanged. New camps add interior hills, fallen-tree cover, field communications and cargo details, with a sealed invisible containment shell behind the timber perimeter. Existing initialized camps are preserved.

Friendly hub guards are passive persistent sentries. The hub disables player damage, rejects hostile entity loads, keeps the checkpoint closed and returns survival players who cross the compound boundary. Creative/spectator players may inspect the surrounding finite forest. Hub beds are resting scenery: interactions preserve the custom raid respawn flow.

## Build and validation

```sh
./gradlew --no-daemon --max-workers=4 build
bash tools/verify-headless.sh
```

Use a JDK, not only a JRE. `tools/setup-cloud.sh` is the supplied optional Linux toolchain installer; a normal local JDK 17 installation is preferable. The Gradle wrapper is checksum-pinned. Network dependencies include Fabric Maven, Minecraft libraries/assets and the configured package repositories.

See [validation](docs/validation.md) for exact executed checks and remaining manual tests, [Update 2 architecture](docs/update-2-architecture.md) for the dependency map and changed files, [tactical map](docs/update-2-map.md), [outpost assets](docs/OUTPOST_ASSETS.md), and [faction visuals](docs/FACTION_VISUALS.md). Original pre-update documentation remains in `docs/legacy-readme.md`; older architecture notes describe the supplied baseline.

## Persistence and remaining scope

- Hub blocks/containers and NPCs are constructed once. Old saved hubs retain the old geometry and stable storage/table anchors. Unknown/newer raid saves are preserved and disable initialization rather than rebuilding over player edits.
- One shared Warzone contains shared loot and finite encounters. Discovery and deployment cycles are per player. There are no automatic loot resets, encounter reinforcements, duplicate per-player world objects, or private hub inventories.
- Disconnect/reconnect retains the raid. Death uses the existing Minecraft inventory rules and returns to the hub. No inventory snapshots are restored.
- Existing player-fired ammunition/impact reporting still has the supplied multiplayer trust limitations; this update makes NPC firing server-authoritative without redesigning all player networking.
- Live combat balance, client GUI/foliage appearance, full multiplayer trade/deployment/extraction interaction and FPS profiling require manual Minecraft play. Do not infer these from compilation or metadata tests.
