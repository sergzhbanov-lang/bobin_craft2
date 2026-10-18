package com.bobincraft.raid;

import com.bobincraft.raid.guard.WarzoneGuardEntity;
import com.bobincraft.warzone.WarzoneBasePlan;
import com.bobincraft.warzone.WarzoneChunkGenerator;
import com.bobincraft.warzone.WarzoneDimension;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Finite, lazy encounters: each generated site's defenders can be activated exactly once per world. */
public final class WarzoneEncounterManager {
    public static final EntityType<WarzoneGuardEntity> GUARD = Registry.register(Registries.ENTITY_TYPE,
            new Identifier("bobin_craft", "warzone_guard"),
            FabricEntityTypeBuilder.<WarzoneGuardEntity>create(SpawnGroup.MONSTER, WarzoneGuardEntity::new)
                    .dimensions(EntityDimensions.changing(0.6F, 1.95F)).trackRangeChunks(8).trackedUpdateRate(3).build());

    public enum AlarmState { CALM, SUSPICIOUS, ALERTED, LOCKDOWN }
    private record Alarm(AlarmState state, long expires) {}
    private record PreparedGuard(WarzoneGuardEntity entity, WarzoneBasePlan.TacticalSpot spot, BlockPos position) {}
    private static final Map<ServerWorld, Map<Integer, Alarm>> ALARMS = new WeakHashMap<>();
    private static final Map<ServerWorld, GunfireThrottle> GUNFIRE = new WeakHashMap<>();
    private static final int ACTIVATION_RADIUS = 112;
    private WarzoneEncounterManager() {}

    public static void register() {
        FabricDefaultAttributeRegistry.register(GUARD, PillagerEntity.createPillagerAttributes());
        // Encounters belong to the combat dimension, including legacy command-based entry.
        // The hub/raid lifecycle may be inactive in ordinary or existing Minecraft saves.
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (world.getServer().getTicks() % 20 == 0) tick(world);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("bobin")
                        .then(CommandManager.literal("warzone")
                                .then(CommandManager.literal("encounters").executes(context -> diagnose(context.getSource()))))));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ALARMS.clear();
            GUNFIRE.clear();
        });
    }

    /** Read-only diagnostics: explains missing defenders without resetting a site's persistent state. */
    private static int diagnose(ServerCommandSource source) {
        ServerWorld world = source.getServer().getWorld(WarzoneDimension.WORLD);
        WarzoneChunkGenerator generator = WarzoneDimension.generatorOf(world);
        if (generator == null) {
            source.sendError(Text.literal("Warzone dimension/generator is unavailable."));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayer();
        var terrain = generator.terrain();
        double x = player != null && player.getServerWorld() == world ? player.getX() : terrain.landingX();
        double z = player != null && player.getServerWorld() == world ? player.getZ() : terrain.landingZ();
        source.sendFeedback(() -> Text.literal("Warzone encounters: difficulty=" + world.getDifficulty()
                + ", creative=" + (player != null && player.isCreative())
                + ", spectator=" + (player != null && player.isSpectator())
                + ", inWarzone=" + (player != null && player.getServerWorld() == world)), false);
        WarzoneBasePlan.Site site = terrain.basePlan().nearestSite(x, z);
        if (site == null) {
            source.sendFeedback(() -> Text.literal("No planned military sites exist in this arena."), false);
            return 1;
        }
        List<WarzoneBasePlan.TacticalSpot> defenders = site.spots.stream()
                .filter(spot -> spot.role() != WarzoneBasePlan.SpotRole.PATROL_WAYPOINT).toList();
        long loaded = defenders.stream().filter(spot -> world.isChunkLoaded(new BlockPos(spot.x(), spot.y(), spot.z()))).count();
        RaidPersistentState state = RaidPersistentState.get(source.getServer());
        double radius = site.flatRadius + 24;
        int alive = world.getEntitiesByClass(WarzoneGuardEntity.class,
                new Box(site.cx - radius, world.getBottomY(), site.cz - radius,
                        site.cx + radius, world.getTopY(), site.cz + radius),
                guard -> guard.isAlive() && guard.siteId() == site.id).size();
        source.sendFeedback(() -> Text.literal("Nearest site #" + site.id + " " + site.callsign
                + " at " + site.cx + ", " + site.cz + ": distance=" + Math.round(site.distanceTo(x, z))
                + "/" + ACTIVATION_RADIUS + ", loaded defender positions=" + loaded + "/" + defenders.size()
                + ", activated=" + state.activatedEncounters.contains(site.id) + ", loaded living guards=" + alive
                + ", supported save=" + state.isSupported() + ", hub initialized=" + state.hubInitialized), false);
        return 1;
    }

    /** Called once per second by the world tick, never during chunk generation. */
    public static void tick(ServerWorld world) {
        if (!world.getRegistryKey().equals(WarzoneDimension.WORLD) || world.getPlayers().isEmpty()
                || world.getDifficulty() == Difficulty.PEACEFUL) return;
        if (!(world.getChunkManager().getChunkGenerator() instanceof WarzoneChunkGenerator generator)) return;
        RaidPersistentState persistent = RaidPersistentState.get(world.getServer());
        if (!persistent.isSupported()) return;
        // Missing/corrupt hub initialization must not recreate encounters from an empty fallback save.
        if (RaidManager.isManaged(world.getServer()) && !persistent.hubInitialized) return;
        for (WarzoneBasePlan.Site site : generator.terrain().basePlan().sites()) {
            if (persistent.activatedEncounters.contains(site.id)) continue;
            // Creative players can inspect encounters; target selection still excludes them.
            boolean nearby = world.getPlayers().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                    && horizontalDistanceSquared(player.getX(), player.getZ(), site.cx, site.cz) <= ACTIVATION_RADIUS * ACTIVATION_RADIUS);
            if (!nearby) continue;
            List<WarzoneBasePlan.TacticalSpot> defenders = site.spots.stream()
                    .filter(spot -> spot.role() != WarzoneBasePlan.SpotRole.PATROL_WAYPOINT).toList();
            // Never generate remote chunks, and never activate only half of a compound.
            if (defenders.stream().anyMatch(spot -> !world.isChunkLoaded(new BlockPos(spot.x(), spot.y(), spot.z())))) continue;
            List<BlockPos> route = site.spots.stream().filter(spot -> spot.role() == WarzoneBasePlan.SpotRole.PATROL_WAYPOINT)
                    .map(spot -> new BlockPos(spot.x(), spot.y(), spot.z())).limit(16).toList();
            List<PreparedGuard> prepared = new ArrayList<>();
            for (WarzoneBasePlan.TacticalSpot spot : defenders) {
                WarzoneGuardEntity guard = GUARD.create(world);
                if (guard == null) continue;
                BlockPos spawn = safeSpawn(world, guard, spot);
                if (spawn == null) continue;
                prepared.add(new PreparedGuard(guard, spot, spawn));
            }
            // A ruined/blocked site can become usable later, so do not consume an empty activation.
            if (prepared.isEmpty()) continue;
            persistent.activatedEncounters.add(site.id);
            persistent.markDirty();
            for (PreparedGuard entry : prepared) {
                WarzoneGuardEntity guard = entry.entity;
                BlockPos spawn = entry.position;
                WarzoneBasePlan.TacticalSpot spot = entry.spot;
                guard.refreshPositionAndAngles(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, spot.yaw(), 0.0F);
                guard.setHeadYaw(spot.yaw());
                guard.assign(site, spot, route);
                world.spawnEntity(guard);
            }
        }
        Map<Integer, Alarm> alarms = ALARMS.get(world);
        if (alarms != null) alarms.values().removeIf(alarm -> alarm.expires < world.getTime());
    }

    private static BlockPos safeSpawn(ServerWorld world, WarzoneGuardEntity guard, WarzoneBasePlan.TacticalSpot spot) {
        // Start at the actual blueprint floor, preserving sentries on tower roofs.
        for (int radius = 0; radius <= 2; radius++) {
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        BlockPos feet = new BlockPos(spot.x() + dx, spot.y() + dy, spot.z() + dz);
                        if (!world.isChunkLoaded(feet)) continue;
                        if (world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()) continue;
                        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.down()).isEmpty()) continue;
                        Box box = new Box(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2,
                                feet.getX() + 0.8, feet.getY() + 1.95, feet.getZ() + 0.8);
                        if (world.isSpaceEmpty(guard, box)) return feet;
                    }
                }
            }
        }
        return null;
    }

    /** Invoked only after a server-validated shot; spatially bounded, with no entity-wide scan. */
    public static void onGunfire(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        if (!world.getRegistryKey().equals(WarzoneDimension.WORLD)) return;
        if (!GUNFIRE.computeIfAbsent(world, ignored -> new GunfireThrottle()).accept(player.getUuid(), world.getTime())) return;
        Set<Integer> heardSites = new HashSet<>();
        for (WarzoneGuardEntity guard : world.getEntitiesByClass(WarzoneGuardEntity.class, player.getBoundingBox().expand(64),
                entity -> entity.isAlive() && entity.squaredDistanceTo(player) <= 64 * 64)) {
            guard.hearGunfire(player.getPos());
            heardSites.add(guard.siteId());
        }
        for (int site : heardSites) {
            // Repeated shots increase location alertness, but never reveal a target through walls.
            AlarmState previous = alarmState(world, site);
            raiseAlarm(world, site, previous == AlarmState.SUSPICIOUS ? AlarmState.ALERTED : AlarmState.SUSPICIOUS);
        }
    }

    public static void alertAllies(WarzoneGuardEntity reporter, Vec3d lastSeen) {
        if (!(reporter.getWorld() instanceof ServerWorld world)) return;
        AlarmState previous = alarmState(world, reporter.siteId());
        raiseAlarm(world, reporter.siteId(), previous.ordinal() >= AlarmState.ALERTED.ordinal() ? AlarmState.LOCKDOWN : AlarmState.ALERTED);
        for (WarzoneGuardEntity ally : world.getEntitiesByClass(WarzoneGuardEntity.class, reporter.getBoundingBox().expand(36),
                entity -> entity != reporter && entity.isAlive() && entity.siteId() == reporter.siteId()
                        && entity.squaredDistanceTo(reporter) <= 36 * 36)) {
            ally.hearAllyAlert(lastSeen);
        }
    }

    public static AlarmState alarmState(ServerWorld world, int siteId) {
        Alarm alarm = ALARMS.getOrDefault(world, Map.of()).get(siteId);
        return alarm == null || alarm.expires < world.getTime() ? AlarmState.CALM : alarm.state;
    }

    private static void raiseAlarm(ServerWorld world, int siteId, AlarmState state) {
        Map<Integer, Alarm> alarms = ALARMS.computeIfAbsent(world, ignored -> new HashMap<>());
        Alarm previous = alarms.get(siteId);
        if (previous != null && previous.expires >= world.getTime() && previous.state.ordinal() > state.ordinal()) state = previous.state;
        alarms.put(siteId, new Alarm(state, world.getTime() + 400));
    }

    private static double horizontalDistanceSquared(double x, double z, double otherX, double otherZ) {
        double dx = x - otherX;
        double dz = z - otherZ;
        return dx * dx + dz * dz;
    }
}
