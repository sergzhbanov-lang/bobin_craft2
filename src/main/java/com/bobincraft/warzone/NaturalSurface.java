package com.bobincraft.warzone;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

/** A muted forest palette shared by dry woods, weathered ridges and wet valley floors. */
final class NaturalSurface {
    private NaturalSurface() { }

    static void choose(WarzoneTerrain t, WarzoneTerrain.Sample s, double slope,
                       int x, int z, WarzoneSurface.Column out) {
        double r = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 501, x, z));
        out.strata = false;
        out.desertStrata = false;
        out.fillerDepth = 3;
        out.filler = Blocks.DIRT.getDefaultState();
        int waterLevel = t.usesLandscapeRivers() ? s.waterLevel : WarzoneTerrain.SEA_LEVEL;
        if (s.height < waterLevel) {
            if (t.usesRevisedLandscape()) waterbed(t, s, x, z, r, out);
            else {
                out.top = (s.channel > 0.35 ? (r < 0.65 ? Blocks.GRAVEL : Blocks.CLAY) : Blocks.MUD).getDefaultState();
                out.filler = Blocks.CLAY.getDefaultState();
            }
        } else if (s.road > 0.22 || s.path > 0.26) {
            out.top = (t.usesRevisedLandscape() ? route(t, s, x, z, r)
                    : r < (s.trail ? 0.08 : 0.25) ? Blocks.GRAVEL : Blocks.COARSE_DIRT).getDefaultState();
            out.filler = Blocks.DIRT.getDefaultState();
            out.fillerDepth = 2;
        } else if (t.usesRevisedLandscape() && (s.channel > 0.03 || s.valley > 0.42)
                && s.height <= waterLevel + 2) {
            riverbank(t, s, slope, x, z, r, out);
        } else if (slope > 1.35 || (s.mountain > 0.7 && slope > 0.65)) {
            out.top = (t.usesLandscapeRivers() ? exposedRock(t, s, x, z, r)
                    : r < 0.34 ? ForestBlocks.MOSS_COVERED_ROCK : r < 0.72 ? Blocks.ANDESITE : Blocks.STONE).getDefaultState();
            out.filler = Blocks.STONE.getDefaultState();
            out.fillerDepth = 2;
        } else if (s.region == WarzoneTerrain.Region.MARSH || s.height <= WarzoneTerrain.SEA_LEVEL + 1) {
            out.top = (s.height <= WarzoneTerrain.SEA_LEVEL && r < 0.65 ? Blocks.MUD
                    : r < 0.75 ? ForestBlocks.MOSSY_FOREST_SOIL : ForestBlocks.DARK_FOREST_SOIL).getDefaultState();
        } else {
            double forest = t.forest(x, z);
            double patch = patch(t.seed + 539, x, z, 10);
            // Broad forest ecology chooses the floor; small grain breaks up the material boundaries.
            out.top = (forest < 0.20 ? (patch < 0.35 ? ForestBlocks.FOREST_LITTER : Blocks.GRASS_BLOCK)
                    : forest < 0.38 ? (r < 0.58 ? ForestBlocks.FOREST_LITTER : patch < 0.5 ? ForestBlocks.DARK_FOREST_SOIL : Blocks.GRASS_BLOCK)
                    : s.bog > 0.26 ? ForestBlocks.MOSSY_FOREST_SOIL
                    : patch < 0.42 ? ForestBlocks.PINE_NEEDLE_GROUND
                    : patch < 0.76 ? ForestBlocks.DARK_FOREST_SOIL
                    : r < 0.65 ? ForestBlocks.FOREST_LITTER : ForestBlocks.MOSSY_FOREST_SOIL).getDefaultState();
        }
    }

    /** Natural, dark Eastern European forest riverbanks without bright sand/gravel collars. */
    private static void riverbank(WarzoneTerrain t, WarzoneTerrain.Sample s, double slope,
                                  int x, int z, double grain, WarzoneSurface.Column out) {
        double patch = patch(t.seed + 531, x, z, 12);
        double detail = patch(t.seed + 547, x, z, 5);
        int aboveWater = s.height - (t.usesLandscapeRivers() ? s.waterLevel : WarzoneTerrain.SEA_LEVEL);
        Block bank;
        if (slope > 1.05 || (s.waterState == WarzoneTerrain.WaterState.RAPIDS && patch > 0.52)) {
            bank = t.usesLandscapeRivers() ? wetRock(patch, detail) : detail < 0.42 ? ForestBlocks.MOSS_COVERED_ROCK
                    : detail < 0.76 ? Blocks.ANDESITE : Blocks.COBBLESTONE;
            out.filler = Blocks.STONE.getDefaultState();
        } else if (aboveWater == 0) {
            if (s.bog > 0.32 || patch < 0.38) {
                bank = grain < 0.70 ? Blocks.MUD : ForestBlocks.MOSSY_FOREST_SOIL;
            } else if (patch < 0.68) {
                bank = grain < 0.55 ? Blocks.COARSE_DIRT : Blocks.ROOTED_DIRT;
            } else if (patch < 0.88) {
                bank = detail < 0.65 ? Blocks.GRAVEL : t.usesLandscapeRivers() ? ForestBlocks.RIVER_STONE : ForestBlocks.MOSS_COVERED_ROCK;
            } else {
                bank = detail < 0.40 && slope < 0.5 ? Blocks.SAND : Blocks.GRAVEL;
            }
            out.filler = (bank == Blocks.MUD ? Blocks.MUD : Blocks.DIRT).getDefaultState();
        } else {
            // Upper bank terrace blends smoothly into dark forest soil, moss, and litter.
            bank = patch < 0.30 ? ForestBlocks.MOSSY_FOREST_SOIL
                    : patch < 0.60 ? ForestBlocks.DARK_FOREST_SOIL
                    : patch < 0.82 ? Blocks.COARSE_DIRT
                    : grain < 0.6 ? ForestBlocks.FOREST_LITTER : Blocks.ROOTED_DIRT;
            out.filler = Blocks.DIRT.getDefaultState();
        }
        out.top = bank.getDefaultState();
    }

    private static void waterbed(WarzoneTerrain t, WarzoneTerrain.Sample s, int x, int z,
                                 double grain, WarzoneSurface.Column out) {
        int depth = (t.usesLandscapeRivers() ? s.waterLevel : WarzoneTerrain.SEA_LEVEL) - s.height;
        double patch = patch(t.seed + 529, x, z, 13);
        Block bed = switch (s.waterState) {
            case STILL -> patch < 0.68 ? Blocks.CLAY : s.bog > 0.35 ? Blocks.MUD : Blocks.SAND;
            case SLOW -> patch < 0.65 ? Blocks.MUD : Blocks.CLAY;
            case FLOWING -> patch < 0.76 ? Blocks.GRAVEL : grain < 0.7 ? Blocks.SAND : Blocks.CLAY;
            case RAPIDS -> patch < 0.5 ? Blocks.STONE : patch < 0.8 ? Blocks.ANDESITE : Blocks.COBBLESTONE;
            case NONE -> patch < 0.6 ? Blocks.CLAY : Blocks.MUD;
        };
        if (depth <= 2 && s.waterState != WarzoneTerrain.WaterState.RAPIDS) {
            if (s.waterState == WarzoneTerrain.WaterState.STILL) bed = patch < 0.55 ? Blocks.SAND : Blocks.CLAY;
            else if (s.waterState == WarzoneTerrain.WaterState.FLOWING) bed = patch < 0.45 ? Blocks.SAND : Blocks.GRAVEL;
        }
        if (t.usesLandscapeRivers()) {
            // Coherent sediment bars and dark deeper rock shelves tint the water naturally.
            if (s.waterState == WarzoneTerrain.WaterState.RAPIDS) bed = wetRock(patch, grain);
            else if (s.waterState == WarzoneTerrain.WaterState.FLOWING && patch > .56)
                bed = depth >= 5 && patch > .70 ? ForestBlocks.LAYERED_SLATE : ForestBlocks.RIVER_STONE;
            else if (s.waterState == WarzoneTerrain.WaterState.SLOW && depth >= 4 && patch > .76)
                bed = ForestBlocks.RIVER_STONE;
        }
        out.top = bed.getDefaultState();
        out.filler = (s.waterState == WarzoneTerrain.WaterState.RAPIDS ? Blocks.STONE
                : s.waterState == WarzoneTerrain.WaterState.FLOWING ? Blocks.GRAVEL : Blocks.CLAY).getDefaultState();
        out.fillerDepth = 3;
    }

    private static Block exposedRock(WarzoneTerrain t, WarzoneTerrain.Sample sample, int x, int z, double grain) {
        double geology = patch(t.seed + 553, x, z, 27);
        if (sample.mountain > .62) return geology < .47 ? ForestBlocks.LAYERED_SLATE
                : grain < .24 ? ForestBlocks.LICHEN_ROCK : Blocks.ANDESITE;
        return geology < .30 ? ForestBlocks.LICHEN_ROCK : geology < .63 ? ForestBlocks.MOSS_COVERED_ROCK
                : grain < .70 ? ForestBlocks.RIVER_STONE : Blocks.ANDESITE;
    }

    private static Block wetRock(double patch, double detail) {
        return patch < .48 ? ForestBlocks.RIVER_STONE : patch < .82 ? ForestBlocks.LAYERED_SLATE
                : detail < .62 ? ForestBlocks.MOSS_COVERED_ROCK : Blocks.ANDESITE;
    }

    private static Block route(WarzoneTerrain t, WarzoneTerrain.Sample s, int x, int z, double grain) {
        double patch = patch(t.seed + 527, x, z, 8);
        double edgePatch = patch(t.seed + 541, x, z, 4);
        double strength = Math.max(s.road, s.path);
        double wear = s.routeWear;
        // Feather outer path edges into the surrounding dark forest floor.
        if (strength < 0.48 && edgePatch + strength < 0.78) {
            return edgePatch < 0.36 ? ForestBlocks.FOREST_LITTER
                    : edgePatch < 0.68 ? ForestBlocks.DARK_FOREST_SOIL
                    : s.bog > 0.25 ? ForestBlocks.MOSSY_FOREST_SOIL : Blocks.GRASS_BLOCK;
        }
        return switch (s.routeStyle) {
            case 0 -> {
                // Wagon track: overgrown center median with worn wheel ruts and muddy/stony patches.
                if (wear < 0.42) {
                    yield patch < 0.42 ? ForestBlocks.FOREST_LITTER
                            : patch < 0.76 ? Blocks.GRASS_BLOCK
                            : edgePatch < 0.5 ? ForestBlocks.DARK_FOREST_SOIL : Blocks.COARSE_DIRT;
                }
                yield wear > 0.68 && patch < 0.28 ? Blocks.GRAVEL
                        : edgePatch < 0.24 ? Blocks.ROOTED_DIRT
                        : patch > 0.82 && s.valley > 0.2 ? Blocks.MUD : Blocks.COARSE_DIRT;
            }
            case 1 -> wear > 0.52 && patch > 0.30 ? Blocks.GRAVEL
                    : patch < 0.22 ? Blocks.COBBLESTONE
                    : edgePatch < 0.25 ? Blocks.ROOTED_DIRT : Blocks.COARSE_DIRT;
            case 2 -> wear < 0.44 ? (patch < 0.55 ? ForestBlocks.FOREST_LITTER : ForestBlocks.DARK_FOREST_SOIL)
                    : patch < 0.28 ? Blocks.ROOTED_DIRT
                    : patch < 0.70 ? Blocks.COARSE_DIRT : ForestBlocks.DARK_FOREST_SOIL;
            case 3 -> wear < 0.46 ? (patch < 0.48 ? Blocks.GRASS_BLOCK : ForestBlocks.FOREST_LITTER)
                    : patch < 0.24 ? Blocks.DIRT
                    : grain < 0.05 ? Blocks.GRAVEL : Blocks.COARSE_DIRT;
            default -> Blocks.COARSE_DIRT;
        };
    }

    /** Smooth deterministic patches avoid noisy one-block confetti and chunk-aligned material changes. */
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
