# Update 2: tactical field map

After validated deployment, press **M** to open the read-only field map. Rebind **Open tactical field map** in Minecraft Controls. The existing camera toggle uses G, and BobinScreen's other shortcuts do not use M. Fabric's registered KeyBinding handles the new control; screen input events route that binding through BobinScreen because ordinary gameplay bindings are blocked while a Screen is open. Text entry, inventory and other menus retain their input. The configured key also closes the field map, including a mouse binding. Holding the opening key does not immediately close the new screen through key repeat.

Drag to pan and use the wheel to zoom. WASD pans, +/- zooms and R restores the fixed strategic overview. Escape or Close returns to gameplay. The map starts at the arena's fixed midpoint, then remembers the last manual view for the client session. It contains no player arrow, player dot, live coordinates, camera heading, enemy markers or player-follow centering. The remembered view and terrain texture are cleared on disconnect. The map does not pause the raid.

The hub planning table retains the separate deployment interface with exactly three server-authored choices and danger information. A field-map request carries no position or choice, and its response carries zero deployment choices and **no serialized deployment-session token**. The field interface has no Deploy action. The server requires a live, non-spectating player with an active raid in the Warzone, throttles requests, and rechecks those conditions after asynchronous preparation. Deployment still requires a valid hub-table session, proximity to the actual table, the current rotation, an offered choice, prepared chunks and a safe landing. Field requests never create that session.

## Reused systems

| System | Role |
| --- | --- |
| WarzoneTerrain | Actual deterministic geography, water, region colors, forest density and sampled relief |
| WarzoneBasePlan | Roads, trails and military landmark metadata |
| HideoutPlan / PoiPlan | Discoverable hideouts, checkpoints and battlefields |
| DeploymentPlanner | Existing three choices and the extraction point revealed during briefing |
| RaidPersistentState.PlayerRaid.discovered | Persistent per-player landmark discovery IDs |
| RaidManager / RaidNetworking | Server validation, asynchronous map preparation and bounded packets |
| TacticalMapScreen / TacticalMapTextureCache | Shared pan/zoom rendering and one reusable client terrain texture |

Terrain sampling is 96×96 and reads generation metadata, never surrounding world blocks. Both interfaces reuse the same bounded server base-map cache (four arena keys) and client texture. Discovery changes filter marker lists without resampling terrain or regenerating the texture. Offscreen road segments and landmark symbols are skipped during drawing. Map preparation uses the existing single background worker; world access and saved-state changes stay on the server thread.

## Discoveries and fog of war

Only discovered landmark markers are included in either map packet. Base discovery IDs remain compatible with existing saves. Separate negative ID ranges extend the same saved set for hideouts, checkpoints and battlefields. Discovery checks use metadata distances every 40 server ticks and when requesting a field map; they save landmark IDs, not a player trail. Discoveries survive death, extraction and save/reload.

FogOfWarManager remains the existing camera visibility effect. It has no persisted explored-area grid, so the field map uses the existing permanent landmark discoveries rather than treating transient screen visibility as explored terrain. The general terrain and roads remain visible as strategic geography; undiscovered landmark names and markers are withheld. Extraction is visible because the deployment briefing already reveals it. This update does not add live reconnaissance, an explored-terrain grid or map updates for player-built terrain changes.

## Validation and remaining manual checks

TacticalFieldMapTest covers read-only packet constraints, absent deployment authorization, packet roundtrip/trailing-byte rejection, filtering undiscovered landmarks and persistence of discoveries. RaidStateTest retains the deployment packet and save roundtrip coverage. Consult the delivered validation report for executed results.

A rendered Minecraft session is still needed to check M and a remapped key in first-person/Bobin camera modes, mouse bindings, held-key behavior, all UI scales, drag/zoom readability, return from the field map, and concurrent deployment/extraction in multiplayer. This document does not claim those runtime checks were performed.
