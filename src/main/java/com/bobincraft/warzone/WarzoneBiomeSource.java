package com.bobincraft.warzone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Источник биомов Зоны боевых действий: четыре биома по типу местности ({@link WarzoneTerrain.Region}).
 * Тип местности считается той же функцией, что и рельеф, поэтому горы получают горный биом, болота — болотный и т.д.
 * Биомы — только цвет травы/неба/воды; мобы в них не появляются (spawners пусты).
 */
public final class WarzoneBiomeSource extends BiomeSource {
    public static final Codec<WarzoneBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Biome.REGISTRY_CODEC.fieldOf("plains").forGetter(s -> s.plains),
            Biome.REGISTRY_CODEC.fieldOf("highlands").forGetter(s -> s.highlands),
            Biome.REGISTRY_CODEC.fieldOf("badlands").forGetter(s -> s.badlands),
            Biome.REGISTRY_CODEC.fieldOf("marsh").forGetter(s -> s.marsh)
    ).apply(instance, WarzoneBiomeSource::new));

    private final RegistryEntry<Biome> plains;
    private final RegistryEntry<Biome> highlands;
    private final RegistryEntry<Biome> badlands;
    private final RegistryEntry<Biome> marsh;

    /** Рельеф подвязывает генератор чанков, когда становится известен seed мира. */
    private volatile Supplier<WarzoneTerrain> terrain;

    public WarzoneBiomeSource(RegistryEntry<Biome> plains, RegistryEntry<Biome> highlands,
                              RegistryEntry<Biome> badlands, RegistryEntry<Biome> marsh) {
        this.plains = plains;
        this.highlands = highlands;
        this.badlands = badlands;
        this.marsh = marsh;
    }

    void bind(Supplier<WarzoneTerrain> terrainSupplier) {
        this.terrain = terrainSupplier;
    }

    @Override
    protected Codec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return Stream.of(plains, highlands, badlands, marsh);
    }

    @Override
    public RegistryEntry<Biome> getBiome(int qx, int qy, int qz, MultiNoiseUtil.MultiNoiseSampler sampler) {
        Supplier<WarzoneTerrain> supplier = this.terrain;
        if (supplier == null) return plains;
        WarzoneTerrain t = supplier.get();
        if (t == null) return plains;
        // координаты биомов — по 4 блока; берём центр ячейки
        int x = (qx << 2) + 2;
        int z = (qz << 2) + 2;
        return switch (t.region(x, z)) {
            case HIGHLANDS -> highlands;
            case BADLANDS -> badlands;
            case MARSH -> marsh;
            default -> plains;
        };
    }
}
