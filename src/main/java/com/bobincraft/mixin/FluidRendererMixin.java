package com.bobincraft.mixin;

import com.bobincraft.warzone.WarzoneWaterColors;
import net.minecraft.client.render.block.FluidRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The higher-priority RETURN callback runs after Fabric's priority-1000 callback. */
@Mixin(value = FluidRenderer.class, priority = 1100)
public class FluidRendererMixin {
    @Inject(method = "onResourceReload", at = @At("RETURN"))
    private void bobin_craft$reloadRiverTextures(CallbackInfo ci) {
        WarzoneWaterColors.onResourceReload();
    }
}
