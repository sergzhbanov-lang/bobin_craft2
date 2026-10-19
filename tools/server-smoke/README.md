# Fabric server integration smoke harness

This optional development harness runs the checks that require Minecraft registries,
Fabric access transforms and an actual `ServerWorld`. It is separate from the mod's
production source sets and is never included in the release JAR.

Use Java 17 and build the harness:

```sh
./tools/server-smoke/build.sh
```

The script uses a temporary Gradle init task to obtain the project's named compile
classpath and writes `build/server-smoke/hub-smoke.jar`. To reuse a previously saved
classpath without invoking Gradle, pass its text file as the first argument.

Run only in an isolated disposable development server directory. Set `level-type=bobin_craft:warzone` in that directory's `server.properties` for a new world and launch Loom's `runServer` using the temporary init script below. This selects an isolated run directory and explicitly adds the named development harness JAR. Loom 1.7 resolves `runDir` relative to the project even if an absolute-looking value is supplied; use a relative directory. Accept the Minecraft EULA only if you
agree to it. The harness runs after 40 server ticks and stops the server by itself.
It logs `BOBIN_SMOKE_NATURAL_PASS`, `BOBIN_SMOKE_INITIAL_PASS`,
`BOBIN_SMOKE_NPC_PERSISTENCE_PASS`, `BOBIN_SMOKE_FOREST_PASS` and
`BOBIN_SMOKE_PASS` after successful checks. A failed
assertion logs `BOBIN_SMOKE_FAIL` and still shuts down; inspect logs, not just the
process exit code.

Launch the same world a second time. `BOBIN_SMOKE_RESTART_PASS` confirms that the
consumed starter rifle, a player placed diamond block, guard/merchant UUIDs and the
used merchant offer all survived restart without rebuilding/refilling the camp.
The harness deliberately changes those disposable world contents and puts a marker
in starter chest slot 26. For validation without these deliberate changes, add
`-Dbobin.smoke.readOnly=true` to the server JVM arguments. This mode logs
`BOBIN_SMOKE_READ_ONLY_PASS` and checks the hub services and NPC NBT without
consuming supplies, changing trade stock or placing a test block. The server still
creates and saves its isolated disposable world normally.

Natural-world validation requires a **new** world using the updated preset. The
suite checks loaded border settings, codec defaults for older saved generators,
preservation of the legacy landing pad, explicit version-two/version-three codec and saved dimension
precedence, and the current version-four natural profile from both
JSON configuration files. It populates independent in-memory chunks on both sides
of positive and negative seams, all four finite-world edges, the landing area and
dense forest, then compares every block state after reversing generation order.
It checks terrain column/height query contracts, custom floor/trunk/foliage blocks,
registration and block tick costs, elevated river water levels and complete local-depth
water columns, empty structure plans, no generated entities or
block entities, and safe actual landing headroom. Synthetic chunks are never added
to the saved world. Surface queries intentionally exclude ores and decorations;
the suite accounts for that existing generator contract.

To verify saved-world compatibility, make a disposable copy of an existing v1
fixture and add `-Dbobin.smoke.legacy=true` to the server JVM arguments. This mode
requires its saved generator to stay at 1024/v1 while the loaded datapack defines
2048/v4, checks the legacy landing and border, and logs
`BOBIN_SMOKE_LEGACY_WORLD_PASS`. Never run these harnesses against an original
player world; preserve the source fixture and test a separate copy.

The checks cover deterministic forest generation across positive/negative chunk
seams, finite terrain and wall clearance, height queries/column samples, exactly six
connected service zones, every solid perimeter column, safe spawn/NPC headroom,
seven friendly guards, a single native quartermaster with nine registered trades,
and hostile faction/variant/firearm/ammunition/cooldown NBT round trips. They do not
exercise client visuals, player input, deployment/extraction or live NPC combat.

Remove the harness JAR before using that development run directory for normal play.

Example, from the project root (a fresh `../smoke-server` directory):

```sh
mkdir -p ../smoke-server
# Create server.properties there, with level-type=bobin_craft:warzone,
# server-ip=127.0.0.1 and a free server-port. Set your preferred server options.
# Read and accept Minecraft's EULA there if you agree to it.
cat > build/server-smoke/run.init.gradle <<'GRADLE'
allprojects {
    afterEvaluate {
        loom.runs.server.runDir = '../smoke-server'
        tasks.named('runServer').configure {
            jvmArgs "-Dfabric.addMods=${projectDir}/build/server-smoke/hub-smoke.jar", '-Dbobin.smoke.readOnly=true'
        }
    }
}
GRADLE
./gradlew -I build/server-smoke/run.init.gradle --console=plain runServer --args=nogui
# Remove the readOnly JVM option and run twice to test deliberate hub restart markers.
```

The smoke run must show `bobin_smoke_harness` in Fabric's loaded mod list.
No initialization/checks run if that harness was not loaded. Offline-mode local
smoke tests cannot verify online session authentication.
