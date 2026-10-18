package com.bobincraft.raid;

import com.bobincraft.warzone.WarzoneChunkGenerator;
import com.bobincraft.warzone.WarzoneDimension;
import com.bobincraft.warzone.WarzoneTerrain;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.chunk.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.Files;

/** All raid transitions run on the server. The client sends only a persisted choice ID and cycle. */
public final class RaidManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("bobin_craft/raids");
    private static final Map<UUID, Long> mapSessions = new HashMap<>();
    private static final Map<UUID, Integer> extracting = new HashMap<>();
    private static final Set<UUID> pending = new HashSet<>();
    private static final Set<UUID> preparingMaps = new HashSet<>();
    private static final Map<UUID, Long> fieldMapRequests = new HashMap<>();
    private static final Set<UUID> preparingFieldMaps = new HashSet<>();
    private static ExecutorService mapWorker;
    private static MinecraftServer boundServer;
    private static final int EXTRACTION_TICKS = 100;
    private static final double EXTRACTION_RADIUS = 5.0;

    private RaidManager() {}

    public static boolean isManaged(MinecraftServer server) {
        return server.getOverworld().getChunkManager().getChunkGenerator() instanceof HubChunkGenerator;
    }

    static boolean isAvailable(MinecraftServer server) {
        return boundServer == server && isManaged(server);
    }

    public static void register() {
        RaidNetworking.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            clearSessions();
            if (!isManaged(server)) return;
            RaidPersistentState state = RaidPersistentState.get(server);
            boolean existingData = Files.exists(server.getSavePath(WorldSavePath.ROOT).resolve("data/bobin_craft_raids.dat"));
            boolean existingHub = server.getOverworld().getBlockState(HubBuilder.TABLE).isOf(HubContent.MAP_TABLE)
                    || server.getOverworld().getBlockState(new BlockPos(9, 72, 7)).isOf(HubContent.BUNKER_WALL);
            if (!state.isSupported() || (!state.hubInitialized && (existingData || existingHub))) {
                LOGGER.error("Raid state is unsupported or incomplete; preserving all hub blocks and storage. Restore a compatible bobin_craft_raids.dat backup before enabling raids.");
                return;
            }
            mapWorker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "bobin-raid-map");
                thread.setDaemon(true);
                return thread;
            });
            WarzoneChunkGenerator generator = WarzoneDimension.generatorOf(server.getWorld(WarzoneDimension.WORLD));
            if (generator != null && (state.worldSeed != server.getOverworld().getSeed()
                    || state.generationVersion != DeploymentPlanner.GENERATION_VERSION || state.arenaSize != generator.arenaSize())) {
                state.invalidatePlans();
                state.worldSeed = server.getOverworld().getSeed();
                state.generationVersion = DeploymentPlanner.GENERATION_VERSION;
                state.arenaSize = generator.arenaSize();
                state.markDirty();
            }
            if (!state.hubInitialized) {
                HubBuilder.build(server.getOverworld());
                state.hubInitialized = true;
                state.hubDesign = 2;
                state.markDirty();
                server.getOverworld().getPersistentStateManager().save();
            }
            server.getOverworld().setSpawnPos(HubBuilder.SPAWN, 180.0f);
            boundServer = server;
            LOGGER.info("Warzone hub initialized; persistent raid format {}", RaidPersistentState.VERSION);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            clearSessions();
            if (mapWorker != null) { mapWorker.shutdownNow(); mapWorker = null; }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!isAvailable(server)) return;
            ServerPlayerEntity player = handler.player;
            RaidPersistentState state = RaidPersistentState.get(server);
            RaidPersistentState.PlayerRaid raid = state.player(player.getUuid());
            if (!raid.initialized) {
                if (returnToHub(player)) {
                    raid.initialized = true;
                    message(player, "welcome", false);
                    state.markDirty();
                }
            } else if (raid.active && !WarzoneDimension.isWarzone(player.getWorld())) {
                raid.finish(false);
                state.markDirty();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            mapSessions.remove(id); extracting.remove(id);
            fieldMapRequests.remove(id); preparingFieldMaps.remove(id);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity player) || !isAvailable(player.getServer())) return;
            RaidPersistentState state = RaidPersistentState.get(player.getServer());
            RaidPersistentState.PlayerRaid raid = state.player(player.getUuid());
            if (raid.active) {
                raid.finish(false);
                state.markDirty();
            }
            extracting.remove(player.getUuid()); mapSessions.remove(player.getUuid());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (isAvailable(newPlayer.getServer())) returnToHub(newPlayer);
        });
        ServerTickEvents.END_SERVER_TICK.register(RaidManager::tick);
    }

    private static void clearSessions() {
        mapSessions.clear(); extracting.clear(); pending.clear(); preparingMaps.clear();
        fieldMapRequests.clear(); preparingFieldMaps.clear(); boundServer = null;
    }

    public static void openMap(ServerPlayerEntity player, BlockPos table) {
        MinecraftServer server = player.getServer();
        if (!isAvailable(server) || !canUseTable(player, table)) return;
        long tick = server.getTicks();
        Long previous = mapSessions.get(player.getUuid());
        if (previous != null && tick - previous < 10) return;
        RaidPersistentState state = RaidPersistentState.get(server);
        RaidPersistentState.PlayerRaid raid = state.player(player.getUuid());
        if (raid.active || pending.contains(player.getUuid()) || preparingMaps.contains(player.getUuid())) return;
        ServerWorld warzone = server.getWorld(WarzoneDimension.WORLD);
        WarzoneChunkGenerator generator = WarzoneDimension.generatorOf(warzone);
        if (generator == null) { message(player, "unavailable", false); return; }
        if (mapWorker == null) return;
        WarzoneTerrain terrain = generator.terrain();
        List<DeploymentPlanner.Point> savedChoices = raid.choices;
        long rotation = raid.rotation;
        Set<Integer> discoveries = Set.copyOf(raid.discovered);
        UUID uuid = player.getUuid();
        preparingMaps.add(uuid);
        mapSessions.put(uuid, tick);
        message(player, "preparing", true);
        // Only immutable terrain plans are read off-thread. No World, chunks, players, or save data.
        CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            List<DeploymentPlanner.Point> choices = validChoices(terrain, savedChoices)
                    ? savedChoices : DeploymentPlanner.plan(terrain, rotation);
            TacticalMapData data = TacticalMapData.build(terrain, choices, DeploymentPlanner.extraction(terrain), rotation, discoveries);
            LOGGER.debug("Map data prepared in {} ms", (System.nanoTime() - started) / 1_000_000.0);
            return data;
        }, mapWorker).whenComplete((data, error) -> server.execute(() -> {
            preparingMaps.remove(uuid);
            if (!isAvailable(server) || server.getPlayerManager().getPlayer(uuid) != player || !canUseTable(player, table)
                    || raid.active || raid.rotation != rotation) return;
            if (error != null) {
                LOGGER.error("Unable to prepare a safe deployment triple", error);
                message(player, "no_plan", false);
                return;
            }
            raid.choices = data.deployments();
            state.markDirty();
            mapSessions.put(uuid, (long) server.getTicks());
            RaidNetworking.sendMap(player, data);
        }));
    }

    /** Field-map requests never authorize deployment and never accept a client position. */
    public static void openFieldMap(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (!isAvailable(server) || !player.isAlive() || player.isSpectator()
                || !WarzoneDimension.isWarzone(player.getServerWorld()) || mapWorker == null) return;
        RaidPersistentState state = RaidPersistentState.get(server);
        RaidPersistentState.PlayerRaid raid = state.player(player.getUuid());
        if (!raid.active) return;
        UUID uuid = player.getUuid();
        long tick = server.getTicks();
        Long previous = fieldMapRequests.get(uuid);
        if (preparingFieldMaps.contains(uuid) || previous != null && tick - previous < 10) return;
        WarzoneChunkGenerator generator = WarzoneDimension.generatorOf(player.getServerWorld());
        if (generator == null) return;
        WarzoneTerrain terrain = generator.terrain();
        if (TacticalMapData.discover(terrain, player.getX(), player.getZ(), raid.discovered)) state.markDirty();
        Set<Integer> discoveries = Set.copyOf(raid.discovered);
        long rotation = raid.rotation;
        fieldMapRequests.put(uuid, tick);
        preparingFieldMaps.add(uuid);
        // Extraction is already revealed by the deployment briefing. No live entity data is sent.
        CompletableFuture.supplyAsync(() -> TacticalMapData.fieldMap(terrain, discoveries,
                DeploymentPlanner.extraction(terrain)), mapWorker).whenComplete((data, error) -> server.execute(() -> {
            preparingFieldMaps.remove(uuid);
            if (!isAvailable(server) || server.getPlayerManager().getPlayer(uuid) != player
                    || !player.isAlive() || player.isSpectator() || !raid.active || raid.rotation != rotation
                    || !WarzoneDimension.isWarzone(player.getServerWorld())) return;
            if (error != null) {
                LOGGER.error("Unable to prepare field map", error);
                message(player, "unavailable", false);
                return;
            }
            RaidNetworking.sendFieldMap(player, data);
        }));
    }

    private static boolean validChoices(WarzoneTerrain terrain, List<DeploymentPlanner.Point> points) {
        if (points.size() != 3 || points.stream().map(DeploymentPlanner.Point::id).distinct().count() != 3) return false;
        for (int i = 0; i < 3; i++) {
            DeploymentPlanner.Point p = points.get(i);
            if (p.id() < 1 || p.id() > 3 || p.danger() < 0 || p.danger() > 100
                    || p.label() == null || p.label().isEmpty() || p.label().length() > 128
                    || !DeploymentPlanner.isSafeCandidate(terrain, p.x(), p.z())
                    || p.y() != terrain.height(p.x(), p.z()) + 1) return false;
            for (int j = i + 1; j < 3; j++) {
                DeploymentPlanner.Point other = points.get(j);
                if (Math.hypot(p.x() - other.x(), p.z() - other.z()) < DeploymentPlanner.minimumSeparation(terrain)) return false;
            }
        }
        return true;
    }

    private static boolean canUseTable(ServerPlayerEntity player, BlockPos table) {
        return player.isAlive() && !player.isSpectator()
                && player.getServerWorld() == player.getServer().getOverworld()
                && table.equals(HubBuilder.TABLE)
                && player.squaredDistanceTo(Vec3d.ofCenter(table)) <= 64.0
                && player.getWorld().getBlockState(table).isOf(HubContent.MAP_TABLE);
    }

    public static void deploy(ServerPlayerEntity player, long rotation, int id) {
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUuid();
        if (!isAvailable(server) || !canUseTable(player, HubBuilder.TABLE)) return;
        Long session = mapSessions.get(uuid);
        if (session == null || server.getTicks() - session > 20 * 300) return;
        RaidPersistentState state = RaidPersistentState.get(server);
        RaidPersistentState.PlayerRaid raid = state.player(uuid);
        if (raid.active || raid.rotation != rotation || pending.contains(uuid)) return;
        DeploymentPlanner.Point choice = raid.choices.stream().filter(p -> p.id() == id).findFirst().orElse(null);
        ServerWorld world = server.getWorld(WarzoneDimension.WORLD);
        if (choice == null || world == null) return;
        pending.add(uuid);
        message(player, "preparing", true);
        // Generate destination chunks through the engine's future; never run world access on a worker thread.
        DeploymentPlanner.Point extraction = DeploymentPlanner.extraction(WarzoneDimension.generatorOf(world).terrain());
        CompletableFuture.allOf(prepare(world, choice.x(), choice.z(), 6),
                prepare(world, extraction.x(), extraction.z(), 5)).whenComplete((ignored, error) -> server.execute(() -> {
            try {
                if (error != null) throw new IllegalStateException("Destination chunk preparation failed", error);
                if (!isAvailable(server) || server.getPlayerManager().getPlayer(uuid) != player || !canUseTable(player, HubBuilder.TABLE)
                        || raid.active || raid.rotation != rotation) return;
                BlockPos safe = findSafeLanding(world, choice.x(), choice.z(), choice.y(), 5);
                if (safe == null) { message(player, "unsafe", false); return; }
                markExtraction(world, state);
                raid.active = true;
                raid.selected = id;
                state.markDirty();
                try {
                    player.teleport(world, safe.getX() + .5, safe.getY(), safe.getZ() + .5, 180, 0);
                    player.setVelocity(Vec3d.ZERO); player.fallDistance = 0;
                    mapSessions.remove(uuid);
                    message(player, "deployed", false);
                } catch (RuntimeException e) {
                    raid.active = false; raid.selected = -1; state.markDirty(); throw e;
                }
            } catch (RuntimeException e) {
                LOGGER.error("Deployment failed for {}", uuid, e);
                message(player, "unavailable", false);
            } finally { pending.remove(uuid); }
        }));
    }

    private static CompletableFuture<Void> prepare(ServerWorld world, int x, int z, int radius) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                // The underlying getChunkFuture is private in Yarn 1.20.1; use its public main-thread entrypoint.
                futures.add(world.getChunkManager().getChunkFutureSyncOnMainThread(cx, cz, ChunkStatus.FULL, true)
                        .thenApply(result -> {
                            if (result.left().isEmpty()) throw new IllegalStateException("Chunk was not available");
                            return result;
                        }));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** Final geometry check includes actual trees, structures, fluids, full support and escape space. */
    public static BlockPos findSafeLanding(ServerWorld world, int x, int z, int expectedY, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int px = x + dx, pz = z + dz;
                // Start at the planned floor, including inside a roofed hub; a heightmap would pick its roof.
                int[] verticalOffsets = {0, 1, -1, 2, -2};
                for (int dy : verticalOffsets) {
                    BlockPos pos = new BlockPos(px, expectedY + dy, pz);
                    if (!safeCell(world, pos)) continue;
                    int exits = 0;
                    for (Direction direction : Direction.Type.HORIZONTAL) {
                        BlockPos next = pos.offset(direction);
                        if (safeCell(world, next) || safeCell(world, next.up()) || safeCell(world, next.down())) exits++;
                    }
                    if (exits < 2 || !world.getEntitiesByClass(HostileEntity.class, new Box(pos).expand(12), e -> e.isAlive()).isEmpty()) continue;
                    return pos;
                }
            }
        }
        return null;
    }

    private static boolean safeCell(ServerWorld world, BlockPos pos) {
        BlockState floor = world.getBlockState(pos.down());
        if (!floor.isSideSolidFullSquare(world, pos.down(), Direction.UP) || floor.isIn(BlockTags.LEAVES)
                || floor.isOf(Blocks.MAGMA_BLOCK) || floor.isOf(Blocks.CAMPFIRE) || floor.isOf(Blocks.SOUL_CAMPFIRE)
                || floor.isOf(Blocks.CACTUS) || !floor.getFluidState().isEmpty()) return false;
        for (int dy = 0; dy < 2; dy++) {
            BlockPos p = pos.up(dy); BlockState s = world.getBlockState(p);
            if (!s.getFluidState().isEmpty() || !s.getCollisionShape(world, p).isEmpty()
                    || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.SWEET_BERRY_BUSH)
                    || s.isOf(Blocks.POWDER_SNOW)) return false;
        }
        return true;
    }

    private static void markExtraction(ServerWorld world, RaidPersistentState state) {
        if (state.extractionMarked) return;
        DeploymentPlanner.Point point = DeploymentPlanner.extraction(WarzoneDimension.generatorOf(world).terrain());
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (Math.abs(dx) != 4 && Math.abs(dz) != 4) continue;
            BlockPos pos = new BlockPos(point.x() + dx, point.y() - 1, point.z() + dz);
            world.setBlockState(pos, Blocks.YELLOW_CONCRETE.getDefaultState(), 3);
        }
        state.extractionMarked = true; state.markDirty();
    }

    public static boolean requestExtraction(ServerPlayerEntity player) {
        if (!isManaged(player.getServer())) return false;
        message(player, "extract_hint", false);
        return true;
    }

    private static void tick(MinecraftServer server) {
        if (!isAvailable(server)) return;
        ServerWorld world = server.getWorld(WarzoneDimension.WORLD);
        WarzoneChunkGenerator generator = WarzoneDimension.generatorOf(world);
        if (generator == null) return;
        RaidPersistentState state = RaidPersistentState.get(server);
        WarzoneTerrain terrain = generator.terrain();
        DeploymentPlanner.Point exit = DeploymentPlanner.extraction(terrain);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            RaidPersistentState.PlayerRaid raid = state.player(uuid);
            if (raid.active && player.getServerWorld() != world) {
                raid.finish(false);
                state.markDirty();
            }
            if (!raid.active || player.getServerWorld() != world || !player.isAlive()) {
                extracting.remove(uuid); continue;
            }
            if (server.getTicks() % 40 == 0) {
                if (TacticalMapData.discover(terrain, player.getX(), player.getZ(), raid.discovered)) state.markDirty();
            }
            double dx = player.getX() - (exit.x() + .5), dz = player.getZ() - (exit.z() + .5);
            boolean inside = dx * dx + dz * dz <= EXTRACTION_RADIUS * EXTRACTION_RADIUS
                    && Math.abs(player.getY() - exit.y()) <= 3 && player.isOnGround();
            boolean interrupted = inside && (player.hurtTime > 0 || !world.getEntitiesByClass(HostileEntity.class,
                    player.getBoundingBox().expand(8), e -> e.isAlive()).isEmpty());
            if (!inside || interrupted) {
                if (extracting.remove(uuid) != null) message(player, "extract_cancelled", true);
                continue;
            }
            int remaining = extracting.getOrDefault(uuid, EXTRACTION_TICKS);
            if (remaining % 20 == 0) player.sendMessage(Text.translatable("raid.bobin_craft.extracting", remaining / 20), true);
            if (remaining <= 0) {
                if (returnToHub(player)) {
                    raid.finish(true); state.markDirty();
                    message(player, "extracted", false);
                }
                extracting.remove(uuid);
            } else extracting.put(uuid, remaining - 1);
        }
    }

    private static boolean returnToHub(ServerPlayerEntity player) {
        ServerWorld hub = player.getServer().getOverworld();
        BlockPos safe = findSafeLanding(hub, HubBuilder.SPAWN.getX(), HubBuilder.SPAWN.getZ(), HubBuilder.SPAWN.getY(), 4);
        if (safe == null) { message(player, "hub_blocked", false); return false; }
        player.teleport(hub, safe.getX() + .5, safe.getY(), safe.getZ() + .5, 180, 0);
        player.setSpawnPoint(hub.getRegistryKey(), HubBuilder.SPAWN, 180, true, false);
        player.setVelocity(Vec3d.ZERO); player.fallDistance = 0;
        return true;
    }

    private static void message(ServerPlayerEntity player, String key, boolean actionBar) {
        player.sendMessage(Text.translatable("raid.bobin_craft." + key), actionBar);
    }
}
