package com.bobincraft.warzone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Versioned, chunk-local Warzone generation. Saved generators retain their original terrain. */
public final class WarzoneChunkGenerator extends ChunkGenerator {
    public static final int DEFAULT_SIZE = 2048;
    public static final int CURRENT_GENERATION_VERSION = 4;

    public static final Codec<WarzoneChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            // Missing fields must decode as the original saved-world profile.
            Codec.intRange(256, 4096).optionalFieldOf("size", 1024).forGetter(g -> g.size),
            Codec.intRange(1, CURRENT_GENERATION_VERSION).optionalFieldOf("generation_version", 1).forGetter(g -> g.generationVersion)
    ).apply(instance, instance.stable(WarzoneChunkGenerator::new)));

    private final int size;
    private final int generationVersion;
    private volatile WarzoneTerrain terrain;
    private volatile long seed;
    private volatile boolean seeded;

    public WarzoneChunkGenerator(BiomeSource biomeSource, int size) {
        this(biomeSource, size, 1);
    }

    public WarzoneChunkGenerator(BiomeSource biomeSource, int size, int generationVersion) {
        super(biomeSource);
        this.generationVersion = generationVersion;
        // размер арены — кратно 16 (целые чанки), не меньше 256
        this.size = Math.max(256, Math.round(size / 16.0f) * 16);
        if (biomeSource instanceof WarzoneBiomeSource warzoneBiomes) {
            warzoneBiomes.bind(this::terrain);
        }
    }

    // ------------------------------------------------------------------ рельеф и seed

    /** Seed мира узнаём в тот момент, когда сервер строит расчёт структур (до первой генерации чанков). */
    @Override
    public StructurePlacementCalculator createStructurePlacementCalculator(RegistryWrapper<StructureSet> structureSetRegistry,
                                                                           NoiseConfig noiseConfig, long seed) {
        initSeed(seed);
        // Ванильные структуры (деревни, крепости и т.д.) в этом измерении не нужны
        return StructurePlacementCalculator.create(noiseConfig, seed, this.biomeSource, Stream.empty());
    }

    private synchronized void initSeed(long worldSeed) {
        if (!seeded) {
            this.seed = worldSeed;
            this.terrain = new WarzoneTerrain(worldSeed, size, generationVersion);
            this.seeded = true;
        }
    }

    /** Рельеф этого измерения. */
    public WarzoneTerrain terrain() {
        WarzoneTerrain t = this.terrain;
        if (t != null) return t;
        // ServerChunkManager invokes createStructurePlacementCalculator before chunk generation.
        // A guessed seed here can generate real chunks that disagree with the eventual world plan.
        throw new IllegalStateException("Warzone terrain requested before the server supplied its world seed");
    }

    public int arenaSize() {
        return size;
    }

    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    // ------------------------------------------------------------------ генерация

    @Override
    public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender, NoiseConfig noiseConfig,
                                                  StructureAccessor structureAccessor, Chunk chunk) {
        WarzoneTerrain t = terrain();
        return CompletableFuture.supplyAsync(() -> {
            fillChunk(t, chunk);
            return chunk;
        }, executor);
    }

    private void fillChunk(WarzoneTerrain t, Chunk chunk) {
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getStartX();
        int z0 = cp.getStartZ();
        // Чанк целиком за пределами арены — пустота
        if (x0 + 15 < t.min || x0 >= t.max || z0 + 15 < t.min || z0 >= t.max) return;

        ChunkWriter w = new ChunkWriter(chunk);

        // Выборка колонок с запасом в 1 блок для расчёта уклона
        WarzoneTerrain.Sample[][] samples = new WarzoneTerrain.Sample[18][18];
        for (int i = 0; i < 18; i++) {
            for (int j = 0; j < 18; j++) {
                samples[i][j] = t.sample(x0 + i - 1, z0 + j - 1);
            }
        }

        WarzoneSurface.Column col = new WarzoneSurface.Column();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx;
                int z = z0 + lz;
                if (!t.inside(x, z)) continue;

                WarzoneTerrain.Sample s = samples[lx + 1][lz + 1];
                int h = s.height;
                double slope = Math.max(
                        Math.abs(samples[lx + 2][lz + 1].height - samples[lx][lz + 1].height),
                        Math.abs(samples[lx + 1][lz + 2].height - samples[lx + 1][lz].height)) * 0.5;

                WarzoneSurface.choose(t, s, slope, x, z, col);
                int deepslateTop = 6 + (int) (WarzoneNoise.hash(t.seed, x, z) & 3);

                // Основание
                w.set(x, 0, z, Blocks.BEDROCK.getDefaultState());
                for (int y = 1; y <= h; y++) {
                    int depth = h - y;
                    BlockState state;
                    if (depth == 0) {
                        state = col.top;
                    } else if (depth <= col.fillerDepth) {
                        state = col.strata ? (col.desertStrata ? WarzoneSurface.desertStrata(t, y) : WarzoneSurface.strata(t, y)) : col.filler;
                    } else if (y < deepslateTop) {
                        state = y <= 2 && (WarzoneNoise.hash(t.seed + 7, x * 31 + y, z) & 1) == 0
                                ? Blocks.BEDROCK.getDefaultState() : Blocks.DEEPSLATE.getDefaultState();
                    } else {
                        state = Blocks.STONE.getDefaultState();
                    }
                    w.set(x, y, z, state);
                }
                // Вода
                for (int y = h + 1; y <= s.waterLevel; y++) {
                    w.set(x, y, z, Blocks.WATER.getDefaultState());
                }
            }
        }

        // Каждый этап изолирован: ошибка в одном из них пишется в лог (с координатами чанка), но не роняет сервер
        safely("ores", cp, () -> WarzoneDecor.ores(t, w, samples));
        if (t.isNatural()) {
            // Errors propagate: a failed natural stage must not be saved as a permanently partial chunk.
            NaturalForestDecor.vegetation(t, w, samples);
        } else {
            safely("vegetation", cp, () -> WarzoneDecor.vegetation(t, w, samples));
            safely("landingPad", cp, () -> WarzoneDecor.landingPad(t, w));
            safely("structures", cp, () -> WarzoneStructures.generate(t, w, samples));
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("bobin_craft/warzone");

    private static void safely(String stage, ChunkPos cp, Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            LOGGER.error("[Warzone] Ошибка этапа '{}' в чанке {}, {} — этап пропущен", stage, cp.x, cp.z, e);
        }
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
                      StructureAccessor structureAccessor, Chunk chunk, GenerationStep.Carver carverStep) {
        // пещер нет
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        // поверхность строится в populateNoise
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        // никаких ванильных животных при генерации
    }

    // ------------------------------------------------------------------ параметры мира

    @Override
    public int getWorldHeight() {
        return WarzoneTerrain.HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return WarzoneTerrain.SEA_LEVEL;
    }

    @Override
    public int getMinimumY() {
        return WarzoneTerrain.MIN_Y;
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        WarzoneTerrain t = terrain();
        if (!t.inside(x, z)) return world.getBottomY();
        WarzoneTerrain.Sample sample = t.sample(x, z);
        int h = sample.height;
        boolean floorOnly = heightmap == Heightmap.Type.OCEAN_FLOOR || heightmap == Heightmap.Type.OCEAN_FLOOR_WG;
        return (floorOnly ? h : Math.max(h, sample.waterLevel)) + 1;
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        WarzoneTerrain t = terrain();
        BlockState[] states = new BlockState[world.getHeight()];
        WarzoneSurface.Column column = new WarzoneSurface.Column();
        if (t.isNatural() && t.inside(x, z)) {
            double slope = Math.max(Math.abs(t.height(x + 1, z) - t.height(x - 1, z)),
                    Math.abs(t.height(x, z + 1) - t.height(x, z - 1))) * 0.5;
            WarzoneSurface.choose(t, t.sample(x, z), slope, x, z, column);
        }
        int bottom = world.getBottomY();
        boolean in = t.inside(x, z);
        WarzoneTerrain.Sample sample = in ? t.sample(x, z) : null;
        int h = in ? sample.height : bottom - 1;
        for (int i = 0; i < states.length; i++) {
            int y = bottom + i;
            if (y <= h) {
                if (!t.isNatural()) states[i] = Blocks.STONE.getDefaultState();
                else if (y == 0 || (y > 0 && y <= 2 && (WarzoneNoise.hash(t.seed + 7, x * 31 + y, z) & 1) == 0))
                    states[i] = Blocks.BEDROCK.getDefaultState();
                else if (y == h) states[i] = column.top;
                else if (h - y <= column.fillerDepth) states[i] = column.filler;
                else if (y < 6 + (int) (WarzoneNoise.hash(t.seed, x, z) & 3)) states[i] = Blocks.DEEPSLATE.getDefaultState();
                else states[i] = Blocks.STONE.getDefaultState();
            }
            else if (in && y <= sample.waterLevel) states[i] = Blocks.WATER.getDefaultState();
            else states[i] = Blocks.AIR.getDefaultState();
        }
        return new VerticalBlockSample(bottom, states);
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        WarzoneTerrain t = terrain();
        WarzoneBasePlan plan = t.basePlan();
        WarzoneBasePlan.Site nearest = plan != null ? plan.nearestSite(pos.getX(), pos.getZ()) : null;
        String baseInfo = nearest != null
                ? ", база: " + nearest.callsign + " (" + (int) Math.round(nearest.distanceTo(pos.getX(), pos.getZ())) + "м)"
                : "";
        text.add("Warzone: размер " + t.size + ", край " + t.edgeDistance(pos.getX(), pos.getZ())
                + ", местность " + (t.inside(pos.getX(), pos.getZ()) ? t.region(pos.getX(), pos.getZ()) : "—")
                + baseInfo);
    }

    // Для WarzoneDimension (телепорт, границы)
    public Supplier<WarzoneTerrain> terrainSupplier() {
        return this::terrain;
    }
}
