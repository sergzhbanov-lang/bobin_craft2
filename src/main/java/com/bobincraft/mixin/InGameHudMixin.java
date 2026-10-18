package com.bobincraft.mixin;

import com.bobincraft.camera.BobinCameraState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {

    /**
     * Скрываем прицел (crosshair) на экране, пока активен режим Bobin.
     */
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void onRenderCrosshair(DrawContext context, CallbackInfo ci) {
        if (BobinCameraState.isActive()) {
            ci.cancel();
        }
    }
}
