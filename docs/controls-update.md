# Source update 1.0.13

The supplied archive was inspected across client input/camera/rendering, weapons and server hit handling, raid/deployment/persistence, terrain, hub/NPC construction, resources and validation tools before editing. Changes reuse those systems and retain Minecraft 1.20.1/Fabric/Java 17 compatibility targets.

## Behavior

- G camera pans at up to 21 blocks per second near the outer 3.5% of the smaller window dimension. Corner input is normalized. Pan, rotation settling and collision recovery use elapsed time; paused/unfocused/other screens reset frame timing. Camera perspective, collision, zoom, rotation, height and recenter functions remain.
- G-mode configured WASD input is sampled before vanilla travel, normalized, and converted from camera space to the player's current facing. Minecraft retains movement, collision, gravity, jumping and sneak handling. Manual input cancels pending automatic actions immediately and has no forced directional speed penalty, grid target or full-block step. Leaving the screen/focus clears input.
- Right click never sets a movement path. In-reach native block/entity interaction, ground placement, offhand fallback and item use remain; map tables, storage and merchants still use existing server validation. Existing left-click break/attack actions remain.
- Normal-view firearms route attack through the existing handler, without extra melee/mining packets. Both modes share projectiles, penetration, ammo, reloading, damage packets, cooldowns, effects and HUD. Normal aim follows the player eye ray; tactical aim follows the cursor. Reload/scope bindings support remapping. Reload is canceled if its held stack changes.
- No global time scaling was found. The old projectile statistics directly caused 5.75–15 blocks/second flight, and sniper convergence explicitly required 260 ticks (13 seconds). Corrected muzzle velocities are AK 715, sniper 850, breacher 400, spotter 330, cryo 240 and medic 120 metres/second, converted once at 20 ticks/second. Sniper convergence is 20 ticks plus the existing 10-tick entry delay. Firing intervals now use the same game-tick clock as reloads, spread and projectiles. Original damage, ammunition, range, reload durations and firing intervals are preserved. Fast shots visit every crossed voxel and retain interpolated tracer segments.
- Fresh hubs add three low supported hills, fallen trunks, a sandbag overlook, cargo shelter, communications mast and tent details. Six services, paths, table/storage/spawn anchors and NPC lifecycle remain. An invisible shell extends from bedrock to the world ceiling, outside the timber perimeter, with a closed top. Existing initialized hubs are never rebuilt or overwritten.

## Validation performed in this session

| Check | Result |
| --- | --- |
| Full Gradle build and JUnit | Passed with actual Minecraft/Fabric dependencies: 65 tests, 0 failures/errors/skips; Java compilation, test compilation, remapped release JAR and sources JAR succeeded. JDK 17, Gradle 8.8, Loom 1.7.4. |
| Additional dependency-free JUnit execution | 49 passed, 0 failed/skipped: 48 input/camera math tests and 1 weapon timing contract test via JUnit console 1.10.3. These overlap the full Gradle suite. |
| Existing `tools/verify-headless.sh` | Passed: resource/faction validators; 25 guard/throttle checks; 10,055 NPC firing/reload/persistence checks; 3-seed bridge/POI/flora suites; 976 deployment assertions over 8 maps. |
| Java 17 syntax parse | All 106 Java files parsed, 0 failures. This does not validate external APIs or Mixin application. |
| Hub layout and swept-projectile tests | All 3 layout and 2 voxel-collision tests passed with actual named Minecraft classes through Gradle. |
| Release JAR | Produced `bobin_craft-1.0.13.jar`; ZIP CRC, embedded version and absence of server-smoke harness classes verified. |
| Real Fabric fresh hub | Passed: 137,183 world/NPC/containment assertions and 528,351 deterministic forest assertions. All barrier columns/roof/floor, skylight opacity, hills/log support, six zones, storage and NPC anchors verified. |
| Same-world Fabric restart | Passed: 137,193 world/NPC/containment assertions and 528,351 forest assertions. Custom blocks, consumed starter supplies, merchant trades and guard/merchant UUIDs preserved, with no rebuild/refill/duplicates. Both disposable servers stopped cleanly. |
| Graphical client | Not run: this runtime has no graphical display/Xvfb. Manual camera/input/weapon visual and multiplayer interaction checks remain. |

The old validation report describes the supplied 1.0.12 baseline; the results above belong to this source update. Initial Fabric/Minecraft HTTP 403 dependency failures cleared on the final retry. Existing player hit/ammunition networking trust limitations remain. Local smoke servers used offline mode and loopback networking; online authentication was not tested. The reference PlanetMinecraft page returned HTTP 403, so the camp uses an original interpretation of the requested forest military theme rather than a verified visual match.

## Reproduce

On a normal JDK 17 machine:

```sh
./gradlew --no-daemon --max-workers=4 build
bash tools/verify-headless.sh
bash tools/server-smoke/build.sh
```

For this managed cloud runtime, `bash tools/setup-cloud.sh` installs checksum-verified tools under `/workspace/tooling`; use `/workspace/tooling/gradle build` because the supplied wrapper launcher does not forward Java proxy options. Required Fabric/Minecraft host additions were saved in the environment draft: `maven.fabricmc.net`, `piston-meta.mojang.com`, `piston-data.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`; `www.planetminecraft.com` was added for the reference page. The final build fetched those dependencies successfully. Saved draft edits alone do not establish that a running policy changed; use actual command results to diagnose access.

Run the existing two-launch disposable server harness as documented in `tools/server-smoke/README.md`. Test both camera modes manually: short WASD taps and diagonals, collisions/jump/sneak, edges/corners and focus changes, containers/trading/table/placement, every firearm's firing/reload/swap/ADS and wall penetration at several frame rates. Inspect a fresh hub and confirm old worlds retain customized blocks and inventories.
