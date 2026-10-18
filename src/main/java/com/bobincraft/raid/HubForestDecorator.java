package com.bobincraft.raid;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.util.math.Direction;

/**
 * Coordinate-only forest decoration, written directly into the generating chunk.
 * Every query includes nearby anchors, so trees crossing a chunk edge are complete
 * regardless of chunk generation order. Nothing touches a live ServerWorld.
 */
public final class HubForestDecorator {
    public static final int FOREST_START = 63;
    private static final int TREE_GRID = 6;
    private static final int TREE_REACH = 4;
    private static final BlockState SPRUCE_LEAVES = Blocks.SPRUCE_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
    private static final BlockState OAK_LEAVES = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);

    @FunctionalInterface
    public interface BlockPlacer {
        void place(int x, int y, int z, BlockState state);
    }

    private HubForestDecorator() { }

    public static void decorate(int minX, int minZ, int maxX, int maxZ, BlockPlacer placer) {
        if (maxX < -HubChunkGenerator.RADIUS || minX > HubChunkGenerator.RADIUS
                || maxZ < -HubChunkGenerator.RADIUS || minZ > HubChunkGenerator.RADIUS) return;
        if (minX > -FOREST_START && maxX < FOREST_START && minZ > -FOREST_START && maxZ < FOREST_START) return;
        BlockPlacer clipped = (x, y, z, state) -> {
            if (x < minX || x > maxX || z < minZ || z > maxZ || !HubChunkGenerator.inside(x, z)) return;
            if (Math.max(Math.abs(x), Math.abs(z)) < FOREST_START || y <= HubChunkGenerator.terrainHeight(x, z)) return;
            placer.place(x, y, z, state);
        };

        for (int gx = Math.floorDiv(minX - TREE_REACH, TREE_GRID); gx <= Math.floorDiv(maxX + TREE_REACH, TREE_GRID); gx++) {
            for (int gz = Math.floorDiv(minZ - TREE_REACH, TREE_GRID); gz <= Math.floorDiv(maxZ + TREE_REACH, TREE_GRID); gz++) {
                long seed = HubChunkGenerator.hash(gx, gz, 5003);
                int x = gx * TREE_GRID + (int) (seed % TREE_GRID);
                int z = gz * TREE_GRID + (int) ((seed >>> 8) % TREE_GRID);
                int distance = Math.max(Math.abs(x), Math.abs(z));
                // The nearest canopy remains eleven blocks from the palisade.
                if (distance < FOREST_START + TREE_REACH || distance > HubChunkGenerator.RADIUS - TREE_REACH) continue;
                int style = (int) ((seed >>> 16) % 12);
                if (style < 8) spruce(clipped, x, z, 7 + (int) ((seed >>> 24) % 4));
                else if (style < 10) oak(clipped, x, z, 5 + (int) ((seed >>> 24) % 3));
                else bush(clipped, x, z);
            }
        }

        for (int gx = Math.floorDiv(minX - 3, 7); gx <= Math.floorDiv(maxX + 3, 7); gx++) {
            for (int gz = Math.floorDiv(minZ - 3, 7); gz <= Math.floorDiv(maxZ + 3, 7); gz++) {
                long seed = HubChunkGenerator.hash(gx, gz, 8117);
                int x = gx * 7 + (int) (seed % 7);
                int z = gz * 7 + (int) ((seed >>> 8) % 7);
                int distance = Math.max(Math.abs(x), Math.abs(z));
                if (distance < FOREST_START + 3 || distance > HubChunkGenerator.RADIUS - 3) continue;
                int style = (int) ((seed >>> 16) % 10);
                if (style == 0) fallenLog(clipped, x, z, (seed & 128) == 0);
                else if (style < 3) rock(clipped, x, z, (seed & 128) == 0);
            }
        }

        for (int x = Math.max(minX, -HubChunkGenerator.RADIUS); x <= Math.min(maxX, HubChunkGenerator.RADIUS); x++) {
            for (int z = Math.max(minZ, -HubChunkGenerator.RADIUS); z <= Math.min(maxZ, HubChunkGenerator.RADIUS); z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) < FOREST_START) continue;
                long seed = HubChunkGenerator.hash(x, z, 9133);
                int variant = (int) (seed % 16);
                BlockState plant = variant < 4 ? Blocks.FERN.getDefaultState()
                        : variant < 7 ? Blocks.GRASS.getDefaultState()
                        : variant == 7 ? Blocks.BROWN_MUSHROOM.getDefaultState()
                        : variant == 8 ? Blocks.MOSS_CARPET.getDefaultState() : null;
                if (plant != null) clipped.place(x, HubChunkGenerator.terrainHeight(x, z) + 1, z, plant);
            }
        }
    }

    private static void spruce(BlockPlacer placer, int x, int z, int height) {
        int ground = HubChunkGenerator.terrainHeight(x, z);
        for (int level = 2; level <= height; level++) {
            int fromTop = height - level;
            int radius = fromTop == 0 ? 0 : Math.min(3, 1 + fromTop / 3);
            if ((fromTop & 1) == 0 && radius > 1) radius--;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 0) continue;
                    placer.place(x + dx, ground + level, z + dz, SPRUCE_LEAVES);
                }
            }
        }
        for (int y = 1; y < height; y++) placer.place(x, ground + y, z, Blocks.SPRUCE_LOG.getDefaultState());
    }

    private static void oak(BlockPlacer placer, int x, int z, int height) {
        int ground = HubChunkGenerator.terrainHeight(x, z);
        for (int level = height - 2; level <= height + 1; level++) {
            int radius = level >= height ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && ((dx + dz + level) & 1) == 0) continue;
                    placer.place(x + dx, ground + level, z + dz, OAK_LEAVES);
                }
            }
        }
        for (int y = 1; y <= height; y++) placer.place(x, ground + y, z, Blocks.OAK_LOG.getDefaultState());
    }

    private static void bush(BlockPlacer placer, int x, int z) {
        int ground = HubChunkGenerator.terrainHeight(x, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) placer.place(x + dx, ground + 1, z + dz, OAK_LEAVES);
        }
        placer.place(x, ground + 2, z, OAK_LEAVES);
        placer.place(x, ground + 1, z, Blocks.OAK_LOG.getDefaultState());
    }

    private static void fallenLog(BlockPlacer placer, int x, int z, boolean alongX) {
        BlockState log = Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, alongX ? Direction.Axis.X : Direction.Axis.Z);
        for (int offset = -2; offset <= 1; offset++) {
            int px = x + (alongX ? offset : 0);
            int pz = z + (alongX ? 0 : offset);
            placer.place(px, HubChunkGenerator.terrainHeight(px, pz) + 1, pz, log);
        }
    }

    private static void rock(BlockPlacer placer, int x, int z, boolean mossy) {
        BlockState stone = (mossy ? Blocks.MOSSY_COBBLESTONE : Blocks.ANDESITE).getDefaultState();
        placer.place(x, HubChunkGenerator.terrainHeight(x, z) + 1, z, stone);
        placer.place(x + 1, HubChunkGenerator.terrainHeight(x + 1, z) + 1, z, stone);
        placer.place(x, HubChunkGenerator.terrainHeight(x, z + 1) + 1, z + 1, stone);
    }

    /** Logs win over foliage at overlapping anchors; decoration never erases ground. */
    public static boolean shouldReplace(BlockState oldState, BlockState newState) {
        return priority(newState) > priority(oldState);
    }

    private static int priority(BlockState state) {
        if (state.isAir()) return 0;
        if (state.isOf(Blocks.OAK_LOG) || state.isOf(Blocks.SPRUCE_LOG)) return 50;
        if (state.isOf(Blocks.MOSSY_COBBLESTONE) || state.isOf(Blocks.ANDESITE)) return 40;
        if (state.getBlock() instanceof LeavesBlock) return 20;
        if (state.isOf(Blocks.FERN) || state.isOf(Blocks.GRASS) || state.isOf(Blocks.BROWN_MUSHROOM)
                || state.isOf(Blocks.MOSS_CARPET)) return 10;
        return 100;
    }
}
