package com.bobincraft.raid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/** Finite forest island with a level, reserved military-compound clearing. */
public final class HubChunkGenerator extends ChunkGenerator {
    public static final int FLOOR_Y = 70;
    public static final int RADIUS = 104;
    public static final int BEDROCK_Y = FLOOR_Y - 5;
    private static final int CLEARING_RADIUS = 60;
    public static final Codec<HubChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource)
    ).apply(instance, instance.stable(HubChunkGenerator::new)));

    public HubChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    public static boolean inside(int x, int z) {
        return x >= -RADIUS && x <= RADIUS && z >= -RADIUS && z <= RADIUS;
    }

    /** Ground stays level beneath the compound, gates, and cleared perimeter. */
    public static int terrainHeight(int x, int z) {
        if (!inside(x, z)) return BEDROCK_Y - 1;
        int distance = Math.max(Math.abs(x), Math.abs(z));
        if (distance <= CLEARING_RADIUS) return FLOOR_Y;
        double blend = Math.min(1.0, (distance - CLEARING_RADIUS) / 12.0);
        double hills = smoothNoise(x, z, 15, 19) * 2.2 + smoothNoise(x, z, 7, 71) * 0.9;
        return FLOOR_Y + (int) Math.round(hills * blend);
    }

    public static BlockState terrainState(int x, int y, int z) {
        int top = terrainHeight(x, z);
        if (!inside(x, z) || y < BEDROCK_Y || y > top) return Blocks.AIR.getDefaultState();
        if (y == BEDROCK_Y) return Blocks.BEDROCK.getDefaultState();
        if (y < top - 2) return Blocks.STONE.getDefaultState();
        if (y < top) return Blocks.DIRT.getDefaultState();
        double patch = smoothNoise(x, z, 5, 211);
        if (patch > 0.47) return Blocks.PODZOL.getDefaultState();
        if (patch < -0.5) return Blocks.COARSE_DIRT.getDefaultState();
        if (patch > 0.15 && hash(x, z, 131) % 7 == 0) return Blocks.MOSS_BLOCK.getDefaultState();
        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    /** Coordinate-only noise keeps reloads and chunk generation order identical. */
    static long hash(int x, int z, long salt) {
        long value = x * 341873128712L + z * 132897987541L + salt * 42317861L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return (value ^ (value >>> 31)) & Long.MAX_VALUE;
    }

    private static double smoothNoise(int x, int z, int spacing, long salt) {
        int gridX = Math.floorDiv(x, spacing);
        int gridZ = Math.floorDiv(z, spacing);
        double dx = Math.floorMod(x, spacing) / (double) spacing;
        double dz = Math.floorMod(z, spacing) / (double) spacing;
        dx = dx * dx * (3.0 - 2.0 * dx);
        dz = dz * dz * (3.0 - 2.0 * dz);
        double a = noiseValue(gridX, gridZ, salt);
        double b = noiseValue(gridX + 1, gridZ, salt);
        double c = noiseValue(gridX, gridZ + 1, salt);
        double d = noiseValue(gridX + 1, gridZ + 1, salt);
        return (a + (b - a) * dx) * (1.0 - dz) + (c + (d - c) * dx) * dz;
    }

    private static double noiseValue(int x, int z, long salt) {
        return (hash(x, z, salt) & 65535L) / 32767.5 - 1.0;
    }

    @Override
    public StructurePlacementCalculator createStructurePlacementCalculator(RegistryWrapper<StructureSet> registry,
                                                                          NoiseConfig noiseConfig, long seed) {
        return StructurePlacementCalculator.create(noiseConfig, seed, biomeSource, Stream.empty());
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender, NoiseConfig noiseConfig,
                                                 StructureAccessor structures, Chunk chunk) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Heightmap floor = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);
        int x0 = chunk.getPos().getStartX();
        int z0 = chunk.getPos().getStartZ();
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                if (!inside(x, z)) continue;
                for (int y = BEDROCK_Y; y <= terrainHeight(x, z); y++) {
                    BlockState state = terrainState(x, y, z);
                    chunk.setBlockState(pos.set(x, y, z), state, false);
                    floor.trackUpdate(x & 15, y, z & 15, state);
                    surface.trackUpdate(x & 15, y, z & 15, state);
                }
            }
        }
        HubForestDecorator.decorate(x0, z0, x0 + 15, z0 + 15, (x, y, z, state) -> {
            pos.set(x, y, z);
            if (!HubForestDecorator.shouldReplace(chunk.getBlockState(pos), state)) return;
            chunk.setBlockState(pos, state, false);
            floor.trackUpdate(x & 15, y, z & 15, state);
            surface.trackUpdate(x & 15, y, z & 15, state);
        });
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void carve(ChunkRegion region, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
                      StructureAccessor structures, Chunk chunk, GenerationStep.Carver step) { }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) { }

    @Override
    public void populateEntities(ChunkRegion region) { }

    @Override
    public int getWorldHeight() { return 384; }

    @Override
    public int getMinimumY() { return -64; }

    @Override
    public int getSeaLevel() { return FLOOR_Y; }

    @Override
    public int getHeight(int x, int z, Heightmap.Type type, HeightLimitView world, NoiseConfig noiseConfig) {
        if (!inside(x, z)) return world.getBottomY();
        BlockState[] states = columnStates(x, z, world);
        for (int i = states.length - 1; i >= 0; i--) {
            if (type.getBlockPredicate().test(states[i])) return world.getBottomY() + i + 1;
        }
        return world.getBottomY();
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        return new VerticalBlockSample(world.getBottomY(), columnStates(x, z, world));
    }

    private static BlockState[] columnStates(int x, int z, HeightLimitView world) {
        BlockState[] states = new BlockState[world.getHeight()];
        Arrays.fill(states, Blocks.AIR.getDefaultState());
        if (!inside(x, z)) return states;
        for (int y = BEDROCK_Y; y <= terrainHeight(x, z); y++) {
            int index = y - world.getBottomY();
            if (index >= 0 && index < states.length) states[index] = terrainState(x, y, z);
        }
        HubForestDecorator.decorate(x, z, x, z, (px, y, pz, state) -> {
            int index = y - world.getBottomY();
            if (index >= 0 && index < states.length && HubForestDecorator.shouldReplace(states[index], state)) {
                states[index] = state;
            }
        });
        return states;
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("Bobin Craft: forest military hub / finite safe staging area");
    }
}
