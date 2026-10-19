package com.bobincraft.warzone;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;

/** Sparse surface motion for revised rivers; the water blocks keep vanilla source-water behavior. */
public final class NaturalWaterEffects {
    private static final int TICK_INTERVAL = 6;
    private static final int MAX_SAMPLES = 32;
    private static final int MAX_PARTICLES = 12;
    private static final int SAMPLE_RADIUS = 18;

    private NaturalWaterEffects() { }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(NaturalWaterEffects::tick);
    }

    private static void tick(ServerWorld world) {
        if (world.getTime() % TICK_INTERVAL != 0) return;
        WarzoneChunkGenerator generator = WarzoneDimension.generatorOf(world);
        if (generator == null) return;
        WarzoneTerrain terrain = generator.terrain();
        if (!terrain.usesRevisedLandscape()) return;

        List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) return;
        long frame = world.getTime() / TICK_INTERVAL;
        int firstPlayer = (int) Math.floorMod(frame, players.size());
        int emitted = 0;
        BlockPos.Mutable surface = new BlockPos.Mutable();

        for (int attempt = 0; attempt < MAX_SAMPLES && emitted < MAX_PARTICLES; attempt++) {
            ServerPlayerEntity player = players.get((firstPlayer + attempt) % players.size());
            if (!player.isAlive() || (!terrain.usesLandscapeRivers()
                    && Math.abs(player.getY() - WarzoneTerrain.SEA_LEVEL) > 24.0)) continue;

            long hash = WarzoneNoise.mix(terrain.seed ^ frame * 0x9E3779B97F4A7C15L ^ attempt * 0x632BE59BD9B4E019L);
            int x = player.getBlockX() + (int) Math.floor(WarzoneNoise.unit(hash) * (SAMPLE_RADIUS * 2 + 1)) - SAMPLE_RADIUS;
            hash = WarzoneNoise.mix(hash);
            int z = player.getBlockZ() + (int) Math.floor(WarzoneNoise.unit(hash) * (SAMPLE_RADIUS * 2 + 1)) - SAMPLE_RADIUS;
            if (!terrain.inside(x, z)) continue;

            // getWorldChunk is a nonblocking lookup: missing chunks never get loaded for decoration.
            WorldChunk chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
            if (chunk == null) continue;
            WarzoneTerrain.Sample sample = terrain.sample(x, z);
            int waterLevel = terrain.usesLandscapeRivers() ? sample.waterLevel : WarzoneTerrain.SEA_LEVEL;
            surface.set(x, waterLevel, z);
            if (!chunk.getBlockState(surface).isOf(Blocks.WATER)) continue;
            // WORLD_SURFACE includes roofs, bridges, leaves and lily pads, so covered water stays quiet.
            if (chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z) != waterLevel) continue;

            boolean rapids = sample.waterState == WarzoneTerrain.WaterState.RAPIDS;
            if (!rapids && sample.waterState != WarzoneTerrain.WaterState.FLOWING) continue;

            double px = x + 0.2 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 1)) * 0.6;
            double pz = z + 0.2 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 2)) * 0.6;
            double py = waterLevel + 0.91;
            if (player.squaredDistanceTo(px, py, pz) > 30.0 * 30.0) continue;

            // A count of zero sends one particle with this exact velocity, aligned to the channel.
            world.spawnParticles(ParticleTypes.BUBBLE_POP, px, py, pz, 0,
                    sample.flowX * 0.12, 0.012, sample.flowZ * 0.12, 1.0);
            emitted++;
            if (rapids && emitted < MAX_PARTICLES) {
                // Vanilla SPLASH honors horizontal velocity when its initial vertical velocity is zero.
                world.spawnParticles(ParticleTypes.SPLASH, px, py, pz, 0,
                        sample.flowX * 0.16, 0.0, sample.flowZ * 0.16, 1.0);
                emitted++;
            }
        }
    }
}
