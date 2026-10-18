package com.bobincraft.smoke;

import com.bobincraft.raid.HubChunkGenerator;
import com.bobincraft.raid.HubForestDecorator;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.chunk.VerticalBlockSample;

import java.util.HashMap;
import java.util.Map;

import java.util.Objects;
import java.util.function.Supplier;

final class HubForestChecks {
    static int assertions;
    static void run() {
        initializeBlocks();
        HubForestChecks suite = new HubForestChecks();
        suite.chunkClippingRecreatesTheSameForestAcrossPositiveAndNegativeSeams();
        suite.forestCannotEnterTheCompoundOrTheClearedPalisadePerimeter();
        suite.terrainIsFiniteSupportedAndLevelUnderTheEntireCompound();
        suite.columnSamplesAndHeightQueriesMatchTheChunkDecorationPlan();
    }
    private static void assertTrue(boolean value) { assertTrue(value, "Expected true"); }
    private static void assertTrue(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void assertTrue(boolean value, Supplier<String> message) { assertions++; if (!value) throw new AssertionError(message.get()); }
    private static void assertFalse(boolean value) { assertTrue(!value); }
    private static void assertFalse(boolean value, String message) { assertTrue(!value, message); }
    private static void assertEquals(Object expected, Object actual, String message) {
        assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }

    private static Map<Long, BlockState> fullForest;

    static void initializeBlocks() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        fullForest = new HashMap<>();
        decorateInto(fullForest, -120, -120, 120, 120);
        assertFalse(fullForest.isEmpty(), "The forest ring must contain decoration");
    }

    void chunkClippingRecreatesTheSameForestAcrossPositiveAndNegativeSeams() {
        Map<Long, BlockState> chunkForest = new HashMap<>();
        // Reverse chunk order also proves that adjacent chunks never write each other.
        for (int chunkX = 7; chunkX >= -8; chunkX--) {
            for (int chunkZ = 7; chunkZ >= -8; chunkZ--) {
                int minX = chunkX * 16;
                int minZ = chunkZ * 16;
                decorateInto(chunkForest, minX, minZ, minX + 15, minZ + 15);
            }
        }
        assertEquals(fullForest, chunkForest,
                "Generating chunks independently must preserve trunks, canopy seams, and props");
        assertTrue(fullForest.keySet().stream().map(BlockPos::fromLong).anyMatch(p -> p.getX() < 0 && p.getZ() < 0));
        assertTrue(fullForest.keySet().stream().map(BlockPos::fromLong).anyMatch(p -> p.getX() >= 0 && p.getZ() >= 0));
    }

    void forestCannotEnterTheCompoundOrTheClearedPalisadePerimeter() {
        fullForest.forEach((packed, state) -> {
            BlockPos pos = BlockPos.fromLong(packed);
            assertTrue(HubChunkGenerator.inside(pos.getX(), pos.getZ()), () -> "Outside finite terrain: " + pos);
            assertTrue(Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ())) >= HubForestDecorator.FOREST_START,
                    () -> "Decoration entered the reserved perimeter: " + pos);
            assertTrue(pos.getY() > HubChunkGenerator.terrainHeight(pos.getX(), pos.getZ()),
                    () -> "Decoration overwrote solid terrain: " + pos);
        });
        Map<Long, BlockState> clearing = new HashMap<>();
        decorateInto(clearing, -62, -62, 62, 62);
        assertTrue(clearing.isEmpty(), "The compound and its eleven-block outer clearance must stay unobstructed");
    }

    void terrainIsFiniteSupportedAndLevelUnderTheEntireCompound() {
        int minimumForestHeight = Integer.MAX_VALUE;
        int maximumForestHeight = Integer.MIN_VALUE;
        for (int x = -HubChunkGenerator.RADIUS; x <= HubChunkGenerator.RADIUS; x++) {
            for (int z = -HubChunkGenerator.RADIUS; z <= HubChunkGenerator.RADIUS; z++) {
                assertTrue(HubChunkGenerator.inside(x, z));
                assertTrue(HubChunkGenerator.terrainState(x, HubChunkGenerator.BEDROCK_Y, z).isOf(Blocks.BEDROCK));
                int ground = HubChunkGenerator.terrainHeight(x, z);
                assertFalse(HubChunkGenerator.terrainState(x, ground, z).isAir());
                assertTrue(HubChunkGenerator.terrainState(x, ground + 1, z).isAir());
                if (Math.max(Math.abs(x), Math.abs(z)) <= 60) {
                    assertEquals(HubChunkGenerator.FLOOR_Y, ground, "Compound, paths, gates, and moat require level ground");
                } else {
                    minimumForestHeight = Math.min(minimumForestHeight, ground);
                    maximumForestHeight = Math.max(maximumForestHeight, ground);
                }
            }
        }
        assertTrue(minimumForestHeight < HubChunkGenerator.FLOOR_Y);
        assertTrue(maximumForestHeight > HubChunkGenerator.FLOOR_Y);
        for (int edge : new int[]{-105, 105, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertFalse(HubChunkGenerator.inside(edge, 0));
            assertFalse(HubChunkGenerator.inside(0, edge));
            assertTrue(HubChunkGenerator.terrainState(edge, HubChunkGenerator.FLOOR_Y, 0).isAir());
        }
    }

    void columnSamplesAndHeightQueriesMatchTheChunkDecorationPlan() {
        // These methods are independent of biome lookup; no world or server is needed.
        HubChunkGenerator generator = new HubChunkGenerator(null);
        HeightLimitView height = HeightLimitView.create(-64, 384);
        int[] coordinates = {-105, -104, -97, -96, -81, -80, -65, -64, -63, -52, 0, 52, 63, 64, 79, 80, 95, 96, 104, 105};
        for (int x : coordinates) {
            for (int z : coordinates) {
                VerticalBlockSample sample = generator.getColumnSample(x, z, height, null);
                for (int y = HubChunkGenerator.BEDROCK_Y - 1; y <= HubChunkGenerator.FLOOR_Y + 15; y++) {
                    BlockState expected = fullForest.getOrDefault(BlockPos.asLong(x, y, z), HubChunkGenerator.terrainState(x, y, z));
                    assertEquals(expected, sample.getState(y), "Column differs from chunk plan at " + new BlockPos(x, y, z));
                }
                for (Heightmap.Type type : Heightmap.Type.values()) {
                    int expectedHeight = height.getBottomY();
                    for (int y = height.getTopY() - 1; y >= height.getBottomY(); y--) {
                        if (type.getBlockPredicate().test(sample.getState(y))) {
                            expectedHeight = y + 1;
                            break;
                        }
                    }
                    assertEquals(expectedHeight, generator.getHeight(x, z, type, height, null),
                            "Height differs from generated column at " + x + ", " + z + " for " + type);
                }
            }
        }
    }

    private static void decorateInto(Map<Long, BlockState> result, int minX, int minZ, int maxX, int maxZ) {
        HubForestDecorator.decorate(minX, minZ, maxX, maxZ, (x, y, z, state) -> {
            assertTrue(x >= minX && x <= maxX && z >= minZ && z <= maxZ, "Decorator wrote outside its chunk");
            long key = BlockPos.asLong(x, y, z);
            BlockState old = result.getOrDefault(key, Blocks.AIR.getDefaultState());
            if (HubForestDecorator.shouldReplace(old, state)) result.put(key, state);
        });
    }
}
