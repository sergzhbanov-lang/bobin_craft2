package com.bobincraft.mixin;

import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.render.BuildingCutawayManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Крыша (Roof Cutaway): при построении чанка скрытые блоки выглядят как воздух.
 * Это работает независимо от рендерера (ваниль / Fabric Indigo), в отличие от отмены BlockRenderManager.renderBlock,
 * и заодно открывает грани соседних блоков (верх стен под крышей больше не «дырявый»).
 */
@Mixin(ChunkRendererRegion.class)
public class ChunkRendererRegionMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 0)
    private void bobin_craft$hideCutawayBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (BobinCameraState.isActive() && BuildingCutawayManager.isCutawayBlock(pos)) {
            cir.setReturnValue(Blocks.AIR.getDefaultState());
        }
    }
}
