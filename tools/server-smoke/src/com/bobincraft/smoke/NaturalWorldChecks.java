package com.bobincraft.smoke;

import com.bobincraft.raid.guard.WarzoneGuardEntity;
import com.bobincraft.warzone.ForestBlocks;
import com.bobincraft.warzone.NaturalLandscape;
import com.bobincraft.warzone.WarzoneChunkGenerator;
import com.bobincraft.warzone.WarzoneDimension;
import com.bobincraft.warzone.WarzoneTerrain;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registry and chunk-generation checks. All synthetic chunks remain in memory. */
final class NaturalWorldChecks {
    private static final Logger LOG = LoggerFactory.getLogger("bobin-smoke");
    static int assertions;
    private static long customFloorBlocks;
    private static long customTrunkBlocks;
    private static long foliageBlocks;

    /** Open a disposable copy of a v1 world under the new datapack to detect silent generator replacement. */
    static void runLegacy(MinecraftServer server) throws Exception {
        ServerWorld world = server.getWorld(WarzoneDimension.WORLD);
        check(world != null && world.getChunkManager().getChunkGenerator() instanceof WarzoneChunkGenerator,
                "saved Warzone dimension retained");
        WarzoneChunkGenerator generator = (WarzoneChunkGenerator) world.getChunkManager().getChunkGenerator();
        WarzoneTerrain terrain = generator.terrain();
        check(!terrain.isNatural() && terrain.generationVersion() == 1 && generator.arenaSize() == 1024,
                "current datapack did not replace saved 1024/v1 generator");
        check(terrain.min == -512 && terrain.max == 512, "saved world finite bounds retained");
        check(world.getWorldBorder().getSize() == server.getOverworld().getWorldBorder().getSize(),
                "saved v1 profile retains its original inherited border behavior");
        JsonObject current = resource("/data/bobin_craft/dimension/warzone.json").getAsJsonObject("generator");
        check(current.get("size").getAsInt() == 2048 && current.get("generation_version").getAsInt() == 4,
                "compatibility regression runs against revised natural datapack");
        check(!terrain.basePlan().sites().isEmpty(), "saved military plan remains available");
        BlockPos landing = new BlockPos(terrain.landingX(), terrain.landingY(), terrain.landingZ());
        world.getChunk(landing);
        check(world.getBlockState(landing).isOf(Blocks.CHISELED_STONE_BRICKS), "saved legacy landing generation remains intact");
        LOG.info("BOBIN_SMOKE_LEGACY_WORLD_PASS assertions={} size={} version={} saved terrain retained under natural datapack",
                assertions, generator.arenaSize(), terrain.generationVersion());
    }

    static void run(MinecraftServer server) throws Exception {
        ServerWorld world = server.getWorld(WarzoneDimension.WORLD);
        check(world != null, "Warzone dimension loaded");
        check(world.getChunkManager().getChunkGenerator() instanceof WarzoneChunkGenerator, "Warzone generator selected");
        WarzoneChunkGenerator actual = (WarzoneChunkGenerator) world.getChunkManager().getChunkGenerator();
        check(actual.terrain().usesRevisedLandscape() && actual.terrain().generationVersion() == 4
                        && actual.arenaSize() == 2048,
                "fresh-world preset selects revised 2048/version-four profile");
        check(world.getWorldBorder().getSize() == 2048.0, "runtime Warzone border is 2048");
        check(world.getWorldBorder().getCenterX() == 0 && world.getWorldBorder().getCenterZ() == 0, "runtime border centered at origin");
        check(server.getWorld(World.NETHER) != null && server.getWorld(World.END) != null, "vanilla dimensions retained");

        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager());
        JsonObject preset = resource("/data/bobin_craft/worldgen/world_preset/warzone.json")
                .getAsJsonObject("dimensions").getAsJsonObject("bobin_craft:warzone").getAsJsonObject("generator");
        JsonObject standalone = resource("/data/bobin_craft/dimension/warzone.json").getAsJsonObject("generator");
        check(preset.equals(standalone), "preset and standalone Warzone generator settings agree");
        WarzoneChunkGenerator forward = decode(preset, ops, server, world, world.getSeed());
        WarzoneChunkGenerator reverse = decode(preset, ops, server, world, world.getSeed());
        check(forward.terrain().usesRevisedLandscape() && forward.terrain().generationVersion() == 4
                        && forward.arenaSize() == 2048,
                "natural generator codec decodes revised version and size");

        JsonObject previousNatural = preset.deepCopy();
        previousNatural.addProperty("generation_version", 2);
        WarzoneChunkGenerator versionTwo = decode(previousNatural, ops, server, world, world.getSeed());
        check(versionTwo.terrain().isNatural() && !versionTwo.terrain().usesRevisedLandscape()
                        && versionTwo.terrain().generationVersion() == 2 && versionTwo.arenaSize() == 2048,
                "saved natural version-two generator retains its original profile");
        JsonObject versionTwoEncoded = WarzoneChunkGenerator.CODEC.encodeStart(ops, versionTwo)
                .getOrThrow(false, message -> { throw new AssertionError(message); }).getAsJsonObject();
        check(versionTwoEncoded.get("generation_version").getAsInt() == 2,
                "saving a version-two generator does not upgrade its generation version");
        JsonObject savedRevised = preset.deepCopy();
        savedRevised.addProperty("generation_version", 3);
        WarzoneChunkGenerator versionThree = decode(savedRevised, ops, server, world, world.getSeed());
        check(versionThree.terrain().usesRevisedLandscape() && versionThree.terrain().generationVersion() == 3,
                "saved version-three landscape remains available");
        JsonObject versionThreeEncoded = WarzoneChunkGenerator.CODEC.encodeStart(ops, versionThree)
                .getOrThrow(false, message -> { throw new AssertionError(message); }).getAsJsonObject();
        check(versionThreeEncoded.get("generation_version").getAsInt() == 3,
                "saving a version-three generator does not upgrade its landscape");
        JsonObject old = preset.deepCopy();
        old.remove("generation_version");
        old.remove("size");
        WarzoneChunkGenerator legacy = decode(old, ops, server, world, world.getSeed());
        check(!legacy.terrain().isNatural() && legacy.arenaSize() == 1024,
                "saved generators missing version and size retain original 1024 terrain");
        check(!legacy.terrain().basePlan().sites().isEmpty(), "legacy structure plan preserved");
        WarzoneTerrain oldTerrain = legacy.terrain();
        ProtoChunk oldLanding = generate(legacy, world, new ChunkPos(new BlockPos(oldTerrain.landingX(), 0, oldTerrain.landingZ())));
        check(oldLanding.getBlockState(new BlockPos(oldTerrain.landingX(), oldTerrain.landingY(), oldTerrain.landingZ()))
                .isOf(Blocks.CHISELED_STONE_BRICKS), "legacy landing-pad generation preserved");

        WarzoneTerrain terrain = forward.terrain();
        check(terrain.min == -1024 && terrain.max == 1024 && terrain.size == 2048, "exact centered finite terrain bounds");
        check(terrain.inside(-1024, -1024) && terrain.inside(1023, 1023), "both inclusive playable corners exist");
        check(!terrain.inside(-1025, 0) && !terrain.inside(1024, 0)
                && !terrain.inside(0, -1025) && !terrain.inside(0, 1024), "all exclusive outer edges rejected");
        check(terrain.basePlan().sites().isEmpty() && terrain.hideoutPlan().hideouts().isEmpty()
                && terrain.poiPlan().checkpoints().isEmpty() && terrain.poiPlan().battlefields().isEmpty(),
                "natural generation has no legacy military objects");
        check(terrain.naturalLandscape().reservations().size() >= 5, "future terrain reservations distributed across the natural world");
        check(terrain.naturalLandscape().connectedReservationCount() == terrain.naturalLandscape().reservations().size(),
                "natural roads connect every future reservation to the landing network");
        verifyRegistrations();

        List<ChunkPos> positions = positions(terrain);
        Map<ChunkPos, int[]> first = new LinkedHashMap<>();
        long before = System.nanoTime();
        for (ChunkPos position : positions) {
            ProtoChunk chunk = generate(forward, world, position);
            check(chunk.getEntities().isEmpty() && chunk.getBlockEntityPositions().isEmpty(), "no generated entities or block entities at " + position);
            first.put(position, snapshot(chunk, terrain, true));
            verifyTerrainQueries(forward, chunk, world);
        }
        Collections.reverse(positions);
        for (ChunkPos position : positions) {
            ProtoChunk chunk = generate(reverse, world, position);
            check(Arrays.equals(first.get(position), snapshot(chunk, terrain, false)),
                    "identical complete block states after reverse chunk generation at " + position);
        }
        int waterAndRouteChunks = verifyWaterAndRoutes(forward, world);
        verifyLanding(world, actual);
        for (Entity entity : world.iterateEntities()) {
            check(!(entity instanceof WarzoneGuardEntity), "natural world has no generated hostile guards");
        }
        check(customFloorBlocks > 0 && customTrunkBlocks > 0 && foliageBlocks > 0,
                "actual generated chunks contain custom forest floors, custom trunks and foliage");

        WarzoneChunkGenerator otherSeed = decode(preset, ops, server, world, world.getSeed() + 1);
        boolean differs = false;
        for (int x = -800; x <= 800 && !differs; x += 100) {
            differs = terrain.height(x, 317) != otherSeed.terrain().height(x, 317);
        }
        check(differs, "different seeds produce different terrain");
        LOG.info("BOBIN_SMOKE_NATURAL_PASS assertions={} generated_chunks={} custom_floor={} custom_trunks={} foliage={} elapsed_ms={}",
                assertions, first.size() * 2 + waterAndRouteChunks + 1, customFloorBlocks, customTrunkBlocks, foliageBlocks,
                (System.nanoTime() - before) / 1_000_000);
    }

    private static int verifyWaterAndRoutes(WarzoneChunkGenerator generator, ServerWorld world) {
        WarzoneTerrain terrain = generator.terrain();
        Map<WarzoneTerrain.WaterState, BlockPos> waters = new EnumMap<>(WarzoneTerrain.WaterState.class);
        Map<WarzoneTerrain.WaterState, BlockPos> shallows = new EnumMap<>(WarzoneTerrain.WaterState.class);
        Map<NaturalLandscape.RouteStyle, BlockPos> routes = new EnumMap<>(NaturalLandscape.RouteStyle.class);
        Map<WarzoneTerrain.WaterState, BlockPos> elevated = new EnumMap<>(WarzoneTerrain.WaterState.class);
        collectRevisedSamples(terrain, 8, waters, shallows, routes, elevated);
        if (waters.size() < 4 || shallows.size() < 2 || routes.size() < 4 || elevated.isEmpty())
            collectRevisedSamples(terrain, 4, waters, shallows, routes, elevated);
        check(waters.size() == 4, "bounded metadata search found still, slow, flowing and rapid water");
        check(shallows.size() == 2, "bounded metadata search found calm and slow shallow shelves");
        check(routes.size() == 4, "bounded metadata search found all four visible route types");
        check(!elevated.isEmpty(), "mountain river channels include water above the global sea level");

        Set<ChunkPos> positions = new LinkedHashSet<>();
        for (BlockPos point : waters.values()) positions.add(new ChunkPos(point));
        for (BlockPos point : elevated.values()) positions.add(new ChunkPos(point));
        for (BlockPos point : shallows.values()) positions.add(new ChunkPos(point));
        for (BlockPos point : routes.values()) positions.add(new ChunkPos(point));
        Set<WarzoneTerrain.WaterState> generatedWater = new LinkedHashSet<>();
        Set<NaturalLandscape.RouteStyle> generatedRoutes = new LinkedHashSet<>();
        int aquaticPlants = 0, elevatedWaterColumns = 0;
        for (ChunkPos position : positions) {
            ProtoChunk chunk = generate(generator, world, position);
            verifyTerrainQueries(generator, chunk, world);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int x = position.getStartX() + lx, z = position.getStartZ() + lz;
                terrain.sample(x, z, sample);
                int depth = sample.waterLevel - sample.height;
                BlockPos bedPosition = new BlockPos(x, sample.height, z);
                BlockState actualBed = chunk.getBlockState(bedPosition);
                if (depth > 0) {
                    generatedWater.add(sample.waterState);
                    if (sample.waterLevel > WarzoneTerrain.SEA_LEVEL) elevatedWaterColumns++;
                    VerticalBlockSample column = generator.getColumnSample(x, z, world, world.getChunkManager().getNoiseConfig());
                    BlockState expectedBed = column.getState(sample.height);
                    check(waterbedPalette(sample.waterState, expectedBed),
                            "state-specific generated riverbed palette at " + bedPosition + ": " + sample.waterState + " / " + expectedBed);
                    boolean ore = expectedBed.isOf(Blocks.STONE)
                            && Registries.BLOCK.getId(actualBed.getBlock()).getPath().endsWith("_ore");
                    check(actualBed.equals(expectedBed) || ore, "actual riverbed matches queried bed at " + bedPosition);
                    check(column.getState(sample.waterLevel + 1).isAir(),
                            "queried river surface ends at its local water elevation at " + bedPosition);
                    for (int y = sample.height + 1; y <= sample.waterLevel; y++) {
                        check(column.getState(y).getFluidState().isIn(FluidTags.WATER),
                                "queried river column is filled to its local water elevation");
                        check(chunk.getBlockState(new BlockPos(x, y, z)).getFluidState().isIn(FluidTags.WATER),
                                "generated channel contains water through its full sampled depth at " + x + ", " + y + ", " + z);
                    }
                } else if (sample.road > .3 || sample.path > .35) {
                    NaturalLandscape.RouteStyle style = NaturalLandscape.RouteStyle.values()[sample.routeStyle];
                    generatedRoutes.add(style);
                    check(routePalette(style, actualBed), "actual route uses its material family at " + bedPosition + ": " + style);
                }
                for (int y = sample.height + 1; y <= sample.waterLevel + 1; y++) {
                    BlockState block = chunk.getBlockState(new BlockPos(x, y, z));
                    if (!block.isOf(Blocks.SEAGRASS) && !block.isOf(Blocks.TALL_SEAGRASS) && !block.isOf(Blocks.LILY_PAD)) continue;
                    aquaticPlants++;
                    check(depth > 0 && depth <= 7 && sample.waterState != WarzoneTerrain.WaterState.RAPIDS,
                            "aquatic vegetation stays in supported non-rapid water at " + x + ", " + y + ", " + z);
                    if (block.isOf(Blocks.LILY_PAD)) {
                        check(depth <= 3 && (sample.waterState == WarzoneTerrain.WaterState.STILL
                                        || sample.waterState == WarzoneTerrain.WaterState.SLOW),
                                "lily pads remain in sheltered shallow water");
                        check(y == sample.waterLevel + 1
                                        && chunk.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isIn(FluidTags.WATER),
                                "lily pad has water support at the surface");
                    } else {
                        boolean upperSeagrass = block.isOf(Blocks.TALL_SEAGRASS) && y == sample.height + 2
                                && chunk.getBlockState(new BlockPos(x, y - 1, z)).isOf(Blocks.TALL_SEAGRASS);
                        check(y == sample.height + 1 || upperSeagrass, "seagrass is anchored directly to the riverbed");
                    }
                }
            }
        }
        check(generatedWater.containsAll(waters.keySet()), "real generated chunks represent every water state");
        check(generatedRoutes.containsAll(routes.keySet()), "real generated chunks represent every route type");
        check(elevatedWaterColumns > 0, "generated mountain rivers retain elevated water levels");
        check(aquaticPlants > 0, "representative shallow chunks contain actual aquatic vegetation");
        LOG.info("BOBIN_SMOKE_WATER_ROUTES_PASS generated_chunks={} water_states={} route_styles={} aquatic_plants={}",
                positions.size(), generatedWater.size(), generatedRoutes.size(), aquaticPlants);
        return positions.size();
    }

    private static void collectRevisedSamples(WarzoneTerrain terrain, int step,
                                              Map<WarzoneTerrain.WaterState, BlockPos> waters,
                                              Map<WarzoneTerrain.WaterState, BlockPos> shallows,
                                              Map<NaturalLandscape.RouteStyle, BlockPos> routes,
                                              Map<WarzoneTerrain.WaterState, BlockPos> elevated) {
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        for (int z = terrain.min + 64; z < terrain.max - 64; z += step) {
            for (int x = terrain.min + 64; x < terrain.max - 64; x += step) {
                terrain.sample(x, z, sample);
                int depth = sample.waterLevel - sample.height;
                if (depth > 0 && sample.waterState != WarzoneTerrain.WaterState.NONE) {
                    waters.putIfAbsent(sample.waterState, new BlockPos(x, sample.height, z));
                    if (sample.waterLevel > WarzoneTerrain.SEA_LEVEL)
                        elevated.putIfAbsent(sample.waterState, new BlockPos(x, sample.height, z));
                    if (depth <= 2 && !terrain.isReserved(x, z)
                            && (sample.waterState == WarzoneTerrain.WaterState.STILL || sample.waterState == WarzoneTerrain.WaterState.SLOW))
                        shallows.putIfAbsent(sample.waterState, new BlockPos(x, sample.height, z));
                } else if (depth <= 0 && (sample.road > .3 || sample.path > .35)) {
                    routes.putIfAbsent(NaturalLandscape.RouteStyle.values()[sample.routeStyle], new BlockPos(x, sample.height, z));
                }
                if (waters.size() == 4 && shallows.size() == 2 && routes.size() == 4 && !elevated.isEmpty()) return;
            }
        }
    }

    private static boolean waterbedPalette(WarzoneTerrain.WaterState state, BlockState bed) {
        return switch (state) {
            case STILL -> bed.isOf(Blocks.CLAY) || bed.isOf(Blocks.MUD) || bed.isOf(Blocks.SAND);
            case SLOW -> bed.isOf(Blocks.MUD) || bed.isOf(Blocks.CLAY) || bed.isOf(ForestBlocks.RIVER_STONE);
            case NONE -> bed.isOf(Blocks.MUD) || bed.isOf(Blocks.CLAY);
            case FLOWING -> bed.isOf(Blocks.GRAVEL) || bed.isOf(Blocks.SAND) || bed.isOf(Blocks.CLAY)
                    || bed.isOf(ForestBlocks.RIVER_STONE) || bed.isOf(ForestBlocks.LAYERED_SLATE);
            case RAPIDS -> bed.isOf(ForestBlocks.RIVER_STONE) || bed.isOf(ForestBlocks.LAYERED_SLATE)
                    || bed.isOf(ForestBlocks.MOSS_COVERED_ROCK) || bed.isOf(Blocks.ANDESITE);
        };
    }

    private static boolean routePalette(NaturalLandscape.RouteStyle style, BlockState bed) {
        // Soft route verges share the surrounding forest floor at every route style.
        if (bed.isOf(Blocks.GRASS_BLOCK) || bed.isOf(ForestBlocks.FOREST_LITTER)
                || bed.isOf(ForestBlocks.DARK_FOREST_SOIL) || bed.isOf(ForestBlocks.MOSSY_FOREST_SOIL)) return true;
        return switch (style) {
            case WAGON_TRACK -> bed.isOf(Blocks.GRASS_BLOCK) || bed.isOf(ForestBlocks.FOREST_LITTER)
                    || bed.isOf(Blocks.GRAVEL) || bed.isOf(Blocks.COARSE_DIRT) || bed.isOf(Blocks.ROOTED_DIRT) || bed.isOf(Blocks.MUD);
            case GRAVEL_ROAD -> bed.isOf(Blocks.GRAVEL) || bed.isOf(Blocks.COBBLESTONE)
                    || bed.isOf(Blocks.COARSE_DIRT) || bed.isOf(Blocks.ROOTED_DIRT);
            case WOODLAND_TRAIL -> bed.isOf(ForestBlocks.FOREST_LITTER) || bed.isOf(Blocks.ROOTED_DIRT)
                    || bed.isOf(Blocks.COARSE_DIRT) || bed.isOf(ForestBlocks.DARK_FOREST_SOIL);
            case WORN_FOOTPATH -> bed.isOf(Blocks.GRASS_BLOCK) || bed.isOf(Blocks.DIRT)
                    || bed.isOf(Blocks.GRAVEL) || bed.isOf(Blocks.COARSE_DIRT);
        };
    }

    private static JsonObject resource(String path) throws Exception {
        try (InputStream stream = WarzoneChunkGenerator.class.getResourceAsStream(path)) {
            if (stream == null) throw new AssertionError("Missing runtime configuration " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static WarzoneChunkGenerator decode(JsonObject json, RegistryOps<com.google.gson.JsonElement> ops,
                                                MinecraftServer server, ServerWorld world, long seed) {
        WarzoneChunkGenerator generator = WarzoneChunkGenerator.CODEC.parse(ops, json)
                .getOrThrow(false, message -> { throw new AssertionError("Generator codec: " + message); });
        generator.createStructurePlacementCalculator(server.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),
                world.getChunkManager().getNoiseConfig(), seed);
        return generator;
    }

    private static ProtoChunk generate(WarzoneChunkGenerator generator, ServerWorld world, ChunkPos position) {
        ProtoChunk chunk = new ProtoChunk(position, UpgradeData.NO_UPGRADE_DATA, world,
                world.getRegistryManager().get(RegistryKeys.BIOME), null);
        generator.populateNoise(Runnable::run, Blender.getNoBlending(), world.getChunkManager().getNoiseConfig(),
                world.getStructureAccessor(), chunk).join();
        return chunk;
    }

    private static List<ChunkPos> positions(WarzoneTerrain terrain) {
        Set<ChunkPos> result = new LinkedHashSet<>();
        // Every pair straddles a positive or negative chunk boundary. Include all four quadrants.
        for (int x : new int[]{-1, 0}) for (int z : new int[]{-1, 0}) result.add(new ChunkPos(x, z));
        for (int x : new int[]{-24, 23}) for (int z : new int[]{-24, 23}) {
            result.add(new ChunkPos(x, z));
            result.add(new ChunkPos(x + 1, z));
        }
        // Entirely outside, first inside and last inside chunks on all four edges.
        for (int edge : new int[]{-65, -64, 63, 64}) {
            result.add(new ChunkPos(edge, 0));
            result.add(new ChunkPos(0, edge));
        }
        result.add(new ChunkPos(new BlockPos(terrain.landingX(), 0, terrain.landingZ())));
        // Locate dense natural forest without loading the rest of the world.
        for (int x = -768; x <= 768; x += 96) {
            for (int z = -768; z <= 768; z += 96) {
                if (terrain.forest(x, z) > .65 && !terrain.isReserved(x, z)
                        && terrain.height(x, z) > WarzoneTerrain.SEA_LEVEL + 2) {
                    result.add(new ChunkPos(new BlockPos(x, 0, z)));
                    result.add(new ChunkPos(new BlockPos(x + 16, 0, z)));
                    return new ArrayList<>(result);
                }
            }
        }
        throw new AssertionError("No dense forest found in bounded terrain samples");
    }

    private static int[] snapshot(ProtoChunk chunk, WarzoneTerrain terrain, boolean countMaterials) {
        int[] result = new int[chunk.getHeight() * 256];
        BlockPos.Mutable position = new BlockPos.Mutable();
        int index = 0;
        boolean outside = !terrain.inside(chunk.getPos().getStartX(), chunk.getPos().getStartZ());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            for (int y = chunk.getBottomY(); y < chunk.getTopY(); y++) {
                BlockState state = chunk.getBlockState(position.set(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z));
                result[index++] = Block.getRawIdFromState(state);
                if (outside && !state.isAir()) throw new AssertionError("Generated blocks outside finite terrain at " + position);
                if (state.hasBlockEntity() || state.isOf(Blocks.STONE_BRICKS) || state.isOf(Blocks.CHISELED_STONE_BRICKS)
                        || state.isOf(Blocks.IRON_BARS) || state.isOf(Blocks.LANTERN)) {
                    throw new AssertionError("Artificial structure block in natural generation at " + position + ": " + state);
                }
                if (state.isOf(Blocks.TALL_GRASS) || state.isOf(Blocks.LARGE_FERN)
                        || state.isOf(Blocks.TALL_SEAGRASS) || state.isOf(ForestBlocks.RIVER_REEDS)) {
                    boolean lower = state.get(TallPlantBlock.HALF) == DoubleBlockHalf.LOWER;
                    BlockState partner = chunk.getBlockState(lower ? position.up() : position.down());
                    check(partner.isOf(state.getBlock()) && partner.get(TallPlantBlock.HALF)
                                    == (lower ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER),
                            "generated tall plant has both halves at " + position);
                }
                if (!countMaterials) continue;
                if (state.isOf(ForestBlocks.DARK_FOREST_SOIL) || state.isOf(ForestBlocks.PINE_NEEDLE_GROUND)
                        || state.isOf(ForestBlocks.MOSSY_FOREST_SOIL) || state.isOf(ForestBlocks.FOREST_LITTER)) customFloorBlocks++;
                if (state.isOf(ForestBlocks.DARK_PINE_WOOD) || state.isOf(ForestBlocks.WEATHERED_BARK)) customTrunkBlocks++;
                if (state.isOf(Blocks.SPRUCE_LEAVES) || state.isOf(Blocks.OAK_LEAVES) || state.isOf(Blocks.BIRCH_LEAVES)) foliageBlocks++;
            }
        }
        assertions++;
        return result;
    }

    private static void verifyTerrainQueries(WarzoneChunkGenerator generator, ProtoChunk chunk, ServerWorld world) {
        WarzoneTerrain terrain = generator.terrain();
        for (int localX : new int[]{0, 7, 15}) for (int localZ : new int[]{0, 7, 15}) {
            int x = chunk.getPos().getStartX() + localX, z = chunk.getPos().getStartZ() + localZ;
            boolean inside = terrain.inside(x, z);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            terrain.sample(x, z, sample);
            int height = inside ? sample.height : world.getBottomY() - 1;
            VerticalBlockSample column = generator.getColumnSample(x, z, world, world.getChunkManager().getNoiseConfig());
            int floor = generator.getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, world, world.getChunkManager().getNoiseConfig());
            int surface = generator.getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, world, world.getChunkManager().getNoiseConfig());
            check(floor == (inside ? height + 1 : world.getBottomY()), "terrain floor query at " + x + ", " + z);
            check(surface == (inside ? Math.max(height, sample.waterLevel) + 1 : world.getBottomY()),
                    "terrain surface query at " + x + ", " + z);
            if (!inside) {
                check(column.getState(0).isAir(), "outside column sample is void");
                continue;
            }
            check(chunk.getBlockState(new BlockPos(x, 0, z)).isOf(Blocks.BEDROCK), "solid finite world foundation");
            // Column queries intentionally describe the underlying terrain, excluding ore veins and vegetation.
            // Surface and shallow filler remain exact, including either side of each selected chunk seam.
            for (int y = height - 3; y <= height; y++) {
                BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
                BlockState expected = column.getState(y);
                boolean ore = (expected.isOf(Blocks.STONE) || expected.isOf(Blocks.DEEPSLATE))
                        && Registries.BLOCK.getId(state.getBlock()).getPath().endsWith("_ore");
                check(expected.equals(state) || ore, "terrain column matches populated surface at " + new BlockPos(x, y, z));
            }
            check(Math.abs(height - terrain.height(x + (localX == 15 ? 1 : -1), z)) <= 8
                            || terrain.edgeDistance(x, z) < 48,
                    "no abrupt chunk-seam terrain discontinuity at " + x + ", " + z);
        }
    }

    private static void verifyLanding(ServerWorld world, WarzoneChunkGenerator generator) {
        WarzoneTerrain terrain = generator.terrain();
        BlockPos feet = new BlockPos(terrain.landingX(), terrain.landingY() + 1, terrain.landingZ());
        // This is a normal fresh-world chunk load in the isolated smoke fixture.
        world.getChunk(feet);
        check(world.getBlockState(feet.down()).isFullCube(world, feet.down()), "natural deployment landing has solid support");
        check(world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty(), "natural deployment landing has two blocks of headroom");
        check(world.getBlockState(feet).getFluidState().isEmpty()
                && world.getBlockState(feet.down()).getFluidState().isEmpty(), "natural deployment landing is dry");
    }

    private static void verifyRegistrations() {
        for (String name : new String[]{"dark_forest_soil", "pine_needle_ground", "mossy_forest_soil", "forest_litter",
                "weathered_bark", "dark_pine_wood", "rotten_wood", "moss_covered_rock", "dry_undergrowth",
                "forest_grass", "pale_sedge", "low_forest_bush", "needle_undergrowth",
                "river_stone", "layered_slate", "lichen_rock", "river_pebbles", "river_reeds"}) {
            Identifier id = new Identifier("bobin_craft", name);
            check(Registries.BLOCK.containsId(id) && Registries.ITEM.containsId(id), "registered natural block and item " + name);
            BlockState state = Registries.BLOCK.get(id).getDefaultState();
            check(!state.hasBlockEntity() && !state.hasRandomTicks(), "natural block has no block entity or random tick cost " + name);
        }
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
}
