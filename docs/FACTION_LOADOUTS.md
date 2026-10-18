# Faction firearms and AI configuration

The existing seven factions share one `WarzoneGuardEntity`. Each generated tactical
spot supplies the actual site's faction, role and danger. Faction, one of three
appearance variants, assigned weapon, difficulty, armor, magazine, finite reserve,
reload progress and cooldown survive NBT save/reload. The server tracks faction
and variant to clients; equipment uses Minecraft's normal equipment synchronization.
Existing saved guards receive their site's faction and firearm once when upgraded.

Edit `src/main/resources/data/bobin_craft/warzone/faction_loadouts.json` and rebuild
to tune new encounters. This is a bundled JSON configuration, not a live datapack
reload hook. All seven faction keys are required. A malformed or missing profile
logs a warning and falls back to a conservative rifle loadout. Weapon names are
members of `NpcWeapon`, which resolve registered Bobin Craft items; no new weapons
or ammunition items are introduced.

| Faction | Standard | Sentry | High-danger support variant |
| --- | --- | --- | --- |
| Regular Army | AK-47 | AK-47 | Cryo carbine |
| Iron Wolves | AK-47 | AK-47 | Breacher |
| Glass Company | AK-47 | Spotter | Cryo carbine |
| Frost Corps | Cryo carbine | AK-47 | Cryo carbine |
| Ash Raiders | Spotter | AK-47 | Breacher |
| Black Crows | Spotter | AK-47 | AK-47 |
| Marsh Reavers | Spotter | Spotter | Breacher |

The existing 50-damage sniper and Medic are supported by the adapter but omitted
from default hostile loadouts: beginner enemies avoid sniper one-shot kills and
healing-weapon behavior. Native rifle/special-weapon damage remains unchanged.

Profile fields:

- `primary`, `sentry`, `elite`: registered firearm adapter names.
- `reactionTicks`: aim acquisition after confirmed sight (clamped 10–60).
- `spreadDegrees`: base cone spread (2–12 degrees); moving adds 2.5 degrees.
- `burstSize`: 1–5 shots; `burstPauseTicks`: 8–80 ticks after a burst.
- `reserveMagazines`: 1–5 finite spare magazines; the loaded magazine is additional.
- `detectionRange`: 20–48 blocks; sneaking reduces it to at most 28.
- `armored`: standardized helmet and body armor versus improvised leather.

Danger is 0 for camps/watchtowers, 1 for outposts/bunkers, and 2 for fortresses;
highlands/badlands add 1. Only danger 2+ support variants use `elite`. Danger
slightly improves reaction and accuracy and upgrades armored chest protection.
Sentries retain their roof position; other roles may approach within their own
territory. Perception remains staggered every 10–30 ticks and pathfinding is
throttled, preserving the existing awareness, hearing, patrol and cover behavior.

All shots originate on server ticks. `NpcFireControl` enforces the same weapon
magazine size, reload time and fire interval used by existing firearm stats. It
consumes finite reserve rounds and keeps ticking reloads when sight is lost.
Losing sight resets aiming. Rejoining/reloading a chunk preserves ammunition and
reload progress and requires fresh aim; it cannot refill a guard's reserves.

`NpcWeaponAdapter` validates the living non-creative/non-spectator player, held
registered firearm, range, territory and an unobstructed collision ray before
firing. Spread uses the server RNG. Shared `BulletPenetration` caliber traversal
and damage-loss rules bound each ray; rays end just beyond the intended target.
Entity-box intersections select the first bystander, so guards and merchants
intercept bullets without receiving friendly fire. NPC shots preserve terrain.
`ShotServer.applyHit` supplies original rifle damage/headshots/knockback;
`GunServer.applyEffects` supplies the existing Breacher/Spotter/Cryo/Medic effects
and marked/frozen damage behavior for both players and NPCs.

`assignHubGuard(post)` selects a regular-army soldier with an actual rifle, then
disables AI and grants invulnerability. Hub guards never target players, never
join vanilla raids and survive Peaceful difficulty. Hub placement remains owned
by the hub NPC spawner rather than encounter activation.

## Verification

```sh
source /workspace/tooling/bobin-env.sh  # or use an installed Java 17 JDK
bash tools/headless/run-guard.sh
python3 tools/validate_faction_loadouts.py
python3 tools/generate_faction_textures.py --check
```

The headless suite covers reaction delay, fire cadence, burst pause, no shots
without sight, finite reserves, reload timing, interrupted aiming and saved-state
restoration/clamping. Live multiplayer testing is still needed for visual aim,
cover/pathfinding behavior and final combat balance.
