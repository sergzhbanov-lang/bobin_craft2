package com.bobincraft.mixin;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

/** Preserve a saved or explicitly selected Warzone profile when datapack dimensions are merged. */
@Mixin(DimensionOptionsRegistryHolder.class)
public abstract class DimensionOptionsRegistryHolderMixin {
    @Shadow @Final private Registry<DimensionOptions> dimensions;

    // Vanilla prefers datapack entries over saved entries, which would otherwise silently replace
    // a legacy 1024-block generator with the current 2048-block default on the next world load.
    @Redirect(method = "method_45521", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/registry/Registry;getOrEmpty(Lnet/minecraft/registry/RegistryKey;)Ljava/util/Optional;",
            ordinal = 0), require = 1)
    private Optional<DimensionOptions> bobin_craft$preserveWarzoneProfile(
            Registry<DimensionOptions> datapackDimensions, RegistryKey<DimensionOptions> key) {
        if (key.getValue().getNamespace().equals("bobin_craft") && key.getValue().getPath().equals("warzone")) {
            Optional<DimensionOptions> selected = dimensions.getOrEmpty(key);
            if (selected.isPresent()) return selected;
        }
        return datapackDimensions.getOrEmpty(key);
    }
}
