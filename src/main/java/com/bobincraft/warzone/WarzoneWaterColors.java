package com.bobincraft.warzone;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/** River ripples and continuous mineral/peat colors, scoped to the Warzone dimension. */
@Environment(EnvType.CLIENT)
public final class WarzoneWaterColors {
    private static final WarzoneNoise COLOR = new WarzoneNoise(0x71a9e24L);
    private static boolean enabled;

    private WarzoneWaterColors() { }

    public static void register() {
        // Sprite loading happens after client initializers, in the fluid renderer's reload callback.
        enabled = true;
    }

    /** Runs after Fabric restores its vanilla handlers on both initial load and resource reloads. */
    public static void onResourceReload() {
        if (!enabled) return;
        FluidRenderHandler previous = FluidRenderHandlerRegistry.INSTANCE.get(Fluids.WATER);
        if (previous instanceof RiverWaterHandler river) previous = river.original;
        FluidRenderHandler original = previous != null ? previous : new SimpleFluidRenderHandler(
                SimpleFluidRenderHandler.WATER_STILL, SimpleFluidRenderHandler.WATER_FLOWING,
                SimpleFluidRenderHandler.WATER_OVERLAY) {
            @Override
            public int getFluidColor(BlockRenderView view, BlockPos pos, FluidState state) {
                return view != null && pos != null ? BiomeColors.getWaterColor(view, pos) : 0x3f76e4;
            }
        };
        RiverWaterHandler handler = new RiverWaterHandler(original);
        SpriteAtlasTexture atlas = MinecraftClient.getInstance().getBakedModelManager()
                .getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
        handler.reloadTextures(atlas);
        FluidRenderHandlerRegistry.INSTANCE.register(Fluids.WATER, Fluids.FLOWING_WATER, handler);
    }

    private static final class RiverWaterHandler extends SimpleFluidRenderHandler {
        private final FluidRenderHandler original;

        RiverWaterHandler(FluidRenderHandler original) {
            super(new Identifier("bobin_craft", "block/river_water_still"),
                    new Identifier("bobin_craft", "block/river_water_flow"), WATER_OVERLAY);
            this.original = original;
        }

        @Override
        public void reloadTextures(SpriteAtlasTexture atlas) {
            super.reloadTextures(atlas);
            original.reloadTextures(atlas);
        }

        private boolean inWarzone(BlockRenderView view, BlockPos pos) {
            var world = MinecraftClient.getInstance().world;
            return view != null && pos != null && world != null
                    && world.getRegistryKey().equals(WarzoneDimension.WORLD);
        }

        @Override
        public Sprite[] getFluidSprites(BlockRenderView view, BlockPos pos, FluidState state) {
            return inWarzone(view, pos) ? super.getFluidSprites(view, pos, state)
                    : original.getFluidSprites(view, pos, state);
        }

        @Override
        public int getFluidColor(BlockRenderView view, BlockPos pos, FluidState state) {
            int biomeColor = original.getFluidColor(view, pos, state);
            if (!inWarzone(view, pos)) return biomeColor;
            double patch = .5 + .5 * COLOR.noise(pos.getX() / 61.0, pos.getZ() / 61.0);
            // Peaty marshes keep their olive character; upland water carries a cool mineral tint.
            double peat = Math.max(0, Math.min(1, (((biomeColor >> 16) & 255) - 45) / 85.0));
            int color = blend(0x418b91, 0x729277, peat * .75);
            color = blend(color, pos.getY() > 82 ? 0x73b6b5 : 0x669c88, patch * .28);
            if (!view.getFluidState(pos.down(3)).isIn(FluidTags.WATER))
                color = blend(color, 0x93b9a2, .20);
            return color;
        }
    }

    private static int blend(int a, int b, double weight) {
        int r = (int) (((a >> 16) & 255) * (1 - weight) + ((b >> 16) & 255) * weight);
        int g = (int) (((a >> 8) & 255) * (1 - weight) + ((b >> 8) & 255) * weight);
        int blue = (int) ((a & 255) * (1 - weight) + (b & 255) * weight);
        return r << 16 | g << 8 | blue;
    }
}
