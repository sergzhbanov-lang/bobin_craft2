package com.bobincraft.warzone;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

/** Chunk-local material adapter for the pure natural forest plans. */
final class NaturalForestDecor {
    private NaturalForestDecor() { }

    static void vegetation(WarzoneTerrain terrain, ChunkWriter writer, WarzoneTerrain.Sample[][] samples) {
        NaturalForest forest = terrain.naturalForest();
        for (NaturalForest.Plant plant : forest.plantsForChunk(writer.minX, writer.minZ)) {
            forest.shape(plant).forEach((dx, dy, dz, code) -> {
                int x = plant.x() + dx, y = plant.y() + dy, z = plant.z() + dz;
                if (!writer.contains(x, y, z) || terrain.isReserved(x, z)) return;
                BlockState old = writer.get(x, y, z);
                boolean soft = old.isAir() || old.getBlock() instanceof PlantBlock || old.isOf(Blocks.MOSS_CARPET);
                switch (NaturalForest.type(code)) {
                    case NaturalForest.LOG -> {
                        if (soft || old.isIn(BlockTags.LEAVES)) writer.set(x, y, z, log(code));
                    }
                    case NaturalForest.LEAF -> {
                        if (soft) writer.set(x, y, z, leaf(code));
                    }
                    case NaturalForest.ROCK -> {
                        if (soft) writer.set(x, y, z, boulder(terrain, plant, dy));
                    }
                    default -> throw new IllegalArgumentException("Unknown natural forest block: " + code);
                }
            });
        }
        floor(terrain, forest, writer, samples);
    }

    private static BlockState boulder(WarzoneTerrain terrain, NaturalForest.Plant plant, int dy) {
        if (!terrain.usesLandscapeRivers()) return ForestBlocks.MOSS_COVERED_ROCK.getDefaultState();
        double material = WarzoneNoise.unit(WarzoneNoise.mix(plant.seed() + 19));
        Block body = material < .28 ? ForestBlocks.MOSS_COVERED_ROCK : material < .53 ? ForestBlocks.LICHEN_ROCK
                : material < .77 ? ForestBlocks.LAYERED_SLATE : ForestBlocks.RIVER_STONE;
        if (dy > 0 && body == ForestBlocks.LAYERED_SLATE && material < .65) body = ForestBlocks.LICHEN_ROCK;
        return body.getDefaultState();
    }

    private static BlockState log(int code) {
        Block block = switch (NaturalForest.material(code)) {
            case NaturalForest.PINE -> ForestBlocks.DARK_PINE_WOOD;
            case NaturalForest.ROTTEN -> ForestBlocks.ROTTEN_WOOD;
            case NaturalForest.BIRCH -> Blocks.BIRCH_LOG;
            default -> ForestBlocks.WEATHERED_BARK;
        };
        Direction.Axis axis = switch (NaturalForest.axis(code)) {
            case NaturalForest.X -> Direction.Axis.X;
            case NaturalForest.Z -> Direction.Axis.Z;
            default -> Direction.Axis.Y;
        };
        return block.getDefaultState().with(PillarBlock.AXIS, axis);
    }

    private static BlockState leaf(int code) {
        Block block = NaturalForest.material(code) == NaturalForest.BIRCH ? Blocks.BIRCH_LEAVES : Blocks.SPRUCE_LEAVES;
        return block.getDefaultState().with(LeavesBlock.PERSISTENT, true);
    }

    /** Cluster fields keep short vegetation rich and varied without obstructing movement. */
    private static void floor(WarzoneTerrain terrain, NaturalForest forest, ChunkWriter writer, WarzoneTerrain.Sample[][] samples) {
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = writer.minX + lx, z = writer.minZ + lz;
            WarzoneTerrain.Sample sample = samples[lx + 1][lz + 1];
            if (!terrain.inside(x, z) || sample.edge < 8) continue;
            if (Math.hypot(x, z - terrain.landingZ()) <= 25) continue;
            int y = sample.height + 1;
            long hash = WarzoneNoise.hash(terrain.seed + 521, x, z);
            double choice = WarzoneNoise.unit(hash);
            double secondary = WarzoneNoise.unit(WarzoneNoise.mix(hash + 1));
            int waterLevel = terrain.usesLandscapeRivers() ? sample.waterLevel : WarzoneTerrain.SEA_LEVEL;
            if (sample.height < waterLevel) {
                if (terrain.usesRevisedLandscape()) waterPlants(terrain, writer, sample, x, z, choice, secondary);
                else {
                    if (sample.bog > .3 && sample.height >= WarzoneTerrain.SEA_LEVEL - 2
                            && choice < .03 && writer.get(x, WarzoneTerrain.SEA_LEVEL, z).isOf(Blocks.WATER))
                        writer.setIfAir(x, WarzoneTerrain.SEA_LEVEL + 1, z, Blocks.LILY_PAD.getDefaultState());
                    if (sample.height >= WarzoneTerrain.SEA_LEVEL - 3 && choice < .09
                            && writer.get(x, y, z).isOf(Blocks.WATER)) writer.set(x, y, z, Blocks.SEAGRASS.getDefaultState());
                }
                continue;
            }
            if (sample.mountain > .76) continue;
            BlockState soil = writer.get(x, sample.height, z);
            if (terrain.usesLandscapeRivers() && !terrain.isReserved(x, z)
                    && sample.height <= waterLevel + 3 && (sample.channel > .02 || sample.valley > .45)
                    && (soil.isOf(Blocks.GRAVEL) || soil.isOf(ForestBlocks.RIVER_STONE)
                    || soil.isOf(ForestBlocks.LAYERED_SLATE) || soil.isOf(Blocks.SAND)) && choice < .20) {
                pebbles(writer, x, y, z, secondary, false);
                continue;
            }
            boolean plantable = soil.isIn(BlockTags.DIRT) || soil.isOf(Blocks.GRASS_BLOCK) || soil.isOf(Blocks.MOSS_BLOCK)
                    || soil.isOf(ForestBlocks.DARK_FOREST_SOIL) || soil.isOf(ForestBlocks.PINE_NEEDLE_GROUND)
                    || soil.isOf(ForestBlocks.MOSSY_FOREST_SOIL) || soil.isOf(ForestBlocks.FOREST_LITTER);
            if (terrain.usesLandscapeRivers() && (soil.isOf(Blocks.MUD) || soil.isOf(Blocks.CLAY))) plantable = true;
            if (!plantable) continue;

            double routeStrength = Math.max(sample.road, sample.path);
            if (terrain.usesRevisedLandscape() && routeStrength > 0.04) {
                decorateRouteVerge(terrain, writer, sample, x, y, z, routeStrength, choice, secondary);
                continue;
            }
            if (routeStrength > .01) continue;

            if (terrain.usesRevisedLandscape() && (sample.channel > .02 || sample.valley > .45)
                    && sample.height <= waterLevel + 2) {
                decorateRiverbank(terrain, writer, sample, x, y, z, choice, secondary);
                continue;
            }
            if (sample.channel > .04) continue;

            double cover = forest.undergrowth(x, z);
            double bushCluster = patch(terrain.seed + 611, x, z, 11);
            double fernCluster = patch(terrain.seed + 617, x, z, 14);
            double dryCluster = patch(terrain.seed + 623, x, z, 13);

            if (forest.movementCorridor(x, z) || cover < .10) {
                if (choice < .11) {
                    writer.setIfAir(x, y, z, (secondary < .55 ? ForestBlocks.FOREST_GRASS
                            : secondary < .82 ? Blocks.GRASS : ForestBlocks.PALE_SEDGE).getDefaultState());
                }
                continue;
            }

            if (sample.bog > .22 && choice < .22 * cover) {
                if (secondary < .38) writer.setIfAir(x, y, z, ForestBlocks.PALE_SEDGE.getDefaultState());
                else if (secondary < .65) writer.setIfAir(x, y, z, Blocks.MOSS_CARPET.getDefaultState());
                else writer.setIfAir(x, y, z, ForestBlocks.LOW_FOREST_BUSH.getDefaultState());
            } else if (bushCluster > .62 && choice < .18 * cover) {
                if (secondary < .48) writer.setIfAir(x, y, z, ForestBlocks.LOW_FOREST_BUSH.getDefaultState());
                else writer.setIfAir(x, y, z, ForestBlocks.NEEDLE_UNDERGROWTH.getDefaultState());
            } else if (fernCluster > .54 && choice < .26 + .28 * cover) {
                if (secondary < (terrain.usesLandscapeRivers() ? .30 : .14) * cover) tall(writer, x, y, z, Blocks.LARGE_FERN);
                else if (secondary < .62) writer.setIfAir(x, y, z, Blocks.FERN.getDefaultState());
                else writer.setIfAir(x, y, z, ForestBlocks.NEEDLE_UNDERGROWTH.getDefaultState());
            } else if (dryCluster > .58 && choice < .24 + .26 * cover) {
                if (secondary < .42) writer.setIfAir(x, y, z, ForestBlocks.PALE_SEDGE.getDefaultState());
                else if (secondary < .82) writer.setIfAir(x, y, z, ForestBlocks.DRY_UNDERGROWTH.getDefaultState());
                else writer.setIfAir(x, y, z, Blocks.DEAD_BUSH.getDefaultState());
            } else if (choice < (terrain.usesLandscapeRivers() ? .32 + .40 * cover : .22 + .36 * cover)) {
                if (secondary < (terrain.usesLandscapeRivers() ? .42 : .14)) tall(writer, x, y, z, Blocks.TALL_GRASS);
                else if (secondary < (terrain.usesLandscapeRivers() ? .62 : .52)) writer.setIfAir(x, y, z, ForestBlocks.FOREST_GRASS.getDefaultState());
                else if (secondary < .78) writer.setIfAir(x, y, z, ForestBlocks.NEEDLE_UNDERGROWTH.getDefaultState());
                else writer.setIfAir(x, y, z, Blocks.GRASS.getDefaultState());
            } else if (choice > .982 && cover > .38) {
                writer.setIfAir(x, y, z, secondary < .7 ? Blocks.BROWN_MUSHROOM.getDefaultState() : Blocks.RED_MUSHROOM.getDefaultState());
            } else if (choice > .93 && cover > .50) {
                writer.setIfAir(x, y, z, Blocks.MOSS_CARPET.getDefaultState());
            }
        }
    }

    /** Blends path edges and median strips with tufts of grass, sedge, and low undergrowth without blocking travel. */
    private static void decorateRouteVerge(WarzoneTerrain terrain, ChunkWriter writer, WarzoneTerrain.Sample sample,
                                           int x, int y, int z, double strength, double choice, double secondary) {
        double cluster = patch(terrain.seed + 631, x, z, 7);
        // Center of wagon tracks or lightly worn trails gets occasional low grass/undergrowth tufts.
        if (strength >= 0.55) {
            if (sample.routeStyle == 0 && sample.routeWear < 0.36 && choice < 0.22 * cluster) {
                writer.setIfAir(x, y, z, (secondary < 0.65 ? ForestBlocks.FOREST_GRASS : ForestBlocks.PALE_SEDGE).getDefaultState());
            } else if (sample.trail && sample.routeWear < 0.42 && choice < 0.16 * cluster) {
                writer.setIfAir(x, y, z, (secondary < 0.55 ? ForestBlocks.NEEDLE_UNDERGROWTH : ForestBlocks.DRY_UNDERGROWTH).getDefaultState());
            }
            return;
        }
        // Irregular outer verges receive clustered shrubs, ferns, sedges, and dark forest grass.
        double vergeDensity = (1.0 - strength) * (0.4 + 0.6 * cluster);
        if (terrain.usesLandscapeRivers() && strength < .28 && secondary < .30 && choice < .40 * vergeDensity) {
            tall(writer, x, y, z, Blocks.TALL_GRASS);
            return;
        }
        if (choice < 0.34 * vergeDensity) {
            Block plant = secondary < 0.32 ? ForestBlocks.FOREST_GRASS
                    : secondary < 0.56 ? ForestBlocks.NEEDLE_UNDERGROWTH
                    : secondary < 0.76 ? ForestBlocks.PALE_SEDGE
                    : secondary < 0.90 ? ForestBlocks.DRY_UNDERGROWTH : ForestBlocks.LOW_FOREST_BUSH;
            writer.setIfAir(x, y, z, plant.getDefaultState());
        }
    }

    /** Riparian sedges, ferns, moss, and low bushes along damp riverbanks. */
    private static void decorateRiverbank(WarzoneTerrain terrain, ChunkWriter writer, WarzoneTerrain.Sample sample,
                                          int x, int y, int z, double choice, double secondary) {
        double cluster = patch(terrain.seed + 641, x, z, 9);
        if (terrain.usesLandscapeRivers()) {
            if (choice > .46 + .34 * cluster) return;
            BlockState soil = writer.get(x, y - 1, z);
            boolean fertile = soil.isIn(BlockTags.DIRT) || soil.isOf(Blocks.MOSS_BLOCK);
            if (sample.height <= sample.waterLevel + 1 && cluster > .32 && (secondary < .30 || !fertile))
                tall(writer, x, y, z, ForestBlocks.RIVER_REEDS);
            else if (!fertile) writer.setIfAir(x, y, z, ForestBlocks.PALE_SEDGE.getDefaultState());
            else if (secondary < .45) tall(writer, x, y, z, Blocks.TALL_GRASS);
            else if (secondary < .60) writer.setIfAir(x, y, z, ForestBlocks.PALE_SEDGE.getDefaultState());
            else if (secondary < .76) writer.setIfAir(x, y, z, Blocks.FERN.getDefaultState());
            else if (secondary < .87) tall(writer, x, y, z, Blocks.LARGE_FERN);
            else if (secondary < .95) writer.setIfAir(x, y, z, ForestBlocks.LOW_FOREST_BUSH.getDefaultState());
            else writer.setIfAir(x, y, z, Blocks.MOSS_CARPET.getDefaultState());
            return;
        }
        if (choice > (0.28 + 0.34 * cluster)) return;
        if (secondary < 0.36) writer.setIfAir(x, y, z, ForestBlocks.PALE_SEDGE.getDefaultState());
        else if (secondary < 0.62) writer.setIfAir(x, y, z, Blocks.FERN.getDefaultState());
        else if (secondary < 0.78) writer.setIfAir(x, y, z, ForestBlocks.LOW_FOREST_BUSH.getDefaultState());
        else if (secondary < 0.90) writer.setIfAir(x, y, z, ForestBlocks.FOREST_GRASS.getDefaultState());
        else tall(writer, x, y, z, Blocks.LARGE_FERN);
    }

    private static void waterPlants(WarzoneTerrain terrain, ChunkWriter writer, WarzoneTerrain.Sample sample,
                                    int x, int z, double choice, double secondary) {
        if (terrain.usesLandscapeRivers()) {
            riverWaterPlants(terrain, writer, sample, x, z, choice, secondary);
            return;
        }
        int depth = WarzoneTerrain.SEA_LEVEL - sample.height;
        boolean still = sample.waterState == WarzoneTerrain.WaterState.STILL;
        if (depth > 2 || (!still && sample.waterState != WarzoneTerrain.WaterState.SLOW)) return;
        double patch = WarzoneNoise.unit(WarzoneNoise.hash(terrain.seed + 533,
                Math.floorDiv(x, 7), Math.floorDiv(z, 7)));
        int bottom = sample.height + 1;
        BlockState water = writer.get(x, bottom, z);
        if (choice < (still ? .24 : .17) * (.35 + patch) && sourceWater(water))
            writer.set(x, bottom, z, Blocks.SEAGRASS.getDefaultState());
        if (secondary < (still ? .055 : .022) * patch
                && sourceWater(writer.get(x, WarzoneTerrain.SEA_LEVEL, z)))
            writer.setIfAir(x, WarzoneTerrain.SEA_LEVEL + 1, z, Blocks.LILY_PAD.getDefaultState());
    }

    private static void riverWaterPlants(WarzoneTerrain terrain, ChunkWriter writer, WarzoneTerrain.Sample sample,
                                         int x, int z, double choice, double secondary) {
        int depth = sample.waterLevel - sample.height;
        int bottom = sample.height + 1;
        double cluster = patch(terrain.seed + 653, x, z, 10);
        boolean calm = sample.waterState == WarzoneTerrain.WaterState.STILL || sample.waterState == WarzoneTerrain.WaterState.SLOW;
        if (!sourceWater(writer.get(x, bottom, z))) return;
        if (sample.waterState == WarzoneTerrain.WaterState.RAPIDS || !calm && cluster < .42) {
            if (choice < .18 && depth <= 5) pebbles(writer, x, bottom, z, secondary, true);
            return;
        }
        if (depth <= 7 && choice < (calm ? .24 : .09) * (.35 + cluster)) {
            if (depth >= 2 && secondary < .46 && sourceWater(writer.get(x, bottom + 1, z))) {
                writer.set(x, bottom, z, Blocks.TALL_SEAGRASS.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.LOWER));
                writer.set(x, bottom + 1, z, Blocks.TALL_SEAGRASS.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER));
            } else writer.set(x, bottom, z, Blocks.SEAGRASS.getDefaultState());
        } else if (choice > .91 && depth <= 4) pebbles(writer, x, bottom, z, secondary, true);
        // Pads gather in shallow sheltered reaches, leaving the active channel open.
        if (calm && depth <= 3 && cluster > .54 && secondary < .06
                && sourceWater(writer.get(x, sample.waterLevel, z)))
            writer.setIfAir(x, sample.waterLevel + 1, z, Blocks.LILY_PAD.getDefaultState());
    }

    private static void pebbles(ChunkWriter writer, int x, int y, int z, double choice, boolean wet) {
        if (wet ? !sourceWater(writer.get(x, y, z)) : !writer.get(x, y, z).isAir()) return;
        writer.set(x, y, z, ForestBlocks.RIVER_PEBBLES.getDefaultState()
                .with(ForestBlocks.PebbleBlock.VARIANT, Math.min(2, (int) (choice * 3)))
                .with(Properties.WATERLOGGED, wet));
    }

    private static boolean sourceWater(BlockState state) {
        return state.isOf(Blocks.WATER) && state.getFluidState().isStill();
    }

    private static void tall(ChunkWriter writer, int x, int y, int z, Block plant) {
        if (!writer.contains(x, y + 1, z) || !writer.get(x, y, z).isAir() || !writer.get(x, y + 1, z).isAir()) return;
        BlockState soil = writer.get(x, y - 1, z);
        boolean fertile = soil.isIn(BlockTags.DIRT) || soil.isOf(Blocks.GRASS_BLOCK)
                || soil.isOf(Blocks.MOSS_BLOCK) || soil.isOf(Blocks.FARMLAND);
        if (plant == ForestBlocks.RIVER_REEDS) {
            if (!fertile && !soil.isOf(Blocks.MUD) && !soil.isOf(Blocks.CLAY)
                    && !soil.isOf(Blocks.SAND) && !soil.isOf(Blocks.GRAVEL)) return;
        } else if (!fertile) return;
        writer.set(x, y, z, plant.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.LOWER));
        writer.set(x, y + 1, z, plant.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static double patch(long seed, int x, int z, int scale) {
        int cellX = Math.floorDiv(x, scale), cellZ = Math.floorDiv(z, scale);
        double fx = WarzoneNoise.smoothstep(0, 1, Math.floorMod(x, scale) / (double) scale);
        double fz = WarzoneNoise.smoothstep(0, 1, Math.floorMod(z, scale) / (double) scale);
        double north = WarzoneNoise.lerp(WarzoneNoise.unit(WarzoneNoise.hash(seed, cellX, cellZ)),
                WarzoneNoise.unit(WarzoneNoise.hash(seed, cellX + 1, cellZ)), fx);
        double south = WarzoneNoise.lerp(WarzoneNoise.unit(WarzoneNoise.hash(seed, cellX, cellZ + 1)),
                WarzoneNoise.unit(WarzoneNoise.hash(seed, cellX + 1, cellZ + 1)), fx);
        return WarzoneNoise.lerp(north, south, fz);
    }
}
