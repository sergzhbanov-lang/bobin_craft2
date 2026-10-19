package com.bobincraft.smoke;

import com.bobincraft.warzone.WarzoneChunkGenerator;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;

/** Exercises the actual transformed vanilla dimension merge, using loaded runtime registries. */
final class DimensionProfileChecks {
    private static final RegistryKey<DimensionOptions> WARZONE = RegistryKey.of(
            RegistryKeys.DIMENSION, new Identifier("bobin_craft", "warzone"));

    static void run(MinecraftServer server) {
        Registry<DimensionOptions> loaded = server.getCombinedDynamicRegistries()
                .getCombinedRegistryManager().get(RegistryKeys.DIMENSION);
        DimensionOptions warzone = loaded.get(WARZONE);
        require(warzone != null, "Warzone runtime dimension exists");

        // This is the pre-redesign saved generator shape: no generation_version field.
        JsonObject fixedBiome = new JsonObject();
        fixedBiome.addProperty("type", "minecraft:fixed");
        fixedBiome.addProperty("biome", "minecraft:plains");
        JsonObject saved = new JsonObject();
        saved.add("biome_source", fixedBiome);
        saved.addProperty("size", 1024);
        var ops = RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager());
        WarzoneChunkGenerator legacyGenerator = WarzoneChunkGenerator.CODEC.parse(ops, saved)
                .getOrThrow(false, message -> { throw new AssertionError(message); });
        require(legacyGenerator.arenaSize() == 1024, "Missing-version generator keeps legacy size");
        JsonObject encoded = WarzoneChunkGenerator.CODEC.encodeStart(ops, legacyGenerator)
                .getOrThrow(false, message -> { throw new AssertionError(message); }).getAsJsonObject();
        require(!encoded.has("generation_version") || encoded.get("generation_version").getAsInt() == 1,
                "Missing-version generator decodes as legacy version one");
        saved.remove("size");
        require(WarzoneChunkGenerator.CODEC.parse(ops, saved)
                .getOrThrow(false, message -> { throw new AssertionError(message); }).arenaSize() == 1024,
                "Missing-size legacy generator keeps the original 1024-block default");
        DimensionOptions legacy = new DimensionOptions(warzone.dimensionTypeEntry(), legacyGenerator);
        DimensionOptions natural = new DimensionOptions(warzone.dimensionTypeEntry(),
                new WarzoneChunkGenerator(legacyGenerator.getBiomeSource(), 2048, 4));
        DimensionOptions originalOverworld = loaded.get(DimensionOptions.OVERWORLD);
        DimensionOptions datapackOverworld = new DimensionOptions(originalOverworld.dimensionTypeEntry(),
                originalOverworld.chunkGenerator());

        Registry<DimensionOptions> datapack = copy(loaded, natural, datapackOverworld);
        Registry<DimensionOptions> savedHolder = copy(loaded, legacy, originalOverworld);
        Registry<DimensionOptions> merged = new DimensionOptionsRegistryHolder(savedHolder).toConfig(datapack).dimensions();
        require(merged.get(WARZONE) == legacy, "Saved legacy Warzone wins over current natural datapack");
        require(merged.get(DimensionOptions.OVERWORLD) == datapackOverworld,
                "Other dimensions retain vanilla datapack precedence");

        saved.addProperty("size", 2048);
        saved.addProperty("generation_version", 2);
        WarzoneChunkGenerator versionTwoGenerator = WarzoneChunkGenerator.CODEC.parse(ops, saved)
                .getOrThrow(false, message -> { throw new AssertionError(message); });
        JsonObject versionTwoEncoded = WarzoneChunkGenerator.CODEC.encodeStart(ops, versionTwoGenerator)
                .getOrThrow(false, message -> { throw new AssertionError(message); }).getAsJsonObject();
        require(versionTwoEncoded.get("generation_version").getAsInt() == 2,
                "Explicit saved version two survives codec round trip");
        DimensionOptions versionTwo = new DimensionOptions(warzone.dimensionTypeEntry(), versionTwoGenerator);
        Registry<DimensionOptions> mergedVersionTwo = new DimensionOptionsRegistryHolder(
                copy(loaded, versionTwo, originalOverworld)).toConfig(datapack).dimensions();
        require(mergedVersionTwo.get(WARZONE) == versionTwo,
                "Saved natural version two wins over revised version-four datapack");

        saved.addProperty("generation_version", 3);
        WarzoneChunkGenerator versionThreeGenerator = WarzoneChunkGenerator.CODEC.parse(ops, saved)
                .getOrThrow(false, message -> { throw new AssertionError(message); });
        JsonObject versionThreeEncoded = WarzoneChunkGenerator.CODEC.encodeStart(ops, versionThreeGenerator)
                .getOrThrow(false, message -> { throw new AssertionError(message); }).getAsJsonObject();
        require(versionThreeEncoded.get("generation_version").getAsInt() == 3,
                "Explicit saved version three survives codec round trip");
        DimensionOptions versionThree = new DimensionOptions(warzone.dimensionTypeEntry(), versionThreeGenerator);
        Registry<DimensionOptions> mergedVersionThree = new DimensionOptionsRegistryHolder(
                copy(loaded, versionThree, originalOverworld)).toConfig(datapack).dimensions();
        require(mergedVersionThree.get(WARZONE) == versionThree,
                "Saved natural version three wins over version-four datapack");

        Registry<DimensionOptions> fresh = new DimensionOptionsRegistryHolder(copy(loaded, null, originalOverworld))
                .toConfig(datapack).dimensions();
        require(fresh.get(WARZONE) == natural, "An absent saved Warzone receives the new natural default");
        DimensionOptions selectedNatural = new DimensionOptions(warzone.dimensionTypeEntry(),
                new WarzoneChunkGenerator(legacyGenerator.getBiomeSource(), 2048, 4));
        Registry<DimensionOptions> preset = new DimensionOptionsRegistryHolder(copy(loaded, selectedNatural, originalOverworld))
                .toConfig(datapack).dimensions();
        require(preset.get(WARZONE) == selectedNatural, "An explicitly selected natural preset is preserved");
    }

    private static Registry<DimensionOptions> copy(Registry<DimensionOptions> source,
            DimensionOptions warzone, DimensionOptions overworld) {
        SimpleRegistry<DimensionOptions> result = new SimpleRegistry<>(RegistryKeys.DIMENSION, Lifecycle.stable());
        for (var entry : source.getEntrySet()) {
            RegistryKey<DimensionOptions> key = entry.getKey();
            DimensionOptions value = key.equals(WARZONE) ? warzone
                    : key.equals(DimensionOptions.OVERWORLD) ? overworld : entry.getValue();
            if (value != null) result.add(key, value, Lifecycle.stable());
        }
        return result.freeze();
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
